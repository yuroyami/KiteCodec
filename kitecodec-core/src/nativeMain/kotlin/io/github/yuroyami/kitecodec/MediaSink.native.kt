package io.github.yuroyami.kitecodec

import ffmpeg.AVCodecContext
import ffmpeg.AVFrame
import ffmpeg.AVPacket
import ffmpeg.avcodec_find_encoder_by_name
import ffmpeg.avcodec_receive_packet
import ffmpeg.avcodec_send_frame
import ffmpeg.ffkmp_codec_first_sample_fmt
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_frame_size
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_set_audio
import ffmpeg.ffkmp_codecctx_set_global_header
import ffmpeg.ffkmp_codecctx_set_opt
import ffmpeg.ffkmp_codecctx_set_video
import ffmpeg.ffkmp_codecpar_copy_for_mux
import ffmpeg.ffkmp_codecpar_from_context
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_pts
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_pts
import ffmpeg.ffkmp_packet_rescale_ts
import ffmpeg.ffkmp_packet_set_dts
import ffmpeg.ffkmp_packet_set_pts
import ffmpeg.ffkmp_packet_set_stream_index
import ffmpeg.ffkmp_packet_unref
import ffmpeg.AVFormatContext
import ffmpeg.AVStream
import ffmpeg.ffkmp_fmt_alloc_output
import ffmpeg.ffkmp_fmt_free_output
import ffmpeg.ffkmp_fmt_io_open
import ffmpeg.ffkmp_fmt_new_stream
import ffmpeg.ffkmp_fmt_set_metadata
import ffmpeg.ffkmp_fmt_write_frame
import ffmpeg.ffkmp_fmt_write_header
import ffmpeg.ffkmp_fmt_write_trailer
import ffmpeg.ffkmp_oformat_global_header
import ffmpeg.ffkmp_rescale_q
import ffmpeg.ffkmp_stream_codecpar
import ffmpeg.ffkmp_stream_index
import ffmpeg.ffkmp_stream_set_time_base
import ffmpeg.ffkmp_stream_time_base
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow

