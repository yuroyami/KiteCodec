# KiteCodec

**One coroutine-first Kotlin API for video and audio.** Decode, encode, transcode and filter media from a single suspend-friendly surface, backed by native bindings to FFmpeg's libav\* libraries. No `ffmpeg` subprocess, no JVM, no JNI hop, and constant memory regardless of how long the input runs.

```kotlin
// One call: demux -> decode -> filter -> encode -> mux, in a single pass.
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
    onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
)
```

!!! note
    `CodecId.Libx264` above is software x264 from the optional `kitecodec-gpl` add-on. The default `kitecodec-core` ships hardware H.264 / H.265 and royalty-free software AV1 under LGPL, which is the App-Store-safe path. See [Platform support](platforms.md).

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started in a few minutes](getting-started.md)
- :material-book-open-variant: **Browse the guides** below, or jump to the [API reference](https://yuroyami.github.io/KiteCodec/api/)

</div>

## Why KiteCodec

The usual way to do media work from Kotlin is to shell out to the `ffmpeg` CLI and parse its stderr, or to wrap a binary like FFmpegKit. You marshal arguments into a string, launch a process, and read progress back out of log lines. The codec engine lives outside your program.

KiteCodec is a **single Kotlin API over libav\* directly**. You call `Transcoder.transcode(...)` and it opens the file via libavformat, demuxes **once**, routes packets to per-stream libavcodec decoders, pumps frames through libavfilter graphs, encodes, and interleaves the streams into a valid container. There is no process to spawn and no log to scrape. Progress arrives as a typed callback, errors arrive as typed exceptions, and frames flow as a coroutine `Flow`.

Everything routes through one demux pass. When you decode several streams, or composite two inputs, the demuxer reads the file a single time and fans packets out to the decoders that need them.

## Install

KiteCodec is **not on Maven Central yet** (Central publishing is a v0.4 roadmap item). Today you consume it by building from source against an FFmpeg you provide. The Gradle build discovers a system FFmpeg or cross-compiles a vendored static one through its own build tasks.

!!! warning "FFmpeg is a prerequisite"
    The bindings link against libav\*. You need FFmpeg present at build and run time, either installed through your package manager or produced by the bundled FFmpeg build tasks.

=== "macOS"

    ```bash
    brew install ffmpeg
    ./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64
    ```

=== "Linux"

    ```bash
    sudo apt install ffmpeg libavcodec-dev libavformat-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev
    ./gradlew :kitecodec-core:linuxX64Test
    ```

The published coordinate is `io.github.yuroyami:kitecodec-core:0.0.1`, built locally for now. The default `kitecodec-core` profile is LGPL and commercial-safe; a `kitecodec-gpl` module that adds libx264 / libx265 for GPL projects is planned. See [Platform support](platforms.md) for what is verified where.

!!! note "Kotlin/Native only, today"
    KiteCodec is a Kotlin/Native library. macOS arm64 is verified end to end. Linux and Windows build and test in CI. Android compiles as a native klib. iOS code is written but not yet CI-verified. There is no JVM or Android-app AAR yet.

## What you can do

### Read

Open a file, inspect its streams, and pull decoded frames as a `Flow`. The demuxer wraps `AVFormatContext`; the decode loop is EAGAIN-correct and promotes `best_effort_timestamp` to `pts`.

```kotlin
MediaSource.open("input.mp4").use { src ->
    println("${src.formatName}, ${src.durationMicros} us")

    val video = src.primaryVideo ?: error("no video stream")
    src.decodedFrames(video).collect { frame ->
        val info = frame.info               // width, height, pts, pixelFormat...
        val pixels = frame.copyPlanesToByteArray()
        // ...
    }
}
```

Grab a single frame for a thumbnail and encode it straight to image bytes:

```kotlin
MediaSource.open("input.mp4").use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame ->
        writeFile(frame.encodeImage(CodecId.Mjpeg))
    }
}
```

See **[Decoding & frames](decoding.md)**.

### Transcode

The high-level pipeline handles trim, audio copy, subtitle copy, metadata and progress in one call. Skip the video codec (`spec = null`) for an audio-only transcode such as mp3 to aac.

```kotlin
// Frame-exact clip, output timestamps rebased to zero.
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    audioCopy = true,                    // stream-copy audio bit-exact
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
    metadata = mapOf("title" to "My clip"),
)
```

Nothing to re-encode at all? Rewrite the container losslessly:

```kotlin
Remuxer.remux("input.mp4", "output.mkv")   // no decode, no encode, runs in seconds
```

See **[Transcoding](transcoding.md)** and **[Remuxing](remuxing.md)**.

### Filter

Build a libavfilter graph from a plain FFmpeg filter description and drive frames through it. Single-input graphs expose a `Flow` pipeline; multi-input graphs (overlay, amix) take a push callback.

```kotlin
// Two inputs -> one output: watermark in the bottom-right corner.
val graph = FilterGraph.buildVideoMulti(
    "[in0][in1]overlay=W-w-10:H-h-10[out]",
    listOf(mainVideoInput, logoInput),
)
graph.feedInput(0, videoFrame) { composited -> /* encode */ }
graph.feedInput(1, logoFrame)  { /* ... */ }
```

See **[Filtering](filtering.md)**.

## Guides

| | |
|---|---|
| **[Getting started](getting-started.md)** | Install FFmpeg, build, and run your first transcode. |
| **[Transcoding](transcoding.md)** | The `Transcoder.transcode` pipeline: specs, trim, audio copy, progress. |
| **[Decoding & frames](decoding.md)** | `MediaSource`, decode flows, single-pass multi-stream, thumbnails. |
| **[Filtering](filtering.md)** | `FilterGraph` video and audio graphs, single and multi-input. |
| **[Encoding & muxing](encoding-muxing.md)** | `MediaSink`, `VideoEncoder` / `AudioEncoder`, encoder specs and options. |
| **[Remuxing](remuxing.md)** | Lossless container rewrite and keyframe-snapped trim. |
| **[Recipes](recipes.md)** | Copy-paste patterns for common tasks. |
| **[Platform support](platforms.md)** | What runs where, and how FFmpeg is sourced. |

## Status

KiteCodec is at v0.3. The full demux -> decode -> filter -> encode -> mux pipeline is live for video and audio, including frame-exact trim, audio and subtitle stream copy, multi-input filter graphs, lossless remux, thumbnails, and hardware H.264 encode via VideoToolbox on macOS. Hardware decode and full zero-copy hwframes pipelines are on the roadmap for v0.4, alongside Maven Central publishing and an Android AAR.

For the project's design, FFmpeg sourcing modes, and the full roadmap, see **[About KiteCodec](about.md)**.
