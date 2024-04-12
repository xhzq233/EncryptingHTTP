#include <jni.h>
#include <string>
#include <linux/socket.h>
#include <netinet/ip.h>
#include <android/log.h>
#include <arpa/inet.h>

/* function: ip_checksum_add
 * adds data to a checksum
 * current - the current checksum (or 0 to start a new checksum)
 * data        - the data to add to the checksum
 * len         - length of data
 */
uint32_t ip_checksum_add(uint32_t current, const void *data, int len) {
    uint32_t checksum = current;
    int left = len;
    const uint16_t *data_16 = (const uint16_t *) data;
    while (left > 1) {
        checksum += *data_16;
        data_16++;
        left -= 2;
    }
    if (left) {
        checksum += *(uint8_t *) data_16;
    }
    return checksum;
}

/* function: ip_checksum_fold
 * folds a 32-bit partial checksum into 16 bits
 * temp_sum - sum from ip_checksum_add
 * returns: the folded checksum in network byte order
 */
uint16_t ip_checksum_fold(uint32_t temp_sum) {
    while (temp_sum > 0xffff)
        temp_sum = (temp_sum >> 16) + (temp_sum & 0xFFFF);
    return temp_sum;
}

/* function: ip_checksum_finish
 * folds and closes the checksum
 * temp_sum - sum from ip_checksum_add
 * returns: a header checksum value in network byte order
 */
uint16_t ip_checksum_finish(uint32_t temp_sum) {
    return ~ip_checksum_fold(temp_sum);
}

/* function: ip_checksum
 * combined ip_checksum_add and ip_checksum_finish
 * data - data to checksum
 * len  - length of data
 */
uint16_t ip_checksum(const void *data, int len) {
    uint32_t temp_sum;
    temp_sum = ip_checksum_add(0, data, len);
    return ip_checksum_finish(temp_sum);
}

std::string ip_to_str(uint32_t ip) {
    struct in_addr addr;
    addr.s_addr = ip;
    return std::string(inet_ntoa(addr));
}

/* Return 1 if the packet is to be write back into the TUN interface, 0 if the packet is to be sent to localloop */
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_vpnmodule_NativeLib_handleIpPkt(JNIEnv *env, jobject thiz, jbyteArray ip_pkt, jint length) {
    // Get bytes
    jbyte *ip_pkt_ptr = env->GetByteArrayElements(ip_pkt, NULL);

    // Classify the packet
    iphdr *ip_header = (iphdr *) ip_pkt_ptr;

    if (length < sizeof(iphdr) || ip_header->ihl < 5 || ip_header->version != 4) {
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Invalid packet or not IPv4 packet");
        env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
        return 1;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Recv pkt from %s to %s, protocol %d, tot_len %d, pkt_len %d",
                        ip_to_str(ip_header->saddr).c_str(), ip_to_str(ip_header->daddr).c_str(),
                        ip_header->protocol, ip_header->tot_len, length);

    __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Hexdump of the packet data:");
    char buffer[length * 2];
    for (int i = 0; i < length; i++) {
        sprintf(buffer + i * 2, "%02x", (unsigned char) ip_pkt_ptr[i]);
    }
    __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "%s", buffer);

    int protocol = ip_header->protocol;
    if (protocol != IPPROTO_TCP) {
        __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "%d not TCP, write back", protocol);
        env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
        return 1;
    }
    // Change the destination address to local loop, the source address to the original destination address

//    in_addr_t tmp_addr = ip_header->saddr;
//    ip_header->saddr = ip_header->daddr;
//    ip_header->daddr = tmp_addr;
//    __android_log_print(ANDROID_LOG_DEBUG, "VPNModule", "Change pkt from %s to %s",
//                        ip_to_str(ip_header->saddr).c_str(), ip_to_str(ip_header->daddr).c_str());

    // Recalculate the checksum
//    ip_header->check = ip_checksum(ip_header, ip_header->ihl * 4);

    env->ReleaseByteArrayElements(ip_pkt, ip_pkt_ptr, 0);
    return 0;
}

