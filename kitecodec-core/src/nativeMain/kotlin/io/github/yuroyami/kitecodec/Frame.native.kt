package io.github.yuroyami.kitecodec

import ffmpeg.AVFrame
import ffmpeg.avcodec_find_encoder_by_name
import ffmpeg.avcodec_receive_packet
import ffmpeg.avcodec_send_frame
import ffmpeg.ffkmp_codec_first_pix_fmt
import ffmpeg.ffkmp_codec_supports_pix_fmt
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_set_full_range
import ffmpeg.ffkmp_codecctx_set_video
import ffmpeg.ffkmp_frame_alloc
import ffmpeg.ffkmp_frame_channels
import ffmpeg.ffkmp_frame_clone
import ffmpeg.ffkmp_frame_convert_pixfmt
import ffmpeg.ffkmp_frame_copy_to_buffer
import ffmpeg.ffkmp_frame_fill_audio
import ffmpeg.ffkmp_frame_fill_video
import ffmpeg.ffkmp_frame_format
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_get_buffer
import ffmpeg.ffkmp_frame_height
import ffmpeg.ffkmp_frame_width
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_pts
import ffmpeg.ffkmp_frame_sample_rate
import ffmpeg.ffkmp_frame_set_ch_layout_default
import ffmpeg.ffkmp_frame_set_format
import ffmpeg.ffkmp_frame_set_height
import ffmpeg.ffkmp_frame_set_nb_samples
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_frame_set_sample_rate
import ffmpeg.ffkmp_frame_set_width
import ffmpeg.ffkmp_frame_unref
import ffmpeg.ffkmp_image_get_buffer_size
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_data
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_size
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_samples_copy_to_buffer
import ffmpeg.ffkmp_samples_get_buffer_size
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * AVFrame-backed [Frame] implementation. The native pointer ([nativeFrame]) is `internal` —
 * users go through [info] / [copyPlanesToByteArray]; the filter graph & encoder modules in
 * this package read the pointer directly for zero-copy hand-offs.
 *
 * Construction: see [Frame.acquire] (alloc) and [Frame.wrap] (when an existing AVFrame should
 * be adopted, e.g. from a decoder).
 */
