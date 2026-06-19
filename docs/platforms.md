# Platform support

Decode, encode, transcode, remux, and filter video and audio from shared Kotlin code. The API lives in `commonMain`; every target binds to the same FFmpeg libav\* libraries underneath. KiteCodec is Kotlin/Native only today. There is no JVM or Android-app artifact yet.

## Target matrix

`kitecodec-core` is one consolidated cinterop module over FFmpeg's libav\* libraries. The `commonMain` API (`Transcoder`, `Remuxer`, `MediaSource`, `MediaSink`, `FilterGraph`, `FFmpeg`) is the same everywhere; what varies is how far each target has been carried through CI and end-to-end validation.

| Platform | Status | Notes |
|---|:-:|---|
| **macOS arm64** | ✓ verified | End-to-end: video + audio, ffprobe-validated. The reference target. |
| **Linux x64** | CI | Built, tested, and e2e-transcoded on every push (FFmpeg 6.1). |
| **Windows** (mingwX64) | CI | Built, tested, and e2e-transcoded on every push (BtbN mingw build). |
| **Android native** (arm64 / arm32 / x64) | CI klib | CI cross-compiles FFmpeg with the NDK (LGPL + MediaCodec) and builds the `.klib`. |
| **macOS x64** | code written | `macosX64` deprecated by Kotlin 2.3.20; not CI-verified. |
| **iOS arm64** | code written | Needs vendored FFmpeg in CI; not yet verified. |
| **iOS Simulator** (arm64) | code written | Not yet CI-verified. |
| **Linux arm64** | code written | Not yet CI-verified. |
| **Android AAR** (JVM apps via `androidTarget`) | not yet | Next milestone: a JNI substrate over the same `ffkmp_*` C layer. |
| **JVM / Desktop** | not yet | No JVM target today; Kotlin/Native only. |

!!! note "What \"code written\" means"
    The same `nativeMain` actuals compile for these targets, but they have not been carried through CI or validated end-to-end. Treat them as untested until a release note says otherwise. macOS arm64 is the only target verified end-to-end today.

## FFmpeg is a prerequisite

KiteCodec links FFmpeg's libav\* libraries. It does not ship them in the published `0.0.1` artifact, and the library is **not on Maven Central yet** (that is a v0.4 roadmap item). Today you consume KiteCodec by building from source, which means you must have FFmpeg available in one of two forms.

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

For a self-contained binary, build a minimal FFmpeg from source. The Gradle task cross-compiles a pinned codec and filter set and drops `.a` libraries under `native-libs/<target>/`. `FFmpegPaths` notices and switches cinterop to static linking; the resulting executable carries everything it needs, around 25 MB.

```bash
./gradlew :kitecodec-core:buildFFmpegForMacosArm64
# or build every configured target at once:
./gradlew :kitecodec-core:buildFFmpegForAll
```

The static profile enables the GPL codec ladder (x264 / x265 / svtav1 / aom / vpx / opus). If you ship through a GPL-hostile channel (for example the iOS App Store), turn `--enable-gpl` off in [`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt). See [Licensing](#licensing) below.

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

The Apache 2.0 licence covers KiteCodec's own Kotlin code. The FFmpeg you link against carries its own licence, and that is what determines whether your binary is App-Store-safe. KiteCodec is planned as two artifacts to make the choice explicit.

| Artifact | FFmpeg licence | Encoders | Use for |
|---|---|---|---|
| **`kitecodec-core`** | LGPL-2.1+ build (no `--enable-gpl`) | Platform hardware (VideoToolbox / MediaCodec) for H.264 / H.265 | Commercial / closed-source / App Store distribution |
| **`kitecodec-gpl`** | GPL-2.0+ add-on | Adds libx264 / libx265 for quality-focused software encode | GPL-compatible projects only (open-source apps, server tools, internal use) |

!!! warning "GPL is not App-Store-safe"
    `kitecodec-gpl` adds libx264 / libx265 by enabling `--enable-gpl`. A binary that links those is GPL-2.0+ and must not ship through the iOS App Store or any other closed-source / commercial channel. For those, stay on `kitecodec-core` (LGPL) and use the hardware encoders.

!!! note "`kitecodec-gpl` is planned, not published"
    Today only `kitecodec-core` exists. The split is reflected at the FFmpeg build level: the static build task enables `--enable-gpl` by default, and you toggle it off in [`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt) for an LGPL-only binary.

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
val codec = if (FFmpeg.hasEncoder("h264_videotoolbox")) {
    CodecId.H264VideoToolbox
} else {
    CodecId.Libx264
}
```

## Related

- [Getting started](getting-started.md): build the sample and run your first transcode.
- [Encoding and muxing](encoding-muxing.md): `MediaSink`, encoder specs, and codec options.
- [API reference](https://yuroyami.github.io/KiteCodec/api/): the full public surface.
