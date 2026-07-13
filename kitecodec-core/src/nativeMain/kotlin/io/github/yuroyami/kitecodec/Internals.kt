package io.github.yuroyami.kitecodec

import ffmpeg.ffkmp_averror_eagain
import ffmpeg.ffkmp_averror_eof
import ffmpeg.ffkmp_pix_fmt_from_name
import ffmpeg.ffkmp_pix_fmt_name
import ffmpeg.ffkmp_sample_fmt_from_name
import ffmpeg.ffkmp_sample_fmt_name
import ffmpeg.ffkmp_strerror
import kotlinx.cinterop.toKString

/** Cached AVERROR codes. Read once at first call (lazy through `by lazy`). */
internal object FFErrors {
    val EAGAIN: Int by lazy { ffkmp_averror_eagain() }
    val EOF: Int    by lazy { ffkmp_averror_eof() }
}

/** Wrap an FFmpeg int return code as a typed [FFmpegError] (semantic classification). */
internal fun avError(code: Int): FFmpegError {
    val msg = ffkmp_strerror(code)?.toKString() ?: "AVERROR($code)"
    return FFmpegError.fromCode(code, "$msg (code=$code)")
}

internal fun check0(rc: Int, label: String) {
    if (rc < 0) {
        val err = avError(rc)
        throw FFmpegException(FFmpegError.fromCode(err.code, "$label: ${err.message}"))
    }
}

internal fun pixelFormatFromAv(value: Int): PixelFormat {
    if (value < 0) return PixelFormat.None
    val name = ffkmp_pix_fmt_name(value)?.toKString() ?: return PixelFormat.None
    return PixelFormat(name)
}

internal fun pixelFormatToAv(format: PixelFormat): Int = ffkmp_pix_fmt_from_name(format.name)

internal fun sampleFormatFromAv(value: Int): SampleFormat {
    if (value < 0) return SampleFormat.None
    val name = ffkmp_sample_fmt_name(value)?.toKString() ?: return SampleFormat.None
    return SampleFormat(name)
}

internal fun sampleFormatToAv(format: SampleFormat): Int = ffkmp_sample_fmt_from_name(format.name)
