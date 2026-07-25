# Getting Started

Learn how to install FFmpeg, wire the module, probe what your build can do, inspect a media file, and run your first transcode with KiteCodec: a coroutine-first Kotlin/Native binding to FFmpeg's libav* libraries.

!!! warning "Before you start"

    KiteCodec is **Kotlin/Native only**. There is no JVM target, no Android AAR, and no web target. Nothing is published yet, so this guide uses the in-repository path. You build against an FFmpeg you install, or against a vendored static build the Gradle tasks produce. The consumer-project build script and the [release status](https://github.com/yuroyami/KiteCodec#release-status) are in the README. The [target table](https://github.com/yuroyami/KiteCodec#targets) records what CI verifies.

## Step 1: Get FFmpeg

KiteCodec links against FFmpeg's libav* libraries. You need them present before you build. There are two ways to source them.

=== "System FFmpeg (default)"

    Install FFmpeg with your package manager. This is what the macOS arm64 build does today.

    ```bash
    # macOS
    brew install ffmpeg

    # Debian / Ubuntu
    sudo apt install -y \
        libavformat-dev libavcodec-dev libavfilter-dev \
        libavutil-dev libswscale-dev libswresample-dev
    ```

    `FFmpegPaths` finds Homebrew on macOS (override with `kitecodec.macos.homebrew.prefix` in `gradle.properties`) or apt-installed libraries on Linux, then points cinterop at their headers and shared libraries. Your users need their own FFmpeg installed at runtime.

=== "Vendored static build"

    For a release where you do not want a runtime FFmpeg dependency, build a minimal static FFmpeg from source with the Gradle task. It drops `.a` libraries under `native-libs/<license>/<target>/`, and `FFmpegPaths` switches the cinterop to static linking automatically.

    The task expects the FFmpeg source tree at `vendor/ffmpeg`. Cloning it is a **mandatory first step**:

    ```bash
    git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg

    ./gradlew :kitecodec-core:buildFFmpegForMacosArm64
    # or build every target you have toolchains for:
    ./gradlew :kitecodec-core:buildFFmpegForAll
    ```

    You also need FFmpeg's usual build prerequisites on the machine: `make`, a C toolchain, `nasm`/`yasm` for the x86 assembly, `pkg-config`, and the third-party encoder libraries the profile enables: svt-av1, libvpx, aom, opus, lame, webp, freetype, harfbuzz, fribidi, and libass. On macOS run `brew install nasm pkg-config svt-av1 libvpx aom opus lame webp freetype harfbuzz fribidi libass`. See [Troubleshooting](troubleshooting.md#vendored-build-prerequisites) if configure fails.

    The default flavor is **LGPL** (no libx264 / libx265). For the GPL flavor, run the `Gpl` task variants (for example `buildFFmpegForMacosArm64Gpl`) and build with `-Pkitecodec.ffmpeg.license=gpl`. The resulting executable carries everything it needs (around 25 MB).

!!! tip "Android"

    Android uses a different FFmpeg profile (LGPL only, MediaCodec hardware codecs). Cross-compile it with the NDK before building the klib. See [Platform support](platforms.md) for the `buildFFmpegForAndroidArm64` flow.

## Step 2: Wire the module

Nothing is published, so there are two routes.

**Inside the KiteCodec repository.** The `:kitecodec-sample` module already depends on `:kitecodec-core` and is the fastest way to run the API against real arguments. Everything below works from a plain clone.

**From your own project.** Clone KiteCodec alongside it and compose the builds:

=== "settings.gradle.kts"

    ```kotlin
    includeBuild("../KiteCodec")
    ```

=== "build.gradle.kts"

    ```kotlin
    kotlin {
        macosArm64()
        sourceSets.commonMain.dependencies {
            implementation("io.github.yuroyami:kitecodec-core")
        }
    }
    ```

    A composite build substitutes the dependency with the included project, so the version is omitted deliberately. Your FFmpeg comes from KiteCodec's own `FFmpegPaths` resolution (Step 1), not from the Gradle plugin.

Once `kitecodec-core` and the [Gradle plugin](gradle-plugin.md) are published, the consumer build script replaces all of this. It is written out in full in the [README](https://github.com/yuroyami/KiteCodec#install); the short version is that the plugin is mandatory (the klib's `ffmpeg.def` carries no `-L`, so the coordinate alone will not link) and so is the `license` choice.

!!! note "`kitecodec-gpl` does not exist"

    `kitecodec-core` is the LGPL default and is safe for commercial distribution. A `kitecodec-gpl` add-on packaging libx264 / libx265 has a README in the repository and nothing else. It has no build script, and it is commented out of `settings.gradle.kts`. The GPL flavor is reached through the `Gpl` build tasks plus `-Pkitecodec.ffmpeg.license=gpl`.

## Step 3: Probe what your build can do

Every public type lives under `io.github.yuroyami.kitecodec`. Start with the `FFmpeg` object: it reports the linked library versions and tells you which encoders, decoders, and filters are available in this particular build.

```kotlin
import io.github.yuroyami.kitecodec.FFmpeg

fun printCapabilities() {
    val v = FFmpeg.versions
    println("avcodec ${v.avcodec}, avformat ${v.avformat}, avfilter ${v.avfilter}")
    println("build config: ${FFmpeg.buildConfiguration}")

    println("libx264 available: ${FFmpeg.hasEncoder("libx264")}")
    println("aac available:     ${FFmpeg.hasEncoder("aac")}")
    println("h264 decoder:      ${FFmpeg.hasDecoder("h264")}")
    println("scale filter:      ${FFmpeg.hasFilter("scale")}")
}
```

Capability probing matters because builds differ. A hardware encoder like `h264_videotoolbox` exists on macOS but not in a Linux VM; checking `FFmpeg.hasEncoder(...)` at runtime lets you pick a codec that is actually present.

## Step 4: Open and inspect a file

`MediaSource.open(path)` opens an input via libavformat and exposes its streams and metadata. It is `AutoCloseable`, so wrap it in `use { }`.

```kotlin
import io.github.yuroyami.kitecodec.MediaSource

MediaSource.open("input.mp4").use { src ->
    println("container: ${src.formatName}")
    println("duration:  ${src.durationMicros?.let { it / 1_000_000.0 } ?: "unknown"} s")
    println("metadata:  ${src.metadata}")

    for (stream in src.streams) {
        print("  stream #${stream.index}  ${stream.type}  ${stream.codec.name}")
        stream.video?.let { print("  ${it.width}x${it.height} @ ${it.frameRate}") }
        stream.audio?.let { print("  ${it.sampleRate} Hz  ${it.channels}ch") }
        println()
    }

    // Convenience accessors for the streams you usually want:
    val v = src.primaryVideo
    val a = src.primaryAudio
}
```

Each `StreamInfo` carries an `index`, a `type` (`MediaType.Video`, `Audio`, `Subtitle`, ...), a `codec` (`CodecId`), a `timeBase` (`Rational`), and either a `video` (`VideoStreamInfo`) or `audio` (`AudioStreamInfo`) detail block. Reading frames out of a stream is covered in [Decoding](decoding.md).

## Step 5: Your first transcode

`Transcoder.transcode(...)` runs the full pipeline in one pass: demux -> decode -> filter -> encode -> mux. Demux means split a container file into its separate streams. Mux means write streams back into a container file. It is a `suspend` function, so call it from a coroutine.

```kotlin
import io.github.yuroyami.kitecodec.Transcoder
import io.github.yuroyami.kitecodec.VideoEncoderSpec
import io.github.yuroyami.kitecodec.AudioEncoderSpec
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.Rational
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec = VideoEncoderSpec(
            // mpeg4 is the dependency-free baseline present in every FFmpeg profile.
            codec = CodecId("mpeg4"),
            width = 320, height = 180,
            frameRate = Rational(30, 1),
            bitrateBps = 1_500_000,
        ),
        videoFilter = "scale=320:180,format=yuv420p",
        audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),
        onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
    )
}
```

!!! tip "Pick the video encoder by probing"
    `mpeg4` is used above because it is in every profile. For H.264 or H.265, ask the linked build what it has rather than hard-coding a name. `CodecId.Libx264` only resolves in a GPL FFmpeg. The vendored default is LGPL, and asking for it there throws `FFmpegException` from `addVideoEncoder`.

    ```kotlin
    val codec = listOf(
        CodecId.H264VideoToolbox,   // macOS / iOS, LGPL-safe
        CodecId.H264MediaCodec,     // Android, LGPL-safe
        CodecId.Libx264,            // GPL builds only
        CodecId("mpeg4"),           // always present
    ).first { FFmpeg.hasEncoder(it.name) }
    ```

    See [Platform support](platforms.md#licensing) and [Licensing](licensing.md).

A few defaults worth knowing:

- Pass `audioSpec = null` to drop audio, or `audioCopy = true` to stream-copy it instead of re-encoding. A stream copy moves the encoded packets across unchanged, so the audio stays bit-exact.
- Pass `spec = null` for an audio-only transcode (for example mp3 -> aac).
- `startMicros` and `endMicros` cut a frame-exact clip; output timestamps rebase to zero. `endMicros` has no upper bound unless you set it.
- `onProgress` receives a `TranscodeProgress` with `framesEncoded`, `outputMicros`, and a nullable `percent`. It fires roughly every 30 video frames (or every 100 frames for audio-only work), not on an exact count.

```kotlin
// Cut a clip from 12.3s to 45.6s, re-encoded frame-exact:
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = spec,
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
)
```

If you do not need to touch the codecs at all, skip the transcoder and rewrite the container losslessly:

```kotlin
import io.github.yuroyami.kitecodec.Remuxer

Remuxer.remux("input.mp4", "output.mkv")   // no re-encode, runs in seconds
```

See [Transcoding](transcoding.md) for filters, hardware encoders, and progress in depth, and [Remuxing](remuxing.md) for stream-copy and keyframe-snapped trim.

## Step 6: Run the sample

The `:kitecodec-sample` module is a small macOS arm64 CLI that exercises the whole API. Build it, then point it at any media file.

```bash
brew install ffmpeg                     # macOS prereq
./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64

KEXE=kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe

# Capability probe (same data as FFmpeg.versions / hasEncoder):
$KEXE info

# Inspect any media file (streams, duration, metadata):
$KEXE probe path/to/clip.mp4

# Full transcode: decode, filter, video + aac encode, interleaved mux.
# The sample probes for its video encoder (libx264, else mpeg4, libsvtav1, mjpeg).
$KEXE transcode input.mp4 output.mp4 "scale=1280:720,format=yuv420p"

# Video only / audio passthrough / hardware encode:
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -an
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -acopy
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -vt     # h264_videotoolbox

# Frame-exact clip + metadata:
$KEXE transcode input.mp4 clip.mp4 "scale=1280:720" --ss 12.3 --to 45.6 --title "My clip"

# Audio-only (mp3 in, aac out):
$KEXE transcode song.mp3 song.m4a

# Thumbnail at 90s:
$KEXE thumbnail input.mp4 frame.jpg 90.0

# Lossless container rewrite:
$KEXE remux input.mp4 output.mkv
```

Reading the sample source is the fastest way to see each API used against real arguments.

## Where to next?

- **[Decoding](decoding.md)**: pull `Frame`s out of a stream, decode several streams in one demux pass, extract thumbnails.
- **[Transcoding](transcoding.md)**: the full `Transcoder.transcode(...)` surface: filters, hardware encoders, trim, progress.
- **[Filtering](filtering.md)**: build single-input and multi-input `FilterGraph`s for scaling, overlay, and audio mixing.
- **[Encoding & muxing](encoding-muxing.md)**: drive `VideoEncoder` / `AudioEncoder` directly through a `MediaSink`.
- **[Remuxing](remuxing.md)**: lossless `Remuxer.remux(...)` and stream-copy.
- **[Concurrency](concurrency.md)**: the threading, confinement, and cancellation rules.
- **[Recipes](recipes.md)**: copy-paste patterns for common tasks.
- **[Troubleshooting](troubleshooting.md)**: FFmpeg not found, Windows setup, VideoToolbox on VMs.
- **[API reference](https://yuroyami.github.io/KiteCodec/api/)**: every public type and signature.
