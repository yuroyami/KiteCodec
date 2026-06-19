# About KiteCodec

**One coroutine-first Kotlin API for video and audio.** KiteCodec binds Kotlin/Native directly to FFmpeg's libav\* libraries, so you decode, encode, transcode and filter media from a single suspend-friendly surface. No `ffmpeg` subprocess, no JVM, no JNI hop, and constant memory regardless of input length.

This page covers the project's current status, its roadmap, the binding architecture, and the licence. For the API itself, start with [Getting started](getting-started.md) or the [API reference](https://yuroyami.github.io/KiteCodec/api/).

## Philosophy

The usual way to do media work from Kotlin is to shell out to the `ffmpeg` CLI and scrape its stderr, or to wrap a binary like FFmpegKit. You marshal arguments into a string, launch a process, and read progress back out of log lines. The codec engine lives outside your program.

KiteCodec is the opposite. You call `Transcoder.transcode(...)` and it opens the file via libavformat, demuxes once, routes packets to per-stream libavcodec decoders, pumps frames through libavfilter graphs, encodes, and interleaves the streams into a valid container. There is no process to spawn and no log to scrape. Progress arrives as a typed callback, errors arrive as typed exceptions, and frames flow as a coroutine `Flow`.

Everything routes through a single demux pass. When you decode several streams, or composite two inputs, the demuxer reads the file once and fans packets out to the decoders that need them.

## Current Status

KiteCodec is pre-1.0 and actively developed. The full **demux -> decode -> filter -> encode -> mux** pipeline is live for both video and audio, in a single pass. The table below is the honest summary of what works today.

