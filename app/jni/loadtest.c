#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

typedef int (*HookFunType)(void *, void *, void **);
typedef int (*UnhookFunType)(void *);
typedef void (*NativeOnModuleLoaded)(const char *, void *);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;
typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *);

static int mock_hook(void *func, void *replace, void **backup) {
    printf("hook func=%p replace=%p\n", func, replace);
    if (backup != NULL) *backup = NULL;
    return 0;
}

static int mock_unhook(void *func) {
    (void) func;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <lib>\n", argv[0]);
        return 2;
    }
    void *handle = dlopen(argv[1], RTLD_NOW);
    if (handle == NULL) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
    NativeInit init = (NativeInit) dlsym(handle, "native_init");
    if (init == NULL) {
        fprintf(stderr, "dlsym: %s\n", dlerror());
        return 1;
    }
    NativeAPIEntries entries = {1, mock_hook, mock_unhook};
    init(&entries);
    printf("loaded lib=%s handle=%p native_init=%p\n", argv[1], handle, (void *) init);
    return 0;
}
