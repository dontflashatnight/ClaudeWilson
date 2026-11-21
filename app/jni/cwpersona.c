/*
 * Native side of the Vector module.
 *
 * Installed through Vector's native_init hook. It provides the libc layer the Java hooks cannot
 * reach: property_get and the /proc/self read paths that leak module/loader residue.
 *
 * The fake /proc files are memfd-backed, so nothing is written to the target's data directory and
 * the caller still receives a normal read-only fd.
 */
#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <link.h>
#include <net/if.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include "native_api.h"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#define TAG "ClaudeWilsonHook"
#define MAX_PROPS 64
#define MAX_PROP_VALUE 92
#define MAX_HIDDEN_IFACES 32
#define MAX_FAKE_BYTES (16 * 1024 * 1024)

typedef struct prop_info prop_info;
typedef void (*prop_read_cb)(void *cookie, const char *name, const char *value, uint32_t serial);
typedef int (*dl_phdr_cb)(struct dl_phdr_info *info, size_t size, void *data);

static HookFunType hook_func;
static int (*orig_open)(const char *, int, ...);
static int (*orig_openat)(int, const char *, int, ...);
static FILE *(*orig_fopen)(const char *, const char *);
static int (*orig_property_get)(const char *, char *);
static const prop_info *(*orig_property_find)(const char *);
static void (*orig_property_read_callback)(const prop_info *, prop_read_cb, void *);
static int (*orig_dl_iterate_phdr)(dl_phdr_cb, void *);
static int (*orig_getifaddrs)(struct ifaddrs **);

typedef struct {
    uint32_t serial;
    uint8_t pad[124];
} cw_prop_sentinel;

static pthread_mutex_t prop_lock = PTHREAD_MUTEX_INITIALIZER;
static char *prop_keys[MAX_PROPS];
static char *prop_values[MAX_PROPS];
static _Alignas(8) cw_prop_sentinel prop_sentinels[MAX_PROPS];
static int prop_count;
static uint8_t spoofed_v6[16];
static char spoofed_v6_hex[33];
static volatile int has_spoofed_v6;
static char *hidden_ifaces[MAX_HIDDEN_IFACES];
static int hidden_iface_count;
static volatile int native_ready;

static int is_cellular_iface(const char *name) {
    if (name == NULL || name[0] == '\0') return 0;
    return strncmp(name, "rmnet", 5) == 0
            || strncmp(name, "r_rmnet", 7) == 0
            || strncmp(name, "rev_rmnet", 9) == 0
            || strncmp(name, "cdma_rmnet", 10) == 0
            || strncmp(name, "ccmni", 5) == 0
            || strncmp(name, "pdp", 3) == 0
            || strncmp(name, "svnet", 5) == 0
            || strncmp(name, "wwan", 4) == 0
            || strncmp(name, "clat", 4) == 0
            || strncmp(name, "epdg", 4) == 0
            || strncmp(name, "v4-rmnet", 8) == 0
            || strncmp(name, "v4-ccmni", 8) == 0;
}