actual class MediaSink internal constructor(
    private val ctx: CPointer<AVFormatContext>,
    private val outputPath: String,
) : AutoCloseable {

    private val encoderCores = mutableListOf<EncoderCore>()
    private var headerWritten = false
    private var closed = false

    actual fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder {
        val codecCtx = newEncoderContext(spec.codec.name) { codec, cc ->
            ffkmp_codecctx_set_video(
                cc,
                spec.width, spec.height,
                pixelFormatToAv(spec.pixelFormat),
                spec.frameRate.num, spec.frameRate.den,
                spec.frameRate.den, spec.frameRate.num,  // time_base = inverse of frame rate
                spec.bitrateBps,
                spec.keyframeIntervalFrames,
            )
            spec.options.forEach { (k, v) -> check0(ffkmp_codecctx_set_opt(cc, k, v), "av_opt_set ('$k')") }
        }
        val stream = newStreamFor(codecCtx)
        val core = EncoderCore(
            sink = this,
            codecCtx = codecCtx,
            stream = stream,
            codecTimeBase = Rational(spec.frameRate.den, spec.frameRate.num),
            isAudio = false,
        )
        encoderCores += core
        return VideoEncoder(core)
    }

    actual fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream {
        check(!headerWritten) { "Cannot add streams after the muxer has started writing." }
        check(!closed) { "MediaSink is closed" }

        val sourcePar = source.codecparOf(stream)
            ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} has no codec parameters"))
        val outStream = ffkmp_fmt_new_stream(ctx, null)
            ?: throw FFmpegException(FFmpegError.Internal("avformat_new_stream returned NULL"))
        val outPar = ffkmp_stream_codecpar(outStream)
            ?: throw FFmpegException(FFmpegError.Internal("New stream missing codecpar"))
        check0(ffkmp_codecpar_copy_for_mux(outPar, sourcePar), "avcodec_parameters_copy")
        // Seed the output time-base with the input's; the muxer may still rewrite it in
        // avformat_write_header, which is why writeCopyPacket re-reads it per packet.
        ffkmp_stream_set_time_base(outStream, stream.timeBase.num, stream.timeBase.den)

        return CopyStream(sink = this, stream = outStream, sourceTimeBase = stream.timeBase, sourceIndex = stream.index)
    }

    actual fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder {
        var negotiatedFormat = spec.sampleFormat
        val codecCtx = newEncoderContext(spec.codec.name) { codec, cc ->
            if (negotiatedFormat == SampleFormat.None) {
                val first = ffkmp_codec_first_sample_fmt(codec)
                if (first < 0) throw FFmpegException(
                    FFmpegError.Internal("Encoder '${spec.codec.name}' does not advertise sample formats — set AudioEncoderSpec.sampleFormat explicitly")
                )
                negotiatedFormat = sampleFormatFromAv(first)
            }
            ffkmp_codecctx_set_audio(
                cc,
                spec.sampleRate,
                sampleFormatToAv(negotiatedFormat),
                spec.channels,
                spec.bitrateBps,
            )
            spec.options.forEach { (k, v) -> check0(ffkmp_codecctx_set_opt(cc, k, v), "av_opt_set ('$k')") }
        }
        val stream = newStreamFor(codecCtx)
        val core = EncoderCore(
            sink = this,
            codecCtx = codecCtx,
            stream = stream,
            codecTimeBase = Rational(1, spec.sampleRate),
            isAudio = true,
        )
        encoderCores += core
        return AudioEncoder(
            core = core,
            frameSize = ffkmp_codecctx_frame_size(codecCtx),
            sampleFormat = negotiatedFormat,
            sampleRate = spec.sampleRate,
            channels = spec.channels,
        )
    }

    /** Find + configure + open one encoder context; [configure] sets type-specific fields. */
    private inline fun newEncoderContext(
        codecName: String,
        configure: (codec: CPointer<ffmpeg.AVCodec>, cc: CPointer<AVCodecContext>) -> Unit,
    ): CPointer<AVCodecContext> {
        check(!headerWritten) { "Cannot add encoders after the muxer has started writing." }
        check(!closed) { "MediaSink is closed" }

        val codec = avcodec_find_encoder_by_name(codecName)
            ?: throw FFmpegException(FFmpegError.Internal("No encoder named '$codecName'"))
        val codecCtx = ffkmp_codecctx_alloc(codec)
            ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
        try {
            configure(codec, codecCtx)
            if (ffkmp_oformat_global_header(ctx) == 1) {
                ffkmp_codecctx_set_global_header(codecCtx)
            }
            check0(ffkmp_codecctx_open(codecCtx, codec), "avcodec_open2 (encoder)")
            return codecCtx
        } catch (t: Throwable) {
            ffkmp_codecctx_free(codecCtx)
            throw t
        }
    }

    /** Create the muxer stream for an opened encoder context and copy its parameters over. */
    private fun newStreamFor(codecCtx: CPointer<AVCodecContext>): CPointer<AVStream> {
        try {
            val stream = ffkmp_fmt_new_stream(ctx, null)
                ?: throw FFmpegException(FFmpegError.Internal("avformat_new_stream returned NULL"))
            val par = ffkmp_stream_codecpar(stream)
                ?: throw FFmpegException(FFmpegError.Internal("New stream missing codecpar"))
            check0(ffkmp_codecpar_from_context(par, codecCtx), "avcodec_parameters_from_context")
            memScoped {
                val n = alloc<IntVar>(); val d = alloc<IntVar>()
                ffmpeg.ffkmp_codecctx_time_base(codecCtx, n.ptr, d.ptr)
                ffkmp_stream_set_time_base(stream, n.value, d.value)
            }
            return stream
        } catch (t: Throwable) {
            ffkmp_codecctx_free(codecCtx)
            throw t
        }
    }

    actual fun setMetadata(metadata: Map<String, String>) {
        check(!headerWritten) { "Metadata must be set before the muxer writes its header." }
        check(!closed) { "MediaSink is closed" }
        metadata.forEach { (k, v) ->
            check0(ffkmp_fmt_set_metadata(ctx, k, v), "av_dict_set (metadata '$k')")
        }
    }

    internal fun ensureHeaderWritten() {
        if (headerWritten) return
        headerWritten = true
        check0(ffkmp_fmt_io_open(ctx, outputPath), "avio_open")
        check0(ffkmp_fmt_write_header(ctx), "avformat_write_header")
    }

    internal fun writePacket(packet: CPointer<AVPacket>) {
        ensureHeaderWritten()
        val rc = ffkmp_fmt_write_frame(ctx, packet)
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    actual override fun close() {
        if (closed) return
        closed = true
        var trailerRc = 0
        try {
            encoderCores.forEach { runCatching { it.close() } }
            encoderCores.clear()
            if (headerWritten) {
                trailerRc = ffkmp_fmt_write_trailer(ctx)
            }
        } finally {
            memScoped {
                val pp = alloc<CPointerVar<AVFormatContext>>().also { it.value = ctx }
                ffkmp_fmt_free_output(pp.ptr)
            }
        }
        // A failed trailer means the file on disk is broken (e.g. mp4 moov never written) —
        // surfacing that beats handing the caller a corrupt output with a green checkmark.
        if (trailerRc < 0) throw FFmpegException(avError(trailerRc))
    }

    actual companion object {
        actual fun open(path: String): MediaSink {
            val arena = kotlinx.cinterop.Arena()
            val ctxVar = arena.allocPointerTo<AVFormatContext>()
            val rc = ffkmp_fmt_alloc_output(ctxVar.ptr, path)
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }
            val ctx = ctxVar.value!!
            arena.clear()
            return MediaSink(ctx, path)
        }
    }
}

