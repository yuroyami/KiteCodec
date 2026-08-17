package io.github.yuroyami.kitecodec

import io.github.yuroyami.kitecodec.dsl.DecoderOptions
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_alloc
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_from_par
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_open
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_set_low_delay
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_bit_rate
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_channels
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_codec_id
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_codec_type
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_format
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_height
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_sample_aspect_ratio
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_sample_rate
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecpar_width
import io.github.yuroyami.kitecodec.wasm.ffkmp_codec_id_name
import io.github.yuroyami.kitecodec.wasm.ffkmp_find_decoder_by_id
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_close_input_io
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_duration
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_find_stream_info
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_iformat_name
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_is_seekable
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_nb_streams
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_start_time
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_stream
import io.github.yuroyami.kitecodec.wasm.ffkmp_media_type_audio
import io.github.yuroyami.kitecodec.wasm.ffkmp_media_type_subtitle
import io.github.yuroyami.kitecodec.wasm.ffkmp_media_type_video
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_avg_frame_rate
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_codecpar
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_duration_micros
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_index
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_rotation_degrees
import io.github.yuroyami.kitecodec.wasm.ffkmp_stream_time_base
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An open container, over the codec module (17.14 X-07).
 *
 * Opened from a [MediaByteSource] only. There is no filesystem in a browser, so the `open(path)`
 * overloads refuse rather than pretending: whatever the caller has is already bytes.
 */
