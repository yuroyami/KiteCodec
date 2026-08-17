package io.github.yuroyami.kitecodec

import io.github.yuroyami.kitecodec.wasm.ffkmp_filter_exists
import io.github.yuroyami.kitecodec.wasm.ffkmp_find_decoder_by_name
import io.github.yuroyami.kitecodec.wasm.ffkmp_find_encoder_by_name
import io.github.yuroyami.kitecodec.wasm.kc_ffmpeg_configuration

/**
 * The real web backend, over the generated binding (17.14 X-07).
 *
 * Every member requires [KiteCodecWeb.load] to have completed. That is not a quirk of this object:
 * the codec is a separate wasm module and there is nothing to ask before it is instantiated.
 */
public actual object FFmpeg {

    public actual val buildConfiguration: String
        get() = utf8OrNull(requireModule(), kc_ffmpeg_configuration(requireModule())).orEmpty()

    public actual val versions: Versions get() = versionsFrom(identity)

    public actual val identity: FFmpegIdentity get() = webIdentity()

    public actual fun hasEncoder(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_find_encoder_by_name(requireModule(), ptr) != 0 }

    public actual fun hasDecoder(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_find_decoder_by_name(requireModule(), ptr) != 0 }

    public actual fun hasFilter(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_filter_exists(requireModule(), ptr) != 0 }
}
