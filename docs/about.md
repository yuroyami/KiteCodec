# About KiteCodec

**One coroutine-first Kotlin API for video and audio.** KiteCodec binds Kotlin/Native directly to FFmpeg's libav\* libraries, so you decode, encode, transcode and filter media from a single suspend-friendly surface. No `ffmpeg` subprocess, no JVM, no JNI layer, and constant memory regardless of input length.

This page covers the project's current status, its roadmap, the binding architecture, and the license. For the API itself, start with [Getting started](getting-started.md) or the [API reference](https://yuroyami.github.io/KiteCodec/api/).

## Approach

Media work from Kotlin normally means launching the `ffmpeg` CLI and parsing its stderr output, or wrapping a prebuilt binary like FFmpegKit. You build arguments into a string, launch a process, and read progress back out of log lines.

KiteCodec calls the libraries directly. `Transcoder.transcode(...)` opens the file via libavformat, demuxes once, routes packets to per-stream libavcodec decoders, pushes frames through libavfilter graphs, encodes, and interleaves the streams into a valid container. Progress arrives as a typed callback, errors arrive as typed exceptions, and frames flow as a coroutine `Flow`.

Everything routes through a single demux pass. When you decode several streams, or composite two inputs, the demuxer reads the file once and fans packets out to the decoders that need them.

## Current Status

KiteCodec is pre-1.0 and actively developed. The full **demux -> decode -> filter -> encode -> mux** pipeline is live for both video and audio, in a single pass, for every published target. The Kotlin library is complete; the binary distribution it needs is not.