static int is_vpn_iface(const char *name) {
    if (name == NULL || name[0] == '\0') return 0;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < hidden_iface_count; i++) {
        if (hidden_ifaces[i] != NULL && strcmp(hidden_ifaces[i], name) == 0) {
            pthread_mutex_unlock(&prop_lock);
            return 1;
        }
    }
    pthread_mutex_unlock(&prop_lock);
    return strncmp(name, "tun", 3) == 0
            || strncmp(name, "tap", 3) == 0
            || strncmp(name, "ppp", 3) == 0
            || strncmp(name, "wg", 2) == 0
            || strncmp(name, "utun", 4) == 0
            || strncmp(name, "clash", 5) == 0
            || strncmp(name, "netproxy", 8) == 0
            || strncmp(name, "singbox", 7) == 0
            || strncmp(name, "sing-box", 8) == 0
            || strncmp(name, "mihomo", 6) == 0
            || strcmp(name, "Meta") == 0
            || strcmp(name, "meta") == 0
            || strncmp(name, "meta0", 5) == 0
            || strncmp(name, "meta1", 5) == 0
            || strncmp(name, "meta_", 5) == 0
            || strncmp(name, "meta-", 5) == 0
            || strncmp(name, "xray", 4) == 0
            || strncmp(name, "v2ray", 5) == 0
            || strncmp(name, "nekobox", 7) == 0
            || strncmp(name, "flclash", 7) == 0
            || strncmp(name, "hiddify", 7) == 0
            || strncmp(name, "karing", 6) == 0
            || strncmp(name, "exclave", 7) == 0
            || strncmp(name, "surfboard", 9) == 0
            || strncmp(name, "shadowsocks", 11) == 0
            || strncmp(name, "awg", 3) == 0
            || strncmp(name, "amnezia", 7) == 0
            || strncmp(name, "warp", 4) == 0
            || strncmp(name, "ovpn", 4) == 0
            || strncmp(name, "zerotier", 8) == 0
            || strncmp(name, "adguard", 7) == 0
            || strncmp(name, "tproxy", 6) == 0
            || strncmp(name, "tailscale", 9) == 0
            || strncmp(name, "ipsec", 5) == 0;
}

static int is_global_v6_bytes(const uint8_t b[16]) {
    int all_zero = 1;
    for (int i = 0; i < 16; i++) {
        if (b[i] != 0) {
            all_zero = 0;
            break;
        }
    }
    if (all_zero) return 0;
    // Loopback ::1
    int loopback = 1;
    for (int i = 0; i < 15; i++) {
        if (b[i] != 0) {
            loopback = 0;
            break;
        }
    }
    if (loopback && b[15] == 1) return 0;
    // Link-local fe80::/10
    if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return 0;
    // Multicast ff00::/8
    if (b[0] == 0xff) return 0;
    return 1;
}

static int is_proc_path(const char *path) {
    if (path == NULL || strncmp(path, "/proc/", 6) != 0) return 0;
    const char *base = strrchr(path, '/');
    if (base == NULL) return 0;
    base++;
    return strcmp(base, "maps") == 0 || strcmp(base, "smaps") == 0
            || strcmp(base, "smaps_rollup") == 0
            || strcmp(base, "status") == 0 || strcmp(base, "stat") == 0
            || strcmp(base, "if_inet6") == 0;
}

static int should_drop(const char *line, size_t len) {
    static const char *terms[] = {
            "cwpersona", "claudewilson", "vector", "lsplant", "zygisk",
            "frida", "gum-js", "finject", "linjector", "libbrid",
    };
    for (size_t i = 0; i < sizeof(terms) / sizeof(terms[0]); i++) {
        size_t term_len = strlen(terms[i]);
        if (term_len <= len && memmem(line, len, terms[i], term_len) != NULL) return 1;
    }
    return 0;
}

static int append(char *dst, size_t cap, size_t *used, const char *src, size_t n) {
    if (*used + n >= cap) return 0;
    memcpy(dst + *used, src, n);
    *used += n;
    return 1;
}

