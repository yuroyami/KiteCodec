package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
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
 *         license = FFmpegLicense.LGPL // mandatory: the build fails without an explicit choice
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
     * Licence flavour for desktop targets. **No default**: the flavour decides the consumer's
     * legal obligations, so it must be set explicitly; the plugin fails the build otherwise
     * (unless every wired target is Android, which always uses the LGPL MediaCodec build
     * regardless of this value). Selecting [FFmpegLicense.GPL] logs a warning describing the
     * GPL-3.0 obligations it places on the whole application.
     */
    abstract val license: Property<FFmpegLicense>

    /** GitHub `owner/repo` whose Releases host the prebuilt binaries. Defaults to KiteCodec's. */
    abstract val repo: Property<String>

    /**
     * Pinned SHA-256 checksums, keyed by Release asset name (for example
     * `"ffmpeg-n8.0-lgpl-macos-arm64.zip"`). When an asset has a pinned value it is authoritative:
     * the `.sha256` file published next to the asset is ignored, and a download that does not match
     * fails the build. Assets without a pinned value fall back to the published `.sha256`.
     */
    abstract val pinnedSha256: MapProperty<String, String>
}
