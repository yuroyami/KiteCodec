package io.github.yuroyami.kitecodec

import ffmpeg.KC_LIB_AVCODEC
import ffmpeg.KC_LIB_AVFILTER
import ffmpeg.KC_LIB_AVFORMAT
import ffmpeg.KC_LIB_AVUTIL
import ffmpeg.KC_LIB_SWRESAMPLE
import ffmpeg.KC_LIB_SWSCALE
import ffmpeg.ffkmp_filter_exists
import ffmpeg.ffkmp_find_decoder_by_name
import ffmpeg.ffkmp_find_encoder_by_name
import ffmpeg.kc_ffmpeg_configuration
import kotlinx.cinterop.toKString

public actual object FFmpeg {

    /*
     * Register item B1-22. The version/configuration queries come through the identity gate, and the
     * three availability queries come through KiteCodec's opaque helper boundary. No raw libav
     * declaration is needed by this file.
     *
     * Every member here except `identity` calls requireCompatibleFFmpeg() first. `identity` runs the
     * gate and does not throw, because a diagnostic that is unreadable on a rejected runtime is
     * unreadable exactly when someone needs it.
     */

    public actual val buildConfiguration: String
        get() {
            requireCompatibleFFmpeg()
            return kc_ffmpeg_configuration()?.toKString() ?: ""
        }

    public actual val versions: Versions
        get() {
            requireCompatibleFFmpeg()
            return versionsFrom(ffmpegIdentity)
        }

    public actual val identity: FFmpegIdentity
        get() = ffmpegIdentity

    public actual fun hasEncoder(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_find_encoder_by_name(name) != null
    }

    public actual fun hasDecoder(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_find_decoder_by_name(name) != null
    }

    public actual fun hasFilter(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_filter_exists(name) != 0
    }
}

/**
 * Both version columns of [identity], flattened into [Versions].
 *
 * `internal` rather than private so `FFmpegIdentityTest` can drive it from a report it filled itself,
 * which is how the header column is checked against a value that is not simply the runtime's own.
 */
internal fun versionsFrom(identity: FFmpegIdentity): Versions = Versions(
    avutil = identity.libraries[KC_LIB_AVUTIL].runtimeVersion,
    avcodec = identity.libraries[KC_LIB_AVCODEC].runtimeVersion,
    avformat = identity.libraries[KC_LIB_AVFORMAT].runtimeVersion,
    avfilter = identity.libraries[KC_LIB_AVFILTER].runtimeVersion,
    swscale = identity.libraries[KC_LIB_SWSCALE].runtimeVersion,
    swresample = identity.libraries[KC_LIB_SWRESAMPLE].runtimeVersion,
    avutilHeader = identity.libraries[KC_LIB_AVUTIL].headerVersion,
    avcodecHeader = identity.libraries[KC_LIB_AVCODEC].headerVersion,
    avformatHeader = identity.libraries[KC_LIB_AVFORMAT].headerVersion,
    avfilterHeader = identity.libraries[KC_LIB_AVFILTER].headerVersion,
    swscaleHeader = identity.libraries[KC_LIB_SWSCALE].headerVersion,
    swresampleHeader = identity.libraries[KC_LIB_SWRESAMPLE].headerVersion,
)

/**
 * `AV_VERSION_INT(major, minor, micro)` packs (major << 16) | (minor << 8) | micro.
 * Unpack it back to the familiar `M.m.µ` string.
 *
 * Kept although [FFmpeg] no longer calls the six `*_version()` functions: it is the shared spelling of
 * a packed FFmpeg version, and `kc_abi_version()` uses the same packing for KiteCodec's own C ABI.
 */
internal fun decodePackedVersion(packed: UInt): String {
    val major = (packed shr 16) and 0xFFu
    val minor = (packed shr 8)  and 0xFFu
    val micro = packed and 0xFFu
    return "$major.$minor.$micro"
}
