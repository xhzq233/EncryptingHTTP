#include "naticelib.h"

// CAP_NET_RAW or root permission
void send_by_raw(int32_t pkt_length, jint tun_fd, iphdr *ip_header) {
    auto socket_fd = socket(AF_INET, SOCK_RAW, IPPROTO_IP);
    if (socket_fd < 0) {
        LOG_ERROR("Failed to create raw socket %d", socket_fd);
        return;
    }
    ifreq ifr;

    memset(&ifr, 0, sizeof(ifr));
    snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "eth0");
    if (setsockopt(socket_fd, SOL_SOCKET, SO_BINDTODEVICE, (void *) &ifr, sizeof(ifr)) < 0) {
        LOG_ERROR("Failed to bind to device %s", ifr.ifr_name);
    }

    sockaddr_in dest_addr;
    dest_addr.sin_family = AF_INET;
    dest_addr.sin_port = 0;
    dest_addr.sin_addr.s_addr = ip_header->daddr;

    // Send the packet
    auto res = sendto(socket_fd, ip_header, pkt_length, 0, (struct sockaddr *) &dest_addr, sizeof(dest_addr));
    if (res < 0) {
        LOG_ERROR("Failed to send packet");
    } else {
        LOG_DEBUG("Sent packet");

        // Recv the packet
        char recvbuf[DEFAULT_RECV_BUF_LEN];
        sockaddr_in r_addr;
        socklen_t r_addr_len = sizeof(r_addr);
        res = recvfrom(socket_fd, recvbuf, sizeof(recvbuf), 0, (struct sockaddr *) &r_addr, &r_addr_len);

        if (res < 0) {
            LOG_ERROR("Failed to recv packet");
        } else {
            LOG_DEBUG("Recv packet, writing to tun_fd");
            res = write(tun_fd, recvbuf, res);
            if (res < 0) {
                LOG_ERROR("Failed to write packet to tun_fd");
            }
        }
    }
}

/// Use datagram socket if no permission to create raw socket.
/// Caller should ensure the packet is a udp packet.
void send_udp(jint tun_fd, iphdr *ip_header) {
    auto socket_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_fd < 0) {
        LOG_ERROR("Failed to create udp socket %d", socket_fd);
        return;
    }
    udphdr *udp_header = (udphdr * )((char *) ip_header + ip_header->ihl * 4);
    sockaddr_in dest_addr;
    dest_addr.sin_family = AF_INET;
    dest_addr.sin_port = udp_header->dest;
    dest_addr.sin_addr.s_addr = ip_header->daddr;

    auto udp_payload = (char *) udp_header + sizeof(udphdr);
    auto udp_payload_len = udp_header->len - sizeof(udphdr);

    // Send the packet
    auto res = sendto(socket_fd, udp_payload, udp_payload_len, 0, (struct sockaddr *) &dest_addr, sizeof(dest_addr));
    if (res < 0) {
        LOG_ERROR("Failed to send packet");
    } else {
        LOG_DEBUG("Sent packet");

        // Recv the packet
        char recvbuf[DEFAULT_RECV_BUF_LEN];
        sockaddr_in r_addr;
        socklen_t r_addr_len = sizeof(r_addr);
        res = recvfrom(socket_fd, recvbuf, sizeof(recvbuf), 0, (struct sockaddr *) &r_addr, &r_addr_len);

        if (res < 0) {
            LOG_ERROR("Failed to recv packet");
        } else {
            // Add ip&udp header
            auto ip_len = ip_header->ihl * 4;
            auto udp_len = sizeof(udphdr);
            auto total_len = ip_len + udp_len + res;
            char sendbuf[total_len];
            auto new_iphr = (iphdr *) sendbuf;
            memcpy(new_iphr, ip_header, ip_len);
            new_iphr->daddr = ip_header->saddr;
            new_iphr->saddr = ip_header->daddr;
            new_iphr->tot_len = htons(total_len);
            auto new_udphr = (udphdr * )(sendbuf + ip_len);
            memcpy(new_udphr, udp_header, udp_len);
            new_udphr->dest = udp_header->source;
            new_udphr->source = udp_header->dest;
            new_udphr->len = htons(udp_len + res);
            auto new_payload = sendbuf + ip_len + udp_len;
            memcpy(new_payload, recvbuf, res);

            LOG_DEBUG("Recv packet, writing to tun_fd");
            res = write(tun_fd, sendbuf, total_len);
            if (res < 0) {
                LOG_ERROR("Failed to write packet to tun_fd");
            }
        }
    }
}

