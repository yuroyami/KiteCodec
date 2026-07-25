package io.github.yuroyami.kitecodec

import ffmpeg.AVCodecContext
import ffmpeg.AVMEDIA_TYPE_ATTACHMENT
import ffmpeg.AVMEDIA_TYPE_AUDIO
import ffmpeg.AVMEDIA_TYPE_DATA
import ffmpeg.AVMEDIA_TYPE_SUBTITLE
import ffmpeg.AVMEDIA_TYPE_VIDEO
import ffmpeg.AVPacket
import ffmpeg.avcodec_receive_frame
import ffmpeg.avcodec_send_packet
import ffmpeg.ffkmp_codec_id_name
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_from_par
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecpar_bit_rate
import ffmpeg.ffkmp_codecpar_channels
import ffmpeg.ffkmp_codecpar_codec_id
import ffmpeg.ffkmp_codecpar_codec_type
import ffmpeg.ffkmp_codecpar_format
import ffmpeg.ffkmp_codecpar_height
import ffmpeg.ffkmp_codecpar_sample_aspect_ratio
import ffmpeg.ffkmp_codecpar_sample_rate
import ffmpeg.ffkmp_codecpar_width
import ffmpeg.ffkmp_find_decoder_by_id
import ffmpeg.ffkmp_frame_use_best_effort_ts
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_stream_index
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_rescale_q
import ffmpeg.AVFormatContext
import ffmpeg.ffkmp_fmt_close_input
import ffmpeg.ffkmp_fmt_duration
import ffmpeg.ffkmp_fmt_find_stream_info
import ffmpeg.ffkmp_fmt_iformat_name
import ffmpeg.ffkmp_fmt_metadata
import ffmpeg.ffkmp_fmt_nb_streams
import ffmpeg.ffkmp_fmt_open_input
import ffmpeg.ffkmp_fmt_read_frame
import ffmpeg.ffkmp_fmt_seek_micros
import ffmpeg.ffkmp_fmt_start_time
import ffmpeg.ffkmp_fmt_stream
import ffmpeg.ffkmp_stream_avg_frame_rate
import ffmpeg.ffkmp_stream_codecpar
import ffmpeg.ffkmp_stream_duration_micros
import ffmpeg.ffkmp_stream_index
import ffmpeg.ffkmp_stream_metadata
import ffmpeg.ffkmp_stream_time_base
import ffmpeg.ffkmp_dict_entry_key
import ffmpeg.ffkmp_dict_entry_value
import ffmpeg.ffkmp_dict_get
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public actual class MediaSource internal constructor(
    private val ctx: CPointer<AVFormatContext>,
    public actual val streams: List<StreamInfo>,
    public actual val durationMicros: Long?,
    public actual val formatName: String,
    public actual val metadata: Map<String, String>,
    /**
     * Where the container's timeline begins, in microseconds (0 for most mp4, ~1.4s for MPEG-TS).
     *
     * Everything libavformat hands out — packet dts/pts, decoded frame pts, seek targets with
     * `stream_index = -1` — is ABSOLUTE and includes this offset. Everything KiteCodec's public
     * API takes or reports — [seekMicros], [extractFrame]'s `atMicros`, `Transcoder`/`Remuxer`
     * trim bounds — is media-RELATIVE, i.e. "n microseconds into the content". This is the single
     * conversion between the two; [toRelativeMicros] and [toAbsoluteMicros] apply it.
     */
    public actual val startTimeMicros: Long,
) : AutoCloseable {

    /** Absolute timestamp [ts] (in [timeBase]) → media-relative microseconds. */
    internal fun toRelativeMicros(ts: Long, timeBase: Rational): Long =
        ffkmp_rescale_q(ts, timeBase.num, timeBase.den, 1, 1_000_000) - startTimeMicros

    /** Media-relative microseconds → the absolute microsecond timestamp the demuxer speaks. */
    internal fun toAbsoluteMicros(relativeMicros: Long): Long = relativeMicros + startTimeMicros

    /**
     * Guards the {closed, demuxing} state machine. The demuxer is ONE cursor: exactly one
     * decode pass may run at a time, seek is rejected while a pass runs, and close is
     * rejected until the pass ends — turning would-be native crashes into
     * [IllegalStateException]s.
     */
    private val stateLock = SynchronizedObject()
    private var closed = false
    private var demuxing = false

    private fun beginDemux() = synchronized(stateLock) {
        check(!closed) { "MediaSource is closed" }
        check(!demuxing) {
            "Another decode flow is already collecting on this MediaSource — the demuxer is a " +
                "single cursor. Use decodeStreams(listOf(a, b)) to read several streams in one pass."
        }
        demuxing = true
    }

    private fun endDemux() = synchronized(stateLock) { demuxing = false }

    private val isClosed: Boolean get() = synchronized(stateLock) { closed }

    public actual val primaryVideo: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Video }
    public actual val primaryAudio: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Audio }

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> = decodeStreams(listOf(stream))

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> = flow {
        // Emit OWNED clones (O(1) refcount bumps): collectors may buffer or hold frames and
        // must close each one. The internal reusable landing frame never escapes this call.
        demuxRouted(decode = streams, copy = emptyList(), onFrame = { emit(it.copy()) }, onPacket = { _, _ -> })
    }

    /**
     * The one demuxer pass everything builds on: packets for [decode] streams go through a
     * decoder and surface as [onFrame] callbacks; packets for [copy] streams surface raw via
     * [onPacket] (stream-copy / remux — the packet is valid only during the callback).
     * Decoders are flushed at container EOF.
     */
    internal suspend fun demuxRouted(
        decode: List<StreamInfo>,
        copy: List<StreamInfo>,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (CPointer<AVPacket>, StreamInfo) -> Unit,
    ) {
        val all = decode + copy
        require(all.isNotEmpty()) { "Need at least one stream to demux" }
        require(decode.all { it.type.isAv }) { "Only video/audio streams can be decoded" }
        require(all.distinctBy { it.index }.size == all.size) { "Duplicate stream indices" }

        beginDemux()
        try {
            val copyByIndex = copy.associateBy { it.index }
            val decoders = decode.map { DecoderState.open(ctx, it) }
            try {
                // Frame first, packet second: if the second alloc throws, the first is
                // released by its own factory's failure path plus this try/finally shape.
                val frame = FrameOps.acquire()
                try {
                    withPacket { packet ->
                        try {
                            pump(decoders, copyByIndex, packet, frame, onFrame, onPacket)
                        } catch (_: StopDemux) {
                            // A callback decided it has everything it needs (trim end bound,
                            // frame found). Buffered decoder frames are FUTURE frames —
                            // deliberately not flushed; fall through to normal teardown.
                        }
                    }
                } finally {
                    frame.close()
                }
            } finally {
                decoders.forEach { it.free() }
            }
        } finally {
            endDemux()
        }
    }

    private suspend fun pump(
        decoders: List<DecoderState>,
        copyByIndex: Map<Int, StreamInfo>,
        packet: CPointer<AVPacket>,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (CPointer<AVPacket>, StreamInfo) -> Unit,
    ) {
        val decoderByIndex = decoders.associateBy { it.stream.index }
        val eof = FFErrors.EOF
        while (true) {
            // Honor cancellation between packets: copy-only pipelines (Remuxer) otherwise
            // never hit a suspension point and would run to completion after cancel.
            currentCoroutineContext().ensureActive()
            val readRc = ffkmp_fmt_read_frame(ctx, packet)
            if (readRc == eof) break
            if (readRc < 0) throw FFmpegException(avError(readRc))
            val index = ffkmp_packet_stream_index(packet)
            val decoder = decoderByIndex[index]
            val copyInfo = if (decoder == null) copyByIndex[index] else null
            try {
                when {
                    decoder != null -> sendAndDrain(decoder, packet, frame, onFrame)
                    copyInfo != null -> onPacket(packet, copyInfo)
                }
            } finally {
                ffkmp_packet_unref(packet)
            }
        }
        // EOF on the container — flush each decoder's buffered frames.
        for (decoder in decoders) {
            sendAndDrain(decoder, null, frame, onFrame)
        }
    }

    /**
     * Correct send/receive interleaving: `avcodec_send_packet` returning EAGAIN means the
     * decoder's output queue is full — drain it and retry the SAME packet. The previous
     * implementation dropped the packet on EAGAIN, silently losing frames.
     */
    private suspend fun sendAndDrain(
        decoder: DecoderState,
        packet: CPointer<AVPacket>?,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
    ) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val sendRc = avcodec_send_packet(decoder.codecCtx, packet)
            when {
                sendRc == 0 || sendRc == eof -> { drain(decoder, frame, onFrame); return }
                sendRc == eagain -> drain(decoder, frame, onFrame)  // output full → drain, then resend
                // A packet the decoder cannot use is not a reason to abandon the file. Every seek
                // into a stream that carries its parameter sets in band (MPEG-TS above all) lands
                // before the next SPS/PPS, so the first packets after it decode to nothing —
                // "non-existing PPS 0 referenced". Aborting there would make trimming a broadcast
                // capture impossible. Skip the packet and keep going, which is what ffmpeg's own
                // CLI does; if the stream is genuinely broken, no frames arrive and the callers
                // that need output say so.
                sendRc == FFmpegError.AVERROR_INVALIDDATA -> { drain(decoder, frame, onFrame); return }
                else -> throw FFmpegException(avError(sendRc))
            }
        }
    }

    private suspend fun drain(decoder: DecoderState, frame: Frame, onFrame: suspend (Frame) -> Unit) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val rc = avcodec_receive_frame(decoder.codecCtx, frame.nativeFrame)
            if (rc == eagain || rc == eof) return
            // Same tolerance as the send side: a frame that could not be reconstructed from the
            // packets seen so far is skipped, not fatal.
            if (rc == FFmpegError.AVERROR_INVALIDDATA) return
            if (rc < 0) throw FFmpegException(avError(rc))
            // Decoders fill best_effort_timestamp even for files with missing pts — promote it
            // so everything downstream (filters, encoders) sees a usable timestamp.
            ffkmp_frame_use_best_effort_ts(frame.nativeFrame)
            onFrame(FrameOps.wrap(frame.nativeFrame, decoder.stream.index, decoder.stream.type, decoder.stream.timeBase))
            // The wrap'd Frame is reference-only; the callback reads info / pixels and calls
            // close() which does av_frame_unref. The underlying AVFrame pointer is reused for
            // the next iteration after the callback returns.
        }
    }

    public actual suspend fun seekMicros(micros: Long) {
        synchronized(stateLock) {
            check(!closed) { "MediaSource is closed" }
            check(!demuxing) { "Cannot seek while a decode flow is collecting — the demuxer cursor is shared" }
        }
        // [micros] is media-relative; av_seek_frame with stream_index = -1 wants an absolute
        // AV_TIME_BASE timestamp. On a container that starts at a nonzero time (MPEG-TS) the
        // two differ by exactly startTimeMicros.
        val rc = ffkmp_fmt_seek_micros(ctx, -1, toAbsoluteMicros(micros))
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    /**
     * Seek for a pipeline that will DECODE forward to the exact target.
     *
     * [seekMicros] asks libavformat for the keyframe at or before the target, but that is a
     * preference, not a guarantee. Indexless containers (MPEG-TS above all) resolve a seek by
     * binary-searching byte positions, so the demuxer can hand back a position slightly PAST the
     * keyframe it aimed at — and then the video decoder, which cannot output anything until it
     * sees the next IDR, silently starts a whole GOP late. Landing early costs a few discarded
     * frames; landing late is unrecoverable, because nothing downstream can rewind.
     *
     * So aim deliberately early by [DECODE_SEEK_BACKOFF_MICROS] and let the caller's existing
     * decode-and-discard loop walk forward to the frame it actually wants. Callers that stream-copy
     * instead of decoding ([Remuxer]) must NOT use this — for them the extra pre-roll would land in
     * the output as real content.
     */
    internal suspend fun seekForDecode(micros: Long) {
        seekMicros((micros - DECODE_SEEK_BACKOFF_MICROS).coerceAtLeast(0L))
    }

    /** Native codec parameters of a stream — for stream-copy setups ([MediaSink.addCopyStream]). */
    internal fun codecparOf(stream: StreamInfo): CPointer<ffmpeg.AVCodecParameters>? {
        check(!isClosed) { "MediaSource is closed" }
        return ffkmp_fmt_stream(ctx, stream.index.toUInt())?.let { ffkmp_stream_codecpar(it) }
    }

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame {
        val target = stream ?: primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("No video stream to extract a frame from"))
        // [atMicros] is media-relative; seekForDecode and toRelativeMicros both apply the
        // container's start offset, so the comparison below stays in one consistent timeline.
        // seekForDecode (not seekMicros) because the loop below decodes forward to the target and
        // a seek that overshoots would silently return a much later frame.
        seekForDecode(atMicros)
        var result: Frame? = null
        demuxRouted(
            decode = listOf(target),
            copy = emptyList(),
            onFrame = { frame ->
                val ptsMicros = if (frame.info.hasPts) {
                    toRelativeMicros(frame.info.pts, target.timeBase)
                } else Long.MAX_VALUE  // no pts → accept the first frame we see
                if (ptsMicros >= atMicros) {
                    result = frame.copy()
                    frame.close()
                    throw StopDemux()
                }
                frame.close()  // decode-discard: frames between keyframe and target
            },
            onPacket = { _, _ -> },
        )
        return result
            ?: throw FFmpegException(FFmpegError.Internal("No frame at ${atMicros}µs (beyond end of stream?)"))
    }

    actual override fun close() {
        synchronized(stateLock) {
            if (closed) return
            check(!demuxing) {
                "Cannot close MediaSource while a decode flow is collecting — cancel/finish " +
                    "collection first (freeing the demuxer under an active decode would crash)."
            }
            closed = true
        }
        memScoped {
            val pp = alloc<CPointerVar<AVFormatContext>>()
            pp.value = ctx
            ffkmp_fmt_close_input(pp.ptr)
        }
    }

    public actual companion object {
        public actual fun open(path: String): MediaSource = openMediaSource(path)

        /**
         * How far before the requested time [seekForDecode] aims. Must comfortably exceed one GOP:
         * broadcast MPEG-TS typically uses 0.5–2s, and file-based content rarely exceeds 10s. The
         * only cost of overshooting backwards is decoding frames that are then discarded.
         */
        private const val DECODE_SEEK_BACKOFF_MICROS = 5_000_000L
    }
}

