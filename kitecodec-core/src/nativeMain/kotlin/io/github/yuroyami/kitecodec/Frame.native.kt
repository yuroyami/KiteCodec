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
import ffmpeg.ffkmp_frame_format
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_height
import ffmpeg.ffkmp_frame_width
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_pts
import ffmpeg.ffkmp_frame_sample_rate
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_frame_unref
import ffmpeg.ffkmp_image_get_buffer_size
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_data
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_size
import ffmpeg.ffkmp_samples_copy_to_buffer
import ffmpeg.ffkmp_samples_get_buffer_size
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret

/**
 * AVFrame-backed [Frame] implementation. The native pointer ([nativeFrame]) is `internal` —
 * users go through [info] / [copyPlanesToByteArray]; the filter graph & encoder modules in
 * this package read the pointer directly for zero-copy hand-offs.
 *
 * Construction: see [Frame.acquire] (alloc) and [Frame.wrap] (when an existing AVFrame should
 * be adopted, e.g. from a decoder).
 */
actual class Frame internal constructor(
    internal val nativeFrame: CPointer<AVFrame>,
    private val ownsPointer: Boolean,
    internal val streamIndex: Int,
    internal val streamType: MediaType,
    internal val streamTimeBase: Rational,
) : AutoCloseable {

    private var closed = false

    actual val info: FrameInfo by lazy {
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

    actual fun copyPlanesToByteArray(): ByteArray = when (streamType) {
        MediaType.Video -> copyVideoPlanes()
        MediaType.Audio -> copyAudioSamples()
        else -> ByteArray(0)
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

    actual fun copy(): Frame {
        val cloned = ffkmp_frame_clone(nativeFrame)
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_clone returned NULL"))
        return Frame(cloned, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = streamTimeBase)
    }

    actual fun encodeImage(codec: CodecId): ByteArray {
        check(streamType == MediaType.Video) { "encodeImage works on video frames" }
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
                    check0(avcodec_send_frame(ctx, sendFrame), "avcodec_send_frame (image)")
                    check0(avcodec_send_frame(ctx, null), "avcodec_send_frame (image flush)")
                    check0(avcodec_receive_packet(ctx, packet), "avcodec_receive_packet (image)")
                    val size = ffkmp_packet_size(packet)
                    val data = ffkmp_packet_data(packet)
                        ?: throw FFmpegException(FFmpegError.Internal("Image packet has no data"))
                    return data.readBytes(size)
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