static int sanitize_proc(const char *path, char *data, size_t size) {
    const char *base = strrchr(path, '/');
    if (base == NULL) return (int) size;
    base++;
    if (strcmp(base, "if_inet6") == 0) {
        char v6_hex[33];
        int use_v6 = 0;
        pthread_mutex_lock(&prop_lock);
        if (has_spoofed_v6) {
            memcpy(v6_hex, spoofed_v6_hex, 33);
            use_v6 = 1;
        }
        pthread_mutex_unlock(&prop_lock);

        char *out = malloc(size + 64);
        if (out == NULL) return (int) size;
        size_t used = 0;
        char *line = data;
        while (line < data + size) {
            char *nl = memchr(line, '\n', (size_t) (data + size - line));
            size_t len = nl == NULL ? (size_t) (data + size - line) : (size_t) (nl - line) + 1;
            char addr_tok[40] = {0};
            char idx_tok[16] = {0};
            char plen_tok[16] = {0};
            char scope_tok[16] = {0};
            char flags_tok[16] = {0};
            char iface_tok[64] = {0};
            char tmp[256];
            size_t copy_len = len < sizeof(tmp) - 1 ? len : sizeof(tmp) - 1;
            memcpy(tmp, line, copy_len);
            tmp[copy_len] = '\0';
            int fields = sscanf(tmp, "%39s %15s %15s %15s %15s %63s",
                                addr_tok, idx_tok, plen_tok, scope_tok, flags_tok, iface_tok);
            if (fields == 6) {
                if (is_vpn_iface(iface_tok)) {
                    // Drop VPN tunnel interface entry completely.
                } else if (strcmp(scope_tok, "00") == 0) {
                    // Global unicast IPv6: rewrite with regional spoofed IPv6 or strip.
                    if (use_v6 && strlen(addr_tok) == 32 && len >= 32) {
                        append(out, size + 64, &used, v6_hex, 32);
                        append(out, size + 64, &used, line + 32, len - 32);
                    }
                } else {
                    append(out, size + 64, &used, line, len);
                }
            } else {
                append(out, size + 64, &used, line, len);
            }
            if (nl == NULL) break;
            line = nl + 1;
        }
        if (used > 0) {
            memcpy(data, out, used);
        }
        free(out);
        return (int) used;
    }
    if (strcmp(base, "status") == 0) {
        char *out = malloc(size + 64);
        if (out == NULL) return (int) size;
        size_t used = 0;
        char *line = data;
        while (line < data + size) {
            char *nl = memchr(line, '\n', (size_t) (data + size - line));
            size_t len = nl == NULL ? (size_t) (data + size - line) : (size_t) (nl - line) + 1;
            if (strncmp(line, "TracerPid:", 10) == 0) {
                const char *zero = "TracerPid:\t0\n";
                append(out, size + 64, &used, zero, strlen(zero));
            } else {
                append(out, size + 64, &used, line, len);
            }
            if (nl == NULL) break;
            line = nl + 1;
        }
        if (used == 0) {
            free(out);
            return (int) size;
        }
        memcpy(data, out, used);
        free(out);
        return (int) used;
    }

    char *out = malloc(size + 1);
    if (out == NULL) return (int) size;
    size_t used = 0;
    char *line = data;
    while (line < data + size) {
        char *nl = memchr(line, '\n', (size_t) (data + size - line));
        size_t len = nl == NULL ? (size_t) (data + size - line) : (size_t) (nl - line) + 1;
        if (!should_drop(line, len) || len == 0) {
            append(out, size + 1, &used, line, len);
        }
        if (nl == NULL) break;
        line = nl + 1;
    }
    memcpy(data, out, used);
    free(out);
    return (int) used;
}

typedef struct {
    int wfd;
    char *data;
    size_t offset;
    size_t len;
} pipe_writer_args;

static void *pipe_writer_thread(void *arg) {
    pipe_writer_args *w = (pipe_writer_args *) arg;
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &mask, NULL);

    size_t written = w->offset;
    while (written < w->len) {
        ssize_t n = write(w->wfd, w->data + written, w->len - written);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            break;
        }
        written += (size_t) n;
    }
    close(w->wfd);
    free(w->data);
    free(w);
    return NULL;
}

