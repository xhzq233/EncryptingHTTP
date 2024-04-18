#include <jni.h>
#include <string>
#include <linux/socket.h>
#include <netinet/ip.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <unistd.h>

std::string ip_to_str(uint32_t ip) {
    struct in_addr addr;
    addr.s_addr = ip;
    return std::string(inet_ntoa(addr));
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
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Invalid packet or not IPv4 packet");
        env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
        return;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Recv pkt from %s to %s, protocol %d, tot_len %d, pkt_len %d",
                        ip_to_str(ip_header->saddr).c_str(), ip_to_str(ip_header->daddr).c_str(),
                        ip_header->protocol, ip_header->tot_len, length);

    // Open a raw socket and send the packet
    auto socket_fd = socket(AF_INET, SOCK_RAW, IPPROTO_IP);
    if (socket_fd < 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Failed to create raw socket %d", socket_fd);
        env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
        return;
    }
    sockaddr_in dest_addr;
    dest_addr.sin_family = AF_INET;
    dest_addr.sin_port = 0;
    dest_addr.sin_addr.s_addr = ip_header->daddr;

    // Send the packet
    auto res = sendto(socket_fd, ip_pkt_ptr, length, 0, (struct sockaddr *) &dest_addr, sizeof(dest_addr));
    if (res < 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Failed to send packet");
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Sent packet");

        // Recv the packet
        char recvbuf[4096];
        sockaddr_in r_addr;
        socklen_t r_addr_len = sizeof(r_addr);
        res = recvfrom(socket_fd, recvbuf, sizeof(recvbuf), 0, (struct sockaddr *) &r_addr, &r_addr_len);

        if (res < 0) {
            __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Failed to recv packet");
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Recv packet, writing to tun_fd");
            res = write(tun_fd, recvbuf, res);
            if (res < 0) {
                __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Failed to write packet to tun_fd");
            }
        }
    }

    env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
    return;
}