public actual class Frame internal constructor(
    internal val nativeFrame: CPointer<AVFrame>,
    private val ownsPointer: Boolean,
    internal val streamIndex: Int,
    internal val streamType: MediaType,
    internal val streamTimeBase: Rational,
) : AutoCloseable {

    private var closed = false

    private fun checkOpen() = check(!closed) { "Frame is closed — its native buffers are gone" }

    public actual val info: FrameInfo by lazy {
        checkOpen()
        FrameInfo(
            streamIndex   = streamIndex,
            type          = streamType,
            pts           = ffkmp_frame_pts(nativeFrame),
            timeBase      = streamTimeBase,
            width         = ffkmp_frame_width(nativeFrame),
            height        = ffkmp_frame_height(nativeFrame),
            pixelFormat   = if (streamType == MediaType.Video) pixelFormatFromAv(ffkmp_frame_format(nativeFrame)) else PixelFormat.None,
            sampleCount   = ffkmp_frame_nb_samples(nativeFrame),
            sampleRate    = ffkmp_frame_sample_rate(nativeFrame),
            channelCount  = if (streamType == MediaType.Audio) ffkmp_frame_channels(nativeFrame) else 0,
            sampleFormat  = if (streamType == MediaType.Audio) sampleFormatFromAv(ffkmp_frame_format(nativeFrame)) else SampleFormat.None,
        )
    }

    public actual fun copyPlanesToByteArray(): ByteArray {
        checkOpen()
        return when (streamType) {
            MediaType.Video -> copyVideoPlanes()
            MediaType.Audio -> copyAudioSamples()
            else -> ByteArray(0)
        }
    }

    private fun copyVideoPlanes(): ByteArray = memScoped {
        val width = ffkmp_frame_width(nativeFrame)
        val height = ffkmp_frame_height(nativeFrame)
        val format = ffkmp_frame_format(nativeFrame)
        if (width <= 0 || height <= 0 || format < 0) return@memScoped ByteArray(0)

        val needed = ffkmp_image_get_buffer_size(format, width, height, 1)
        check0(needed, "av_image_get_buffer_size")
        val buf = allocArray<ByteVar>(needed)
        val written = ffkmp_frame_copy_to_buffer(nativeFrame, buf.reinterpret(), needed)
        check0(written, "av_image_copy_to_buffer")
        buf.readBytes(written)
    }

    private fun copyAudioSamples(): ByteArray = memScoped {
        if (ffkmp_frame_nb_samples(nativeFrame) <= 0) return@memScoped ByteArray(0)
        val needed = ffkmp_samples_get_buffer_size(nativeFrame)
        check0(needed, "av_samples_get_buffer_size")
        val buf = allocArray<ByteVar>(needed)
        val written = ffkmp_samples_copy_to_buffer(nativeFrame, buf.reinterpret(), needed)
        check0(written, "samples_copy_to_buffer")
        buf.readBytes(written)
    }

    public actual fun copy(): Frame {
        checkOpen()
        val cloned = ffkmp_frame_clone(nativeFrame)
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_clone returned NULL"))
        return Frame(cloned, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = streamTimeBase)
    }

    public actual fun encodeImage(codec: CodecId): ByteArray {
        checkOpen()
        if (streamType != MediaType.Video) {
            throw FFmpegException(FFmpegError.Internal("encodeImage works on video frames, this is a $streamType frame"))
        }
        val width = ffkmp_frame_width(nativeFrame)
        val height = ffkmp_frame_height(nativeFrame)
        val format = ffkmp_frame_format(nativeFrame)
        if (width <= 0 || height <= 0 || format < 0) {
            throw FFmpegException(FFmpegError.Internal("Frame carries no image data"))
        }
        val encoder = avcodec_find_encoder_by_name(codec.name)
            ?: throw FFmpegException(FFmpegError.Internal("No encoder named '${codec.name}'"))

        // Image codecs are picky about input pixel format (png: rgb*, mjpeg: yuvj*) —
        // convert when the frame's own format isn't accepted.
        val needsConvert = ffkmp_codec_supports_pix_fmt(encoder, format) == 0
        val sendFrame: CPointer<AVFrame>
        var converted: CPointer<AVFrame>? = null
        if (needsConvert) {
            val targetFmt = ffkmp_codec_first_pix_fmt(encoder)
            if (targetFmt < 0) throw FFmpegException(FFmpegError.Internal("Encoder '${codec.name}' advertises no pixel formats"))
            converted = ffkmp_frame_convert_pixfmt(nativeFrame, targetFmt)
                ?: throw FFmpegException(FFmpegError.Internal("Pixel format conversion failed"))
            sendFrame = converted
        } else {
            sendFrame = nativeFrame
        }

        try {
            val ctx = ffkmp_codecctx_alloc(encoder)
                ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
            try {
                ffkmp_codecctx_set_video(
                    ctx, width, height,
                    ffkmp_frame_format(sendFrame),
                    25, 1, 1, 25,  // dummy frame rate / time base — single image, irrelevant
                    8_000_000, 1,  // bitrate steers mjpeg quality; png ignores it
                )
                ffkmp_codecctx_set_full_range(ctx)  // mjpeg refuses limited-range yuv since FFmpeg 7
                check0(ffkmp_codecctx_open(ctx, encoder), "avcodec_open2 (image encoder)")
                val packet = ffkmp_packet_alloc()
                    ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
                try {
                    ffkmp_frame_set_pts(sendFrame, 0)
                    val eagain = FFErrors.EAGAIN
                    val eof = FFErrors.EOF
                    var bytes: ByteArray? = null
                    // EAGAIN-correct send/drain, same shape as the codec loops elsewhere —
                    // image codecs emit one packet, but nothing in the API guarantees it.
                    fun drainAll() {
                        while (true) {
                            val rc = avcodec_receive_packet(ctx, packet)
                            if (rc == eagain || rc == eof) return
                            check0(rc, "avcodec_receive_packet (image)")
                            if (bytes == null) {
                                val size = ffkmp_packet_size(packet)
                                bytes = ffkmp_packet_data(packet)?.readBytes(size)
                            }
                            ffkmp_packet_unref(packet)
                        }
                    }
                    while (true) {
                        val rc = avcodec_send_frame(ctx, sendFrame)
                        if (rc == 0) break
                        if (rc == eagain) { drainAll(); continue }
                        check0(rc, "avcodec_send_frame (image)")
                    }
                    while (true) {
                        val rc = avcodec_send_frame(ctx, null)
                        if (rc == 0 || rc == eof) break
                        if (rc == eagain) { drainAll(); continue }
                        check0(rc, "avcodec_send_frame (image flush)")
                    }
                    drainAll()
                    return bytes
                        ?: throw FFmpegException(FFmpegError.Internal("Image encoder produced no packet"))
                } finally {
                    ffkmp_packet_free(packet)
                }
            } finally {
                ffkmp_codecctx_free(ctx)
            }
        } finally {
            converted?.let { ffkmp_frame_free(it) }
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        if (ownsPointer) {
            ffkmp_frame_free(nativeFrame)
        } else {
            ffkmp_frame_unref(nativeFrame)
        }
    }

    public actual companion object {

        public actual fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long,
        ): Frame {
            require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
            val fmt = pixelFormatToAv(pixelFormat)
            if (fmt < 0) throw FFmpegException(FFmpegError.Internal("Unknown pixel format '${pixelFormat.name}'"))
            val raw = ffkmp_frame_alloc()
                ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
            try {
                ffkmp_frame_set_width(raw, width)
                ffkmp_frame_set_height(raw, height)
                ffkmp_frame_set_format(raw, fmt)
                check0(ffkmp_frame_get_buffer(raw, 0), "av_frame_get_buffer (video)")
                bytes.usePinned { pinned ->
                    check0(
                        ffkmp_frame_fill_video(raw, pinned.addressOf(0).reinterpret(), bytes.size),
                        "frame_fill_video (need packed ${pixelFormat.name} planes for ${width}x$height)",
                    )
                }
                ffkmp_frame_set_pts(raw, ptsMicros)
            } catch (t: Throwable) {
                ffkmp_frame_free(raw)
                throw t
            }
            return Frame(raw, ownsPointer = true, streamIndex = -1, streamType = MediaType.Video, streamTimeBase = Rational.Tb_us)
        }

        public actual fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long,
        ): Frame {
            require(sampleCount > 0) { "sampleCount must be positive" }
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(channels in 1..8) { "channels must be 1..8 (got $channels)" }
            val fmt = sampleFormatToAv(sampleFormat)
            if (fmt < 0) throw FFmpegException(FFmpegError.Internal("Unknown sample format '${sampleFormat.name}'"))
            val raw = ffkmp_frame_alloc()
                ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
            try {
                ffkmp_frame_set_nb_samples(raw, sampleCount)
                ffkmp_frame_set_sample_rate(raw, sampleRate)
                ffkmp_frame_set_format(raw, fmt)
                ffkmp_frame_set_ch_layout_default(raw, channels)
                check0(ffkmp_frame_get_buffer(raw, 0), "av_frame_get_buffer (audio)")
                bytes.usePinned { pinned ->
                    check0(
                        ffkmp_frame_fill_audio(raw, pinned.addressOf(0).reinterpret(), bytes.size),
                        "frame_fill_audio (need $sampleCount ${sampleFormat.name} samples x $channels ch)",
                    )
                }
                ffkmp_frame_set_pts(raw, ptsMicros)
            } catch (t: Throwable) {
                ffkmp_frame_free(raw)
                throw t
            }
            return Frame(raw, ownsPointer = true, streamIndex = -1, streamType = MediaType.Audio, streamTimeBase = Rational.Tb_us)
        }
    }
}

internal object FrameOps {
    /** Allocate a fresh AVFrame wrapper that owns its pointer. */
    fun acquire(
        streamIndex: Int = -1,
        streamType: MediaType = MediaType.Video,
        timeBase: Rational = Rational(1, 1_000_000),
    ): Frame {
        val raw = ffkmp_frame_alloc()
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
        return Frame(raw, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = timeBase)
    }

    /** Wrap an externally-owned AVFrame pointer (caller frees). */
    fun wrap(
        raw: CPointer<AVFrame>,
        streamIndex: Int,
        streamType: MediaType,
        timeBase: Rational,
    ): Frame = Frame(raw, ownsPointer = false, streamIndex = streamIndex, streamType = streamType, streamTimeBase = timeBase)
}
