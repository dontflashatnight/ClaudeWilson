/*
 * Host-side (Termux) tests for the pure native helper logic. This file is not packaged.
 * It includes cwpersona.c so the static sanitizer and property table can be exercised without
 * launching the target process.
 */
#include "cwpersona.c"

#include <assert.h>
#include <string.h>

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
    assert(is_proc_path("/system/lib64/libc.so") == 0);

    prop_keys[0] = strdup("ro.product.model");
    prop_values[0] = strdup("SM-S928B");
    prop_count = 1;
    char value[MAX_PROP_VALUE];
    assert(property_lookup("ro.product.model", value) == 8);
    assert(strcmp(value, "SM-S928B") == 0);
    assert(property_lookup("ro.product.brand", value) == -1);

    puts("filter-test: maps/status/property checks passed");
    return 0;
}
