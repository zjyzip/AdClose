# Native regression tests

These tests compile the production `native_hook.cpp`, `http2_parser.cpp`, and vendored nghttp2 HPACK implementation into a POSIX executable. ShadowHook registration is stubbed. A JNI dispatch stub verifies that an H2 match cannot abort the shared SSL connection, while H1 blocking still returns the expected errors. No Android device or running JVM is needed.

The suite covers split prefaces and HEADERS/CONTINUATION, bidirectional header blocks and trailers, informational responses, HPACK dynamic tables across PUSH_PROMISE, RST/GOAWAY cleanup, independent request/response completion, large streaming DATA frames and padding, capture disablement, header/stream/connection limits, concurrent feed results, partial writes and retries, UTF-8 conversion, fd reuse, errno, MSG_PEEK, scoped Java/Native capture ownership, and SSL error/free behavior.

On Linux with a C/C++ compiler:

```sh
python app/src/test/cpp/run_native_tests.py \
  --jni "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h" \
  --sanitize
```

On Windows, Zig can cross-compile the executable for a WSL distribution:

```powershell
python app/src/test/cpp/run_native_tests.py --zig C:/Tools/zig/zig.exe --wsl Ubuntu --jni C:/Android/ndk/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include/jni.h --sanitize
```

Omit `--sanitize` to run the ordinary optimized build. The runner explicitly enables assertions in both modes. Artifacts are written under the ignored `build/native-tests/` directory. JNI strings and callback signatures are also compiled for Android by the normal Gradle build; the host stubs do not validate ART or live ShadowHook patching.

The Kotlin `H2BodyStoreTest` suite separately checks body budgets, eviction, late chunks, stream/connection closure, and capture changes.

## Runtime boundaries

- Native fd capture observes Internet stream sockets. Datagram and Unix sockets are excluded from the HTTP stream parser.
- Each native connection gets a new 64-bit ID. fd entries validate device/inode identity. Missing optional SSL symbols no longer disable available SSL read/write capture.
- H1 writes are observed before forwarding so rules can block them. Short writes and retries skip previously observed prefixes. If the retry diverges or would retain over 64 KiB, observation is discarded for that connection and original I/O continues.
- H2 TLS observation does not abort the connection when a rule matches and never injects RST bytes. URL blocking occurs at an identifiable request boundary, such as OkHttp newStream, WebView, or Cronet. Custom Native H2 implementations visible only at TLS level are observed without per-URL enforcement.
- H2 parsing retains at most 512 connections, 1000 streams per connection, 64 KiB per header block, and 1 MiB of stream metadata per connection. DATA frames are streamed in 64 KiB pieces and support the protocol's 24-bit frame length; a DATA frame over 256 KiB no longer disables capture. Malformed or oversized metadata disables observation without altering original I/O.
- H2 bodies are limited to 5 MiB each and 32 MiB of logical bytes across streams. Each direction is published when that direction ends. Eviction or overflow discards the entire affected body; later chunks cannot restart it with a misleading tail.
- Java capture stays enabled when Native capture is enabled. Native only skips the current thread's operations while a supported Java hook owns the same call.

See [network-regression-review.md](../../../../docs/network-regression-review.md) for the changes that caused regressions and the recovery decisions.
