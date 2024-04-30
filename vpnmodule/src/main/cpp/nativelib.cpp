#include <jni.h>
#include <string>
#include <linux/socket.h>
#include <netinet/ip.h>
#include <netinet/udp.h>
#include <netinet/tcp.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sys/epoll.h>
#include <unordered_map>

#define LOG_TAG "TUNService_NATIVE"
#define DEFAULT_RECV_BUF_LEN 4096

#define ip_to_str(ip, addr) \
char addr[INET_ADDRSTRLEN]; \
inet_ntop(AF_INET, &ip, addr, INET_ADDRSTRLEN)

#define LOG_DEBUG(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ##__VA_ARGS__)
#define LOG_ERROR(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

void send_by_raw(int32_t pkt_length, jint tun_fd, iphdr *ip_header) {
    auto socket_fd = socket(AF_INET, SOCK_RAW, IPPROTO_IP);
    if (socket_fd < 0) {
        LOG_ERROR("Failed to create raw socket %d", socket_fd);
        return;
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
    udphdr *udp_header = (udphdr *) ((char *) ip_header + ip_header->ihl * 4);
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
            auto new_udphr = (udphdr *) (sendbuf + ip_len);
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

#define TUN_IP "192.168.0.1"
#define TUN_LISTEN_PORT 23333
#define MAX_CONN 16
// tun src(ip, port) -> dst(ip, port)
// change to
// dst(ip, port) -> (TUN_IP, TUN_LISTEN_PORT)
// and save dst(ip, port) -> tun src(ip, port) to map
static std::unordered_map<uint64_t, uint64_t> ip_port_map;

void epoll_ctl_add(int epfd, int fd, uint32_t events) {
    struct epoll_event ev;
    ev.events = events;
    ev.data.fd = fd;
    if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev) == -1) {
        perror("epoll_ctl()\n");
        exit(1);
    }
}

static int setnonblocking(int sockfd) {
    if (fcntl(sockfd, F_SETFL, fcntl(sockfd, F_GETFL, 0) | O_NONBLOCK) ==
        -1) {
        return -1;
    }
    return 0;
}

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

    int res = bind(listen_sock, (sockaddr *) &addr, sizeof(addr));
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
    int client_socklen = sizeof(client_addr);
    int number_fds;
    int recv_len;
    int conn_sock;
    struct epoll_event events[MAX_CONN];

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
                        write(events[i].data.fd, buf,
                              strlen(buf));
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

struct ip_port {
    uint64_t ip: 32,
            port: 16,
            padding: 16;
};

#define TO_IP_PORT(ip, port) (uint64_t) (port) << 32 | (ip)

void send_tcp_by_loopback(jint tun_fd, iphdr *ip_header, int pkt_length) {
    tcphdr *tcp_header = (tcphdr *) ((char *) ip_header + ip_header->ihl * 4);
    uint64_t src_ip_port = TO_IP_PORT(ip_header->saddr, tcp_header->source);
    uint64_t dst_ip_port = TO_IP_PORT(ip_header->daddr, tcp_header->dest);
    ip_port_map[dst_ip_port] = src_ip_port;

    ip_header->saddr = ip_header->daddr;
    tcp_header->source = tcp_header->dest;
    ip_header->daddr = inet_addr(TUN_IP);
    tcp_header->dest = htons(TUN_LISTEN_PORT);

    // Send the packet
    auto res = write(tun_fd, ip_header, pkt_length);
    if (res < 0) {
        LOG_ERROR("Failed to write packet to tun_fd");
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

    // Open a raw socket and send the packet
    send_by_raw(length, tun_fd, ip_header);

    env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
    return;
}