There is one status table for the whole project, and it lives in the [README](https://github.com/yuroyami/KiteCodec#targets). It records, per target, whether the target is in the published set, what CI builds and tests, and where FFmpeg comes from.

The two things a reader most often needs from it:

- **KiteCodec cannot be consumed from Maven Central today.** Neither `kitecodec-core` nor the Gradle plugin has been published, and the FFmpeg Release assets the plugin's default `FFmpegSource.Prebuilt` downloads do not exist. See [Release status](https://github.com/yuroyami/KiteCodec#release-status) for the blocker.
- **There is no JVM target**, and no web target of any kind. `nativeMain` is the only implementation source set, and every published artifact is a Kotlin/Native klib.

!!! note "Frame ownership"
    Frames emitted by the public `Flow` APIs (`MediaSource.decodedFrames`, `MediaSource.decodeStreams`, `FilterGraph.process`) are **owned by the collector**. Each stays valid until you close it, so buffering operators such as `buffer()` and `toList()` are safe. Every collected frame must be closed, or its native buffers leak. Frames passed to a callback (`FilterGraph.feedInput`'s `onOutput`) are valid only for the duration of that call. `Frame.copy()` takes an O(1) owned snapshot. The native `AVFrame*` is deliberately not exposed in `commonMain`.

## What's next

- **The FFmpeg binary release**: unblocking `release-binaries.yml` is what turns `FFmpegSource.Prebuilt` from a 404 into the default path, and is the prerequisite for publishing `kitecodec-core` at all.
- **Android AAR**: a JNI substrate so `androidTarget` (regular Android apps on Kotlin/JVM) gets the same API, over the same `ffkmp_*` C helpers. FFmpegKit's retirement left that niche empty.
- **Bitstream filters** (h264 to/from Annex B) so stream copy reaches MPEG-TS.
- **Hardware decode and full hwframes pipelines**: zero-copy VideoToolbox / CUDA.
- **iOS**: nothing in this repository can cross-build an FFmpeg for iOS today, so the iOS targets compile only against a tree you produce yourself. That build chain comes first, CI verification after.

## Architecture

### One consolidated cinterop module

The binding is **one** cinterop module (`kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`) that includes every libav\* header behind a single Kotlin package (`ffmpeg.*`).

The pragmatic alternative, six separate cinterops (one per library), does not work. It produces six duplicate `AVCodec` / `AVFrame` / `AVPacket` types that will not pass across module boundaries. A frame decoded through one cinterop's `AVFrame` cannot be handed to a filter graph built against another cinterop's `AVFrame`. Consolidating into a single module is the only way to keep the types unified across decode -> filter -> encode.

### The `ffkmp_*` C helpers

The `.def` file also exports 141 small `static inline` C helpers, all prefixed `ffkmp_*`. They exist because some of FFmpeg's surface does not survive cinterop cleanly:

- **Macros that don't survive cinterop.** `av_err2str` is a compound-statement macro; `AVERROR(EAGAIN)` and `AVERROR_EOF` are function-style macros. Each gets a real C function the indexer can actually read.
- **Struct field accessors.** `ffkmp_stream_codecpar(AVStream*)`, `ffkmp_frame_pts(AVFrame*)`, and similar accessors. Modern FFmpeg marks many fields "do not access directly," and several vary across libav versions. A thin C accessor pins one stable read path per field.
- **128-bit-safe timestamp math.** `ffkmp_rescale_q` wraps `av_rescale_q`, the only overflow-safe way to convert timestamps between time-bases.
- **Double-pointer ceremony for alloc/free pairs.** `avformat_close_input(AVFormatContext**)` and similar become single-pointer wrappers that Kotlin/Native interop calls cleanly.
- **One-shot pipeline helpers.** `ffkmp_fmt_open_input(...)` does alloc plus open in one call; `ffkmp_graph_build_video(...)` / `ffkmp_graph_build_audio(...)` build a complete buffer -> chain -> buffersink graph from a single filter description. The audio variant appends `aformat` so output arrives ready for the encoder. Filter-string syntax stays stable across FFmpeg versions, while buffersink option names do not.

### Source layout

```
kitecodec-core/src/
├── nativeInterop/cinterop/
│   └── ffmpeg.def                   ← unified cinterop + C helpers
├── commonMain/kotlin/io/github/yuroyami/kitecodec/
│   ├── FFmpeg.kt                    ← capability probing + Versions
│   ├── MediaSource.kt               ← demuxer + decode flows (expect)
│   ├── Frame.kt                     ← AVFrame wrapper
│   ├── FilterGraph.kt               ← video + audio graph wrappers
│   ├── MediaSink.kt                 ← muxer + Video/AudioEncoder + specs
│   ├── Transcoder.kt                ← high-level one-pass A/V pipeline (expect)
│   ├── StreamInfo.kt / Errors.kt
│   └── MediaType.kt / Rational.kt   ← value types for FFmpeg's small types
└── nativeMain/kotlin/io/github/yuroyami/kitecodec/
    ├── FFmpeg.native.kt
    ├── MediaSource.native.kt        ← single-pass multi-stream decode loop
    ├── Frame.native.kt              ← frame acquire / wrap
    ├── FilterGraph.native.kt        ← Flow + push-style graph driving
    ├── MediaSink.native.kt          ← muxer + shared encode core
    ├── Transcoder.native.kt         ← interleaved A/V orchestration
    └── Internals.kt                 ← error mapping, format mapping
```

Every `expect` class in `commonMain` has its `actual` in `nativeMain`. All public types live flat under `io.github.yuroyami.kitecodec`, with no internal subpackages.

### Timestamp handling

KiteCodec follows `ffmpeg.c`'s own rules at every stage:

- **Decode.** Decoders promote `best_effort_timestamp` to `pts`, so files with missing or broken pts still cut correctly.
- **Filter.** Filter graphs report their **output** time-base, which is not always the input's: `fps` and `atempo` change it. Frames leaving a graph are stamped with the output time-base. The `FilterGraph.outputTimeBase` property exposes it.
- **Encode.** Encoders rescale incoming pts onto the codec time-base via `av_rescale_q` and force strict monotonicity. Frames with no pts at all fall back to a synthetic timeline: frame count for video, accumulated sample count for audio.
- **Mux.** Packet timestamps are rescaled once more onto whatever stream time-base the muxer actually chose after `avformat_write_header`.

!!! tip "Why `Rational` is its own type"
    FFmpeg time-bases and frame rates are exact fractions, not floats. `Rational` is always normalized, and its `times(scalar: Long)` operator does overflow-safe rescaling. Use it instead of converting to seconds and back, where rounding accumulates. See the [API reference](https://yuroyami.github.io/KiteCodec/api/) for the full `Rational` surface.

## FFmpeg sourcing

KiteCodec links against an FFmpeg you provide. In a consumer project the [Gradle plugin](gradle-plugin.md) does the providing; inside this repository there are two modes:

=== "Dynamic (default)"

    Links against a system FFmpeg. This is what the macOS arm64 build does today. The Gradle build finds Homebrew on macOS or apt-installed libraries on Linux, points cinterop at their headers, and links the dylibs. Your users need their own FFmpeg installed at run time.

    ```bash
    brew install ffmpeg                     # macOS
    sudo apt install ffmpeg libavcodec-dev libavformat-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev   # Linux
    ```

=== "Vendored static (release)"

    Cross-compiles a minimal FFmpeg from source through a Gradle task, with a pinned codec and filter set, and drops `.a` libraries under `native-libs/<license>/<target>/` (`lgpl` by default; `gpl` for the opt-in `Gpl` task variants). The build notices them and switches cinterop to static linking, so the resulting binary carries everything it needs.

    ```bash
    git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
    ./gradlew :kitecodec-core:buildFFmpegForMacosArm64   # or :buildFFmpegForAll
    ```

See [Platform support](platforms.md) for the per-target detail.

## License

KiteCodec's own code is licensed under the **Apache License 2.0**. You can freely use, modify, and distribute it in commercial and open-source projects.

The FFmpeg you link against carries its own license, separate from KiteCodec's. It is **LGPL-2.1+** when FFmpeg is built without `--enable-gpl`, and **GPL** with it. KiteCodec's GPL build flavor also sets `--enable-version3`, so its effective license is **GPL-3.0**. The default flavor is LGPL and is commercial- and App-Store-safe (with the usual [LGPL distribution obligations](licensing.md)). A `kitecodec-gpl` module that would package the GPL flavor (libx264 / libx265) as a drop-in artifact does not exist: it is a README and nothing else, with no `build.gradle.kts`, commented out of `settings.gradle.kts`.

When you build a vendored static FFmpeg, the license flavor is a build-time choice: `buildFFmpegFor<Target>` produces the LGPL default, `buildFFmpegFor<Target>Gpl` the GPL opt-in (selected with `-Pkitecodec.ffmpeg.license=gpl`). Stay on the LGPL default if you ship through a GPL-hostile distribution channel such as the iOS App Store. Full compliance guidance lives in the [Licensing guide](licensing.md).

## Acknowledgments

- **FFmpeg** and the libav\* libraries: the codec, container, and filter engine KiteCodec binds to.
- **`ffmpeg.c`**: the reference for correct demux, decode, filter, encode, and mux orchestration, especially timestamp handling.
