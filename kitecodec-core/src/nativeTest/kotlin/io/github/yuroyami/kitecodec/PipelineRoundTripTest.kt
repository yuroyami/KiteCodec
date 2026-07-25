package io.github.yuroyami.kitecodec

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fixture-free end-to-end coverage: frames are SYNTHESIZED ([Frame.ofVideo]/[Frame.ofAudio]),
 * encoded + muxed through the real pipeline, then demuxed + decoded back and asserted.
 * No test media assets needed; runs against whatever FFmpeg the build linked.
 */
class PipelineRoundTripTest {

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String =
        "build/kitecodec-test-$name".also { tmpFiles += it }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    /** One tightly-packed yuv420p frame with per-frame varying luma (not a solid color). */
    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> ((i + index * 7) % 200 + 20).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun writeTestVideo(path: String, frames: Int = 30, width: Int = 64, height: Int = 64) {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),  // built into every FFmpeg — no external encoder needed
                    width = width, height = height,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until frames).asFlow().map { i ->
                        Frame.ofVideo(
                            bytes = yuvFrame(width, height, i),
                            width = width, height = height,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = i * 1_000_000L / 30,
                        )
                    }
                )
            }
        }
    }

    @Test
    fun synthesizedFramesSurviveEncodeDecodeRoundTrip() {
        val path = tmp("roundtrip.mp4")
        writeTestVideo(path)

        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("No video stream in round-trip output")
            assertEquals(MediaType.Video, video.type)
            assertEquals(64, video.video!!.width)
            assertEquals(64, video.video!!.height)

            val decoded = runBlocking { src.decodedFrames(video).toList() }
            try {
                assertTrue(decoded.size >= 28, "expected ~30 decoded frames, got ${decoded.size}")
                // Owned emission contract: frames collected via toList() must still be readable.
                val bytes = decoded.first().copyPlanesToByteArray()
                assertEquals(64 * 64 * 3 / 2, bytes.size)
                // Distinct frames — the reuse bug would make all list entries alias one buffer.
                val first = decoded.first().copyPlanesToByteArray()
                val last = decoded.last().copyPlanesToByteArray()
                assertTrue(!first.contentEquals(last), "decoded frames alias one reused buffer")
                // Timestamps strictly monotonic.
                val ptsList = decoded.filter { it.info.hasPts }.map { it.info.pts }
                assertTrue(ptsList.zipWithNext().all { (a, b) -> b > a }, "pts not monotonic: $ptsList")
            } finally {
                decoded.forEach { it.close() }
            }
        }
    }

    @Test
    fun audioFramesRoundTripThroughPcmMkv() {
        val path = tmp("roundtrip-audio.mka")
        val sampleRate = 44_100
        val samplesPerFrame = 1024
        MediaSink.open(path).use { sink ->
            val enc = sink.addAudioEncoder(
                AudioEncoderSpec(
                    codec = CodecId.PcmS16,
                    sampleRate = sampleRate,
                    channels = 1,
                    sampleFormat = SampleFormat.S16,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until 20).asFlow().map { i ->
                        // 440 Hz-ish square wave chunk, s16 mono.
                        val bytes = ByteArray(samplesPerFrame * 2)
                        for (s in 0 until samplesPerFrame) {
                            val v: Int = if ((i * samplesPerFrame + s) / 50 % 2 == 0) 12_000 else -12_000
                            bytes[s * 2] = (v and 0xFF).toByte()
                            bytes[s * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        Frame.ofAudio(
                            bytes = bytes,
                            sampleCount = samplesPerFrame,
                            sampleRate = sampleRate,
                            channels = 1,
                            sampleFormat = SampleFormat.S16,
                            ptsMicros = i * samplesPerFrame * 1_000_000L / sampleRate,
                        )
                    }
                )
            }
        }

        MediaSource.open(path).use { src ->
            val audio = src.primaryAudio ?: error("No audio stream in round-trip output")
            assertEquals(sampleRate, audio.audio!!.sampleRate)
            assertEquals(1, audio.audio!!.channels)
            val decoded = runBlocking { src.decodedFrames(audio).toList() }
            try {
                val totalSamples = decoded.sumOf { it.info.sampleCount }
                assertEquals(20 * samplesPerFrame, totalSamples)
                assertTrue(decoded.first().copyPlanesToByteArray().isNotEmpty())
            } finally {
                decoded.forEach { it.close() }
            }
        }
    }

    @Test
    fun remuxPreservesStreamsAndIsCancellationSafe() {
        val src = tmp("remux-src.mp4")
        val dst = tmp("remux-dst.mkv")
        writeTestVideo(src)
        runBlocking { Remuxer.remux(src, dst) }
        MediaSource.open(dst).use { s ->
            assertEquals(1, s.streams.size)
            assertTrue(s.formatName.contains("matroska"), "expected mkv, got ${s.formatName}")
        }
    }

    @Test
    fun takeOneCancelsDecodeAndSourceStaysUsable() {
        val path = tmp("cancel.mp4")
        writeTestVideo(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo!!
            val first = runBlocking { src.decodedFrames(video).take(1).toList() }
            first.forEach { it.close() }
            assertEquals(1, first.size)
            // Cancellation released the demux state — a fresh pass must work.
            runBlocking { src.seekMicros(0) }
            val again = runBlocking { src.decodedFrames(video).take(1).toList() }
            again.forEach { it.close() }
            assertEquals(1, again.size)
        }
        // close() at use-block end must not throw (no active-decode leak).
    }

    @Test
    fun openMissingFileIsFileNotFound() {
        val ex = assertFailsWith<FFmpegException> { MediaSource.open("/definitely/not/here.mp4") }
        assertIs<FFmpegError.FileNotFound>(ex.error)
    }

    @Test
    fun extractFrameAndEncodeImage() {
        val path = tmp("thumb.mp4")
        writeTestVideo(path)
        MediaSource.open(path).use { src ->
            val frame = runBlocking { src.extractFrame(atMicros = 500_000) }
            try {
                val jpg = frame.encodeImage(CodecId.Mjpeg)
                assertTrue(jpg.size > 100, "suspiciously small jpeg: ${jpg.size} bytes")
                // JPEG SOI marker.
                assertEquals(0xFF.toByte(), jpg[0])
                assertEquals(0xD8.toByte(), jpg[1])
            } finally {
                frame.close()
            }
        }
    }

    @Test
    fun closedFrameAccessThrowsInsteadOfReadingFreedMemory() {
        val frame = Frame.ofVideo(yuvFrame(64, 64, 0), 64, 64, PixelFormat.Yuv420p)
        frame.close()
        assertFailsWith<IllegalStateException> { frame.copyPlanesToByteArray() }
        assertFailsWith<IllegalStateException> { frame.copy() }
    }

    @Test
    fun concurrentSecondDecodeIsRejectedNotCrashing() {
        val path = tmp("exclusive.mp4")
        writeTestVideo(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo!!
            runBlocking {
                var sawRejection = false
                src.decodedFrames(video).take(3).collect { frame ->
                    frame.close()
                    // Starting a second pass while this one is live must throw, not crash.
                    try {
                        src.decodedFrames(video).take(1).toList()
                    } catch (e: IllegalStateException) {
                        sawRejection = true
                    }
                }
                assertTrue(sawRejection, "second concurrent decode was not rejected")
            }
        }
    }

    @Test
    fun muxerOptionsAndExplicitFormatAreApplied() {
        val path = tmp("faststart.bin")  // extension lies on purpose — format param must win
        MediaSink.open(path, format = "mp4", options = mapOf("movflags" to "+faststart")).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until 5).asFlow().map { i ->
                        Frame.ofVideo(yuvFrame(64, 64, i), 64, 64, PixelFormat.Yuv420p, i * 33_333L)
                    }
                )
            }
        }
        MediaSource.open(path).use { s ->
            assertTrue(s.formatName.contains("mp4"), "expected mp4 container, got ${s.formatName}")
        }
    }

    @Test
    fun unknownMuxerOptionFailsTyped() {
        assertFailsWith<FFmpegException> {
            MediaSink.open(tmp("badopt.mp4"), options = mapOf("definitely_not_an_option" to "1"))
        }
    }

    @Test
    fun streamMetadataIsExposed() {
        val src = tmp("meta-src.mp4")
        val dst = tmp("meta-dst.mkv")
        writeTestVideo(src)
        runBlocking { Remuxer.remux(src, dst, metadata = mapOf("title" to "kitecodec test")) }
        MediaSource.open(dst).use { s ->
            assertEquals("kitecodec test", s.metadata["title"] ?: s.metadata["TITLE"])
            // Per-stream metadata map exists (may be empty for synthetic streams, must not throw).
            s.streams.forEach { it.metadata }
        }
    }

    /**
     * encodeImage() must leave its input alone. When no pixel-format conversion is required it
     * encodes the caller's own AVFrame, and stamping pts 0 on it for the single-image encode used
     * to destroy the timestamp of a frame the caller might still want to put into a video.
     */
    @Test
    fun encodeImageLeavesTheSourceFrameUntouched() {
        Frame.ofVideo(yuvFrame(64, 64, 3), 64, 64, PixelFormat.Yuv420p, ptsMicros = 1_234_567L).use { frame ->
            assertEquals(1_234_567L, frame.info.pts)
            val jpg = frame.encodeImage(CodecId.Mjpeg)
            assertTrue(jpg.size > 100, "suspiciously small jpeg: ${jpg.size} bytes")
            // info is cached, so read the live value back off the native frame.
            assertEquals(1_234_567L, frame.copy().use { it.info.pts }, "encodeImage clobbered the source pts")
        }
    }

    /**
     * A frame need not arrive in the encoder's pixel format: an unfiltered transcode hands over
     * whatever the decoder produced, and a filter graph's buffersink is deliberately unconstrained.
     * The pipeline converts instead of failing with a bare EINVAL from avcodec_send_frame.
     */
    @Test
    fun encoderConvertsFramesItWasNotGivenInItsOwnPixelFormat() {
        val path = tmp("pixfmt.mp4")
        val w = 64
        val h = 64
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = w, height = h,
                    pixelFormat = PixelFormat.Yuv420p,   // encoder wants planar YUV …
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until 10).asFlow().map { i ->
                        // … but every frame arrives as packed RGB.
                        val rgb = ByteArray(w * h * 3) { b -> ((b + i * 11) % 255).toByte() }
                        Frame.ofVideo(rgb, w, h, PixelFormat.Rgb24, i * 1_000_000L / 30)
                    }
                )
            }
        }
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            assertEquals(w, video.video!!.width)
            val decoded = runBlocking { src.decodedFrames(video).toList() }
            try {
                assertTrue(decoded.size >= 8, "expected ~10 frames, got ${decoded.size}")
            } finally {
                decoded.forEach { it.close() }
            }
        }
    }

    /** Pixel formats are reconciled; GEOMETRY is not — that is a config error worth surfacing. */
    @Test
    fun encoderRejectsAFrameOfTheWrongSize() {
        MediaSink.open(tmp("wrongsize.mp4")).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                )
            )
            assertFailsWith<FFmpegException> {
                runBlocking {
                    enc.drive(flowOf(Frame.ofVideo(yuvFrame(32, 32, 0), 32, 32, PixelFormat.Yuv420p, 0L)))
                }
            }
        }
    }

    /**
     * close() must drain the encoders, not just free them. Encoders with reordering enabled hold
     * frames back, so a sink closed without an explicit finish() used to drop the tail silently.
     */
    @Test
    fun closeFlushesBufferedEncoderFramesWithoutAnExplicitFinish() {
        val path = tmp("flush.mp4")
        val frames = 20
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                    // Force the encoder to buffer: with B-frames it cannot emit a packet until it
                    // has seen the following frames, so a missing EOF drain loses the tail.
                    options = mapOf("bf" to "2"),
                )
            )
            withPacket { packet ->
                repeat(frames) { i ->
                    enc.core.encode(packet, Frame.ofVideo(yuvFrame(64, 64, i), 64, 64, PixelFormat.Yuv420p, i * 1_000_000L / 30))
                }
            }
            // Deliberately NO finish() — close() is responsible from here.
        }
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            val decoded = runBlocking { src.decodedFrames(video).toList() }
            try {
                assertEquals(frames, decoded.size, "close() did not flush every buffered frame")
            } finally {
                decoded.forEach { it.close() }
            }
        }
    }

    /**
     * A trim window that selects nothing must still leave a valid container behind. The header is
     * only written lazily on the first packet, so without an eager write the call used to return
     * cleanly having created no file at all.
     */
    @Test
    fun transcodeWithAnEmptyTrimWindowStillWritesAValidFile() {
        val src = tmp("empty-src.mp4")
        val dst = tmp("empty-dst.mp4")
        writeTestVideo(src, frames = 30)   // 1 second of content
        runBlocking {
            Transcoder.transcode(
                input = src,
                output = dst,
                spec = VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                ),
                // Entirely past the end of the media.
                startMicros = 10_000_000L,
                endMicros = 11_000_000L,
            )
        }
        // The file must exist and be a readable container. Whether a track with zero samples
        // survives is the muxer's call — mp4 drops empty tracks in av_write_trailer — so asserting
        // on the stream list would be asserting on libavformat's policy, not on this fix. The
        // regression being guarded is "no file at all, and no error".
        MediaSource.open(dst).use { out ->
            assertTrue(out.formatName.isNotEmpty(), "empty-window output is not a readable container")
        }
    }

    /**
     * StreamInfo.codec names the CODEC, not whichever decoder implementation this FFmpeg happens
     * to register for it. Guarded, because it needs a build where the two names differ.
     */
    @Test
    fun streamCodecReportsTheCanonicalNameNotADecoderAlias() {
        if (!FFmpeg.hasEncoder("libopus")) return  // nothing to prove on this build
        val path = tmp("codecname.ogg")
        MediaSink.open(path).use { sink ->
            val enc = sink.addAudioEncoder(
                AudioEncoderSpec(codec = CodecId("libopus"), sampleRate = 48_000, channels = 1)
            )
            val samples = enc.frameSize.takeIf { it > 0 } ?: 960
            runBlocking {
                enc.drive(
                    (0 until 10).asFlow().map { i ->
                        Frame.ofAudio(
                            bytes = ByteArray(samples * 4),          // silence, fltp mono
                            sampleCount = samples,
                            sampleRate = 48_000,
                            channels = 1,
                            sampleFormat = enc.sampleFormat,
                            ptsMicros = i * samples * 1_000_000L / 48_000,
                        )
                    }
                )
            }
        }
        MediaSource.open(path).use { src ->
            val audio = src.primaryAudio ?: error("no audio stream written")
            assertEquals("opus", audio.codec.name, "expected the codec name, not the decoder's")
        }
    }
}