/**
 * Shared encode loop for video + audio: pts normalization, EAGAIN-correct send/receive
 * interleaving, packet rescale to the muxer's stream time-base, flush.
 */
internal class EncoderCore(
    private val sink: MediaSink,
    private val codecCtx: CPointer<AVCodecContext>,
    private val stream: CPointer<AVStream>,
    private val codecTimeBase: Rational,
    private val isAudio: Boolean,
) {
    private var closed = false
    private val streamIndex = ffkmp_stream_index(stream)
    private var lastPts = Long.MIN_VALUE
    private var basePts = Long.MIN_VALUE
    var framesEncoded: Long = 0; private set

    /** Where the output timeline currently ends, in microseconds. 0 until the first frame. */
    val outputMicros: Long
        get() = if (lastPts == Long.MIN_VALUE) 0
        else ffkmp_rescale_q(lastPts, codecTimeBase.num, codecTimeBase.den, 1, 1_000_000)

    /** Encode one frame (already in the encoder's expected format). Closes [frame]. */
    fun encode(packet: CPointer<AVPacket>, frame: Frame) {
        check(!closed) { "Encoder is closed" }
        restampPts(frame)
        try {
            sendAndDrain(packet, frame.nativeFrame)
        } finally {
            frame.close()
        }
        framesEncoded += 1
    }

    /** Signal EOF to the encoder and write out everything it still buffers. */
    fun finish(packet: CPointer<AVPacket>) {
        check(!closed) { "Encoder is closed" }
        sendAndDrain(packet, null)
    }

    /**
     * Rescale the frame's pts from its own time-base onto the encoder's and rebase the
     * timeline to start at zero (trimmed inputs would otherwise produce a file whose first
     * frame sits at the trim offset). Frames with no pts fall back to a synthetic timeline
     * (frame count for video, accumulated samples for audio). Output is forced strictly
     * monotonic — both libx264 and muxers reject non-increasing pts.
     */
    private fun restampPts(frame: Frame) {
        val raw = ffkmp_frame_pts(frame.nativeFrame)
        val sourceTb = frame.streamTimeBase
        var pts = if (raw == FrameInfo.NOPTS) {
            if (lastPts == Long.MIN_VALUE) 0
            else lastPts + if (isAudio) ffkmp_frame_nb_samples(frame.nativeFrame).toLong().coerceAtLeast(1) else 1
        } else {
            val rescaled = ffkmp_rescale_q(raw, sourceTb.num, sourceTb.den, codecTimeBase.num, codecTimeBase.den)
            if (basePts == Long.MIN_VALUE) basePts = rescaled
            rescaled - basePts
        }
        if (lastPts != Long.MIN_VALUE && pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        ffkmp_frame_set_pts(frame.nativeFrame, pts)
    }

    /**
     * `avcodec_send_frame` returning EAGAIN means the output queue is full — drain packets and
     * retry the SAME frame. The previous implementation dropped the frame on EAGAIN.
     */
    private fun sendAndDrain(packet: CPointer<AVPacket>, frame: CPointer<AVFrame>?) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val sendRc = avcodec_send_frame(codecCtx, frame)
            when {
                sendRc == 0 || sendRc == eof -> { drain(packet); return }
                sendRc == eagain -> drain(packet)  // output full → drain, then resend
                else -> throw FFmpegException(avError(sendRc))
            }
        }
    }

    private fun drain(packet: CPointer<AVPacket>) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val recRc = avcodec_receive_packet(codecCtx, packet)
            if (recRc == eagain || recRc == eof) return
            if (recRc < 0) throw FFmpegException(avError(recRc))
            // The muxer may rewrite stream time-bases inside avformat_write_header (mp4 turns
            // 1/30 into 1/15360). The header MUST be on disk before we read the stream tb below,
            // or the first packet gets rescaled against the stale pre-header value.
            sink.ensureHeaderWritten()
            ffkmp_packet_set_stream_index(packet, streamIndex)
            // Packet pts is in the codec time-base. Convert into the muxer's stream time-base,
            // which avformat_write_header may have rewritten — read it fresh each pass.
            memScoped {
                val n = alloc<IntVar>(); val d = alloc<IntVar>()
                ffkmp_stream_time_base(stream, n.ptr, d.ptr)
                ffkmp_packet_rescale_ts(
                    packet,
                    codecTimeBase.num, codecTimeBase.den,
                    n.value, d.value,
                )
            }
            try {
                sink.writePacket(packet)
            } finally {
                ffkmp_packet_unref(packet)
            }
        }
    }

    fun ensureHeaderWritten() = sink.ensureHeaderWritten()

    fun close() {
        if (closed) return
        closed = true
        ffkmp_codecctx_free(codecCtx)
    }
}

