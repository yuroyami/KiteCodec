package io.github.yuroyami.kitecodec

import io.github.yuroyami.kitecodec.wasm.ffkmp_averror_eagain
import io.github.yuroyami.kitecodec.wasm.ffkmp_averror_eof
import io.github.yuroyami.kitecodec.wasm.ffkmp_avseek_flag_any
import io.github.yuroyami.kitecodec.wasm.ffkmp_avseek_flag_backward
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_flush
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_receive_frame
import io.github.yuroyami.kitecodec.wasm.ffkmp_codecctx_send_packet
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_read_frame
import io.github.yuroyami.kitecodec.wasm.ffkmp_fmt_seek_file
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_alloc
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_free
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_alloc
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_clone
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_data
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_dts
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_duration
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_free
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_is_keyframe
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_pos
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_pts
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_size
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_stream_index
import io.github.yuroyami.kitecodec.wasm.ffkmp_packet_unref

/** FFmpeg's AV_NOPTS_VALUE, the sentinel a stream without timestamps carries. */
private const val NOPTS: Long = Long.MIN_VALUE

private fun Long.microsOrNull(timeBase: Rational): Long? =
    if (this == NOPTS) null else rescaleQ(this, timeBase, MICRO)

private val MICRO = Rational.of(1L, 1_000_000L)

public actual class Packet internal constructor(
    internal var pointer: Int,
    private val base: Rational,
) : AutoCloseable {

    private fun alive(): Int =
        if (pointer != 0) pointer else throw FFmpegException(FFmpegError.Internal("this packet is closed"))

    public actual val timeBase: Rational get() = base
    public actual val streamIndex: Int get() = ffkmp_packet_stream_index(requireModule(), alive())
    public actual val pts: Long get() = ffkmp_packet_pts(requireModule(), alive())
    public actual val dts: Long get() = ffkmp_packet_dts(requireModule(), alive())
    public actual val duration: Long get() = ffkmp_packet_duration(requireModule(), alive())
    public actual val isKeyframe: Boolean get() = ffkmp_packet_is_keyframe(requireModule(), alive()) != 0
    public actual val sizeBytes: Int get() = ffkmp_packet_size(requireModule(), alive())
    public actual val bytePosition: Long get() = ffkmp_packet_pos(requireModule(), alive())
    public actual val hasPts: Boolean get() = pts != NOPTS
    public actual val ptsMicros: Long? get() = pts.microsOrNull(base)
    public actual val dtsMicros: Long? get() = dts.microsOrNull(base)
    public actual val durationMicros: Long? get() = if (duration == 0L) null else rescaleQ(duration, base, MICRO)

    public actual fun copy(): Packet {
        val cloned = ffkmp_packet_clone(requireModule(), alive())
        if (cloned == 0) throw FFmpegException(FFmpegError.Internal("packet clone failed"))
        return Packet(cloned, base)
    }

    public actual fun copyBytes(): ByteArray {
        val m = requireModule()
        val p = alive()
        val size = ffkmp_packet_size(m, p)
        if (size <= 0) return ByteArray(0)
        return readBytes(m, ffkmp_packet_data(m, p), size)
    }

    actual override fun close() {
        val p = pointer
        if (p != 0) {
            pointer = 0
            val m = requireModule()
            ffkmp_packet_unref(m, p)
            ffkmp_packet_free(m, p)
        }
    }
}

public actual enum class SeekDirection {
    Backward,
    Forward,
    Any,
}

