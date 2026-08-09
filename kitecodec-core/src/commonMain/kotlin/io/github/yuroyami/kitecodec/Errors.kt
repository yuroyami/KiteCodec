package io.github.yuroyami.kitecodec

/**
 * Every KiteCodec failure surfaces as [FFmpegException] wrapping a typed [FFmpegError].
 *
 * The hierarchy maps the raw `AVERROR_*` codes onto semantic categories so callers can react
 * without decoding negative errno math:
 *
 * ```kotlin
 * try { Transcoder.transcode(...) } catch (e: FFmpegException) {
 *     when (e.error) {
 *         is FFmpegError.FileNotFound     -> promptForFile()
 *         is FFmpegError.EncoderNotFound  -> fallBackToSoftwareEncoder()
 *         is FFmpegError.InvalidData      -> reportCorruptInput()
 *         else                            -> log(e.error.code, e.message)
 *     }
 * }
 * ```
 *
 * Every subclass keeps the raw code in [code] (0 for [Internal]), so nothing is lost by the
 * classification; unmapped codes surface as [AvError].
 */
public sealed class FFmpegError(public val code: Int, public val message: String) {

    /** Input path does not exist (`AVERROR(ENOENT)`). */
    public class FileNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Filesystem permission denied (`AVERROR(EACCES)` / `AVERROR(EPERM)`). */
    public class PermissionDenied(code: Int, message: String) : FFmpegError(code, message)

    /** Corrupt or unrecognized bitstream/container data (`AVERROR_INVALIDDATA`). */
    public class InvalidData(code: Int, message: String) : FFmpegError(code, message)

    /** Invalid parameter combination rejected by FFmpeg (`AVERROR(EINVAL)`). */
    public class InvalidArgument(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such encoder (`AVERROR_ENCODER_NOT_FOUND`). */
    public class EncoderNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such decoder (`AVERROR_DECODER_NOT_FOUND`). */
    public class DecoderNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No demuxer recognizes the input (`AVERROR_DEMUXER_NOT_FOUND`). */
    public class DemuxerNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No muxer for the requested output format (`AVERROR_MUXER_NOT_FOUND`). */
    public class MuxerNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such filter (`AVERROR_FILTER_NOT_FOUND`). */
    public class FilterNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No protocol handler for the URL scheme (`AVERROR_PROTOCOL_NOT_FOUND`). */
    public class ProtocolNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Requested stream does not exist (`AVERROR_STREAM_NOT_FOUND`). */
    public class StreamNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Unknown codec/format private option (`AVERROR_OPTION_NOT_FOUND`). */
    public class OptionNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Feature not implemented in FFmpeg (`AVERROR_PATCHWELCOME`). */
    public class Unsupported(code: Int, message: String) : FFmpegError(code, message)

    /** Native allocation failure (`AVERROR(ENOMEM)`). */
    public class OutOfMemory(code: Int, message: String) : FFmpegError(code, message)

    /** End of file/stream surfaced as an error (`AVERROR_EOF`). Rare, loops consume it. */
    public class EndOfFile(code: Int, message: String) : FFmpegError(code, message)

    /** Generic I/O failure (`AVERROR(EIO)`). */
    public class Io(code: Int, message: String) : FFmpegError(code, message)

    /** Any `AVERROR_*` code without a dedicated category above. */
    public class AvError(code: Int, message: String) : FFmpegError(code, message)

    /** Library-internal invariant failure, not an FFmpeg return code. */
    public class Internal(message: String) : FFmpegError(0, message)

    override fun toString(): String = "${this::class.simpleName}(code=$code, message=$message)"

    public companion object {
        // AVERROR_* tags are FFERRTAG('a','b','c','d') = -MKTAG(a,b,c,d); errno-style codes
        // are -errno. All values below are identical across the supported platforms except
        // ENOSYS, which gets each platform's known value listed explicitly.
        private fun tag(a: Int, b: Char, c: Char, d: Char): Int =
            -(a or (b.code shl 8) or (c.code shl 16) or (d.code shl 24))
        private fun tag(a: Char, b: Char, c: Char, d: Char): Int = tag(a.code, b, c, d)

        internal val AVERROR_EOF                = tag('E', 'O', 'F', ' ')
        internal val AVERROR_INVALIDDATA        = tag('I', 'N', 'D', 'A')
        internal val AVERROR_PATCHWELCOME       = tag('P', 'A', 'W', 'E')
        internal val AVERROR_DECODER_NOT_FOUND  = tag(0xF8, 'D', 'E', 'C')
        internal val AVERROR_ENCODER_NOT_FOUND  = tag(0xF8, 'E', 'N', 'C')
        internal val AVERROR_DEMUXER_NOT_FOUND  = tag(0xF8, 'D', 'E', 'M')
        internal val AVERROR_MUXER_NOT_FOUND    = tag(0xF8, 'M', 'U', 'X')
        internal val AVERROR_FILTER_NOT_FOUND   = tag(0xF8, 'F', 'I', 'L')
        internal val AVERROR_PROTOCOL_NOT_FOUND = tag(0xF8, 'P', 'R', 'O')
        internal val AVERROR_STREAM_NOT_FOUND   = tag(0xF8, 'S', 'T', 'R')
        internal val AVERROR_OPTION_NOT_FOUND   = tag(0xF8, 'O', 'P', 'T')

        private const val ENOENT = -2
        private const val EPERM = -1
        private const val EIO = -5
        private const val ENOMEM = -12
        private const val EACCES = -13
        private const val EINVAL = -22

        /** Classify a raw `AVERROR_*` [code] into the semantic hierarchy. */
        internal fun fromCode(code: Int, message: String): FFmpegError = when (code) {
            AVERROR_EOF                 -> EndOfFile(code, message)
            AVERROR_INVALIDDATA         -> InvalidData(code, message)
            AVERROR_PATCHWELCOME        -> Unsupported(code, message)
            AVERROR_DECODER_NOT_FOUND   -> DecoderNotFound(code, message)
            AVERROR_ENCODER_NOT_FOUND   -> EncoderNotFound(code, message)
            AVERROR_DEMUXER_NOT_FOUND   -> DemuxerNotFound(code, message)
            AVERROR_MUXER_NOT_FOUND     -> MuxerNotFound(code, message)
            AVERROR_FILTER_NOT_FOUND    -> FilterNotFound(code, message)
            AVERROR_PROTOCOL_NOT_FOUND  -> ProtocolNotFound(code, message)
            AVERROR_STREAM_NOT_FOUND    -> StreamNotFound(code, message)
            AVERROR_OPTION_NOT_FOUND    -> OptionNotFound(code, message)
            ENOENT                      -> FileNotFound(code, message)
            EACCES, EPERM               -> PermissionDenied(code, message)
            ENOMEM                      -> OutOfMemory(code, message)
            EINVAL                      -> InvalidArgument(code, message)
            EIO                         -> Io(code, message)
            else                        -> AvError(code, message)
        }
    }
}

/**
 * The single exception type KiteCodec throws for FFmpeg-related failures. Inspect [error]
 * for the semantic category and [code] for the raw `AVERROR_*` value.
 */
public class FFmpegException(public val error: FFmpegError) : RuntimeException(error.message) {
    /** The raw `AVERROR_*` code, or 0 for internal errors. */
    public val code: Int get() = error.code
}