/**
 * Thrown by a demux callback to stop the pump early (trim end bound reached, target frame
 * found). Caught inside [MediaSource.demuxRouted]; never escapes to user code.
 */
internal class StopDemux : Throwable()

/** One opened decoder bound to one input stream. */
private class DecoderState(
    val stream: StreamInfo,
    val codecCtx: CPointer<AVCodecContext>,
) {
    fun free() = ffkmp_codecctx_free(codecCtx)

    companion object {
        fun open(ctx: CPointer<AVFormatContext>, stream: StreamInfo): DecoderState {
            val streamPtr = ffkmp_fmt_stream(ctx, stream.index.toUInt())
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} disappeared"))
            val codecpar = ffkmp_stream_codecpar(streamPtr)
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} missing codecpar"))
            val codecId = ffkmp_codecpar_codec_id(codecpar)
            val codec = ffkmp_find_decoder_by_id(codecId)
                ?: throw FFmpegException(FFmpegError.Internal("No decoder for codec id $codecId"))

            val codecCtx = ffkmp_codecctx_alloc(codec)
                ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
            try {
                check0(ffkmp_codecctx_from_par(codecCtx, codecpar), "avcodec_parameters_to_context")
                check0(ffkmp_codecctx_open(codecCtx, codec), "avcodec_open2")
            } catch (t: Throwable) {
                ffkmp_codecctx_free(codecCtx)
                throw t
            }
            return DecoderState(stream, codecCtx)
        }
    }
}

