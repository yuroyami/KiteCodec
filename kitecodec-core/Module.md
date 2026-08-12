# Module kitecodec-core

A coroutine-first Kotlin Multiplatform API over FFmpeg's libav* libraries.

`MediaSource` to demux and decode, `FilterGraph` for any FFmpeg filter chain,
`MediaSink` to encode and mux, and `Transcoder`/`Remuxer` for the whole pipeline
in one call. Kotlin/Native actuals use cinterop; JVM and Android actuals use a
dynamically registered JNI adapter over the same opaque C helper boundary. There
is no subprocess. Native FFmpeg binaries are supplied at build time rather than
bundled in the Kotlin sources; no public JVM jar or Android AAR exists yet.