// Send payload to dst(ip, port) and write the response to socket
void handle_tcp_payload(void *payload, int payload_len, int socket, int32_t dst_ip, int16_t dst_port);

// tun src(ip, port) -> dst(ip, port)
// change to
// dst(ip, port) -> (TUN_IP, TUN_LISTEN_PORT)
// and save dst(ip, port) -> tun src(ip, port) to map
static std::unordered_map <ip_port_t, ip_port_t> dst_src_map;

[[noreturn]] void listen_tun_tcp() {
    int listen_sock = socket(AF_INET, SOCK_STREAM, 0);

    if (listen_sock < 0) {
        LOG_ERROR("Failed to create socket %d", listen_sock);
        exit(1);
    }

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(TUN_LISTEN_PORT);
    addr.sin_addr.s_addr = inet_addr(TUN_IP);

    int res = bind(listen_sock, (sockaddr * ) & addr, sizeof(addr));
    if (res < 0) {
        LOG_ERROR("Failed to bind socket %d", res);
        exit(1);
    }

    setnonblocking(listen_sock);
    res = listen(listen_sock, MAX_CONN);
    if (res < 0) {
        LOG_ERROR("Failed to listen socket %d", res);
        exit(1);
    }
    sockaddr_in client_addr;
    int epfd;
    epfd = epoll_create(1);
    char buf[DEFAULT_RECV_BUF_LEN];
    epoll_ctl_add(epfd, listen_sock, EPOLLIN | EPOLLOUT | EPOLLET);
    socklen_t client_socklen = sizeof(client_addr);
    int number_fds;
    int recv_len;
    int conn_sock;
    struct epoll_event events[MAX_CONN];
    std::unordered_map<int, ip_port_t> fd_ip_port_map;

    for (;;) {
        number_fds = epoll_wait(epfd, events, MAX_CONN, -1);
        for (int i = 0; i < number_fds; i++) {
            if (events[i].data.fd == listen_sock) {
                /* handle new connection */
                conn_sock =
                        accept(listen_sock,
                               (struct sockaddr *) &client_addr,
                               &client_socklen);

                inet_ntop(AF_INET, (char *) &(client_addr.sin_addr),
                          buf, sizeof(client_addr));
                LOG_DEBUG("[+] connected with %s:%d\n", buf,
                          ntohs(client_addr.sin_port));
                fd_ip_port_map[conn_sock] = TO_IP_PORT(client_addr.sin_addr.s_addr,
                                                       client_addr.sin_port);

                setnonblocking(conn_sock);
                epoll_ctl_add(epfd, conn_sock,
                              EPOLLIN | EPOLLET | EPOLLRDHUP |
                              EPOLLHUP);
            } else if (events[i].events & EPOLLIN) {
                /* handle EPOLLIN event */
                for (;;) {
                    bzero(buf, sizeof(buf));
                    recv_len = read(events[i].data.fd, buf,
                                    sizeof(buf));
                    if (recv_len <= 0 /* || errno == EAGAIN */ ) {
                        break;
                    } else {
                        LOG_DEBUG("[+] data: %s\n", buf);
                        auto dst_ip_port = fd_ip_port_map[events[i].data.fd];
                        int32_t dst_ip = GET_IP(dst_ip_port);
                        int16_t dst_port = GET_PORT(dst_ip_port);
                        handle_tcp_payload(buf, recv_len,
                                           events[i].data.fd, dst_ip, dst_port);
                    }
                }
            } else {
                LOG_ERROR("[+] unexpected\n");
            }
            /* check if the connection is closing */
            if (events[i].events & (EPOLLRDHUP | EPOLLHUP)) {
                LOG_DEBUG("[+] connection closed\n");
                epoll_ctl(epfd, EPOLL_CTL_DEL,
                          events[i].data.fd, NULL);
                close(events[i].data.fd);
                continue;
            }
        }
    }
}