static int pipe_fallback(char *data, size_t len, int *owns_data) {
    *owns_data = 0;
    int pfd[2];
    if (pipe2(pfd, O_CLOEXEC) != 0) return -1;
#ifdef F_SETPIPE_SZ
    if (len > 65536) {
        (void) fcntl(pfd[1], F_SETPIPE_SZ, (int) (len + 4096));
    }
#endif
    int fl = fcntl(pfd[1], F_GETFL, 0);
    if (fl >= 0) {
        (void) fcntl(pfd[1], F_SETFL, fl | O_NONBLOCK);
    }
    size_t written = 0;
    while (written < len) {
        ssize_t n = write(pfd[1], data + written, len - written);
        if (n > 0) {
            written += (size_t) n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (fl >= 0) {
                (void) fcntl(pfd[1], F_SETFL, fl & ~O_NONBLOCK);
            }
            pipe_writer_args *w = (pipe_writer_args *) malloc(sizeof(*w));
            if (w == NULL) {
                close(pfd[0]);
                close(pfd[1]);
                return -1;
            }
            w->wfd = pfd[1];
            w->data = data;
            w->offset = written;
            w->len = len;
            pthread_attr_t attr;
            pthread_attr_init(&attr);
            pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
            pthread_t tid;
            int rc = pthread_create(&tid, &attr, pipe_writer_thread, w);
            pthread_attr_destroy(&attr);
            if (rc != 0) {
                free(w);
                close(pfd[0]);
                close(pfd[1]);
                return -1;
            }
            *owns_data = 1;
            return pfd[0];
        }
        close(pfd[0]);
        close(pfd[1]);
        return -1;
    }
    close(pfd[1]);
    return pfd[0];
}

static int fake_proc_fd(const char *path) {
    int real = (int) syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (real < 0) return -1;
    char *data = malloc(MAX_FAKE_BYTES);
    if (data == NULL) {
        close(real);
        return -1;
    }
    ssize_t total = 0;
    while (total < MAX_FAKE_BYTES) {
        ssize_t n = read(real, data + total, (size_t) (MAX_FAKE_BYTES - total));
        if (n <= 0) break;
        total += n;
    }
    close(real);
    if (total <= 0) {
        free(data);
        return -1;
    }
    int clean = sanitize_proc(path, data, (size_t) total);
    if (clean < 0) {
        free(data);
        return -1;
    }
    int fd = (int) syscall(SYS_memfd_create, "", MFD_CLOEXEC);
    if (fd < 0) {
        int owns_data = 0;
        fd = pipe_fallback(data, (size_t) clean, &owns_data);
        if (!owns_data) {
            free(data);
        }
        return fd;
    }
    ssize_t written = 0;
    while (written < clean) {
        ssize_t n = write(fd, data + written, (size_t) (clean - written));
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            close(fd);
            free(data);
            return -1;
        }
        written += n;
    }
    free(data);
    lseek(fd, 0, SEEK_SET);
    return fd;
}

static int cw_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    if (orig_open == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_path(path)) {
        int fd = fake_proc_fd(path);
        if (fd >= 0) return fd;
    }
    return orig_open(path, flags, mode);
}

static int cw_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    if (orig_openat == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_path(path)) {
        int fd = fake_proc_fd(path);
        if (fd >= 0) return fd;
    }
    return orig_openat(dirfd, path, flags, mode);
}

static FILE *cw_fopen(const char *path, const char *mode) {
    if (orig_fopen == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    if (mode != NULL && mode[0] == 'r' && strchr(mode, '+') == NULL
            && is_proc_path(path)) {
        int fd = fake_proc_fd(path);
        if (fd >= 0) return fdopen(fd, mode);
    }
    return orig_fopen(path, mode);
}

static int property_lookup(const char *name, char *value) {
    if (name == NULL || value == NULL) return -1;
    int found = -1;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < prop_count; i++) {
        if (prop_keys[i] != NULL && prop_values[i] != NULL
                && strcmp(prop_keys[i], name) == 0) {
            size_t len = strlen(prop_values[i]);
            if (len > MAX_PROP_VALUE - 1) len = MAX_PROP_VALUE - 1;
            memcpy(value, prop_values[i], len);
            value[len] = '\0';
            found = (int) len;
            break;
        }
    }
    pthread_mutex_unlock(&prop_lock);
    return found;
}

