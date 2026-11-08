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
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#define MAX_FAKE_BYTES (16 * 1024 * 1024)

static HookFunType hook_func;
static int (*orig_open)(const char *, int, ...);
static int (*orig_openat)(int, const char *, int, ...);
static FILE *(*orig_fopen)(const char *, const char *);
static int (*orig_property_get)(const char *, char *);

static pthread_mutex_t prop_lock = PTHREAD_MUTEX_INITIALIZER;
static char *prop_keys[MAX_PROPS];
static char *prop_values[MAX_PROPS];
static int prop_count;
static volatile int native_ready;

static int is_proc_path(const char *path) {
    if (path == NULL || strncmp(path, "/proc/", 6) != 0) return 0;
    const char *base = strrchr(path, '/');
    if (base == NULL) return 0;
    base++;
    return strcmp(base, "maps") == 0 || strcmp(base, "smaps") == 0
            || strcmp(base, "status") == 0 || strcmp(base, "stat") == 0;
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
    int fd = (int) syscall(SYS_memfd_create, "cwpersona", MFD_CLOEXEC);
    if (fd < 0) {
        free(data);
        return -1;
    }
    ssize_t written = 0;
    while (written < clean) {
        ssize_t n = write(fd, data + written, (size_t) (clean - written));
        if (n <= 0) {
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
    int found = -1;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < prop_count; i++) {
        if (strcmp(prop_keys[i], name) == 0) {
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
    int n = property_lookup(name, value);
    if (n >= 0) return n;
    if (orig_property_get == NULL) return 0;
    return orig_property_get(name, value);
}

JNIEXPORT void JNICALL
Java_com_walter_claudewilson_hook_NativeHooks_configure(JNIEnv *env, jclass clazz,
                                                        jobjectArray keys,
                                                        jobjectArray values) {
    (void) clazz;
    if (keys == NULL || values == NULL) return;
    jsize n = (*env)->GetArrayLength(env, keys);
    if (n > MAX_PROPS) n = MAX_PROPS;
    pthread_mutex_lock(&prop_lock);
    for (int i = 0; i < prop_count; i++) {
        free(prop_keys[i]);
        free(prop_values[i]);
    }
    prop_count = 0;
    for (jsize i = 0; i < n; i++) {
        jstring jk = (jstring) (*env)->GetObjectArrayElement(env, keys, i);
        jstring jv = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        if (jk == NULL || jv == NULL) continue;
        const char *k = (*env)->GetStringUTFChars(env, jk, NULL);
        const char *v = (*env)->GetStringUTFChars(env, jv, NULL);
        if (k != NULL && v != NULL) {
            prop_keys[prop_count] = strdup(k);
            prop_values[prop_count] = strdup(v);
            prop_count++;
        }
        if (k != NULL) (*env)->ReleaseStringUTFChars(env, jk, k);
        if (v != NULL) (*env)->ReleaseStringUTFChars(env, jv, v);
        (*env)->DeleteLocalRef(env, jk);
        (*env)->DeleteLocalRef(env, jv);
    }
    pthread_mutex_unlock(&prop_lock);
    native_ready = 1;
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
    return NULL;
}
