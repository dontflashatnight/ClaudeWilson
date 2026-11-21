/*
 * Host-side (Termux) tests for the pure native helper logic. This file is not packaged.
 * It includes cwpersona.c so the static sanitizer and property table can be exercised without
 * launching the target process.
 */
#include "cwpersona.c"

#include <assert.h>
#include <string.h>

static char cb_name[64];
static char cb_val[MAX_PROP_VALUE];
static void test_prop_cb(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void) cookie;
    (void) serial;
    strncpy(cb_name, name, sizeof(cb_name) - 1);
    strncpy(cb_val, value, sizeof(cb_val) - 1);
}

int main(void) {
    char maps[512];
    strcpy(maps,
           "7000-8000 r-xp 00000000 fd:00 1 /system/lib64/libc.so\n"
           "7000-8000 r-xp 00000000 fd:00 2 /data/app/libcwpersona.so\n"
           "7000-8000 r-xp 00000000 fd:00 3 /data/adb/modules/zygisk_vector/libvector.so\n"
           "7000-8000 r-xp 00000000 fd:00 4 /data/local/tmp/frida-agent.so\n"
           "7000-8000 r-xp 00000000 fd:00 5 /system/lib64/liblog.so\n");
    int n = sanitize_proc("/proc/self/maps", maps, strlen(maps));
    maps[n] = '\0';
    assert(strstr(maps, "libc.so") != NULL);
    assert(strstr(maps, "liblog.so") != NULL);
    assert(strstr(maps, "cwpersona") == NULL);
    assert(strstr(maps, "libvector") == NULL);
    assert(strstr(maps, "frida") == NULL);

    char status[] = "Name:\tcom.anthropic.claude\nTracerPid:\t1234\nState:\tS\n";
    n = sanitize_proc("/proc/self/status", status, strlen(status));
    status[n] = '\0';
    assert(strstr(status, "TracerPid:\t0") != NULL);
    assert(strstr(status, "1234") == NULL);
    assert(strstr(status, "State:\tS") != NULL);

    assert(is_proc_path("/proc/self/maps") == 1);
    assert(is_proc_path("/proc/123/smaps") == 1);
    assert(is_proc_path("/proc/self/smaps_rollup") == 1);
    assert(is_proc_path("/system/lib64/libc.so") == 0);

    prop_keys[0] = strdup("ro.product.model");
    prop_values[0] = strdup("SM-S928B");
    prop_count = 1;
    char value[MAX_PROP_VALUE];
    assert(property_lookup("ro.product.model", value) == 8);
    assert(strcmp(value, "SM-S928B") == 0);
    assert(property_lookup("ro.product.brand", value) == -1);

    const prop_info *pi = cw_property_find("ro.product.model");
    assert(pi != NULL);
    cw_property_read_callback(pi, test_prop_cb, NULL);
    assert(strcmp(cb_name, "ro.product.model") == 0);
    assert(strcmp(cb_val, "SM-S928B") == 0);

    char *sample = strdup("line1\nline2\n");
    int owns = 0;
    int pfd = pipe_fallback(sample, strlen(sample), &owns);
    assert(pfd >= 0);
    if (!owns) free(sample);
    char pbuf[64] = {0};
    ssize_t pn = read(pfd, pbuf, sizeof(pbuf) - 1);
    close(pfd);
    assert(pn == 12);
    assert(strcmp(pbuf, "line1\nline2\n") == 0);

    // Exercise detached writer thread path with > 1.5 MB buffer (exceeds unprivileged pipe cap).
    size_t big_len = 1536 * 1024;
    char *big = (char *) malloc(big_len);
    assert(big != NULL);
    memset(big, 'A', big_len);
    int big_owns = 0;
    int bpfd = pipe_fallback(big, big_len, &big_owns);
    assert(bpfd >= 0);
    if (!big_owns) free(big);
    size_t got = 0;
    char chunk[8192];
    for (;;) {
        ssize_t r = read(bpfd, chunk, sizeof(chunk));
        if (r <= 0) break;
        got += (size_t) r;
    }
    close(bpfd);
    assert(got == big_len);

    // IPv6 /proc/net/if_inet6 sanitization (with spoofed AU prefix + VPN drop + loopback preserve).
    assert(is_proc_path("/proc/net/if_inet6") == 1);
    assert(is_proc_path("/proc/self/net/if_inet6") == 1);
    static const uint8_t au_v6[16] = {
            0x20, 0x01, 0x80, 0x03, 0xaa, 0xbb, 0xcc, 0xdd,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88
    };
    memcpy(spoofed_v6, au_v6, 16);
    strcpy(spoofed_v6_hex, "20018003aabbccdd1122334455667788");
    has_spoofed_v6 = 1;

    char if6[512];
    strcpy(if6,
           "240e03901234567890abcdef12345678 14 40 00 80 wlan0\n"
           "fe80000000000000123456fffe789abc 14 40 20 80 wlan0\n"
           "00000000000000000000000000000001 01 80 10 80 lo\n"
           "26064700111122223333444455556666 1f 40 00 80 tun0\n");
    n = sanitize_proc("/proc/net/if_inet6", if6, strlen(if6));
    if6[n] = '\0';
    assert(strstr(if6, "20018003aabbccdd1122334455667788 14 40 00 80 wlan0") != NULL);
    assert(strstr(if6, "240e0390") == NULL);
    assert(strstr(if6, "tun0") == NULL);
    assert(strstr(if6, "fe80000000000000123456fffe789abc") != NULL);
    assert(strstr(if6, "00000000000000000000000000000001") != NULL);

    // Without spoofed IPv6, global IPv6 lines must be dropped cleanly (never leak ISP IPv6).
    has_spoofed_v6 = 0;
    strcpy(if6,
           "240e03901234567890abcdef12345678 14 40 00 80 wlan0\n"
           "00000000000000000000000000000001 01 80 10 80 lo\n");
    n = sanitize_proc("/proc/net/if_inet6", if6, strlen(if6));
    if6[n] = '\0';
    assert(strstr(if6, "240e0390") == NULL);
    assert(strstr(if6, "00000000000000000000000000000001") != NULL);

    // getifaddrs sanitization: rewrite global wlan0 IPv6, down & mask tun0, preserve link-local.
    memcpy(spoofed_v6, au_v6, 16);
    has_spoofed_v6 = 1;
    struct sockaddr_in6 sa_wlan = {0}, sa_ll = {0}, sa_tun = {0};
    sa_wlan.sin6_family = AF_INET6;
    sa_wlan.sin6_addr.s6_addr[0] = 0x24; sa_wlan.sin6_addr.s6_addr[1] = 0x0e;
    sa_ll.sin6_family = AF_INET6;
    sa_ll.sin6_addr.s6_addr[0] = 0xfe; sa_ll.sin6_addr.s6_addr[1] = 0x80; sa_ll.sin6_addr.s6_addr[15] = 0x09;
    sa_tun.sin6_family = AF_INET6;
    sa_tun.sin6_addr.s6_addr[0] = 0x26; sa_tun.sin6_addr.s6_addr[1] = 0x06;
    struct ifaddrs ifa_tun = { .ifa_next = NULL, .ifa_name = "tun0", .ifa_flags = IFF_UP | IFF_RUNNING, .ifa_addr = (struct sockaddr *) &sa_tun };
    struct ifaddrs ifa_ll = { .ifa_next = &ifa_tun, .ifa_name = "wlan0", .ifa_flags = IFF_UP | IFF_RUNNING, .ifa_addr = (struct sockaddr *) &sa_ll };
    struct ifaddrs ifa_wlan = { .ifa_next = &ifa_ll, .ifa_name = "wlan0", .ifa_flags = IFF_UP | IFF_RUNNING, .ifa_addr = (struct sockaddr *) &sa_wlan };
    sanitize_ifaddrs(&ifa_wlan);
    assert(memcmp(sa_wlan.sin6_addr.s6_addr, au_v6, 16) == 0);
    assert(sa_ll.sin6_addr.s6_addr[0] == 0xfe && sa_ll.sin6_addr.s6_addr[15] == 0x09);
    assert((ifa_tun.ifa_flags & (IFF_UP | IFF_RUNNING)) == 0);
    assert(sa_tun.sin6_addr.s6_addr[0] == 0xfe && sa_tun.sin6_addr.s6_addr[1] == 0x80);

    // Dynamic hidden_ifaces + IFF_POINTOPOINT auto-hide for unknown tunnel names (e.g. netproxy/custom0).
    hidden_ifaces[0] = strdup("mybox0");
    hidden_iface_count = 1;
    assert(is_vpn_iface("mybox0") == 1);
    assert(is_vpn_iface("netproxy") == 1);
    assert(is_cellular_iface("rmnet_data1") == 1);
    assert(is_cellular_iface("netproxy") == 0);
    struct sockaddr_in6 sa_p2p = {0};
    sa_p2p.sin6_family = AF_INET6;
    sa_p2p.sin6_addr.s6_addr[0] = 0xfd; sa_p2p.sin6_addr.s6_addr[1] = 0xfe;
    struct ifaddrs ifa_p2p = {
            .ifa_next = NULL, .ifa_name = "randomvpn9",
            .ifa_flags = IFF_UP | IFF_RUNNING | IFF_POINTOPOINT,
            .ifa_addr = (struct sockaddr *) &sa_p2p
    };
    sanitize_ifaddrs(&ifa_p2p);
    assert((ifa_p2p.ifa_flags & (IFF_UP | IFF_RUNNING)) == 0);
    assert(sa_p2p.sin6_addr.s6_addr[0] == 0xfe && sa_p2p.sin6_addr.s6_addr[1] == 0x80);

    puts("filter-test: maps/status/property/callback/pipe(1.5MB)/ipv6 checks passed");
    return 0;
}
