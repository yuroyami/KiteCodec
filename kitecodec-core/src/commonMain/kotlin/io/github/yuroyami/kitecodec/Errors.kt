package io.github.yuroyami.kitecodec

/**
 * Every KiteCodec failure surfaces as [FFmpegException] wrapping a typed [FFmpegError].
 * [FFmpegError.AvError] carries the raw `AVERROR_*` code plus its `av_strerror` text;
 * [FFmpegError.Internal] marks a library-side invariant failure.
 */
sealed class FFmpegError(val code: Int, message: String) : RuntimeException(message) {
    /** Concrete `AVERROR_*` returned by libav. */
    class AvError(code: Int, message: String) : FFmpegError(code, message)
    /** Library-internal invariant failure. */
    class Internal(message: String) : FFmpegError(0, message)
}

class FFmpegException(val error: FFmpegError) : RuntimeException(error.message, error) {
    /** The raw `AVERROR_*` code, or 0 for internal errors. */
    val code: Int get() = error.code
}
