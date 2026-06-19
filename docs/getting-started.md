# Getting Started

Learn how to install FFmpeg, wire the module, probe what your build can do, inspect a media file, and run your first transcode with KiteCodec: a coroutine-first Kotlin/Native binding to FFmpeg's libav* libraries.

!!! note "Platform reality"

    KiteCodec is **Kotlin/Native only** today, and **not yet on Maven Central**. You consume it by building from source against your own FFmpeg, or against a vendored static build produced by the Gradle tasks. macOS arm64 is verified end to end; Linux x64 and Windows (mingw x64) build and test in CI; Android compiles as a native klib; iOS code is written but not yet CI-verified. See [Platform support](platforms.md) for the full matrix.

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

    For a release where you do not want a runtime FFmpeg dependency, build a minimal static FFmpeg from source with the Gradle task. It drops `.a` libraries under `native-libs/<target>/`, and `FFmpegPaths` switches the cinterop to static linking automatically.

    ```bash
    ./gradlew :kitecodec-core:buildFFmpegForMacosArm64
    # or build every target you have toolchains for:
    ./gradlew :kitecodec-core:buildFFmpegForAll
    ```

    The resulting executable carries everything it needs (around 25 MB).

!!! tip "Android"

    Android uses a different FFmpeg profile (LGPL only, MediaCodec hardware codecs). Cross-compile it with the NDK before building the klib. See [Platform support](platforms.md) for the `buildFFmpegForAndroidArm64` flow.

## Step 2: Wire the module

KiteCodec is not on Maven Central. Add `:kitecodec-core` to your build from source.

The simplest path is to clone the repository alongside your project and include the build:

=== "settings.gradle.kts"

    ```kotlin
    includeBuild("../KiteCodec")
    ```

=== "build.gradle.kts"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitecodec-core")
            }
        }
    }
    ```

The published coordinates are `io.github.yuroyami:kitecodec-core:0.0.1`, but no artifact is on a public repository yet. Build from source, or work inside the KiteCodec repo itself (the `:kitecodec-sample` module already depends on `:kitecodec-core`).

!!! note "GPL add-on"

    `kitecodec-core` is the LGPL default and is safe for commercial distribution. A `kitecodec-gpl` add-on that adds libx264 / libx265 for quality-focused software encode is planned. Use it only in GPL-compatible projects.

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

`Transcoder.transcode(...)` runs the full pipeline in one pass: demux -> decode -> filter -> encode -> mux. It is a `suspend` function, so call it from a coroutine.

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
            codec = CodecId.Libx264,
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

!!! note "Libx264 needs the GPL artifact"
    `CodecId.Libx264` is software x264, which ships only in the optional `kitecodec-gpl` add-on. The default `kitecodec-core` (LGPL) gives you hardware H.264 / H.265 (VideoToolbox, MediaCodec, NVENC) and royalty-free software AV1 instead. Pick one of those for an App-Store-safe build. See the licensing notes in [Platform support](platforms.md).

A few defaults worth knowing:

- Pass `audioSpec = null` to drop audio, or `audioCopy = true` to stream-copy it bit-exact instead of re-encoding.
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

# Full transcode: decode, filter, libx264 + aac encode, interleaved mux:
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
- **[Recipes](recipes.md)**: copy-paste patterns for common tasks.
- **[API reference](https://yuroyami.github.io/KiteCodec/api/)**: every public type and signature.