static int cw_property_get(const char *name, char *value) {
    if (name != NULL && value != NULL) {
        int n = property_lookup(name, value);
        if (n >= 0) return n;
    }
    if (orig_property_get == NULL) return 0;
    return orig_property_get(name, value);
}

static const prop_info *cw_property_find(const char *name) {
    const prop_info *real = orig_property_find != NULL ? orig_property_find(name) : NULL;
    if (real != NULL || name == NULL) return real;
    const prop_info *sentinel = NULL;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < prop_count; i++) {
        if (prop_keys[i] != NULL && strcmp(prop_keys[i], name) == 0) {
            prop_sentinels[i].serial = 1U;
            sentinel = (const prop_info *) &prop_sentinels[i];
            break;
        }
    }
    pthread_mutex_unlock(&prop_lock);
    return sentinel;
}

typedef struct {
    prop_read_cb callback;
    void *cookie;
} prop_cb_ctx;

static void prop_cb_trampoline(void *ctx_ptr, const char *name, const char *value,
                               uint32_t serial) {
    prop_cb_ctx *ctx = (prop_cb_ctx *) ctx_ptr;
    if (name != NULL) {
        char buf[MAX_PROP_VALUE];
        if (property_lookup(name, buf) >= 0) {
            ctx->callback(ctx->cookie, name, buf, serial);
            return;
        }
    }
    ctx->callback(ctx->cookie, name, value, serial);
}

static void cw_property_read_callback(const prop_info *pi, prop_read_cb callback, void *cookie) {
    if (pi == NULL || callback == NULL) return;
    const cw_prop_sentinel *raw = (const cw_prop_sentinel *) pi;
    if (raw >= prop_sentinels && raw < prop_sentinels + MAX_PROPS) {
        int idx = (int) (raw - prop_sentinels);
        char key_buf[128];
        char val_buf[MAX_PROP_VALUE];
        int valid = 0;
        pthread_mutex_lock(&prop_lock);
        if (idx >= 0 && idx < prop_count && prop_keys[idx] != NULL && prop_values[idx] != NULL) {
            size_t klen = strlen(prop_keys[idx]);
            if (klen >= sizeof(key_buf)) klen = sizeof(key_buf) - 1;
            memcpy(key_buf, prop_keys[idx], klen);
            key_buf[klen] = '\0';
            size_t vlen = strlen(prop_values[idx]);
            if (vlen >= sizeof(val_buf)) vlen = sizeof(val_buf) - 1;
            memcpy(val_buf, prop_values[idx], vlen);
            val_buf[vlen] = '\0';
            valid = 1;
        }
        pthread_mutex_unlock(&prop_lock);
        if (valid) {
            callback(cookie, key_buf, val_buf, 1U);
        }
        return;
    }
    if (orig_property_read_callback == NULL) return;
    prop_cb_ctx ctx = { callback, cookie };
    orig_property_read_callback(pi, prop_cb_trampoline, &ctx);
}

typedef struct {
    dl_phdr_cb callback;
    void *data;
} dl_cb_ctx;

static int dl_cb_trampoline(struct dl_phdr_info *info, size_t size, void *ctx_ptr) {
    dl_cb_ctx *ctx = (dl_cb_ctx *) ctx_ptr;
    if (info != NULL && info->dlpi_name != NULL && info->dlpi_name[0] != '\0') {
        size_t nlen = strlen(info->dlpi_name);
        if (should_drop(info->dlpi_name, nlen)) {
            return 0;
        }
    }
    return ctx->callback(info, size, ctx->data);
}

static int cw_dl_iterate_phdr(dl_phdr_cb callback, void *data) {
    if (orig_dl_iterate_phdr == NULL) return 0;
    if (callback == NULL) return orig_dl_iterate_phdr(callback, data);
    dl_cb_ctx ctx = { callback, data };
    return orig_dl_iterate_phdr(dl_cb_trampoline, &ctx);
}

