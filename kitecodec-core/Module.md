# Module kitecodec-core

A coroutine-first Kotlin Multiplatform API over FFmpeg's libav* libraries.

`MediaSource` to demux and decode, `FilterGraph` for any FFmpeg filter chain,
`MediaSink` to encode and mux, and `Transcoder`/`Remuxer` for the whole pipeline
in one call. Kotlin/Native actuals use cinterop; the local Android proof uses a dynamically
registered JNI adapter over the same opaque C helper boundary, tested by an unpublished JVM
harness. Public JVM, JS and WasmJs use an invariant unsupported placeholder: diagnostics are
readable, capabilities are empty, and media operations fail with typed
`FFmpegError.Unsupported`. There is no subprocess. Native FFmpeg binaries are supplied at
build time rather than bundled in the Kotlin sources; no functional public JVM jar or Android
AAR exists yet.
