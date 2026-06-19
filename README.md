# KiteCodec

[![Docs](https://img.shields.io/badge/docs-kitecodec.github.io-1f6feb)](https://yuroyami.github.io/KiteCodec/)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteCodec/ci.yml?label=CI)](https://github.com/yuroyami/KiteCodec/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)
![status](https://img.shields.io/badge/status-v0.3%20A%2FV%20transcoding%20live-7C5CFF)

**One coroutine-first codec API for Kotlin Multiplatform. Decode, encode, transcode and filter video and audio from common code, backed by FFmpeg.**

> ## 📖 [Read the documentation →](https://yuroyami.github.io/KiteCodec/)
> Getting started, the transcode pipeline, the FFmpeg build tasks, and the full API reference. **If you read one thing, read this.**

KiteCodec gives Kotlin developers a single coroutine-first API for video and audio decode, encode, transcode and filter graphs. **v0.3** ships Kotlin/Native bindings to [FFmpeg](https://ffmpeg.org)'s libav* libraries (macOS, iOS, Linux, Windows). Android (JNI to bundled FFmpeg or platform `MediaCodec`) and Web (Wasm via `ffmpeg.wasm` / `WebCodecs`) substrates are on the roadmap, each fronting the same Kotlin API.

It is distributed as two artifacts:

- **`kitecodec-core`**: LGPL build. This is the default and is safe for commercial / App Store distribution. It bundles libav* plus platform hardware encoders (VideoToolbox / MediaCodec / NVENC) for H.264 / H.265, plus libsvtav1 (BSD-3) for royalty-free software AV1 encode.
- **`kitecodec-gpl`**: GPL add-on. A drop-in replacement for `kitecodec-core` that adds libx264 / libx265 for quality-focused software encode. Use only in GPL-compatible projects (open-source apps, server tools, internal use). It is not for App Store / commercial closed-source.

## What works today

A complete `demux -> decode -> filter -> encode -> mux` pipeline for video *and* audio, in one pass:

```kotlin
Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 320, height = 180,
        frameRate = Rational(30, 1),
        bitrateBps = 1_500_000,
    ),
    videoFilter = "scale=320:180,eq=brightness=0.1,vignette,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),   // null drops audio
    audioFilter = "volume=0.8",                            // optional
    onProgress  = { frames -> println("encoded $frames frames") },
)
```

Don't need to touch the audio? `audioCopy = true` stream-copies it bit-exact instead of re-encoding. Don't need to touch anything? Skip the codecs entirely:

```kotlin
Remuxer.remux("input.mp4", "output.mkv")   // lossless container rewrite, runs in seconds
```

Cut a clip (frame-exact), grab a thumbnail, composite a watermark:

```kotlin
// ffmpeg -ss 12.3 -to 45.6, output timestamps rebased to zero
Transcoder.transcode(input, output, spec, startMicros = 12_300_000, endMicros = 45_600_000)

// One frame at 90s -> JPEG bytes
MediaSource.open(input).use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame -> writeFile(frame.encodeImage()) }
}

// Two inputs -> one output: watermark in the bottom-right corner
val graph = FilterGraph.buildVideoMulti(
    "[in0][in1]overlay=W-w-10:H-h-10[out]",
    listOf(mainVideoInput, logoInput),
)
graph.feedInput(0, videoFrame) { composited -> /* encode */ }
graph.feedInput(1, logoFrame)  { /* ... */ }
```

That's a real native Kotlin call. It opens the file via libavformat, demuxes **once**, routes packets to per-stream libavcodec decoders, pumps video frames through a libavfilter graph, resamples and chunks audio through a second graph (AAC's fixed 1024-sample frames handled via `av_buffersink_set_frame_size`), encodes with libx264 plus aac, and interleaves both streams into a valid MP4. There is no `ffmpeg` subprocess, no JVM, no JNI hop. Memory stays constant regardless of input length.

| Surface | Status | Notes |
|---|:---:|---|
| Capability probing: `FFmpeg.versions`, `hasEncoder/Decoder/Filter` | ✅ | |
| `MediaSource.open(path)` + `streams` + `metadata` + `seekMicros` | ✅ | demuxer wraps `AVFormatContext` |
| `MediaSource.decodedFrames(stream): Flow<Frame>` | ✅ | EAGAIN-correct decode loop, best-effort pts |
| `MediaSource.decodeStreams(streams): Flow<Frame>` | ✅ | several streams, ONE demux pass |
| `Frame.copyPlanesToByteArray()` | ✅ | video planes *and* audio samples |
| `FilterGraph.buildVideo(...)` / `buildAudio(...)` | ✅ | any FFmpeg filter chain; audio output pinned encoder-ready |
| `MediaSink.addVideoEncoder(spec)` / `addAudioEncoder(spec)` | ✅ | shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options` (`crf`, `preset`) |
| `MediaSink.addCopyStream(...)`: stream copy | ✅ | `-c copy`: no decode/encode, ts rescale only |
| `Remuxer.remux(...)`: lossless container rewrite | ✅ | mp4 / mkv / mov in seconds, bit-exact, keyframe-snapped trim |
| `Transcoder.transcode(...)` | ✅ | one-call A/V pipeline: trim (frame-exact), `audioCopy`, `subtitleCopy`, metadata, rich progress |
| Audio-only transcode (mp3 -> aac etc.) | ✅ | `spec = null` |
| Trim / clip extraction (`startMicros` / `endMicros`) | ✅ | frame-exact re-encode; keyframe-snapped copy |
| `MediaSource.extractFrame` + `Frame.encodeImage` | ✅ | thumbnails: seek -> decode -> jpg/png bytes |
| `Frame.copy()` | ✅ | O(1) owned snapshot to escape the reuse rule |
| Multi-input filter graphs (overlay, amix) | ✅ | `buildVideoMulti` / `buildAudioMulti`, `[in0]...[inN-1]` -> `[out]` |
| Hardware encode: `h264_videotoolbox` | ✅ | verified on macOS arm64; `allow_sw` option for VMs |
| Hardware decode / CUDA / full hwframes pipeline | 🟨 | v0.4 |
| macOS arm64 | ✅ | verified end-to-end (video+audio, ffprobe-validated) |
| Linux x64, Windows (mingw x64) | 🔄 | [CI](.github/workflows/ci.yml) builds + tests + e2e-transcodes on every push |
| Android arm64/arm32/x64 (Kotlin/Native klib) | 🔄 | CI cross-compiles FFmpeg with the NDK (LGPL profile + MediaCodec) and builds the klib |
| Android AAR for JVM apps (`androidTarget`) | 🟨 | next milestone: JNI substrate over the same `ffkmp_*` C layer |
| macOS x64, iOS arm64, iOS sim, Linux arm64 | 🟨 | code written; iOS needs vendored FFmpeg in CI, macosX64 deprecated by Kotlin 2.3.20 |

## How it works inside

The binding is one consolidated cinterop module that exposes every libav* header behind a single Kotlin package (`ffmpeg.*`), plus roughly 100 small `static inline` C helpers (`ffkmp_*`) that bridge the FFmpeg macros, struct accessors and timestamp math that don't survive cinterop on their own. The timestamp handling in particular (best-effort pts promotion, time-base rescaling, strict monotonicity) is the part most A/V code gets wrong, and KiteCodec pins one stable path through it.

The full architecture write-up and the timestamp deep-dive now live in the docs: [About KiteCodec](https://yuroyami.github.io/KiteCodec/about/).

## Try it

```bash
brew install ffmpeg                     # macOS prereq
./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64

KEXE=kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe

# Capability probe:
$KEXE info

# Inspect any media file:
$KEXE probe path/to/clip.mp4

# Full transcode: decode, filter, libx264 + aac encode, interleaved mux:
$KEXE transcode input.mp4 output.mp4 "scale=1280:720,eq=brightness=0.1,format=yuv420p"

# Video only / audio passthrough / subtitles / hardware encode:
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -an
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -acopy
$KEXE transcode input.mkv output.mkv "scale=1280:720" -scopy
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -vt          # h264_videotoolbox

# Frame-exact clip + metadata:
$KEXE transcode input.mp4 clip.mp4 "scale=1280:720" --ss 12.3 --to 45.6 --title "My clip"

# Audio-only (mp3/wav/flac in, aac out):
$KEXE transcode song.mp3 song.m4a

# Thumbnail:
$KEXE thumbnail input.mp4 frame.jpg 90.0

# Lossless container rewrite (no re-encode), optional keyframe-snapped trim:
$KEXE remux input.mp4 output.mkv
$KEXE remux input.mp4 clip.mp4 --ss 60 --to 120
```

## Install

KiteCodec is **not yet on Maven Central** (that is a v0.4 roadmap item). Today you consume it by building from source through the Gradle FFmpeg build tasks, and you need FFmpeg present on the machine.

There are two FFmpeg-sourcing modes:

1. **Dynamic against system FFmpeg** (default, what the macOS arm64 build does today). `FFmpegPaths` finds Homebrew on macOS (override with `kitecodec.macos.homebrew.prefix` in `gradle.properties`) or apt-installed libraries on Linux, then points cinterop at their headers and dylibs. Install FFmpeg with `brew install ffmpeg` or `apt install` the libav* dev packages.

2. **Vendored static** (release). Run `:kitecodec-core:buildFFmpegForMacosArm64` (or `:buildFFmpegForAll`). The Gradle task cross-compiles a minimal FFmpeg from source (pinned codec/filter set, GPL ladder enabled, x264/x265/svtav1/aom/vpx/opus baked in) and drops `.a` libraries under `native-libs/<target>/`. `FFmpegPaths` notices and switches the cinterop to static linking, so the resulting executable carries everything it needs (~25 MB, versus FFmpegKit-Full's ~100 MB).

## Tests

```bash
./gradlew :kitecodec-core:macosArm64Test          # or linuxX64Test / mingwX64Test
scripts/e2e.sh kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe
```

Pure-logic tests (`Rational` normalization/overflow, `FrameInfo` NOPTS handling) live in `commonTest`. `nativeTest` runs against the actually-linked FFmpeg (capability probing, filter graph construction, error mapping, version unpacking). `scripts/e2e.sh` generates a clip with the ffmpeg CLI, transcodes it through the sample binary, and ffprobe-asserts the output. [CI](.github/workflows/ci.yml) runs all of it on macOS (FFmpeg 8), Ubuntu (FFmpeg 6.1, which proves the lavc-6 compat path), and Windows (BtbN mingw build).

## Android

The same `nativeMain` actuals compile untouched for `androidNativeArm64` / `Arm32` / `X64`. Kotlin/Native treats the Android NDK as just another native family, so the entire `decode -> filter -> encode -> mux` pipeline (and `Remuxer`) is available to KMP code targeting Android native today:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kitecodec-core:buildFFmpegForAndroidArm64      # NDK cross-compile, ~6 min
./gradlew :kitecodec-core:compileKotlinAndroidNativeArm64 # the klib
```

The Android FFmpeg profile is deliberately different from desktop:

- **LGPL only**: no `--enable-gpl`, no libx264/x265. Play-Store-safe and closed-source-safe.
- **MediaCodec hardware codecs** (`h264_mediacodec`, `hevc_mediacodec`) instead of GPL software encoders. Caveat: FFmpeg's MediaCodec wrapper needs the app's `JavaVM` handed over via `av_jni_set_java_vm` before the first `*_mediacodec` codec opens; the upcoming Android substrate owns that call.
- Full software *decode* set (h264/hevc/vp8/vp9/av1 plus audio) is identical to desktop.

What this is **not** yet: an AAR a plain Android app can `implementation(...)`. Regular apps run Kotlin/JVM, which needs a JNI bridge over the same `ffkmp_*` C helpers. That substrate is the next milestone and shares this exact FFmpeg build.

## v0.4 roadmap

- **Android AAR**: a JNI substrate so `androidTarget` (regular apps) get the same API; FFmpegKit's retirement left that niche empty.
- Maven Central publishing.
- Bitstream filters (h264 to Annex B) so stream copy reaches MPEG-TS.
- Hardware decode plus full hwframes pipelines (zero-copy VideoToolbox / CUDA).
- A dedicated single-thread dispatcher plus documented cancellation points.
- iOS CI verification (vendored FFmpeg cross-compile).

## Licence

Apache 2.0 for this code. The FFmpeg you link against carries its own licence: typically LGPL-2.1+ when built without `--enable-gpl`, GPL-2.0+ with it. The [`BuildFFmpegTask.kt`](buildSrc/src/main/kotlin/BuildFFmpegTask.kt) script enables `--enable-gpl` by default for libx264/libx265 access; switch it off if you ship via a GPL-hostile distribution channel (for example the iOS App Store).
