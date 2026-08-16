package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
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
 *         dav1d   = true               // optional: link the dav1d AV1 software decoder
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
     * Root of a no-network [FFmpegSource.Local] tree. Its fixed layout is
     * `<localRoot>/<license.id>/<target-triple>/{include,lib}`.
     */
    abstract val localRoot: DirectoryProperty

    /**
     * Licence flavour for non-Android targets. **No default**: the flavour decides the consumer's
     * legal obligations, so it must be set explicitly; the plugin fails the build otherwise
     * (unless every wired target is Android, which always uses the LGPL MediaCodec build
     * regardless of this value). Local iOS accepts only [FFmpegLicense.LGPL]. Selecting
     * [FFmpegLicense.GPL] elsewhere logs a warning describing the GPL-3.0 obligations it places
     * on the whole application.
     */
    abstract val license: Property<FFmpegLicense>

    /** GitHub `owner/repo` whose Releases host the prebuilt binaries. Defaults to KiteCodec's. */
    abstract val repo: Property<String>

    /**
     * Opt into FFmpeg's libdav1d AV1 software decoder (default false). dav1d is an OPTIONAL
     * native library by owner decision D-7: a build that never asks for it ships not one extra
     * byte. Asking for it requires an FFmpeg tree that was built with it, today meaning
     * [FFmpegSource.Local] pointed at a tree produced by KiteCodec's own
     * `:kitecodec-core:buildFFmpegFor<Target>` with `-Pkitecodec.ffmpeg.dav1d=true` (which
     * itself wants `buildDav1dFor<Target>` first). [FFmpegSource.Prebuilt] has no dav1d
     * flavour published yet and fails with exactly that message; [FFmpegSource.System] ignores
     * the toggle, because a system FFmpeg decides its own decoders.
     */
    abstract val dav1d: Property<Boolean>

    /**
     * Pinned SHA-256 checksums, keyed by Release asset name (for example
     * `"ffmpeg-n8.0-lgpl-macos-arm64.zip"`). When an asset has a pinned value it is authoritative:
     * the `.sha256` file published next to the asset is ignored, and a download that does not match
     * fails the build. Assets without a pinned value fall back to the published `.sha256`.
     */
    abstract val pinnedSha256: MapProperty<String, String>
}
