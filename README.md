# KiteCodec

Video and audio processing for Kotlin Multiplatform: read a media file, change it,
and write it back. Native targets reach FFmpeg's libav\* libraries through cinterop. The JVM and
Android variants reach the same libraries through a dynamically registered JNI adapter over the
same opaque C helpers, and the JVM artifact carries that adapter inside its jar, so a desktop app
needs one dependency line and nothing else. JS and WasmJs variants use an explicit unsupported
placeholder: diagnostics and capability probes work, while every media operation fails immediately
with the typed `FFmpegError.Unsupported`. There is no `ffmpeg` process to launch and no log output to parse.

[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteCodec/ci.yml?label=CI)](https://github.com/yuroyami/KiteCodec/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**[Documentation](docs/)** · a guide per task, from installing FFmpeg to
building filter graphs.

> **KiteCodec cannot be consumed from Maven Central today.** Nothing is
> published, and the FFmpeg release assets the Gradle plugin downloads do not
> exist. Read [Install](#install) and [Release status](#release-status) first.

## What you get

One call runs the whole media pipeline: demux, decode, filter, encode, mux. It
runs in a single pass, for video and audio together, and memory does not grow
with the length of the input. If you have not used FFmpeg before, these are the
words this page uses.

| Term | What it means |
|---|---|
| demux | Split one media file into its separate streams of compressed packets. |
| decode | Turn compressed packets into raw frames: pixels, or audio samples. |
| filter graph | A chain of processing steps applied to frames, written as one text string. |
| encode | Turn raw frames back into compressed packets. |
| mux | Write the packets of several streams into one container file. |
| transcode | Decode, then encode again, usually into a different codec or size. |
| remux | Move packets into a different container. Nothing is decoded. |
| stream copy | Pass packets through without decoding or encoding. The result is bit-exact. |
| pts | Presentation timestamp. It records when a frame should appear or play. |

Progress arrives as a typed callback. Failures arrive as one `FFmpegException`
over a sealed 18-case `FFmpegError`. Frames arrive as a `Flow<Frame>`.

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
            // For h264, probe instead. See "Check the linked build" below.
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

Set `audioCopy = true` to stream-copy the audio instead of re-encoding it.
`videoCopy` does the same for video. Both are the equivalent of `-c copy`: no
decode, no encode, timestamp rescale only.

## Install

**KiteCodec cannot be consumed from Maven Central today.** The Kotlin/Native,
JVM, Android, JS and WasmJs variants live in this source tree, but their public binary
distribution does not. `kitecodec-core` and the Gradle plugin have never been
published, and the FFmpeg release assets the plugin downloads do not exist. See
[Release status](#release-status) for the blocker.
Three local routes work until it is fixed: build inside the KiteCodec checkout,
build a desktop consumer against `publishToMavenLocal` with `FFmpegSource.System`,
or point `FFmpegSource.Local` at a complete generated tree. CI uses the System
route. Local trees and Maven-local artifacts are private evidence, not releases.

When the artifacts exist, the script below is all a consumer writes. Three pieces
are load-bearing and none is optional: the Gradle plugin, the library dependency,
and the `license` choice.

```kotlin
// build.gradle.kts
// settings.gradle.kts needs gradlePluginPortal(), mavenCentral() and google()
// under pluginManagement, and mavenCentral() and google() for dependencies.
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
        // Mandatory. There is no default: the flavor you link decides your
        // app's legal obligations, so configuration FAILS if this is unset.
        license = FFmpegLicense.LGPL
    }
}
```

**The Gradle plugin is not optional for Kotlin/Native consumers.** The Maven
coordinate alone does not complete a native link. The `kitecodec-core` klib
carries no FFmpeg bytes, and its `ffmpeg.def`
declares `linkerOpts` as bare `-lavformat -lavcodec …` with no `-L`. The native
link then fails on unresolved libav\* symbols. The plugin supplies the binaries
and adds the `-L<libdir>` flag to every link task. It also keeps FFmpeg's license
separate from KiteCodec's Apache-2.0 artifact.

**`license` has no default, deliberately.** The two values differ in what you may
ship.

| Value | Adds | Effect on your app |
|---|---|---|
| `FFmpegLicense.LGPL` | no libx264, no libx265 | safe for closed source and App Store distribution |
| `FFmpegLicense.GPL` | libx264, libx265 | makes your whole application GPL-3.0 |

Configuration fails when the choice is missing, and the error prints the block to
paste. The plugin also warns when you pick GPL. Android Kotlin/Native-only projects
are exempt, because those targets always link the LGPL MediaCodec build. The full DSL is in
[Gradle plugin](docs/gradle-plugin.md).

### Release status

`FFmpegSource.Prebuilt` is the plugin's default. It downloads from
`https://github.com/yuroyami/KiteCodec/releases/download/ffmpeg-n8.0/`. **No such
release exists.** The workflow that would produce it,
[`release-binaries.yml`](.github/workflows/release-binaries.yml), records its own
blocker in its header. Homebrew ships svt-av1 and graphite2 as shared libraries
only, so `-Pkitecodec.ffmpeg.selfContained=true` fails on macOS and names exactly
those two. The `publish` job declares `needs: [android, macos-desktop,
linux-desktop]`, so that single failure fails the whole release. The three
Android zips are lost with it, and they do build. Two fixes are possible: build
svt-av1 and graphite2 statically from source in that workflow, or drop libsvtav1
and harfbuzz's graphite2 backend from the desktop profile.

Three local paths work until then. `FFmpegSource.System` links a Homebrew or apt
FFmpeg on the host's own desktop target. Inside this repository,
`:kitecodec-core:buildFFmpegFor<Target>` cross-compiles a vendored static tree
into `native-libs/<license>/<target>/`, including the exact normalized configure
invocation at `lib/kitecodec/ffmpeg-configure.txt`. Packaging accepts only that
single-line installed evidence. After a private local publication,
`FFmpegSource.Local` reuses that tree without network access and validates all
six archives plus headers for every wired target.

## What it does

`transcode`, `remux`, `extractFrame` and the encoders' `drive` are suspend
functions, and decode flows are collected. Call them from a coroutine.

| Task | Entry point | Guide |
|---|---|---|
| Transcode a file | `Transcoder.transcode(...)` | [Transcoding](docs/transcoding.md) |
| Cut a frame-exact clip | `transcode(..., startMicros, endMicros)` | [Transcoding](docs/transcoding.md) |
| Rewrite a container losslessly | `Remuxer.remux(...)` | [Remuxing](docs/remuxing.md) |
| Read decoded frames | `MediaSource.decodedFrames(...)` | [Decoding](docs/decoding.md) |
| Decode several streams in one pass | `MediaSource.decodeStreams(...)` | [Decoding](docs/decoding.md) |
| Grab a thumbnail | `MediaSource.extractFrame(...)` | [Decoding](docs/decoding.md) |
| Apply a filter chain | `FilterGraph.buildVideo` / `buildVideoMulti` | [Filtering](docs/filtering.md) |
| Encode frames you generate | `MediaSink` plus `Frame.ofVideo` / `ofAudio` | [Encoding and muxing](docs/encoding-muxing.md) |
| Probe the linked FFmpeg | `FFmpeg.hasEncoder(...)` / `hasFilter(...)` | [Platform support](docs/platforms.md) |

Trim bounds are microseconds into the content, not raw container timestamps. A
filter graph names its inputs `[in0]` to `[inN-1]` and its single output `[out]`.

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
until you close it, so `buffer()` and `toList()` are safe. Every frame you
collect must be closed, or its native buffers leak. Frames handed to a callback
(`FilterGraph.feedInput`'s `onOutput`) are valid only during that call, and
`copy()` takes an O(1) owned snapshot.

`decodeStreams` decodes several streams in one demuxer pass, tagged by
`FrameInfo.streamIndex`. Two concurrent `decodedFrames` flows would race the one
demuxer cursor, so a second one throws `IllegalStateException`.

### Check the linked build

FFmpeg builds differ, so probe the linked one instead of assuming.
`FFmpeg.versions` reports the per-library version triplets and
`FFmpeg.buildConfiguration` returns the configure line. `FFmpeg.hasEncoder(...)`
and `FFmpeg.hasFilter(...)` answer capability questions: FFmpeg marks the `eq`
filter `deps="gpl"`, so `hasFilter("eq")` is false in an LGPL build.

`libx264` and `libx265` exist only in a GPL FFmpeg build, and the vendored
default profile is LGPL. Asking for one there throws `FFmpegException` from
`addVideoEncoder`, before a single frame is read.

**The runtime is checked against the headers before anything else happens.** The
first call into KiteCodec compares the six `LIB*_VERSION_INT` values frozen into
this artifact at compile time against what the linked libraries report, and
refuses to start on a mismatch that can corrupt memory: a different major, or a
runtime minor below the header minor, or six libraries that disagree with each
other about their own configure line. A micro difference is reported and never
fatal. The refusal is a typed error naming both identities, the two licence
strings and what to do about it, and it happens before the first allocation
rather than as a plausible wrong number ten frames later. Older headers against a
newer runtime is the case that motivates it: every symbol resolves, the link
succeeds, and struct offsets are silently wrong. Setting
`KITECODEC_FFMPEG_ABI_BYPASS=1` downgrades the refusal to a warning printed once,
for diagnosis only; it is not a supported configuration and the diagnostic report
says it was used.

### The low-level playback layer

The batch API above fuses demuxing and decoding into one pass, which is right
for transcoding and wrong for a player: a player needs audio and video decoding
to proceed independently, and it needs to seek while both run. For that there
is a second surface, gated behind the `@KiteCodecLowLevelApi` opt-in because it
hands out explicitly owned packets, frames and decoder state with manual lifetimes:

- `MediaSource.openPacketReader(...)` reads owned packets one at a time and
  seeks with a real `avformat_seek_file` window.
- `MediaSource.openDecoder(...)` opens one decoder per stream, driven through
  `send`/`receive`/`flush`/`isDrained`, independent of the reader and of every
  other decoder.
- Overflow-safe timestamp helpers (`ptsMicros`, `dtsMicros`, `durationMicros`
  on packets and frames), colour metadata, dispositions, rotation and channel
  layout masks travel with the streams and frames.

This layer exists because [KitePlayer](https://github.com/yuroyami/KitePlayer)
is built on it, and it is public because any real-time consumer needs the same
things. The safe batch API remains the front door.

| | Desktop LGPL | Desktop GPL | Mobile Apple LGPL | Android LGPL |
|---|---|---|---|---|
| Always | `mpeg4`, `mjpeg`, `png`, `aac`, `flac`, `pcm_*` | same | `mpeg4`, `mjpeg`, `png`, `flac`, `pcm_*` | same as Desktop LGPL |
| Software video | `libsvtav1` | + `libx264`, `libx265` | playback decoders only | none |
| Hardware video | `h264_videotoolbox` / `hevc_videotoolbox` | same | none | `h264_mediacodec`, `hevc_mediacodec` |
| Audio | + `libopus`, `libmp3lame` | same | shared playback decoders | none |

`mpeg4` is the only video encoder guaranteed on every target. A Linux or Windows
LGPL build has `libsvtav1` and no hardware encoder at all. The full codec,
container and filter list is in [Platform support](docs/platforms.md).

## Targets

The public API lives in `commonMain`. Kotlin/Native actuals use cinterop; the JVM
and Android actuals use generation-tagged JNI handles and copied arrays, never raw
native pointers. JS and WasmJs use an unsupported placeholder implementation so a common
dependency resolves cleanly without pretending a Web codec backend exists.

Every target claim in the Kite family means one of these tiers and nothing more.

| Tier | Meaning |
|---|---|
| T1 API | The Kotlin compiles for the target. No claim that any media opens. |
| T2 Codec | A runtime on the target opens, decodes, seeks, cancels and closes real media. |
| Higher product tiers | Output, UI/OS integration and release qualification. This repository makes no such Android claim. |

Against that scale: `macosArm64` is T2, measured on an Apple silicon development
machine by the native and shared-contract suites, seven C suites under three
sanitizer variants, and the e2e script. `linuxX64` and
`mingwX64` are T2 on CI evidence only, never on a machine you can inspect here.
The `androidNative*` klibs are T1: they compile per ABI and nothing runs them.
The Android/JNI layer is source- and host-verified only, and no Android playback or physical-device
qualification is claimed. The JVM variant is no longer a placeholder: it compiles the same JNI tree
Android does, its test source set runs the shared codec contract over real FFmpeg on this arm64
Mac, and the host library is published inside the jar. Only the macOS arm64 library is bundled
today; a Linux or Windows JVM resolves the artifact and then finds no library for its platform,
which the loader says in one sentence.
`iosArm64` and `iosSimulatorArm64` now have a local/private build and consumer
path on this arm64 Mac, but no public or CI tier is inferred from it here.
`linuxX64` and `linuxArm64` now have vendored FFmpeg trees cross-built from the Kotlin/Native
toolchains and 109 native tests passing on linuxArm64 in a container; `mingwX64` has the same
vendored tree and links to a PE32+ binary, with nothing run. `iosX64` and `macosX64` remain
unqualified.
JS and WasmJs are deliberately T1 placeholders: their API and failure contract are tested, but
they do not decode, encode, filter, remux or transcode media.

| Target | Public artifact | Current evidence | FFmpeg comes from |
|---|---|---|---|
| `macosArm64` | no | unit tests, native tests and an e2e transcode, run twice: against Homebrew, and against the vendored LGPL build compiled from source | Homebrew, vendored, or a future prebuilt asset |
| `linuxX64` | no | unit tests, native tests and an e2e transcode, against whatever 6.x FFmpeg Ubuntu 24.04 ships. This is what exercises the lavc-6 compatibility path. | apt, vendored, or a future prebuilt asset |
| `androidNativeArm64` / `Arm32` / `X64` | no | the klib compiles, per ABI. No tests run on Android. | NDK cross-compile of the LGPL MediaCodec profile |
| JVM | no | the real JNI tree, not a placeholder: 41 tests including the shared codec contract and the VideoToolbox hwaccel contract run over real FFmpeg on an arm64 Mac. The host library rides in the jar, self-contained | vendored macOS LGPL tree, linked into the bundled JNI library |
| Android `minSdk 24` actual | no | local AAR model packages JNI for `arm64-v8a` and `x86_64`; both ELF link arms and 16 KiB ELF/app packaging rules are checked. No Android playback or physical-device qualification is claimed. | NDK cross-compile of the LGPL Android profile |
| `js` | no | Node tests verify readable unavailable diagnostics, empty capabilities and typed unsupported failures; no media runtime exists | none; unsupported placeholder |
| `wasmJs` | no | Node/Wasm tests verify the same placeholder contract; no media runtime exists | none; unsupported placeholder |
| `mingwX64` | no | the vendored tree cross-builds and the whole stack links to a PE32+ binary. Nothing has been run: there is no Windows machine here | vendored LGPL cross-build at the reduced desktop profile, from the Kotlin/Native toolchain. CI's pinned BtbN `win64-gpl-shared` zip remains a separate path |
| `iosArm64`, `iosSimulatorArm64` | no | no CI claim; local arm64-Mac proof only | LGPL STANDARD playback build from source, with SDK zlib and VideoToolbox DECODE (encode stays desktop-only), no desktop third-party stack or GPL; consumable through `FFmpegSource.Local` |
| `iosX64` | no | not qualified | the same mobile profile is coded for the simulator SDK, but this stage does not build or consume it |
| `macosX64` | no | not built | Kotlin deprecated the target |
| `linuxArm64` | no | 109 native tests pass in an arm64 Linux container over the vendored cross-build, covering demux, decode, encode, filter and transcode | vendored LGPL cross-build at the reduced desktop profile, from the Kotlin/Native toolchain |

Six triples have no prebuilt asset. For those the Gradle plugin fails
configuration and prints the alternatives, rather than letting the download
return 404 mid-build. A remote publication of `kitecodec-core` requires
`-Pkitecodec.stableTargetsOnly=true` and a real FFmpeg tree for every configured native
target, so it cannot silently drop one. JVM, JS and WasmJs are portable variants included in
every publication scope and are invariant unsupported placeholders.
`publishToMavenLocal` also
accepts `-Pkitecodec.hostTargetsOnly=true`, which publishes the host's own desktop native target
plus those three portable variants. On an arm64 Mac it accepts the mutually exclusive
`-Pkitecodec.applePhoneTargetsOnly=true` for exactly macosArm64, iosArm64 and
iosSimulatorArm64. Every remote publish explicitly refuses the phone selector.
Both exceptions are local smoke paths, not release paths.

The repository now also contains an `androidTarget`/AAR implementation behind the
local `-Pkitecodec.phoneTargetsOnly=true` proof scope. It targets API 24+, packages
`arm64-v8a` and `x86_64` JNI libraries, and uses 16 KiB ELF and app-packaging rules.
The selector also registers the three local Apple targets and the regular Android target; JVM,
JS and WasmJs are already always registered. It is accepted only for Maven-local proof and is
refused by every remote publish. Nothing is published,
and those packaging/link proofs are not a device playback result.
MediaCodec is reached only by asking FFmpeg for a named decoder such as
`h264_mediacodec`; KiteCodec does not call the platform codec API directly.

## Limits

| Not available | What it means for you |
|---|---|
| A functional published JVM/Android distribution | Public JVM is an invariant placeholder. JNI-backed Android actuals and an unpublished JVM test harness exist in the local phone proof scope; its macOS dylib is test-only and no Android AAR is public. |
| A functional Web codec backend | `js` and `wasmJs` are dependency-compatible placeholders only. Capability probes return false and media operations throw typed `FFmpegError.Unsupported`. |
| A working binary distribution | `FFmpegSource.Prebuilt` cannot work against KiteCodec's own repository. The five triples that should have assets return 404, and the six that never had one fail configuration by design. |
| `kitecodec-gpl` | It is a README and nothing else: no `build.gradle.kts`, and it is commented out of `settings.gradle.kts`. Reach the GPL flavor through `buildFFmpegFor<Target>Gpl` plus `-Pkitecodec.ffmpeg.license=gpl`, or through `license = FFmpegLicense.GPL`. |
| A bitstream filter API | Nothing binds `av_bsf_*`, so you cannot give a stream copy one explicitly. The vendored profile does compile the common ones in (`h264_mp4toannexb`, `hevc_mp4toannexb`, `aac_adtstoasc`, `extract_extradata`, `vp9_superframe`), so libavformat can insert them automatically during a copy. |
| Hardware decode, and zero-copy hwframes | Hardware *encode* does work. `h264_videotoolbox` is verified on macOS arm64. Pass `allow_sw` on VMs and CI runners, where the encoder exists but the hardware block does not. |
| Direct MediaCodec or Android UI integration | The Android loader attaches its `JavaVM`, then callers may select an FFmpeg-owned named decoder. There is no direct `MediaCodec` API, Compose component, Android View, Android playback or physical-device qualification here. |
| `https` in the vendored profile | It needs a TLS backend cross-compiled per target. Use `http`, a local file, or link a system FFmpeg. |
| A stable API | 0.0.1 is pre-1.0, so a minor version may still break you. `explicitApi()` is on, every public declaration states its visibility and return type, and there is now a committed klib dump under `kitecodec-core/api/` that `apiCheck` verifies in every local gate (a macOS CI job is configured to run it too, and has not run yet), so an accidental signature change fails a build. That is a change being visible, not a promise that it will not happen. |

## Build and test it here

The sample is a Kotlin/Native CLI that exercises the whole API. It picks its
video encoder by probing (`libx264`, then `mpeg4`, then `libsvtav1`, then
`mjpeg`), so it works against a GPL build and an LGPL build alike.

```bash
brew install ffmpeg
./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64
KEXE=kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe
$KEXE transcode in.mp4 out.mp4 "scale=1280:720" -acopy   # also: info, probe, thumbnail, remux

./gradlew :kitecodec-core:macosArm64Test          # or linuxX64Test / mingwX64Test
scripts/e2e.sh "$KEXE"
```

The local gate includes build-support tests, JVM registration and contract tests
through a test-only macOS dylib, Android host-side packaging-model tests, and native
tests against the FFmpeg the build actually linked. Android source compilation and
both Android JNI link arms are also checked; that evidence is not a device playback
claim. `PipelineRoundTripTest` needs no media
fixtures: it synthesizes frames, muxes them through the real pipeline, then reads
them back. `scripts/e2e.sh` generates a clip with the ffmpeg CLI, runs it through
the sample binary, and checks the output with ffprobe.

The C helper layer has its own build and its own tests, which are not part of the
Gradle run:

```bash
cd native/kitecodec-c
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain   # also asan, tsan
./scripts/check-deleted-surface.sh  # nothing refers to a deleted helper, in either repo
./scripts/symbol-audit.sh         # what the archive needs, exports and keeps private
./scripts/replay-corpus.sh        # every committed fuzz seed, under ASan and UBSan
cd ../.. && ./gradlew :kitecodec-core:apiCheck checkCinteropCoupling
```

Four limits of this machine are measured rather than assumed, and they shape all
of the above: no clang here has a libFuzzer runtime, so coverage-guided fuzzing
is assigned to a Linux CI job that is configured and has not run yet, and the
fuzz-shaped evidence that exists today is the committed-corpus replay; LeakSanitizer is unsupported on macOS arm64, so the
leak instrument is a Mach-O allocation interposer; cmake is not installed and GNU
make truncates a path at an unescaped `#`, which this checkout's own path
contains, so the C harness drives clang directly while BuildFFmpegTask stages
FFmpeg in a hash-free temporary tree; and the Apple phone trees are generated
local inputs rather than committed or released artifacts. What
each instrument can and cannot prove is written out in
[native/kitecodec-c/README.md](native/kitecodec-c/README.md).

Every sample command and every build step is written out in
[Getting started](docs/getting-started.md). The binding design, the `ffkmp_*` C
helpers and the timestamp rules are in [About KiteCodec](docs/about.md).

## License

Apache-2.0 for this code. See [NOTICE](NOTICE) and [CHANGELOG.md](CHANGELOG.md).

The FFmpeg you link carries its own license, and that license decides whether you
may ship your binary. FFmpeg is LGPL-2.1+ without `--enable-gpl`. With it, and
because these builds also pass `--enable-version3`, the effective license of a
GPL-flavor binary is **GPL-3.0**. The default everywhere is LGPL. Do not ship a
GPL-flavor binary through a channel that forbids GPL code, such as the iOS App
Store. Full compliance guidance is in [Licensing](docs/licensing.md).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