internal fun openMediaSource(path: String): MediaSource {
    val arena = Arena()
    val ctxVar = arena.allocPointerTo<AVFormatContext>()
    val openRc = ffkmp_fmt_open_input(ctxVar.ptr, path)
    if (openRc < 0) { arena.clear(); throw FFmpegException(avError(openRc)) }
    val ctx = ctxVar.value
        ?: run { arena.clear(); throw FFmpegException(FFmpegError.Internal("open_input returned NULL")) }
    arena.clear()  // ctxVar was just used to read out the ctx; the ctx pointer is now standalone.

    val infoRc = ffkmp_fmt_find_stream_info(ctx)
    if (infoRc < 0) {
        memScoped {
            val pp = alloc<CPointerVar<AVFormatContext>>().also { it.value = ctx }
            ffkmp_fmt_close_input(pp.ptr)
        }
        throw FFmpegException(avError(infoRc))
    }

    val streams = buildStreams(ctx)
    val durationFromHeader = ffkmp_fmt_duration(ctx).takeIf { it > 0L }
    val formatName = ffkmp_fmt_iformat_name(ctx)?.toKString() ?: "unknown"
    val metadata = readMetadata(ffkmp_fmt_metadata(ctx))

    return MediaSource(ctx, streams, durationFromHeader, formatName, metadata, ffkmp_fmt_start_time(ctx))
}

