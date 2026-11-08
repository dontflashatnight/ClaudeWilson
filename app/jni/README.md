# Native cloak build

The APK packages `app/libs/arm64-v8a/libcwpersona.so`. `app/build.py` does not compile it; it
packages the prebuilt artifact so the normal APK build remains JDK-only.

## Current build (on-device Termux clang)

The library was built with Termux clang 21.1.8, target `aarch64-unknown-linux-android24`:

```sh
PREFIX=/data/data/com.termux/files/usr
LD_LIBRARY_PATH=$PREFIX/lib $PREFIX/bin/clang \
  -shared -fPIC -O2 -Wall -Wextra \
  -I"$PREFIX/include" \
  -o libcwpersona.so cwpersona.c -ldl
```

Then copy `libcwpersona.so` to `app/libs/arm64-v8a/`.

## Symbol check

```sh
$PREFIX/bin/nm -D libcwpersona.so
```

Expected exports:

- `native_init`
- `Java_com_walter_claudewilson_hook_NativeHooks_configure`
- `Java_com_walter_claudewilson_hook_NativeHooks_ready`

## Load test

`loadtest.c` calls `dlopen` and `native_init` with a mock hook API. It verifies that the ELF loads
and that `native_init` registers four hooks. It is not part of the APK.

## Follow-up

Replace this with an NDK/CMake build for reproducible arm64 + 16KB-page output, and add
`armeabi-v7a` only if a supported target actually needs it.
