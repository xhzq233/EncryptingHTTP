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

#define TUN_IP "192.168.0.1"
#define TUN_LISTEN_PORT 23333
#define MAX_CONN 16

struct ip_port {
    uint64_t ip: 32,
            port: 16,
            padding: 16;
};

typedef uint64_t ip_port_t;

#define TO_IP_PORT(ip, port) (ip_port_t) (port) << 32 | (ip)

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