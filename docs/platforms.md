# Platform support

Decode, encode, transcode, remux, and filter video and audio from shared Kotlin code. The API lives in `commonMain`; every target binds to the same FFmpeg libav\* libraries underneath. KiteCodec is Kotlin/Native only today. There is no JVM or Android-app artifact yet.

## Target matrix

There is one target table for the project and it lives in the [README](https://github.com/yuroyami/KiteCodec#targets). It records, per target, whether the target is in the published set, exactly what CI builds and tests, and where FFmpeg comes from. This page covers the part it does not: how to obtain an FFmpeg for each target, and what is inside the one KiteCodec builds.

Two things are worth restating, because they decide whether KiteCodec is usable for you at all:

- `nativeMain` is the only implementation source set. Every target is the same eight files of Kotlin compiled N ways; what differs is which FFmpeg gets linked. There is **no JVM target**, no Android AAR, and no `js` or `wasmJs` target of any kind.
- The published set is five triples: `macosArm64`, `linuxX64`, `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`. `mingwX64` builds and tests in CI but is not published and has no prebuilt asset; `ios*`, `macosX64` and `linuxArm64` are not built anywhere.

## FFmpeg is a prerequisite

KiteCodec links FFmpeg's libav\* libraries and ships none of their bytes. In a consumer project the [Gradle plugin](gradle-plugin.md) provisions them; inside this repository you supply them in one of two forms. Nothing is published yet, so the repository path is the one that works today — see the README's [release status](https://github.com/yuroyami/KiteCodec#release-status).

### Mode 1: dynamic against system FFmpeg

This is the default, and what the macOS arm64 build does today. The Gradle build's `FFmpegPaths` finds your system FFmpeg, points cinterop at its headers and shared libraries, and links dynamically. Your users need FFmpeg installed at runtime.

=== "macOS"

    ```bash
    brew install ffmpeg
    ```

    Override the discovered prefix with `kitecodec.macos.homebrew.prefix` in `gradle.properties` if Homebrew lives somewhere non-standard.

=== "Linux"

    ```bash
    sudo apt install ffmpeg libavformat-dev libavcodec-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev
    ```

`FFmpegPaths` discovers the apt-installed libraries and points cinterop at them.

### Mode 2: vendored static (release)

For a self-contained binary, build a minimal FFmpeg from source. The build expects the FFmpeg source tree at `vendor/ffmpeg`, so clone it first:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg

./gradlew :kitecodec-core:buildFFmpegForMacosArm64
# or build every configured target at once:
./gradlew :kitecodec-core:buildFFmpegForAll
```

The Gradle task cross-compiles a pinned codec and filter set and drops `.a` libraries under `native-libs/<license>/<target>/` (`lgpl` or `gpl`). `FFmpegPaths` notices and switches cinterop to static linking; the resulting executable carries everything it needs, around 25 MB.

The static profile is **LGPL by default**: no `--enable-gpl`, no libx264 / libx265. That is the App-Store- and closed-source-safe flavour.

The set is deliberately small, so it is worth knowing exactly what is in it — a codec that is not listed here is not in the artifact, however common it is elsewhere. The authoritative list is `sharedCoreArgs()` in [`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt); as of `n8.0`:

| | LGPL (default) | GPL (opt-in) | Android (always LGPL) |
|---|---|---|---|
| **Video encode** | `mpeg4`, `libsvtav1`, `mjpeg`, `png`, `h264_videotoolbox` / `hevc_videotoolbox` (Apple) | + `libx264`, `libx265` | `mpeg4`, `mjpeg`, `png`, `h264_mediacodec`, `hevc_mediacodec` |
| **Audio encode** | `aac`, `libopus`, `libmp3lame`, `flac`, `pcm_s16le`/`s24le`/`f32le` | same | `aac`, `flac`, `pcm_*` |
| **Decode** | h264, hevc, vp8, vp9, av1, mpeg4, aac, mp3, opus, vorbis, flac, pcm, png, mjpeg, webp | same | same + MediaCodec h264/hevc |
| **Containers** | mp4/mov, matroska/webm (incl. `.mka`), mpegts, mp3, wav, flac, ogg/opus, image2 | same | same |
| **Protocols** | `file`, `pipe`, `data`, `http`, `tcp` | same | same |
| **Filters** | scale, pad, overlay, hue, unsharp, vignette, colorbalance, colorlevels, curves, lut, colorchannelmixer, split, trim/setpts, drawtext, and the audio set (volume, atempo, aresample, amix, afade, adelay, atrim, aformat) | + `eq`, `boxblur` | same as LGPL, minus `drawtext` (no freetype) |
| **Bitstream filters** | `extract_extradata`, `aac_adtstoasc`, `h264_mp4toannexb`, `hevc_mp4toannexb`, `vp9_superframe` | same | same |

`eq` and `boxblur` are marked `deps="gpl"` by FFmpeg itself, which is why they appear only in the GPL column — reach for `hue` (it has a brightness parameter `b`), `colorlevels` or `curves` instead. The bitstream filters are never named by KiteCodec: libavformat inserts them during stream copy, and without them a copy between container families produces a *corrupt file* rather than an error.

`mpeg4` is the dependency-free video baseline: it is always present, in every flavour, so code that must encode *something* without pulling in a GPL or hardware encoder has a target. `https` is **not** built — it needs a TLS backend cross-compiled for every target, which this profile does not take on; use `http`, a local file, or link a system FFmpeg that has TLS.

Probe rather than assume — `FFmpeg.hasEncoder("libx264")` costs nothing and is the difference between a clear message and a runtime failure on a user's machine.

Want libx264 / libx265? That is the **GPL flavour, and it is opt-in** — currently the only route to libx264 in a vendored build:

```bash
# builds --enable-gpl --enable-version3 + libx264/libx265 into native-libs/gpl/<target>/
./gradlew :kitecodec-core:buildFFmpegForMacosArm64Gpl     # or :buildFFmpegForAllGpl

# then select the GPL tree when building KiteCodec:
./gradlew build -Pkitecodec.ffmpeg.license=gpl
```

See [Licensing](#licensing) below before you ship a GPL-flavour binary.

## Windows (mingwX64)

Windows has **no system-FFmpeg discovery**: `FFmpegPaths` resolves macOS (Homebrew) and Linux (apt) installs, but for `mingwX64` it requires a populated `native-libs/<license>/mingw-x64/` tree. You provide it in one of two ways.

**Option A — drop in a BtbN build (what CI does).** The [BtbN FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases) shared builds carry `include/` and `lib/` (import libraries) in exactly the layout `FFmpegPaths` expects. Download one, unzip, and move it into place:

```powershell
# The "gpl" BtbN variant contains libx264, so it lands under the gpl flavour.
# CI pins an exact autobuild tag, asset name and SHA-256 rather than using `latest`,
# which is a moving target — do the same for anything reproducible.
Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip" -OutFile ffmpeg.zip
Expand-Archive ffmpeg.zip -DestinationPath ffmpeg-tmp
Move-Item ffmpeg-tmp\ffmpeg-master-latest-win64-gpl-shared native-libs\gpl\mingw-x64
```

Then build with `-Pkitecodec.ffmpeg.license=gpl` (matching the flavour directory), and make sure the `bin\` directory with the DLLs is on `PATH` at run time. An LGPL BtbN variant exists too (`...-win64-lgpl-shared.zip`); put it under `native-libs\lgpl\mingw-x64` and skip the property. This is exactly how [CI](https://github.com/yuroyami/KiteCodec/blob/main/.github/workflows/ci.yml) runs the Windows tests and e2e transcode on every push.

**Option B — vendored static cross-compile.** Run `:kitecodec-core:buildFFmpegForMingwX64` (or the `Gpl` variant) with a mingw-w64 cross toolchain (`x86_64-w64-mingw32-gcc`) available. This is realistic from a Linux host or MSYS2; it needs the `vendor/ffmpeg` clone described above.

Windows builds, tests, and e2e-transcodes in CI via Option A, against a BtbN tag, asset name and SHA-256 pinned in the workflow. There is no one-command onboarding path on a bare Windows machine, no system-FFmpeg discovery, and no prebuilt KiteCodec asset for `mingw-x64` — you stage the tree yourself.

## Android NDK build profile

Kotlin/Native treats the Android NDK as just another native family, so the entire decode → filter → encode → mux pipeline (and `Remuxer`) compiles untouched for `androidNativeArm64`, `androidNativeArm32`, and `androidNativeX64`.

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kitecodec-core:buildFFmpegForAndroidArm64       # NDK cross-compile, ~6 min
./gradlew :kitecodec-core:compileKotlinAndroidNativeArm64  # the klib
```

The Android FFmpeg profile is deliberately different from the desktop one:

- **LGPL only.** No `--enable-gpl`, no libx264 / libx265. Play-Store-safe and closed-source-safe.
- **MediaCodec hardware codecs** (`h264_mediacodec`, `hevc_mediacodec`) replace the GPL software encoders. Reach them through the `CodecId.H264MediaCodec` / `CodecId.HevcMediaCodec` companions.
- **Full software decode set** (h264 / hevc / vp8 / vp9 / av1, plus audio) identical to desktop.

!!! warning "MediaCodec needs a JavaVM"
    FFmpeg's MediaCodec wrapper needs the app's `JavaVM` handed over via `av_jni_set_java_vm` before the first `*_mediacodec` codec opens. The upcoming Android substrate owns that call. Until it lands, MediaCodec hardware encode is not wired up for a plain app.

!!! note "Not an AAR yet"
    This produces a Kotlin/Native `.klib` for KMP code targeting Android native, not an `.aar` a regular Kotlin/JVM Android app can `implementation(...)`. The JVM path needs a JNI bridge over the same `ffkmp_*` C helpers; that substrate is the next milestone and shares this exact FFmpeg build.

## Licensing

The Apache 2.0 licence covers KiteCodec's own Kotlin code. The FFmpeg you link against carries its own licence, and that is what determines whether your binary is App-Store-safe. The choice is made at the FFmpeg build level, as two flavours:

| Flavour | FFmpeg licence | Encoders | Use for |
|---|---|---|---|
| **LGPL** (default) | LGPL-2.1+ (no `--enable-gpl`) | Platform hardware (VideoToolbox / MediaCodec) for H.264 / H.265, plus svtav1 / opus / mp3lame software | Commercial / closed-source / App Store distribution (mind the [LGPL obligations](licensing.md)) |
| **GPL** (opt-in) | GPL — effectively **GPL-3.0**, since the build also sets `--enable-version3` | Adds libx264 / libx265 for quality-focused software encode | GPL-compatible projects only (open-source apps, server tools, internal use) |

The LGPL flavour is what `buildFFmpegFor<Target>` produces and what the build links by default. The GPL flavour is a loud opt-in: build with `buildFFmpegFor<Target>Gpl` and select it with `-Pkitecodec.ffmpeg.license=gpl`. This GPL opt-in is currently the **only** route to libx264 / libx265 in a vendored build.

!!! warning "GPL is not App-Store-safe"
    The GPL flavour adds libx264 / libx265 by enabling `--enable-gpl` (and `--enable-version3`, making the effective licence GPL-3.0). A binary that links those must not ship through the iOS App Store or any other closed-source / commercial channel. For those, stay on the LGPL flavour and use the hardware encoders.

!!! note "`kitecodec-gpl` is planned, not published"
    A separate `kitecodec-gpl` artifact that packages the GPL flavour as a drop-in dependency is planned but does not exist yet — it is a README-only skeleton, commented out of `settings.gradle.kts`. Today the GPL flavour is reached only through the build tasks and the `kitecodec.ffmpeg.license` property described above.

For distribution obligations (shipping licence texts, offering FFmpeg source, the LGPL relinking requirement for static builds), see the [Licensing guide](licensing.md).

### Picking an encoder per platform

`CodecId` exposes the relevant encoders as companions. Software (libx264) is GPL; hardware (VideoToolbox, MediaCodec) is LGPL-safe.

=== "App-Store-safe (hardware)"

    ```kotlin
    // macOS / iOS: VideoToolbox
    VideoEncoderSpec(
        codec = CodecId.H264VideoToolbox,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    )

    // Android: MediaCodec
    VideoEncoderSpec(codec = CodecId.H264MediaCodec, width = 1280, height = 720, frameRate = Rational.Fps30)
    ```

=== "GPL software (libx264)"

    ```kotlin
    VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
        options = mapOf("preset" to "medium", "crf" to "20"),
    )
    ```

Encoder availability is resolved at runtime. Probe before you commit to a codec:

```kotlin
val codec = listOf(
    CodecId.H264VideoToolbox,   // macOS / iOS, LGPL-safe
    CodecId.H264MediaCodec,     // Android, LGPL-safe
    CodecId.Libx264,            // GPL builds only
    CodecId("mpeg4"),           // always present, every profile
).first { FFmpeg.hasEncoder(it.name) }
```

## Related

- [Getting started](getting-started.md): build the sample and run your first transcode.
- [Encoding and muxing](encoding-muxing.md): `MediaSink`, encoder specs, and codec options.
- [API reference](https://yuroyami.github.io/KiteCodec/api/): the full public surface.