static void sanitize_ifaddrs(struct ifaddrs *head) {
    static const uint8_t fallback_ll[16] = {
            0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
    };
    uint8_t v6[16];
    int use_v6 = 0;
    pthread_mutex_lock(&prop_lock);
    if (has_spoofed_v6) {
        memcpy(v6, spoofed_v6, 16);
        use_v6 = 1;
    }
    pthread_mutex_unlock(&prop_lock);

    for (struct ifaddrs *ifa = head; ifa != NULL; ifa = ifa->ifa_next) {
        int vpn = is_vpn_iface(ifa->ifa_name)
                || (((ifa->ifa_flags & IFF_POINTOPOINT) != 0)
                        && ifa->ifa_name != NULL
                        && strcmp(ifa->ifa_name, "lo") != 0
                        && !is_cellular_iface(ifa->ifa_name));
        if (vpn) {
            ifa->ifa_flags &= ~(unsigned int) (IFF_UP | IFF_RUNNING);
        }
        if (ifa->ifa_addr != NULL && ifa->ifa_addr->sa_family == AF_INET6) {
            struct sockaddr_in6 *s6 = (struct sockaddr_in6 *) ifa->ifa_addr;
            if (is_global_v6_bytes(s6->sin6_addr.s6_addr)) {
                if (vpn || !use_v6) {
                    memcpy(s6->sin6_addr.s6_addr, fallback_ll, 16);
                } else {
                    memcpy(s6->sin6_addr.s6_addr, v6, 16);
                }
            }
        }
    }
}

