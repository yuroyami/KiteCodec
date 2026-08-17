package io.github.yuroyami.kitecodec

import kotlin.js.JsAny

/**
 * Hands a [MediaByteSource]'s bytes to FFmpeg through the callbacks it expects (17.14 X-06).
 *
 * FFmpeg's IO is synchronous: it calls read and seek and expects an answer before it returns. On
 * the browser's main thread nothing may block, so this bridge does the one thing that is both
 * correct and available today. It drains the source into the codec module's memory ONCE, and the
 * read and seek callbacks are pure JavaScript over that buffer, answering instantly and blocking
 * nothing.
 *
 * That is not a workaround for the common case, it is the shape of the data: a browser gets media
 * from a `File`, a `fetch` response or an `ArrayBuffer`, and all three are already whole. What it
 * does NOT support is a source larger than memory or one served by range requests, which needs the
 * Worker of X-08 where a blocking read is legal. Refused explicitly below rather than half-served.
 */
internal class WebIoBridge private constructor(
    private val module: JsAny,
    private val buffer: Int,
    val readPointer: Int,
    val seekPointer: Int,
) {

    fun release() {
        releaseCallbacks(module, readPointer, seekPointer)
        wasmFree(module, buffer)
    }

    companion object {
        /** Sources above this are refused rather than silently doubling the page's memory use. */
        private const val MAX_BYTES = 512L * 1024 * 1024

        fun install(io: MediaByteSource): WebIoBridge {
            val module = requireModule()
            val size = io.size
                ?: throw FFmpegException(
                    FFmpegError.Unsupported(
                        0,
                        "The web backend needs a MediaByteSource that knows its size, because it " +
                            "stages the bytes for FFmpeg's synchronous IO. A source of unknown " +
                            "length has to stream, which needs the Worker (KPKMP.md 17.14 X-08).",
                    ),
                )
            if (size > MAX_BYTES) {
                throw FFmpegException(
                    FFmpegError.Unsupported(
                        0,
                        "This media is $size bytes and the web backend stages the whole source in " +
                            "memory, so it caps at $MAX_BYTES. Streaming larger media needs the " +
                            "Worker (KPKMP.md 17.14 X-08).",
                    ),
                )
            }
            val total = size.toInt()
            val buffer = wasmAlloc(module, total)
            if (buffer == 0) throw FFmpegException(FFmpegError.Internal("could not stage $total bytes"))
            try {
                drain(io, module, buffer, total)
            } catch (failure: Throwable) {
                wasmFree(module, buffer)
                throw failure
            }
            val callbacks = installCallbacks(module, buffer, total)
            return WebIoBridge(
                module = module,
                buffer = buffer,
                readPointer = callbackRead(callbacks),
                seekPointer = callbackSeek(callbacks),
            )
        }

        /** Copies the source in chunks rather than one huge Kotlin array beside the wasm copy. */
        private fun drain(io: MediaByteSource, module: JsAny, buffer: Int, total: Int) {
            val chunk = ByteArray(CHUNK)
            var written = 0
            io.seek(0)
            while (written < total) {
                val want = minOf(CHUNK, total - written)
                val got = io.read(chunk, 0, want)
                if (got <= 0) {
                    throw FFmpegException(
                        FFmpegError.InvalidData(0, "the byte source ended at $written of $total bytes"),
                    )
                }
                writeBytes(module, buffer + written, chunk, got)
                written += got
            }
        }

        private const val CHUNK = 1 shl 16
    }
}

/** Copies [length] bytes of [bytes] into codec memory at [pointer]. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal fun writeBytes(module: JsAny, pointer: Int, bytes: ByteArray, length: Int) {
    for (i in 0 until length) writeByte(module, pointer + i, bytes[i].toInt() and 0xFF)
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p, v) => { m.HEAPU8[p] = v; }")
private external fun writeByte(module: JsAny, pointer: Int, value: Int)

/**
 * Registers the two callbacks in the codec module's function table and returns both indices.
 *
 * Written in JavaScript on purpose. These close over a buffer that lives in the module's memory and
 * are called BY the module, synchronously, from inside `avformat_open_input`. Routing them back
 * through Kotlin would add a second module crossing to every read for no gain, and Kotlin/Wasm
 * cannot be handed to `addFunction` as a raw table entry anyway.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, base, total) => {
        let pos = 0;
        const read = m.addFunction((opaque, dst, len) => {
            if (pos >= total) return -541478725;
            const n = Math.min(len, total - pos);
            m.HEAPU8.copyWithin(dst, base + pos, base + pos + n);
            pos += n;
            return n;
        }, 'iiii');
        const seek = m.addFunction((opaque, offset, whence) => {
            const off = Number(offset), w = Number(whence);
            if (w === 0x10000) return BigInt(total);
            if (w === 0) pos = off;
            else if (w === 1) pos += off;
            else if (w === 2) pos = total + off;
            else return -1n;
            if (pos < 0) pos = 0;
            if (pos > total) pos = total;
            return BigInt(pos);
        }, 'jiji');
        return { read, seek };
    }"""
)
private external fun installCallbacks(module: JsAny, buffer: Int, total: Int): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.read")
private external fun callbackRead(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.seek")
private external fun callbackSeek(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, r, s) => { m.removeFunction(r); m.removeFunction(s); }")
private external fun releaseCallbacks(module: JsAny, read: Int, seek: Int)
