package io.github.yuroyami.kitecodec

import ffmpeg.KC_LIB_AVCODEC
import ffmpeg.KC_LIB_AVFILTER
import ffmpeg.KC_LIB_AVFORMAT
import ffmpeg.KC_LIB_AVUTIL
import ffmpeg.KC_LIB_SWRESAMPLE
import ffmpeg.KC_LIB_SWSCALE
import ffmpeg.avcodec_find_decoder_by_name
import ffmpeg.avcodec_find_encoder_by_name
import ffmpeg.avfilter_get_by_name
import ffmpeg.kc_ffmpeg_configuration
import kotlinx.cinterop.toKString

public actual object FFmpeg {

    /*
     * Register item B1-22. The six `*_version` functions and `avcodec_configuration` used to be called
     * straight from here, which made this file 7 of the 21 direct libav call sites the coupling ratchet
     * counted. They now come through the identity gate's report and its one configuration accessor,
     * because a caller asking what FFmpeg it has is asking an identity question and the answer has to be
     * the same one the gate compared against. The four hot decode and encode calls stay raw: giving them
     * a C wrapper without B2's typed send and receive outcomes would change their signatures twice, and
     * the three find_*_by_name queries below go with them in B2.
     *
     * The function names in the paragraph above are deliberately written without their parentheses. The
     * ratchet counts a libav call as the name followed by an opening bracket, in any line that is not an
     * import, comments included, so a comment naming a function this file no longer calls would keep
     * that call site on the books for ever. Three such mentions were measured at B1.6 and rewritten for
     * exactly this reason, after which the count fell from 21 to 14, which is the number of call sites
     * that are really left.
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
        return avcodec_find_encoder_by_name(name) != null
    }

    public actual fun hasDecoder(name: String): Boolean {
        requireCompatibleFFmpeg()
        return avcodec_find_decoder_by_name(name) != null
    }

    public actual fun hasFilter(name: String): Boolean {
        requireCompatibleFFmpeg()
        return avfilter_get_by_name(name) != null
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