private fun buildStreams(ctx: CPointer<AVFormatContext>): List<StreamInfo> {
    val nb = ffkmp_fmt_nb_streams(ctx).toInt()
    val out = ArrayList<StreamInfo>(nb)
    for (i in 0 until nb) {
        val s = ffkmp_fmt_stream(ctx, i.toUInt()) ?: continue
        val par = ffkmp_stream_codecpar(s) ?: continue

        val typeRaw = ffkmp_codecpar_codec_type(par)
        val type = when (typeRaw) {
            mediaTypeAsInt(AVMEDIA_TYPE_VIDEO) -> MediaType.Video
            mediaTypeAsInt(AVMEDIA_TYPE_AUDIO) -> MediaType.Audio
            mediaTypeAsInt(AVMEDIA_TYPE_SUBTITLE) -> MediaType.Subtitle
            mediaTypeAsInt(AVMEDIA_TYPE_DATA) -> MediaType.Data
            mediaTypeAsInt(AVMEDIA_TYPE_ATTACHMENT) -> MediaType.Attachment
            else -> MediaType.Unknown
        }

        val codecId = ffkmp_codecpar_codec_id(par)
        // The CODEC's canonical name, not whichever decoder this build registers for it: an AV1
        // stream must read "av1" whether or not libdav1d is compiled in, and a subtitle or
        // attachment stream with no decoder at all must still name its codec.
        val codecName = ffkmp_codec_id_name(codecId)?.toKString() ?: "codec_$codecId"

        val timeBase = readRational { num, den -> ffkmp_stream_time_base(s, num, den) }
        val avgFr    = readRational { num, den -> ffkmp_stream_avg_frame_rate(s, num, den) }
        val sar      = readRational { num, den -> ffkmp_codecpar_sample_aspect_ratio(par, num, den) }
            .let { if (it.num == 0) Rational(1, 1) else it }

        out += StreamInfo(
            index = ffkmp_stream_index(s),
            type = type,
            codec = CodecId(codecName),
            timeBase = timeBase,
            durationMicros = ffkmp_stream_duration_micros(s).takeIf { it > 0L },
            bitrateBps = ffkmp_codecpar_bit_rate(par).takeIf { it > 0L },
            video = if (type == MediaType.Video) VideoStreamInfo(
                width = ffkmp_codecpar_width(par),
                height = ffkmp_codecpar_height(par),
                pixelFormat = pixelFormatFromAv(ffkmp_codecpar_format(par)),
                frameRate = avgFr,
                sampleAspectRatio = sar,
            ) else null,
            audio = if (type == MediaType.Audio) AudioStreamInfo(
                sampleRate = ffkmp_codecpar_sample_rate(par),
                channels = ffkmp_codecpar_channels(par),
                sampleFormat = sampleFormatFromAv(ffkmp_codecpar_format(par)),
            ) else null,
            metadata = readMetadata(ffkmp_stream_metadata(s)),
        )
    }
    return out
}

/** Call [block] with two IntVar out-params, return the resulting [Rational]. */
private inline fun readRational(block: (CPointer<IntVar>, CPointer<IntVar>) -> Unit): Rational = memScoped {
    val n = alloc<IntVar>(); val d = alloc<IntVar>()
    block(n.ptr, d.ptr)
    Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
}

private fun readMetadata(dict: CPointer<cnames.structs.AVDictionary>?): Map<String, String> {
    if (dict == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var entry = ffkmp_dict_get(dict, null)
    while (entry != null) {
        val k = ffkmp_dict_entry_key(entry)?.toKString()
        val v = ffkmp_dict_entry_value(entry)?.toKString()
        if (k != null) out[k] = v ?: ""
        entry = ffkmp_dict_get(dict, entry)
    }
    return out
}

/** AVMediaType in cinterop is a typedef'd enum — reduce it to a plain Int for comparisons. */
private fun mediaTypeAsInt(value: Any): Int = when (value) {
    is Int  -> value
    is UInt -> value.toInt()
    else    -> (value as? Number)?.toInt() ?: -1
}
