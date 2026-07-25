# Module kitecodec-core

A coroutine-first Kotlin API over FFmpeg's libav* libraries.

`MediaSource` to demux and decode, `FilterGraph` for any FFmpeg filter chain,
`MediaSink` to encode and mux, and `Transcoder`/`Remuxer` for the whole pipeline
in one call. Kotlin/Native cinterop bindings, so there is no subprocess and no
JNI hop. The FFmpeg binaries are supplied at build time by the KiteCodec Gradle
plugin, not bundled here.