/** Allocate a packet, run [block] with it, free it after. */
internal inline fun <T> withPacket(block: (CPointer<AVPacket>) -> T): T {
    val packet = ffkmp_packet_alloc()
        ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
    try {
        return block(packet)
    } finally {
        ffkmp_packet_free(packet)
    }
}

actual class CopyStream internal constructor(
    private val sink: MediaSink,
    private val stream: CPointer<AVStream>,
    private val sourceTimeBase: Rational,
    internal val sourceIndex: Int,
) {
    private val streamIndex = ffkmp_stream_index(stream)
    private var baseTs = FrameInfo.NOPTS

    /**
     * Write one demuxed packet through to the muxer: rebase timestamps so the output starts
     * at ~0 (matters for trimmed copies — players choke on a stream starting at 95s), then
     * rescale from the source stream's time-base onto whatever the output muxer chose.
     * `av_interleaved_write_frame` takes ownership of the payload; the packet comes back blank.
     */
    internal fun writeCopyPacket(packet: CPointer<AVPacket>) {
        // Header first — avformat_write_header may rewrite the stream time-base we read below.
        sink.ensureHeaderWritten()
        ffkmp_packet_set_stream_index(packet, streamIndex)

        // Rebase on the first packet's dts (it's ≤ pts, keeping both non-negative after shift).
        val pts = ffkmp_packet_pts(packet)
        val dts = ffkmp_packet_dts(packet)
        if (baseTs == FrameInfo.NOPTS) {
            baseTs = when {
                dts != FrameInfo.NOPTS -> dts
                pts != FrameInfo.NOPTS -> pts
                else -> 0
            }
        }
        if (pts != FrameInfo.NOPTS) ffkmp_packet_set_pts(packet, pts - baseTs)
        if (dts != FrameInfo.NOPTS) ffkmp_packet_set_dts(packet, dts - baseTs)

        memScoped {
            val n = alloc<IntVar>(); val d = alloc<IntVar>()
            ffkmp_stream_time_base(stream, n.ptr, d.ptr)
            ffkmp_packet_rescale_ts(
                packet,
                sourceTimeBase.num, sourceTimeBase.den,
                n.value, d.value,
            )
        }
        sink.writePacket(packet)
    }
}

actual class VideoEncoder internal constructor(
    internal val core: EncoderCore,
) : AutoCloseable {

    actual suspend fun drive(input: Flow<Frame>, onProgress: ((framesEncoded: Long) -> Unit)?, progressEveryNFrames: Int) {
        core.ensureHeaderWritten()
        withPacket { packet ->
            input.collect { frame ->
                core.encode(packet, frame)
                if (onProgress != null && core.framesEncoded % progressEveryNFrames == 0L) {
                    onProgress(core.framesEncoded)
                }
            }
            core.finish(packet)
            onProgress?.invoke(core.framesEncoded)
        }
    }

    actual override fun close() = core.close()
}

actual class AudioEncoder internal constructor(
    internal val core: EncoderCore,
    actual val frameSize: Int,
    actual val sampleFormat: SampleFormat,
    actual val sampleRate: Int,
    actual val channels: Int,
) : AutoCloseable {

    actual suspend fun drive(input: Flow<Frame>) {
        core.ensureHeaderWritten()
        withPacket { packet ->
            input.collect { frame -> core.encode(packet, frame) }
            core.finish(packet)
        }
    }

    actual override fun close() = core.close()
}
