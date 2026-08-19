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
 *     cleanCacheOnClean = true         // optional: `clean` also drops the downloaded FFmpeg cache
 *     ffmpeg {
 *         version = "n8.0"
 *         source  = FFmpegSource.Prebuilt
 *         license = FFmpegLicense.LGPL // mandatory: the build fails without an explicit choice
 *         dav1d   = true               // contract: must MATCH what the FFmpeg tree carries
 *     }
 * }
 * ```
 */
abstract class KiteCodecExtension @Inject constructor(objects: ObjectFactory) {

    val ffmpeg: FFmpegSpec = objects.newInstance(FFmpegSpec::class.java)

    /**
     * When true, this project's `clean` also runs [the `kitecodecCleanCache` task], dropping every
     * FFmpeg archive the plugin has downloaded and unpacked into the shared Gradle cache
     * (`<gradle-user-home>/caches/kitecodec`). Default false, because that cache is shared by
     * every project on the machine and an ordinary `clean` should not force re-downloads.
     *
     * `ffmpeg.localRoot` is NEVER touched either way: that tree belongs to the user, the plugin
     * only reads it, and a plugin must not delete what it did not create.
     */
    abstract val cleanCacheOnClean: Property<Boolean>

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
     * States whether this build carries FFmpeg's libdav1d AV1 software decoder (default false).
     *
     * THIS IS A CONTRACT, NOT A SWITCH, and it is enforced in BOTH directions since 0.0.11.
     * dav1d is compiled into `libavcodec` when FFmpeg itself is built, so a consumer's link
     * cannot add it to a tree without it, and cannot subtract it from a tree that carries it.
     * What the toggle does is refuse a mismatch loudly at configuration time:
     *
     * - `true` against a tree WITHOUT dav1d fails, naming the build task that produces one.
     * - `false` (or unset) against a tree WITH dav1d fails, because linking a decoder the build
     *   script says it does not want is a silent lie; the fix is one line either way.
     * - A match links (or omits) `-ldav1d` accordingly.
     *
     * [FFmpegSource.Prebuilt] has no dav1d flavour published yet and says so; [FFmpegSource.System]
     * ignores the toggle, because a system FFmpeg decides its own decoders. dav1d stays an OPTIONAL
     * library by owner decision D-7: a tree built without it ships not one extra byte.
     */
    abstract val dav1d: Property<Boolean>

    /**
     * Opt into linking the libass rendering chain (libass, harfbuzz, freetype, fribidi) from
     * the local tree's `deps/<target>/ass-chain` installs, which KiteCodec's
     * `:kitecodec-core:buildAssChainFor<Target>` tasks produce (default false). This is what
     * the OPTIONAL `kiteplayer-libass` module links against; without the toggle not one chain
     * byte reaches a binary. Requires [FFmpegSource.Local]; the chain has no prebuilt or
     * system flavour.
     */
    abstract val libass: Property<Boolean>

    /**
     * Pinned SHA-256 checksums, keyed by Release asset name (for example
     * `"ffmpeg-n8.0-lgpl-macos-arm64.zip"`). When an asset has a pinned value it is authoritative:
     * the `.sha256` file published next to the asset is ignored, and a download that does not match
     * fails the build. Assets without a pinned value fall back to the published `.sha256`.
     */
    abstract val pinnedSha256: MapProperty<String, String>
}
