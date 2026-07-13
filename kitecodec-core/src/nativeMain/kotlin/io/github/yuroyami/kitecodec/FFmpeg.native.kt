package io.github.yuroyami.kitecodec

import ffmpeg.avcodec_configuration
import ffmpeg.avcodec_find_decoder_by_name
import ffmpeg.avcodec_find_encoder_by_name
import ffmpeg.avcodec_version
import ffmpeg.avfilter_get_by_name
import ffmpeg.avfilter_version
import ffmpeg.avformat_version
import ffmpeg.avutil_version
import ffmpeg.swresample_version
import ffmpeg.swscale_version
import kotlinx.cinterop.toKString

public actual object FFmpeg {

    public actual val buildConfiguration: String
        get() = avcodec_configuration()?.toKString() ?: ""

    public actual val versions: Versions by lazy {
        Versions(
            avutil     = decodePackedVersion(avutil_version()),
            avcodec    = decodePackedVersion(avcodec_version()),
            avformat   = decodePackedVersion(avformat_version()),
            avfilter   = decodePackedVersion(avfilter_version()),
            swscale    = decodePackedVersion(swscale_version()),
            swresample = decodePackedVersion(swresample_version()),
        )
    }

    public actual fun hasEncoder(name: String): Boolean = avcodec_find_encoder_by_name(name) != null
    public actual fun hasDecoder(name: String): Boolean = avcodec_find_decoder_by_name(name) != null
    public actual fun hasFilter(name: String): Boolean  = avfilter_get_by_name(name) != null
}

/**
 * `AV_VERSION_INT(major, minor, micro)` packs (major << 16) | (minor << 8) | micro.
 * Unpack it back to the familiar `M.m.μ` string.
 */
internal fun decodePackedVersion(packed: UInt): String {
    val major = (packed shr 16) and 0xFFu
    val minor = (packed shr 8)  and 0xFFu
    val micro = packed and 0xFFu
    return "$major.$minor.$micro"
}