| Surface | Status | Notes |
|---|:---:|---|
| Capability probing: `FFmpeg.versions`, `hasEncoder/Decoder/Filter` | Yes | |
| `MediaSource.open(path)` + `streams` + `metadata` + `seekMicros` | Yes | demuxer wraps `AVFormatContext` |
| `MediaSource.decodedFrames(stream): Flow<Frame>` | Yes | EAGAIN-correct decode loop, best-effort pts |
| `MediaSource.decodeStreams(streams): Flow<Frame>` | Yes | several streams, one demux pass |
| `Frame.copyPlanesToByteArray()` | Yes | video planes and audio samples |
| `FilterGraph.buildVideo(...)` / `buildAudio(...)` | Yes | any FFmpeg filter chain; audio output pinned encoder-ready |
| `MediaSink.addVideoEncoder(spec)` / `addAudioEncoder(spec)` | Yes | shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options` (`crf`, `preset`, ...) |
| `MediaSink.addCopyStream(...)`: stream copy | Yes | no decode/encode, timestamp rescale only |
| `Remuxer.remux(...)`: lossless container rewrite | Yes | mp4 / mkv / mov in seconds, bit-exact, keyframe-snapped trim |
| `Transcoder.transcode(...)` | Yes | one-call A/V pipeline: frame-exact trim, `audioCopy`, `subtitleCopy`, metadata, progress |
| Audio-only transcode (mp3 to aac etc.) | Yes | `spec = null` |
| Trim / clip extraction (`startMicros` / `endMicros`) | Yes | frame-exact re-encode; keyframe-snapped copy |
| `MediaSource.extractFrame` + `Frame.encodeImage` | Yes | thumbnails: seek -> decode -> jpg/png bytes |
| `Frame.copy()` | Yes | O(1) owned snapshot, escapes the reuse rule |
| Multi-input filter graphs (overlay, amix) | Yes | `buildVideoMulti` / `buildAudioMulti`, `[in0]...[inN-1]` -> `[out]` |
| Hardware encode: `h264_videotoolbox` | Yes | verified on macOS arm64; `allow_sw` option for VMs |
| Hardware decode / full hwframes pipeline | Planned | on the way |

!!! note "Frame ownership"
    A `Frame` is valid only until the next emission or until the flow closes. The native `AVFrame*` is deliberately not exposed in `commonMain`. When you need to hold a frame past that window, call `Frame.copy()` for an O(1) owned snapshot.

### Platform support

KiteCodec is a Kotlin/Native library today. The matrix below records what is verified versus written-but-not-yet-confirmed. See [Platform support](platforms.md) for the full picture and how FFmpeg is sourced per target.

| Target | Status | Notes |
|---|:---:|---|
| macOS arm64 | Yes | verified end-to-end (video+audio, ffprobe-validated) |
| Linux x64, Windows (mingw x64) | CI | builds, tests, and e2e transcodes on every push |
| Android arm64/arm32/x64 (Kotlin/Native klib) | CI | FFmpeg cross-compiled with the NDK (LGPL profile + MediaCodec), klib built |
| Android AAR for JVM apps | Planned | next milestone: JNI substrate over the same `ffkmp_*` C layer |
| macOS x64, iOS arm64, iOS sim, Linux arm64 | Planned | code written; iOS needs vendored FFmpeg in CI |

!!! warning "FFmpeg is a prerequisite"
    KiteCodec is **not on Maven Central yet**. Today you build from source against an FFmpeg you provide, either installed through your package manager or produced by the bundled FFmpeg build tasks. The published coordinate is `io.github.yuroyami:kitecodec-core:0.0.1`, built locally for now.

## What's next

- **Android AAR**: a JNI substrate so `androidTarget` (regular Android apps on Kotlin/JVM) gets the same API, over the same `ffkmp_*` C helpers. FFmpegKit's retirement left that niche empty.
- **Maven Central publishing**: so consumers can `implementation(...)` instead of building from source.
- **Bitstream filters** (h264 to/from Annex B) so stream copy reaches MPEG-TS.
- **Hardware decode and full hwframes pipelines**: zero-copy VideoToolbox / CUDA.
- **iOS CI verification**: vendored FFmpeg cross-compile, so the written-but-unverified iOS path becomes a checked target.

## Architecture

This section is consolidated here from the README so the guides can stay focused on the API.

### One consolidated cinterop module

The binding is **one** cinterop module (`kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`) that includes every libav\* header behind a single Kotlin package (`ffmpeg.*`).

The pragmatic alternative, six separate cinterops (one per library), does not work. It produces six duplicate `AVCodec` / `AVFrame` / `AVPacket` types that will not pass across module boundaries. A frame decoded through one cinterop's `AVFrame` cannot be handed to a filter graph built against another cinterop's `AVFrame`. Consolidating into a single module is the only way to keep the types unified across decode -> filter -> encode.

### The ~100 `ffkmp_*` C helpers

The `.def` file also exports around 100 small `static inline` C helpers, all prefixed `ffkmp_*`. They exist because some of FFmpeg's surface does not survive cinterop cleanly:

- **Macros that don't survive cinterop.** `av_err2str` is a compound-statement macro; `AVERROR(EAGAIN)` and `AVERROR_EOF` are function-style macros. Each gets a real C function the indexer can actually read.
- **Struct field accessors.** `ffkmp_stream_codecpar(AVStream*)`, `ffkmp_frame_pts(AVFrame*)`, and friends. Modern FFmpeg marks many fields "do not access directly," and several vary across libav versions; a thin C accessor pins one stable read path per field.
- **128-bit-safe timestamp math.** `ffkmp_rescale_q` wraps `av_rescale_q`, the only overflow-safe way to convert timestamps between time-bases.
- **Double-pointer ceremony for alloc/free pairs.** `avformat_close_input(AVFormatContext**)` and similar become single-pointer wrappers that Kotlin/Native interop calls cleanly.
- **One-shot pipeline helpers.** `ffkmp_fmt_open_input(...)` does alloc plus open in one call; `ffkmp_graph_build_video(...)` / `ffkmp_graph_build_audio(...)` build a complete buffer -> chain -> buffersink graph from a single filter description. The audio variant appends `aformat` so output arrives encoder-ready, because filter-string syntax (unlike buffersink option names) never churns across FFmpeg versions.

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

### Timestamps, the part everyone gets wrong

Timestamps are where most homegrown FFmpeg wrappers quietly corrupt their output. KiteCodec follows `ffmpeg.c`'s own discipline at every hop:

- **Decode.** Decoders promote `best_effort_timestamp` to `pts`, so files with missing or broken pts still cut correctly.
- **Filter.** Filter graphs report their **output** time-base, which is not always the input's: `fps` and `atempo` change it. Frames leaving a graph are stamped with the output time-base. The `FilterGraph.outputTimeBase` property exposes it.
- **Encode.** Encoders rescale incoming pts onto the codec time-base via `av_rescale_q` and force strict monotonicity. Frames with no pts at all fall back to a synthetic timeline: frame count for video, accumulated sample count for audio.
- **Mux.** Packet timestamps are rescaled once more onto whatever stream time-base the muxer actually chose after `avformat_write_header`.

!!! tip "Why `Rational` is its own type"
    FFmpeg time-bases and frame rates are exact fractions, not floats. `Rational` is always normalized, and its `times(scalar: Long)` operator does overflow-safe rescaling. Reach for it instead of converting to seconds and back, where rounding accumulates. See the [API reference](https://yuroyami.github.io/KiteCodec/api/) for the full `Rational` surface.

## FFmpeg sourcing

KiteCodec links against an FFmpeg you provide. There are two modes:

=== "Dynamic (default)"

    Links against a system FFmpeg. This is what the macOS arm64 build does today. The Gradle build finds Homebrew on macOS or apt-installed libraries on Linux, points cinterop at their headers, and links the dylibs. Your users need their own FFmpeg installed at run time.

    ```bash
    brew install ffmpeg                     # macOS
    sudo apt install ffmpeg libavcodec-dev libavformat-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev   # Linux
    ```

=== "Vendored static (release)"

    Cross-compiles a minimal FFmpeg from source through a Gradle task, with a pinned codec and filter set, and drops `.a` libraries under `native-libs/<target>/`. The build notices them and switches cinterop to static linking, so the resulting binary carries everything it needs.

    ```bash
    ./gradlew :kitecodec-core:buildFFmpegForMacosArm64   # or :buildFFmpegForAll
    ```

See [Platform support](platforms.md) for the per-target detail.

## Licence

KiteCodec's own code is licensed under the **Apache License 2.0**. You can freely use, modify, and distribute it in commercial and open-source projects.

The FFmpeg you link against carries its own licence, separate from KiteCodec's. It is typically **LGPL-2.1+** when FFmpeg is built without `--enable-gpl`, and **GPL-2.0+** with it. The default `kitecodec-core` profile targets the LGPL build and is commercial and App-Store safe. A `kitecodec-gpl` module that adds libx264 / libx265 for quality-focused software encode is planned for GPL-compatible projects only.

When you build a vendored static FFmpeg, the build script controls which licence ladder you opt into. Switch `--enable-gpl` off if you ship through a GPL-hostile distribution channel such as the iOS App Store.

## Acknowledgements

- **FFmpeg** and the libav\* libraries: the codec, container, and filter engine KiteCodec binds to.
- **`ffmpeg.c`**: the reference for correct demux, decode, filter, encode, and mux orchestration, especially timestamp handling.