public actual class MediaSource internal constructor(
    private val contextSlot: Int,
    private val context: Int,
    private val bridge: WebIoBridge,
    private val unused: List<String>,
) : AutoCloseable {

    private var closed = false

    /** Cleared on close, checked by every reader and decoder that borrows this container. */
    private val lifetime = SourceLifetime()

    private fun alive(): Int =
        if (!closed) context else throw FFmpegException(FFmpegError.Internal("this media source is closed"))

    public actual val streams: List<StreamInfo> by lazy { readStreams(requireModule(), alive()) }

    public actual val durationMicros: Long?
        get() = ffkmp_fmt_duration(requireModule(), alive()).takeIf { it > 0 }

    public actual val formatName: String
        get() = utf8OrNull(requireModule(), ffkmp_fmt_iformat_name(requireModule(), alive())).orEmpty()

    /** Container metadata needs the dictionary walk, which this increment does not carry. */
    public actual val metadata: Map<String, String> get() = emptyMap()

    public actual val chapters: List<Chapter> get() = emptyList()

    /**
     * The keys FFmpeg did not consume, which is a real answer now.
     *
     * It answered `emptyList()` before, while `open` was also discarding every option unread. That
     * pair is the worst of both: a caller probing for option support reads "none unused" as "all
     * supported". The options are forwarded now and this reports what came back.
     */
    public actual val unusedOpenOptions: List<String> get() = unused

    public actual val startTimeMicros: Long
        get() = ffkmp_fmt_start_time(requireModule(), alive()).takeIf { it != Long.MIN_VALUE } ?: 0L

    public actual val isSeekable: Boolean
        get() = ffkmp_fmt_is_seekable(requireModule(), alive()) != 0

    public actual val primaryVideo: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Video }
    public actual val primaryAudio: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Audio }

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> = decodeStreams(listOf(stream))

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> = flow {
        val decoders = streams.associate { it.index to openDecoder(it) }
        val reader = openPacketReader(streams)
        try {
            while (true) {
                val packet = reader.read()
                if (packet == null) {
                    // Drain every decoder before finishing: frames can still be queued inside them.
                    decoders.values.forEach { decoder ->
                        decoder.send(null)
                        while (true) emit(decoder.receive() ?: break)
                    }
                    return@flow
                }
                packet.use { p ->
                    val decoder = decoders[p.streamIndex]
                    if (decoder != null && decoder.send(p)) {
                        while (true) emit(decoder.receive() ?: break)
                    }
                }
            }
        } finally {
            reader.close()
            decoders.values.forEach { it.close() }
        }
    }

    public actual suspend fun seekMicros(micros: Long) {
        openPacketReader(emptyList()).use { it.seek(micros, SeekDirection.Backward, null) }
    }

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame {
        val target = stream ?: primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("this media has no video stream to extract from"))
        seekMicros(atMicros)
        val reader = openPacketReader(listOf(target))
        val decoder = openDecoder(target)
        try {
            while (true) {
                val packet = reader.read() ?: break
                packet.use { if (decoder.send(it)) decoder.receive()?.let { frame -> return frame } }
            }
            decoder.send(null)
            return decoder.receive()
                ?: throw FFmpegException(FFmpegError.Internal("no frame decoded at ${atMicros}us"))
        } finally {
            reader.close()
            decoder.close()
        }
    }

    public actual fun openPacketReader(streams: List<StreamInfo>): PacketReader =
        PacketReader(
            context = alive(),
            timeBases = this.streams.associate { it.index to it.timeBase },
            wanted = streams.map { it.index }.toSet(),
            lifetime = lifetime,
        )

    public actual fun openDecoder(
        stream: StreamInfo,
        threadCount: Int,
        lowDelay: Boolean,
        decoder: CodecId?,
        options: DecoderOptions?,
        hardware: HardwareAccel?,
    ): StreamDecoder {
        val m = requireModule()
        val native = ffkmp_fmt_stream(m, alive(), stream.index)
        val par = ffkmp_stream_codecpar(m, native)
        val codec = ffkmp_find_decoder_by_id(m, ffkmp_codecpar_codec_id(m, par))
        if (codec == 0) {
            throw FFmpegException(
                FFmpegError.Unsupported(0, "no decoder for ${stream.codec.name} in the web build"),
            )
        }
        val ctx = ffkmp_codecctx_alloc(m, codec)
        if (ctx == 0) throw FFmpegException(FFmpegError.Internal("allocating a decoder failed"))
        if (ffkmp_codecctx_from_par(m, ctx, par) < 0) {
            ffkmp_codecctx_free(m, ctx)
            throw FFmpegException(FFmpegError.Internal("copying codec parameters failed"))
        }
        if (lowDelay) ffkmp_codecctx_set_low_delay(m, ctx, 1)
        // Thread count is deliberately not set: the default web artifact has no pthreads, so any
        // value above one would be a request the runtime silently ignores.
        if (ffkmp_codecctx_open(m, ctx, codec) < 0) {
            ffkmp_codecctx_free(m, ctx)
            throw FFmpegException(FFmpegError.Internal("opening the decoder failed"))
        }
        return StreamDecoder(ctx, stream, lifetime)
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Before the context goes: every reader and decoder holding it raw must stop using it.
        lifetime.closed()
        val m = requireModule()
        ffkmp_fmt_close_input_io(m, contextSlot)
        wasmFree(m, contextSlot)
        bridge.release()
    }

    public actual companion object {
        public actual fun open(path: String): MediaSource = throw noFilesystem(path)

        public actual fun open(path: String, options: Map<String, String>): MediaSource =
            throw noFilesystem(path)

        public actual fun open(io: MediaByteSource, options: Map<String, String>): MediaSource {
            val m = requireModule()
            val bridge = WebIoBridge.install(io)
            val slot = wasmAlloc(m, 4)
            val opts = CStringArrays.of(m, options)
            val rc = try {
                openInputIo(
                    m, slot, bridge.readPointer, bridge.seekPointer, io.size ?: 0L,
                    opts.keys, opts.values, options.size,
                )
            } catch (failure: Throwable) {
                opts.free(m); wasmFree(m, slot); bridge.release(); throw failure
            }
            if (rc < 0) {
                opts.free(m); wasmFree(m, slot); bridge.release()
                throw FFmpegException(FFmpegError.InvalidData(rc, "could not open this media ($rc)"))
            }
            // Read the leftovers BEFORE freeing: the C side rewrites the key array in place,
            // NULLing the entries it consumed, so a freed array answers nothing.
            val leftover = opts.survivingKeys(m, options)
            opts.free(m)
            val ctx = readInt32(m, slot)
            if (ffkmp_fmt_find_stream_info(m, ctx) < 0) {
                ffkmp_fmt_close_input_io(m, slot)
                wasmFree(m, slot)
                bridge.release()
                throw FFmpegException(FFmpegError.InvalidData(0, "could not read stream information"))
            }
            return MediaSource(slot, ctx, bridge, leftover)
        }

        private fun noFilesystem(path: String) = FFmpegException(
            FFmpegError.Unsupported(
                0,
                "A browser has no filesystem, so MediaSource.open(\"$path\") cannot work. Open a " +
                    "MediaByteSource instead: whatever the page has is already bytes, from a File, " +
                    "a fetch response or an ArrayBuffer.",
            ),
        )
    }
}