public actual class PacketReader internal constructor(
    private val context: Int,
    private val timeBases: Map<Int, Rational>,
    private val wanted: Set<Int>,
    private val lifetime: SourceLifetime,
) : AutoCloseable {

    private var closed = false

    public actual fun read(): Packet? {
        if (closed) throw FFmpegException(FFmpegError.Internal("this packet reader is closed"))
        lifetime.check("packet reader")
        val m = requireModule()
        val eof = ffkmp_averror_eof(m)
        while (true) {
            val packet = ffkmp_packet_alloc(m)
            if (packet == 0) throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
            val rc = ffkmp_fmt_read_frame(m, context, packet)
            if (rc < 0) {
                ffkmp_packet_free(m, packet)
                if (rc == eof) return null
                throw FFmpegException(FFmpegError.Internal("reading a packet failed with $rc"))
            }
            val index = ffkmp_packet_stream_index(m, packet)
            if (wanted.isEmpty() || index in wanted) {
                return Packet(packet, timeBases[index] ?: MICRO)
            }
            // Not a stream this reader was opened for: drop it and keep going rather than hand the
            // caller a packet it would have to filter itself.
            ffkmp_packet_unref(m, packet)
            ffkmp_packet_free(m, packet)
        }
    }

    public actual fun seek(micros: Long, direction: SeekDirection, notEarlierThan: Long?) {
        lifetime.check("packet reader")
        val m = requireModule()
        val flags = when (direction) {
            SeekDirection.Backward -> ffkmp_avseek_flag_backward(m)
            SeekDirection.Any -> ffkmp_avseek_flag_any(m)
            SeekDirection.Forward -> 0
        }
        val floor = notEarlierThan ?: Long.MIN_VALUE
        val rc = ffkmp_fmt_seek_file(m, context, -1, floor, micros, Long.MAX_VALUE, flags)
        if (rc < 0) throw FFmpegException(FFmpegError.Internal("seek to ${micros}us failed with $rc"))
    }

    actual override fun close() {
        closed = true
    }
}

public actual class StreamDecoder internal constructor(
    private val context: Int,
    public actual val stream: StreamInfo,
    private val lifetime: SourceLifetime,
) : AutoCloseable {

    public actual var isDrained: Boolean = false
        private set

    private var closed = false
    private val frame: Int = ffkmp_frame_alloc(requireModule())

    private fun alive() {
        if (closed) throw FFmpegException(FFmpegError.Internal("this decoder is closed"))
        lifetime.check("decoder")
    }

    public actual fun send(packet: Packet?): Boolean {
        alive()
        val m = requireModule()
        // A null packet is the drain signal, and the C side spells that as a null pointer.
        val rc = ffkmp_codecctx_send_packet(m, context, packet?.pointer ?: 0)
        if (packet == null) isDrained = true
        if (rc == 0) return true
        if (rc == ffkmp_averror_eagain(m)) return false
        if (rc == ffkmp_averror_eof(m)) return false
        throw FFmpegException(FFmpegError.Internal("sending a packet failed with $rc"))
    }

    public actual fun receive(): Frame? {
        alive()
        val m = requireModule()
        val rc = ffkmp_codecctx_receive_frame(m, context, frame)
        if (rc == ffkmp_averror_eagain(m) || rc == ffkmp_averror_eof(m)) return null
        if (rc < 0) throw FFmpegException(FFmpegError.Internal("receiving a frame failed with $rc"))
        // The decoder reuses its frame, so the caller gets a clone it owns outright. Handing out
        // the shared one would make the next receive() mutate a frame the caller still holds.
        val owned = io.github.yuroyami.kitecodec.wasm.ffkmp_frame_clone(m, frame)
        if (owned == 0) throw FFmpegException(FFmpegError.Internal("cloning the decoded frame failed"))
        return Frame(owned, stream.index, stream.type, stream.timeBase)
    }

    public actual fun flush() {
        alive()
        ffkmp_codecctx_flush(requireModule(), context)
        isDrained = false
    }

    actual override fun close() {
        if (closed) return
        closed = true
        val m = requireModule()
        if (frame != 0) ffkmp_frame_free(m, frame)
        // Only if the container still stands: closing it already tore the decoder's context down.
        if (lifetime.isOpen) ffkmp_codecctx_free(m, context)
    }
}

/**
 * The lifetime of the container every reader and decoder borrows from.
 *
 * These hold the `AVFormatContext` as a raw address, and `MediaSource.close()` frees it. Without
 * this, a reader used after its source was closed would read freed memory and answer plausible
 * nonsense, which is exactly the failure the generation-tagged handle table exists to prevent on
 * the JNI side (17.14 X-04). The web backend does not route through that table, so it owes the
 * same guarantee in Kotlin: one flag the parent clears and every child checks first.
 */
internal class SourceLifetime {
    var isOpen: Boolean = true
        private set

    fun closed() {
        isOpen = false
    }

    fun check(what: String) {
        if (!isOpen) {
            throw FFmpegException(
                FFmpegError.Internal(
                    "this $what outlived the MediaSource it came from. The container was closed, " +
                        "so its context is freed and using it would read released memory.",
                ),
            )
        }
    }
}