static int cw_getifaddrs(struct ifaddrs **ifap) {
    if (orig_getifaddrs == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int rc = orig_getifaddrs(ifap);
    if (rc == 0 && ifap != NULL && *ifap != NULL) {
        sanitize_ifaddrs(*ifap);
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_walter_claudewilson_hook_NativeHooks_configure(JNIEnv *env, jclass clazz,
                                                        jobjectArray keys,
                                                        jobjectArray values) {
    (void) clazz;
    if (keys == NULL || values == NULL) return;
    jsize nk = (*env)->GetArrayLength(env, keys);
    jsize nv = (*env)->GetArrayLength(env, values);
    jsize n = nk < nv ? nk : nv;
    if (n > MAX_PROPS) n = MAX_PROPS;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < prop_count; i++) {
        free(prop_keys[i]);
        free(prop_values[i]);
        prop_keys[i] = NULL;
        prop_values[i] = NULL;
    }
    prop_count = 0;
    for (jsize i = 0; i < n; i++) {
        jstring jk = (jstring) (*env)->GetObjectArrayElement(env, keys, i);
        jstring jv = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        if (jk != NULL && jv != NULL) {
            const char *k = (*env)->GetStringUTFChars(env, jk, NULL);
            const char *v = (*env)->GetStringUTFChars(env, jv, NULL);
            if (k != NULL && v != NULL) {
                char *kd = strdup(k);
                char *vd = strdup(v);
                if (kd != NULL && vd != NULL) {
                    prop_keys[prop_count] = kd;
                    prop_values[prop_count] = vd;
                    prop_sentinels[prop_count].serial = 1U;
                    prop_count++;
                } else {
                    free(kd);
                    free(vd);
                }
            }
            if (k != NULL) (*env)->ReleaseStringUTFChars(env, jk, k);
            if (v != NULL) (*env)->ReleaseStringUTFChars(env, jv, v);
        }
        if (jk != NULL) (*env)->DeleteLocalRef(env, jk);
        if (jv != NULL) (*env)->DeleteLocalRef(env, jv);
    }
    pthread_mutex_unlock(&prop_lock);
    native_ready = 1;
}

JNIEXPORT void JNICALL
Java_com_walter_claudewilson_hook_NativeHooks_configureIpv6(JNIEnv *env, jclass clazz,
                                                            jbyteArray addr16) {
    (void) clazz;
    pthread_mutex_lock(&prop_lock);
    has_spoofed_v6 = 0;
    memset(spoofed_v6, 0, sizeof(spoofed_v6));
    memset(spoofed_v6_hex, 0, sizeof(spoofed_v6_hex));
    if (addr16 != NULL && (*env)->GetArrayLength(env, addr16) == 16) {
        jbyte buf[16];
        (*env)->GetByteArrayRegion(env, addr16, 0, 16, buf);
        for (int i = 0; i < 16; i++) {
            uint8_t b = (uint8_t) buf[i];
            spoofed_v6[i] = b;
            snprintf(spoofed_v6_hex + (i * 2), 3, "%02x", (unsigned int) b);
        }
        spoofed_v6_hex[32] = '\0';
        has_spoofed_v6 = 1;
    }
    pthread_mutex_unlock(&prop_lock);
}

JNIEXPORT void JNICALL
Java_com_walter_claudewilson_hook_NativeHooks_configureHiddenIfaces(JNIEnv *env, jclass clazz,
                                                                    jobjectArray ifaces) {
    (void) clazz;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < hidden_iface_count; i++) {
        free(hidden_ifaces[i]);
        hidden_ifaces[i] = NULL;
    }
    hidden_iface_count = 0;
    if (ifaces != NULL) {
        jsize n = (*env)->GetArrayLength(env, ifaces);
        if (n > MAX_HIDDEN_IFACES) n = MAX_HIDDEN_IFACES;
        for (jsize i = 0; i < n; i++) {
            jstring js = (jstring) (*env)->GetObjectArrayElement(env, ifaces, i);
            if (js != NULL) {
                const char *s = (*env)->GetStringUTFChars(env, js, NULL);
                if (s != NULL && s[0] != '\0') {
                    char *dup = strdup(s);
                    if (dup != NULL) {
                        hidden_ifaces[hidden_iface_count++] = dup;
                    }
                }
                if (s != NULL) (*env)->ReleaseStringUTFChars(env, js, s);
                (*env)->DeleteLocalRef(env, js);
            }
        }
    }
    pthread_mutex_unlock(&prop_lock);
}

JNIEXPORT jboolean JNICALL
Java_com_walter_claudewilson_hook_NativeHooks_ready(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return native_ready ? JNI_TRUE : JNI_FALSE;
}

__attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries == NULL || entries->hook_func == NULL) return NULL;
    hook_func = entries->hook_func;

    void *target = dlsym(RTLD_DEFAULT, "open");
    if (target != NULL) hook_func(target, (void *) cw_open, (void **) &orig_open);
    target = dlsym(RTLD_DEFAULT, "openat");
    if (target != NULL) hook_func(target, (void *) cw_openat, (void **) &orig_openat);
    target = dlsym(RTLD_DEFAULT, "fopen");
    if (target != NULL) hook_func(target, (void *) cw_fopen, (void **) &orig_fopen);
    target = dlsym(RTLD_DEFAULT, "__system_property_get");
    if (target != NULL) hook_func(target, (void *) cw_property_get,
                                  (void **) &orig_property_get);
    target = dlsym(RTLD_DEFAULT, "__system_property_find");
    if (target != NULL) hook_func(target, (void *) cw_property_find,
                                  (void **) &orig_property_find);
    target = dlsym(RTLD_DEFAULT, "__system_property_read_callback");
    if (target != NULL) hook_func(target, (void *) cw_property_read_callback,
                                  (void **) &orig_property_read_callback);
    target = dlsym(RTLD_DEFAULT, "dl_iterate_phdr");
    if (target != NULL) hook_func(target, (void *) cw_dl_iterate_phdr,
                                  (void **) &orig_dl_iterate_phdr);
    target = dlsym(RTLD_DEFAULT, "getifaddrs");
    if (target != NULL) hook_func(target, (void *) cw_getifaddrs,
                                  (void **) &orig_getifaddrs);
    return NULL;
}
