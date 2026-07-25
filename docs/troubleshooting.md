# Troubleshooting

Most build-time problems come down to one thing: KiteCodec links against an FFmpeg **you** provide, and the build could not find or produce it.

## "No FFmpeg install found for \<target\>"

`FFmpegPaths.resolve` looks for a vendored static tree under `native-libs/<license>/<target>/{include,lib}` first, then falls back to a system install. This error means neither existed. Fix one of the two:

- install FFmpeg system-wide (`brew install ffmpeg` on macOS; the `libav*-dev` packages via apt on Linux), or
- vendor a static build: `./gradlew :kitecodec-core:buildFFmpegFor<Target>` (see [prerequisites](#vendored-build-prerequisites) below).

Note the `<license>` path segment: if you built the GPL flavour (`buildFFmpegFor<Target>Gpl` → `native-libs/gpl/<target>/`) but did not pass `-Pkitecodec.ffmpeg.license=gpl`, the build looks under `native-libs/lgpl/` and misses your libraries. Flavour and property must match.

## macOS: Homebrew in a non-standard prefix

On macOS the build probes `/opt/homebrew` (Apple Silicon) and `/usr/local` (Intel) for `include/libavformat/avformat.h`. If your Homebrew lives elsewhere — or you want to point at a custom FFmpeg prefix — set the override in `gradle.properties`:

```properties
kitecodec.macos.homebrew.prefix=/custom/prefix
```

The prefix must contain `include/libavformat/avformat.h` and the FFmpeg dylibs under `lib/`. The same property is honoured by the [Gradle plugin](gradle-plugin.md)'s `System` source.

## Windows: nothing is auto-discovered

There is **no system-FFmpeg discovery on Windows**: `FFmpegPaths` resolves Homebrew (macOS) and apt (Linux) installs only; for `mingwX64` it requires a populated `native-libs/<license>/mingw-x64/` tree, full stop. Installing an `ffmpeg.exe` from anywhere will not help — the build needs headers and import libraries.

Stage them yourself, either by dropping in a [BtbN build](https://github.com/BtbN/FFmpeg-Builds/releases) (shared zips carry `include/` + `lib/` in the exact expected layout — this is what CI does) or by cross-compiling the vendored build with a mingw-w64 toolchain. The step-by-step is in [Platform support → Windows](platforms.md#windows-mingwx64). Remember: BtbN "gpl" zips go under `native-libs/gpl/mingw-x64` and need `-Pkitecodec.ffmpeg.license=gpl`; at run time the DLL `bin\` directory must be on `PATH`.

## VideoToolbox fails on VMs / CI runners

`h264_videotoolbox` refuses to open when no hardware encode block is available — the usual case on virtualised macOS (CI runners, VMs). The encoder returns an error at `addVideoEncoder` / during `transcode` even though `FFmpeg.hasEncoder("h264_videotoolbox")` is true, because availability of the *encoder* and availability of the *hardware* are different questions.

Pass `allow_sw` to let VideoToolbox fall back to its software path instead of failing:

```kotlin
VideoEncoderSpec(
    codec = CodecId.H264VideoToolbox,
    width = 1280, height = 720,
    frameRate = Rational(30, 1),
    options = mapOf("allow_sw" to "1"),
)
```

## Vendored build prerequisites

The `buildFFmpegFor<Target>` tasks compile FFmpeg from source, and they fail fast when the ingredients are missing.

**1. The FFmpeg source tree.** The task expects it at `vendor/ffmpeg` and stops with this exact instruction otherwise:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
```

**2. Build tools.** `make`, a C toolchain (clang/gcc), `nasm` or `yasm` (x86 assembly — configure fails without it on x86 targets), and `pkg-config`.

**3. The external encoder libraries** the desktop profile enables. Configure errors like `ERROR: libsvtav1 not found using pkg-config` mean the dev package is missing. On macOS:

```bash
brew install nasm pkg-config svt-av1 libvpx aom opus lame webp \
             freetype harfbuzz fribidi libass
# GPL flavour additionally:
brew install x264 x265
```

On Debian/Ubuntu the equivalents are the `-dev` packages (`libsvtav1-dev`, `libvpx-dev`, `libaom-dev`, `libopus-dev`, `libmp3lame-dev`, `libwebp-dev`, `libfreetype-dev`, `libharfbuzz-dev`, `libfribidi-dev`, `libass-dev`, plus `libx264-dev` / `libx265-dev` for GPL).

**4. Idempotence.** The task skips when `native-libs/<license>/<target>/lib/libavformat.a` already exists. To force a rebuild, delete that directory.

## Android: "Android NDK not found"

The NDK cross-compile resolves its toolchain from, in order: the `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, or `ANDROID_NDK_LATEST_HOME` environment variables, then the newest `ndk/<version>` under the default SDK locations (`~/Library/Android/sdk/ndk` on macOS, `~/Android/Sdk/ndk` on Linux). If none resolve:

```bash
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kitecodec-core:buildFFmpegForAndroidArm64
```

The vendored `vendor/ffmpeg` clone is required here too. Note that Android builds are always the LGPL MediaCodec profile — there is no GPL Android build, and requesting one fails with an explanatory error.

## "libx264 not found" / `CodecId.Libx264` encoder missing at runtime

libx264 only exists in GPL-flavour FFmpeg builds. System FFmpeg from Homebrew/apt usually has it; KiteCodec's vendored **LGPL default does not**. Either opt in to the GPL flavour (`buildFFmpegFor<Target>Gpl` + `-Pkitecodec.ffmpeg.license=gpl` — mind the [licence consequences](licensing.md)) or use an LGPL-safe encoder (`CodecId.H264VideoToolbox`, `CodecId.H264MediaCodec`, or `libsvtav1` for AV1). Probe at runtime with `FFmpeg.hasEncoder("libx264")` before committing to a codec.

## Still stuck?

Check the capability probe first — it answers "what did I actually link?":

```kotlin
println(FFmpeg.versions)
println(FFmpeg.buildConfiguration)   // the exact ./configure line of the linked FFmpeg
```

Then [open an issue](https://github.com/yuroyami/KiteCodec/issues) with the probe output, your platform, and how you sourced FFmpeg.