private fun readStreams(m: kotlin.js.JsAny, context: Int): List<StreamInfo> {
    val video = ffkmp_media_type_video(m)
    val audio = ffkmp_media_type_audio(m)
    val subtitle = ffkmp_media_type_subtitle(m)
    return (0 until ffkmp_fmt_nb_streams(m, context)).map { i ->
        val native = ffkmp_fmt_stream(m, context, i)
        val par = ffkmp_stream_codecpar(m, native)
        val kind = when (ffkmp_codecpar_codec_type(m, par)) {
            video -> MediaType.Video
            audio -> MediaType.Audio
            subtitle -> MediaType.Subtitle
            else -> MediaType.Data
        }
        val timeBase = readTimeBase(m, native)
        StreamInfo(
            index = ffkmp_stream_index(m, native),
            type = kind,
            codec = CodecId(utf8OrNull(m, ffkmp_codec_id_name(m, ffkmp_codecpar_codec_id(m, par))).orEmpty()),
            timeBase = timeBase,
            durationMicros = ffkmp_stream_duration_micros(m, native).takeIf { it > 0 },
            bitrateBps = ffkmp_codecpar_bit_rate(m, par).takeIf { it > 0 },
            video = if (kind == MediaType.Video) {
                VideoStreamInfo(
                    width = ffkmp_codecpar_width(m, par),
                    height = ffkmp_codecpar_height(m, par),
                    pixelFormat = pixelFormatOf(m, ffkmp_codecpar_format(m, par)),
                    frameRate = readRational(m) { n, d -> ffkmp_stream_avg_frame_rate(m, native, n, d) },
                    sampleAspectRatio = readRational(m, fallbackNum = 1, fallbackDen = 1) { n, d ->
                        ffkmp_codecpar_sample_aspect_ratio(m, par, n, d)
                    },
                )
            } else {
                null
            },
            audio = if (kind == MediaType.Audio) {
                AudioStreamInfo(
                    sampleRate = ffkmp_codecpar_sample_rate(m, par),
                    channels = ffkmp_codecpar_channels(m, par),
                    sampleFormat = sampleFormatOf(m, ffkmp_codecpar_format(m, par)),
                )
            } else {
                null
            },
            rotationDegrees = ffkmp_stream_rotation_degrees(m, native),
        )
    }
}

private fun readTimeBase(m: kotlin.js.JsAny, stream: Int): Rational =
    readRational(m, fallbackNum = 1, fallbackDen = 1_000_000) { n, d ->
        ffkmp_stream_time_base(m, stream, n, d)
    }

/**
 * Reads any `(int *num, int *den)` pair out of the codec module.
 *
 * Several entry points answer through two out-parameters, which JavaScript cannot pass directly, so
 * each needs a scratch pair in codec memory. One helper rather than three copies of the same eight
 * lines. A zero denominator means FFmpeg declared nothing and the caller's fallback applies: an
 * undeclared frame rate is 0/1 ("unknown"), an undeclared aspect ratio is 1/1 ("square"), and an
 * undeclared time base is microseconds.
 */
private inline fun readRational(
    m: kotlin.js.JsAny,
    fallbackNum: Long = 0,
    fallbackDen: Long = 1,
    read: (numPtr: Int, denPtr: Int) -> Unit,
): Rational {
    val scratch = wasmAlloc(m, 8)
    try {
        read(scratch, scratch + 4)
        val num = readInt32(m, scratch)
        val den = readInt32(m, scratch + 4)
        return if (den == 0) Rational.of(fallbackNum, fallbackDen) else Rational.of(num.toLong(), den.toLong())
    } finally {
        wasmFree(m, scratch)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, out, readFn, seekFn, size, keys, values, n) => m._ffkmp_fmt_open_input_io(out, 0, readFn, seekFn, BigInt(size), keys, values, n, 0)")
private external fun openInputIo(
    module: kotlin.js.JsAny,
    out: Int,
    readFn: Int,
    seekFn: Int,
    size: Long,
    keys: Int,
    values: Int,
    count: Int,
): Int

/**
 * Two NULL-terminated `char *` arrays in codec memory, which is how the C surface takes options.
 *
 * The key array is an OUT parameter as well as an in one: `ffkmp_fmt_open_input_io` NULLs the entry
 * for every option FFmpeg consumed, so what survives is exactly the unused set. That is why
 * [survivingKeys] must run before [free].
 */
private class CStringArrays(val keys: Int, val values: Int, private val strings: List<Int>) {

    fun survivingKeys(m: kotlin.js.JsAny, options: Map<String, String>): List<String> {
        if (options.isEmpty()) return emptyList()
        return options.keys.filterIndexed { index, _ -> readInt32(m, keys + index * 4) != 0 }
    }

    fun free(m: kotlin.js.JsAny) {
        strings.forEach { wasmFree(m, it) }
        if (keys != 0) wasmFree(m, keys)
        if (values != 0) wasmFree(m, values)
    }

    companion object {
        fun of(m: kotlin.js.JsAny, options: Map<String, String>): CStringArrays {
            if (options.isEmpty()) return CStringArrays(0, 0, emptyList())
            val strings = mutableListOf<Int>()
            val keys = wasmAlloc(m, options.size * 4)
            val values = wasmAlloc(m, options.size * 4)
            options.entries.forEachIndexed { index, (key, value) ->
                val keyPtr = allocCString(m, key)
                val valuePtr = allocCString(m, value)
                strings += keyPtr
                strings += valuePtr
                writeInt32(m, keys + index * 4, keyPtr)
                writeInt32(m, values + index * 4, valuePtr)
            }
            return CStringArrays(keys, values, strings)
        }
    }
}
