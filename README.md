# KiteCodec

A coroutine-first Kotlin/Native API for video and audio: demux, decode, filter,
encode and mux, bound directly to FFmpeg's libav\* libraries.

[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteCodec/ci.yml?label=CI)](https://github.com/yuroyami/KiteCodec/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

## What you get

Media work from Kotlin normally means launching the `ffmpeg` binary and parsing
its stderr, or wrapping a prebuilt one. KiteCodec calls libavformat, libavcodec,
libavfilter, libswscale and libswresample through Kotlin/Native cinterop
instead. `Transcoder.transcode(...)` opens the input, demuxes it once, routes
packets to per-stream decoders, pushes frames through filter graphs, encodes
video and audio, and interleaves both into a valid container — one pass, no
subprocess, no JVM, no JNI hop, and memory that does not grow with the length of
the input.

Progress arrives as a typed callback, failures as one `FFmpegException` over a
sealed 18-case `FFmpegError`, and frames as a `Flow<Frame>`.

```kotlin
import io.github.yuroyami.kitecodec.AudioEncoderSpec
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.Rational
import io.github.yuroyami.kitecodec.Transcoder
import io.github.yuroyami.kitecodec.VideoEncoderSpec
import kotlinx.coroutines.runBlocking

// demux -> decode -> filter -> encode -> mux, video and audio, in one pass.
fun main() = runBlocking {
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec = VideoEncoderSpec(
            // mpeg4 is the dependency-free baseline present in every FFmpeg profile.
            // For h264, probe instead — see "Check the linked build" below.
            codec = CodecId("mpeg4"),
            width = 320, height = 180,
            frameRate = Rational(30, 1),
            bitrateBps = 1_500_000,
        ),
        videoFilter = "scale=320:180,hue=b=0.1,vignette,format=yuv420p",
        audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),   // null drops audio
        audioFilter = "volume=0.8",                            // optional
        onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
    )
}
```

`audioCopy = true` stream-copies the audio bit-exact instead of re-encoding it.
`videoCopy` does the same for video. Both are `-c copy`: no decode, no encode,
timestamp rescale only.

## Install

**KiteCodec cannot be consumed from Maven Central today.** The Kotlin library is
complete; the binary distribution it depends on is not. `kitecodec-core` and the
Gradle plugin have never been published, and the FFmpeg release assets the
plugin downloads do not exist — see [Release status](#release-status) for why.
Until that changes, the only working route is the KiteCodec checkout itself, or
a consumer build against `publishToMavenLocal` with `FFmpegSource.System`, which
is what CI does.

When the artifacts do exist, this is the whole thing a consumer types. Three
pieces are load-bearing and none of them is optional: the Gradle plugin, the
library dependency, and the `license` choice.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}
```

```kotlin
// build.gradle.kts
import io.github.yuroyami.kitecodec.gradle.FFmpegLicense

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

kotlin {
    macosArm64()          // or linuxX64 / androidNativeArm64 / Arm32 / X64
    sourceSets.commonMain.dependencies {
        implementation("io.github.yuroyami:kitecodec-core:0.0.1")
    }
}

kitecodec {
    ffmpeg {
        // Mandatory. There is no default: the flavour you link decides your
        // app's legal obligations, so configuration FAILS if this is unset.
        license = FFmpegLicense.LGPL
    }
}
```

**The Gradle plugin is not optional.** Adding the Maven coordinate on its own
does not produce a working build. `kitecodec-core`'s published klib carries no
FFmpeg bytes, and its `ffmpeg.def` declares `linkerOpts` as bare `-lavformat
-lavcodec …` with no `-L`, so the final native link fails on unresolved libav\*
symbols. The plugin is what supplies the binaries and adds the `-L<libdir>` flag
to every link task. It also keeps FFmpeg's licence separate from KiteCodec's own
Apache-2.0 artifact.

**`license` has no default, deliberately.** `FFmpegLicense.LGPL` is safe for
closed-source and App Store distribution and has no libx264 / libx265;
`FFmpegLicense.GPL` adds them and makes your whole application GPL-3.0. The
plugin fails configuration with the block to paste when the choice is missing,
and warns loudly when GPL is picked. Purely-Android projects are exempt, since
Android always links the LGPL MediaCodec build. Full DSL:
[Gradle plugin](docs/gradle-plugin.md).

### Release status

`FFmpegSource.Prebuilt` is the plugin's default and downloads from
`https://github.com/yuroyami/KiteCodec/releases/download/ffmpeg-n8.0/`. **No such
release exists.** The workflow that would produce it,
[`release-binaries.yml`](.github/workflows/release-binaries.yml), documents its
own blocker in its header: Homebrew ships svt-av1 and graphite2 shared-only, so
`-Pkitecodec.ffmpeg.selfContained=true` fails on macOS naming exactly those two.
Because the `publish` job is `needs: [android, macos-desktop, linux-desktop]`,
that one failure takes the whole release down with it — including the three
Android zips, which do build. Fixing it means building svt-av1 and graphite2
statically from source in that workflow, or dropping libsvtav1 and harfbuzz's
graphite2 backend from the desktop profile.

Until then: `FFmpegSource.System` links a Homebrew or apt FFmpeg on the host's
own desktop target, and inside this repository `:kitecodec-core:buildFFmpegFor<Target>`
cross-compiles a vendored static tree into `native-libs/<license>/<target>/`.

## What it does

`transcode`, `remux`, `extractFrame` and the encoders' `drive` are suspend
functions, and decode flows are collected. The snippets below assume a coroutine
scope around them.

### Rewrite a container without re-encoding

```kotlin
Remuxer.remux("input.mp4", "output.mkv")
```

Packets move from input to output untouched, so a feature-length file remuxes in
seconds and stays bit-exact. Takes an optional stream selection, container
metadata, and a keyframe-snapped trim window.

### Cut a clip, frame-exact

```kotlin
// ffmpeg -ss 12.3 -to 45.6, output timestamps rebased to zero
Transcoder.transcode(input, output, spec, startMicros = 12_300_000, endMicros = 45_600_000)
```

Trim bounds are microseconds into the content, not raw container timestamps, so
a container whose timeline starts at a nonzero point (MPEG-TS typically near
1.4 s) is converted for you. Re-encoded streams are frame-exact; copied streams
start at the preceding keyframe.

### Read decoded frames

```kotlin
MediaSource.open("input.mp4").use { src ->
    val video = src.primaryVideo ?: error("no video stream")
    src.decodedFrames(video).collect { frame ->
        try {
            val pixels = frame.copyPlanesToByteArray()   // tightly packed, no linesize padding
            // ...
        } finally {
            frame.close()
        }
    }
}
```

**Frames emitted by a `Flow` are owned by the collector.** Each one stays valid
until you close it, so `buffer()` and `toList()` are safe — and every frame you
collect must be closed or its native buffers leak. Frames handed to a callback
(`FilterGraph.feedInput`'s `onOutput`) are valid only for that call; `copy()` is
an O(1) owned snapshot.

`decodeStreams(listOf(video, audio))` decodes several streams in one demuxer
pass, tagged by `FrameInfo.streamIndex`. Two concurrent `decodedFrames` flows
would race the single demuxer cursor, so a second one throws
`IllegalStateException` rather than corrupting state.

### Grab a thumbnail

```kotlin
MediaSource.open(input).use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame ->
        writeFile(frame.encodeImage(CodecId.Mjpeg))   // or CodecId.Png
    }
}
```

### Build a filter graph

Any FFmpeg filter chain, single-input or N-input. Inputs are `[in0]`…`[inN-1]`,
the output is `[out]`.

```kotlin
val graph = FilterGraph.buildVideoMulti(
    "[in0][in1]overlay=W-w-10:H-h-10[out]",
    listOf(mainVideoInput, logoInput),
)
graph.feedInput(0, videoFrame) { composited -> /* encode */ }
graph.feedInput(1, logoFrame)  { /* ... */ }
```

`buildVideo` / `buildAudio` take a single input and expose a `Flow` pipeline via
`process(...)`. Audio graphs can pin their output format, sample rate and channel
count so frames arrive encoder-ready, and `setOutputFrameSize` chunks them to
what a fixed-frame-size codec wants (AAC's 1024 samples).

### Encode from scratch

`MediaSink` plus `Frame.ofVideo` / `Frame.ofAudio` covers generative pipelines —
images to video, synthesized audio, pixels from another library. This one needs
no media on disk, which is why it is also how the tests work; the full version is
[`PipelineRoundTripTest`](kitecodec-core/src/nativeTest/kotlin/io/github/yuroyami/kitecodec/PipelineRoundTripTest.kt).

```kotlin
MediaSink.open("out.mp4").use { sink ->
    val enc = sink.addVideoEncoder(
        VideoEncoderSpec(
            codec = CodecId("mpeg4"),
            width = 64, height = 64,
            frameRate = Rational(30, 1),
            bitrateBps = 500_000,
        )
    )
    enc.drive(
        (0 until 30).asFlow().map { i ->
            Frame.ofVideo(
                bytes = yuvBytes(i),                  // Y then U then V, tightly packed
                width = 64, height = 64,
                pixelFormat = PixelFormat.Yuv420p,
                ptsMicros = i * 1_000_000L / 30,
            )
        }
    )
}
```

`close()` drains every encoder before writing the trailer, so nothing buffered
in an encoder's lookahead is lost.

### Check the linked build

Builds differ, so probe instead of assuming.

```kotlin
println(FFmpeg.versions)                       // per-library version triplets
println(FFmpeg.buildConfiguration)             // the configure line
FFmpeg.hasEncoder("libx264")                   // false in the default LGPL profile
FFmpeg.hasEncoder("h264_videotoolbox")         // Apple targets only
FFmpeg.hasFilter("eq")                         // FFmpeg marks eq deps="gpl"
```

`libx264` and `libx265` exist only in a GPL FFmpeg build, and the vendored
default profile is LGPL. Asking for one there throws `FFmpegException` from
`addVideoEncoder`, before a single frame is read.

What is where in the vendored profile:

| | Desktop LGPL | Desktop GPL | Android |
|---|---|---|---|
| Always | `mpeg4`, `mjpeg`, `png`, `aac`, `flac`, `pcm_*` | same | same |
| Software video | `libsvtav1` | + `libx264`, `libx265` | — |
| Hardware video | `h264_videotoolbox` / `hevc_videotoolbox`, **macOS and iOS only** | same | `h264_mediacodec`, `hevc_mediacodec` |
| Audio | + `libopus`, `libmp3lame` | same | — |

So `mpeg4` is the only video encoder guaranteed on every target. A Linux or
Windows LGPL build has `libsvtav1` and no hardware encoder at all.

## Targets

One implementation source set, `nativeMain`, eight files. Every target is the
same Kotlin compiled N ways; what differs is which FFmpeg it links.

| Target | In the published set | Built and tested in CI | FFmpeg comes from |
|---|---|---|---|
| `macosArm64` | yes | unit + native tests + e2e transcode, twice: against Homebrew, and against the vendored LGPL build compiled from source | Homebrew, vendored, or a prebuilt asset |
| `linuxX64` | yes | unit + native tests + e2e transcode, against whatever 6.x FFmpeg Ubuntu 24.04 ships — which is what exercises the lavc-6 compat path | apt, vendored, or a prebuilt asset |
| `androidNativeArm64` / `Arm32` / `X64` | yes | klib compiles, per ABI. No tests are run on Android. | NDK cross-compile of the LGPL MediaCodec profile |
| `mingwX64` | no | native tests + e2e transcode | a pinned BtbN `win64-gpl-shared` zip, unpacked by hand into `native-libs/gpl/mingw-x64` by the CI job. No discovery, no prebuilt asset. |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | no | not built anywhere | `buildFFmpegForIos*` tasks exist and target the iOS SDKs, but the desktop profile they use needs svt-av1, vpx, aom, opus, lame and the freetype/harfbuzz/libass text stack cross-built for iOS. Homebrew ships those for macOS only, so the task cannot succeed on a stock machine. |
| `macosX64` | no | not built | Kotlin deprecated the target |
| `linuxArm64` | no | not built | needs an arm64 runner with Kotlin/Native host support |

For the six triples with no prebuilt asset, the Gradle plugin fails
configuration outright rather than letting the fetch 404 mid-build, and prints
the alternatives. Publishing `kitecodec-core` to a remote repository is guarded
too: it requires `-Pkitecodec.stableTargetsOnly=true` plus a real FFmpeg tree for
every configured target, so a remote publication cannot silently drop one.
`publishToMavenLocal` additionally accepts `-Pkitecodec.hostTargetsOnly=true`,
which publishes the host's own desktop target alone — that is the CI smoke path,
not a release path.

**Android means `androidNative*` klibs, not an AAR.** A normal Android app runs
Kotlin/JVM and cannot depend on these. Reaching it needs a JNI bridge over the
same `ffkmp_*` C helpers, which does not exist yet.

## Limits

- **There is no JVM target.** A KMP app cannot call KiteCodec from `commonMain`
  if that source set also has to compile for JVM or `androidTarget`. Kotlin/Native
  only.
- **No web target of any kind.** No `js`, no `wasmJs`, no source set, no stub.
- **The binary distribution is incomplete.** `FFmpegSource.Prebuilt` cannot work
  against KiteCodec's own repository: the five triples that are supposed to have
  assets 404, and the six that never had one fail configuration by design. See
  [Release status](#release-status).
- **`kitecodec-gpl` does not exist.** It is a README and nothing else — no
  `build.gradle.kts`, commented out of `settings.gradle.kts`. The GPL flavour is
  reached through `buildFFmpegFor<Target>Gpl` plus
  `-Pkitecodec.ffmpeg.license=gpl`, or through the plugin's
  `license = FFmpegLicense.GPL`.
- **No bitstream filter API.** Nothing in the Kotlin surface or the cinterop
  binds `av_bsf_*`, so a stream copy cannot be given one explicitly. The vendored
  profile does compile the common ones in (`h264_mp4toannexb`,
  `hevc_mp4toannexb`, `aac_adtstoasc`, `extract_extradata`, `vp9_superframe`) so
  that libavformat can auto-insert them during a copy.
- **No hardware decode and no zero-copy hwframes pipeline.** Hardware *encode*
  works: `h264_videotoolbox` is verified on macOS arm64 (pass `allow_sw` on VMs
  and CI runners, where the encoder exists but the hardware block does not).
- **MediaCodec is not usable from a plain app yet.** FFmpeg's wrapper needs the
  app's `JavaVM` via `av_jni_set_java_vm` before the first `*_mediacodec` codec
  opens, and nothing here makes that call.
- **`https` is not in the vendored profile.** It needs a TLS backend
  cross-compiled per target. Use `http`, a local file, or link a system FFmpeg.
- KiteCodec is pre-1.0 at `0.0.1`. `explicitApi()` is on, so every public
  declaration states its visibility and return type. The klib binary-compatibility
  validator is configured but has no committed baseline and is not run by CI, so
  nothing currently catches an accidental signature change.

## How it works

The binding is one consolidated cinterop module that exposes every libav\* header
behind a single Kotlin package (`ffmpeg.*`). Six separate cinterops would produce
six duplicate `AVFrame` / `AVCodec` / `AVPacket` types that cannot cross module
boundaries, so a frame decoded through one could not be filtered by another.

The `.def` also carries 141 `static inline` C helpers prefixed `ffkmp_*`, for the
parts of FFmpeg that do not survive cinterop: function-style macros
(`AVERROR(EAGAIN)`), struct fields marked do-not-access-directly, the
double-pointer alloc/free pairs, and `av_rescale_q`, whose 128-bit intermediate
is the only overflow-safe way to move a timestamp between time-bases.

Timestamps follow `ffmpeg.c` at every hop: decoders promote
`best_effort_timestamp` to `pts`; filter graphs report their own output
time-base, since `fps` and `atempo` change it; encoders rescale onto the codec
time-base and force strict monotonicity; the muxer rescales once more onto
whatever stream time-base `avformat_write_header` settled on.

Full write-up: [About KiteCodec](docs/about.md).

## Try it

The sample is a Kotlin/Native CLI that exercises the whole API.

```bash
brew install ffmpeg
./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64

KEXE=kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe

$KEXE info                                                     # capability probe
$KEXE probe path/to/clip.mp4                                   # streams, duration, metadata
$KEXE transcode in.mp4 out.mp4 "scale=1280:720,format=yuv420p"
$KEXE transcode in.mp4 out.mp4 "scale=1280:720" -acopy         # or -an / -scopy / -vt
$KEXE transcode in.mp4 clip.mp4 "scale=1280:720" --ss 12.3 --to 45.6 --title "My clip"
$KEXE transcode song.mp3 song.m4a                              # audio-only
$KEXE thumbnail in.mp4 frame.jpg 90.0
$KEXE remux in.mp4 out.mkv
```

The sample picks its video encoder by probing (`libx264`, then `mpeg4`, then
`libsvtav1`, then `mjpeg`), so it works against a GPL and an LGPL build alike.

## Tests

```bash
./gradlew :kitecodec-core:macosArm64Test          # or linuxX64Test / mingwX64Test
scripts/e2e.sh kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe
```

53 tests in `kitecodec-core`, plus 3 TestKit functional tests for the Gradle
plugin. `commonTest` covers the pure logic (`Rational` normalization and
overflow, `FrameInfo` NOPTS handling). `nativeTest` runs against the FFmpeg the
build actually linked, and `PipelineRoundTripTest` needs no media fixtures: it
synthesizes frames, encodes and muxes them through the real pipeline, then
demuxes and decodes them back. `scripts/e2e.sh` generates a clip with the ffmpeg
CLI, runs it through the sample binary, and asserts on the output with ffprobe;
it probes for the encoder and codec names rather than assuming h264. There are
no TODOs, no stubs and no unimplemented paths in the Kotlin source.

## Documentation

The guides are in [`docs/`](docs/): [getting started](docs/getting-started.md),
[decoding](docs/decoding.md), [encoding and muxing](docs/encoding-muxing.md),
[filtering](docs/filtering.md), [the Gradle plugin](docs/gradle-plugin.md),
[platforms](docs/platforms.md) and [licensing](docs/licensing.md).

There is no hosted site yet. Building the API reference needs the FFmpeg
headers on the machine that generates it, so the docs workflow is blocked behind
the same missing release binaries described under Install.

## Licence

Apache-2.0 for this code. See [NOTICE](NOTICE) and [CHANGELOG.md](CHANGELOG.md).

The FFmpeg you link carries its own licence, and that is what decides whether
your binary can ship. LGPL-2.1+ without `--enable-gpl`; with it, and because
these builds also pass `--enable-version3`, the effective licence of a
GPL-flavour binary is **GPL-3.0**. The default everywhere is LGPL. Do not ship a
GPL-flavour binary through a GPL-hostile channel such as the iOS App Store. Full
compliance guidance: [Licensing](docs/licensing.md).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
