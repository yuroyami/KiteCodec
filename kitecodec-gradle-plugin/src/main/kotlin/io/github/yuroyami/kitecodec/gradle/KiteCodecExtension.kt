package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `kitecodec { ... }` DSL.
 *
 * ```kotlin
 * kitecodec {
 *     ffmpeg {
 *         version = "n8.0"
 *         source  = FFmpegSource.Prebuilt
 *         license = FFmpegLicense.LGPL
 *     }
 * }
 * ```
 */
abstract class KiteCodecExtension @Inject constructor(objects: ObjectFactory) {

    val ffmpeg: FFmpegSpec = objects.newInstance(FFmpegSpec::class.java)

    fun ffmpeg(action: Action<FFmpegSpec>) {
        action.execute(ffmpeg)
    }
}

/** FFmpeg provisioning options. */
abstract class FFmpegSpec {

    /** FFmpeg release to provision, for example `n8.0`. Defaults to the version this plugin ships for. */
    abstract val version: Property<String>

    /** Where FFmpeg comes from. Defaults to [FFmpegSource.Prebuilt]. */
    abstract val source: Property<FFmpegSource>

    /**
     * Licence flavour for desktop targets. Defaults to [FFmpegLicense.LGPL]. Android targets always
     * use the LGPL MediaCodec build regardless of this value.
     */
    abstract val license: Property<FFmpegLicense>

    /** GitHub `owner/repo` whose Releases host the prebuilt binaries. Defaults to KiteCodec's. */
    abstract val repo: Property<String>
}