void send_tcp(jint tun_fd, iphdr *ip_header, int pkt_length) {
    tcphdr *tcp_header = (tcphdr * )((char *) ip_header + ip_header->ihl * 4);

    if(ip_header->saddr == inet_addr(TUN_IP) && ntohs(tcp_header->source) == TUN_LISTEN_PORT) {
        LOG_DEBUG("Recv from loopback, send to tun_fd");
        // change to dst(ip: port) -> src(ip: port)
        ip_port_t dst_ip_port = TO_IP_PORT(ip_header->daddr, tcp_header->dest);
        auto it = dst_src_map.find(dst_ip_port);
        if (it == dst_src_map.end()) {
            LOG_ERROR("No corresponding src ip port found");
            return;
        }
        ip_port_t src_ip_port = it->second;

        ip_header->saddr = ip_header->daddr;
        tcp_header->source = tcp_header->dest;
        ip_header->daddr = GET_IP(src_ip_port);
        tcp_header->dest = GET_PORT(src_ip_port);

        // Send the packet to tun_fd
        auto res = write(tun_fd, ip_header, pkt_length);
        if (res < 0) {
            LOG_ERROR("Failed to write packet to tun_fd");
        }
    } else {
        ip_port_t src_ip_port = TO_IP_PORT(ip_header->saddr, tcp_header->source);
        ip_port_t dst_ip_port = TO_IP_PORT(ip_header->daddr, tcp_header->dest);
        dst_src_map[dst_ip_port] = src_ip_port;

        ip_header->saddr = ip_header->daddr;
        tcp_header->source = tcp_header->dest;
        ip_header->daddr = inet_addr(TUN_IP);
        tcp_header->dest = htons(TUN_LISTEN_PORT);

        // Send the packet to loopback
        auto res = write(tun_fd, ip_header, pkt_length);
        if (res < 0) {
            LOG_ERROR("Failed to write packet to tun_fd");
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_vpnmodule_NativeLib_handleIpPkt(JNIEnv *env, jobject thiz, jbyteArray ip_pkt, jint length,
                                                 jint tun_fd) {
    // Get bytes
    jbyte *ip_pkt_ptr = env->GetByteArrayElements(ip_pkt, NULL);

    // Classify the packet
    iphdr *ip_header = (iphdr *) ip_pkt_ptr;

    if (length < sizeof(iphdr) || ip_header->ihl < 5 || ip_header->version != 4) {
        LOG_ERROR("Invalid packet or not IPv4 packet");
        env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
        return;
    }
    ip_to_str(ip_header->saddr, src_str);
    ip_to_str(ip_header->daddr, dest_str);

    LOG_DEBUG("Recv pkt from %s to %s, protocol %d, tot_len %d, pkt_len %d",
              src_str, dest_str,
              ip_header->protocol, ip_header->tot_len, length);

    // check root permission
    if (getuid() == 0) {
        LOG_DEBUG("Using root permission");
        send_by_raw(length, tun_fd, ip_header);
    } else {
        switch (ip_header->protocol) {
            case IPPROTO_TCP:
                send_tcp(tun_fd, ip_header, length);
                break;
            case IPPROTO_UDP:
                send_udp(tun_fd, ip_header);
                break;
            default:
                LOG_ERROR("Unsupported protocol %d", ip_header->protocol);
                break;
        }
    }

    env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
    return;
}

