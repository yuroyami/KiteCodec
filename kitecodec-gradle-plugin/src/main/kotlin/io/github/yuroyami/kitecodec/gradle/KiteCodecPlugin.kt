package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

internal const val DEFAULT_FFMPEG_VERSION = "n8.0"
internal const val DEFAULT_RELEASE_REPO = "yuroyami/KiteCodec"

/**
 * Provisions the FFmpeg binaries KiteCodec links against, so consumers do not have to build FFmpeg
 * from source. Apply it alongside the Kotlin Multiplatform plugin: for every Kotlin/Native target it
 * fetches (or locates) the matching FFmpeg build and adds the `-L<libdir>` linker flag, wiring the
 * fetch task in ahead of the link step.
 *
 * KiteCodec's published klib contains no FFmpeg bytes; this plugin supplies them at the consumer's
 * build time, which also keeps the FFmpeg licence (LGPL / GPL) cleanly separate from KiteCodec's own
 * Apache-2.0 artifact.
 */
class KiteCodecPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("kitecodec", KiteCodecExtension::class.java)
        ext.ffmpeg.version.convention(DEFAULT_FFMPEG_VERSION)
        ext.ffmpeg.source.convention(FFmpegSource.Prebuilt)
        // license has NO convention on purpose: the flavour decides the consumer's legal obligations,
        // so they must pick one themselves. Validated in validateLicenseChoice() after evaluation.
        ext.ffmpeg.repo.convention(DEFAULT_RELEASE_REPO)
        ext.ffmpeg.pinnedSha256.convention(emptyMap())

        val wiredTriples = mutableSetOf<KiteCodecTarget>()
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { knTarget ->
                val triple = KiteCodecTarget.forKonan(knTarget.konanTarget.name) ?: return@configureEach
                wiredTriples += triple
                wireTarget(project, ext, knTarget, triple)
            }
        }

        project.afterEvaluate {
            validateLicenseChoice(project, ext, wiredTriples.filterNot { it.android }.toSet())
            validatePrebuiltAvailability(ext, wiredTriples)
        }
    }

    /**
     * The `kitecodec { ffmpeg { license = ... } }` choice is mandatory whenever a target other than
     * Android is wired (Android is always the LGPL MediaCodec build, so a purely-Android project has
     * nothing to choose). Failing at configuration time with instructions beats the opaque
     * "provider has no value" error the unset [Provider] would otherwise produce mid-build — and it
     * forces the consumer to make the licence decision consciously rather than inherit a default.
     */
    private fun validateLicenseChoice(
        project: Project,
        ext: KiteCodecExtension,
        nonAndroidTriples: Set<KiteCodecTarget>,
    ) {
        if (nonAndroidTriples.isEmpty()) return

        if (!ext.ffmpeg.license.isPresent) {
            throw GradleException(
                """
                |kitecodec: no FFmpeg licence flavour selected.
                |
                |The FFmpeg flavour you link decides your app's legal obligations, so KiteCodec does
                |not choose one for you. Add the block below to your build script:
                |
                |    kitecodec {
                |        ffmpeg {
                |            license = FFmpegLicense.LGPL
                |            // LGPL: closed-source-friendly. No x264/x265; hardware encoders
                |            //       (VideoToolbox/MediaCodec) + svtav1/opus/mp3lame instead.
                |            // GPL:  adds x264/x265, but your ENTIRE app becomes GPL-3.0 —
                |            //       you must open-source it if you distribute it.
                |        }
                |    }
                |
                |Details: https://yuroyami.github.io/KiteCodec/licensing/
                |(Targets needing the choice: ${nonAndroidTriples.joinToString { it.triple }})
                """.trimMargin(),
            )
        }

        if (ext.ffmpeg.license.get() == FFmpegLicense.GPL) {
            project.logger.warn(
                """
                |kitecodec: GPL FFmpeg flavour selected (x264/x265 enabled).
                |WARNING: linking GPL FFmpeg makes your WHOLE application GPL-3.0. If you distribute
                |the app, its complete source code must be available under a GPL-compatible licence —
                |no closed-source, proprietary, or App Store distribution. Server-side and internal
                |use is fine (GPL obligations trigger on distribution).
                |If that is not what you want, switch to FFmpegLicense.LGPL.
                |Details: https://yuroyami.github.io/KiteCodec/licensing/
                """.trimMargin(),
            )
        }
    }

    /**
     * KiteCodec v0.1 publishes prebuilt FFmpeg assets only for [KiteCodecTarget.hasPrebuiltAsset]
     * triples. When the consumer keeps the [FFmpegSource.Prebuilt] default (its convention) against
     * KiteCodec's own release repo and wires a target with no asset, the fetch would only fail with
     * an HTTP 404 mid-build — fail configuration instead, with the actual options. A custom `repo`
     * is exempt: self-hosting assets for extra triples is one of those options.
     */
    private fun validatePrebuiltAvailability(
        ext: KiteCodecExtension,
        wiredTriples: Set<KiteCodecTarget>,
    ) {
        // Conventions make these .get() calls safe after evaluation.
        if (ext.ffmpeg.source.get() != FFmpegSource.Prebuilt) return
        if (ext.ffmpeg.repo.get() != DEFAULT_RELEASE_REPO) return

        val unavailable = wiredTriples.filterNot { it.hasPrebuiltAsset }
        if (unavailable.isEmpty()) return

        throw GradleException(
            """
            |kitecodec: ${unavailable.joinToString { it.triple }} ${if (unavailable.size == 1) "has" else "have"} no prebuilt FFmpeg asset in KiteCodec v0.1 yet.
            |
            |FFmpegSource.Prebuilt (the default) downloads static FFmpeg builds from KiteCodec's
            |GitHub Releases, which currently cover: ${KiteCodecTarget.entries.filter { it.hasPrebuiltAsset }.joinToString { it.triple }}.
            |
            |Options:
            |  - source = FFmpegSource.System — link a system FFmpeg (brew/apt) where one exists
            |    for the target (desktop hosts only).
            |  - Self-host the asset: build FFmpeg for the target yourself, publish
            |    ffmpeg-<version>-<license>-<triple>.zip to your own repo's Releases, then set
            |    kitecodec { ffmpeg { repo = "you/yourrepo" } } and pin its checksum via
            |    pinnedSha256.put("<asset>.zip", "<sha256>").
            |  - Drop the target for now — prebuilt coverage grows in later KiteCodec releases.
            """.trimMargin(),
        )
    }

    /**
     * Runs while the consumer's `kotlin { }` block executes — i.e. potentially BEFORE their
     * `kitecodec { }` block. Everything derived from the extension therefore stays a [Provider]
     * and is only resolved once the link tasks are realised (after the build script has finished),
     * so the DSL values the consumer configured are always the ones that take effect.
     */
    private fun wireTarget(
        project: Project,
        ext: KiteCodecExtension,
        knTarget: KotlinNativeTarget,
        triple: KiteCodecTarget,
    ) {
        val providers = project.providers
        // Plain values captured at configuration time — configuration-cache safe (no Project in lambdas).
        val gradleUserHome = project.gradle.gradleUserHomeDir
        val isOffline = project.gradle.startParameter.isOffline
        val homebrewPrefix = providers.gradleProperty("kitecodec.macos.homebrew.prefix")

        val source = ext.ffmpeg.source
        // Android has no GPL flavour, so its targets always link the LGPL MediaCodec build.
        val license: Provider<FFmpegLicense> =
            if (triple.android) providers.provider { FFmpegLicense.LGPL } else ext.ffmpeg.license
        val versionAndLicense = ext.ffmpeg.version.zip(license) { v, l -> v to l }

        val assetName = versionAndLicense.map { (v, l) -> "ffmpeg-$v-${l.id}-${triple.triple}.zip" }
        val downloadUrl = ext.ffmpeg.repo.zip(versionAndLicense) { repo, (v, _) ->
            "https://github.com/$repo/releases/download/ffmpeg-$v/"
        }.zip(assetName) { base, asset -> base + asset }
        val cacheDir: Provider<File> = versionAndLicense.map { (v, l) ->
            gradleUserHome.resolve("caches/kitecodec/ffmpeg/$v/${l.id}/${triple.triple}")
        }

        val fetch = project.tasks.register(
            "fetchFFmpeg${triple.name}",
            FetchFFmpegTask::class.java,
        ) { task ->
            task.downloadUrl.set(downloadUrl)
            task.sha256Url.set(downloadUrl.map { "$it.sha256" })
            task.expectedSha256.set(assetName.flatMap { asset -> ext.ffmpeg.pinnedSha256.getting(asset) })
            task.allowUnverified.set(
                providers.gradleProperty("kitecodec.ffmpeg.allowUnverified")
                    .map(String::toBoolean)
                    .orElse(false),
            )
            task.offline.set(isOffline)
            task.destDir.set(project.layout.dir(cacheDir))
            task.onlyIf("kitecodec.ffmpeg.source is Prebuilt") { source.get() == FFmpegSource.Prebuilt }
        }

        val libDir: Provider<File> = source.zip(cacheDir) { src, cache ->
            when (src) {
                FFmpegSource.Prebuilt -> cache.resolve("lib")

                FFmpegSource.System -> systemLibDir(homebrewPrefix.orNull, triple)
                    ?: error(
                        "kitecodec: source = System but no system FFmpeg was found for ${triple.triple}. " +
                            "Install it (brew install ffmpeg / apt install the libav* dev packages) or " +
                            "switch to FFmpegSource.Prebuilt.",
                    )

                FFmpegSource.BuildFromSource -> error(
                    "kitecodec: source = BuildFromSource is only available inside the KiteCodec checkout, " +
                        "which ships the :buildFFmpegFor<Target> tasks. In a consumer project use Prebuilt " +
                        "(the default) or System.",
                )
            }
        }

        knTarget.binaries.all { binary ->
            // Realised lazily, after the consumer's build script (and its kitecodec { } block) has
            // run, so resolving the providers here observes the final DSL values. This also keeps
            // the -L flag configuration-cache safe: the value is fixed at configuration time and
            // serialised with the task, with no Project reference captured in any task action.
            binary.linkTaskProvider.configure { link ->
                if (source.get() == FFmpegSource.Prebuilt) {
                    // The final native link for this target must wait for the binaries to be present.
                    link.dependsOn(fetch)
                }
                binary.linkerOpts("-L${libDir.get().absolutePath}")
                if (source.get() == FFmpegSource.Prebuilt) {
                    // Desktop prebuilt zips bundle the third-party static encoder/text libs; the
                    // final link must name them explicitly (the klib's .def only names libav*).
                    binary.linkerOpts(PrebuiltLinkFlags.extraLinkerOpts(triple, license.get()))
                }
            }
        }
    }

    /**
     * Minimal system-FFmpeg resolution for dev convenience (macOS Homebrew / Linux).
     *
     * Only ever answers for the HOST's own target. These paths are matched by existence, not by
     * architecture: without the [hostTriple] gate, a consumer building `macosX64` on an Apple
     * silicon Mac would be handed the arm64 Homebrew libraries, and `linuxArm64` on an x64 box the
     * x86_64 ones. Cross targets must use [FFmpegSource.Prebuilt].
     */
    private fun systemLibDir(homebrewPrefix: String?, triple: KiteCodecTarget): File? {
        if (triple != hostTriple()) return null
        return when (triple) {
            KiteCodecTarget.MacosArm64, KiteCodecTarget.MacosX64 -> {
                sequenceOf(homebrewPrefix, "/opt/homebrew", "/usr/local")
                    .filterNotNull()
                    .map(::File)
                    .firstOrNull { it.resolve("include/libavformat/avformat.h").exists() }
                    ?.resolve("lib")
            }
            KiteCodecTarget.LinuxX64, KiteCodecTarget.LinuxArm64 -> {
                // Multiarch dir for THIS host first — /usr/lib may hold a foreign-arch copy.
                val multiarch = if (triple == KiteCodecTarget.LinuxArm64) {
                    "/usr/lib/aarch64-linux-gnu"
                } else {
                    "/usr/lib/x86_64-linux-gnu"
                }
                sequenceOf(multiarch, "/usr/lib", "/usr/local/lib")
                    .map(::File)
                    .firstOrNull { it.resolve("libavformat.so").exists() }
            }
            else -> null
        }
    }

    /** The [KiteCodecTarget] this build is running on, or null on a platform with no mapping. */
    private fun hostTriple(): KiteCodecTarget? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val isArm64 = arch in setOf("aarch64", "arm64")
        val isX64 = arch in setOf("amd64", "x86_64")
        return when {
            "mac" in os && isArm64 -> KiteCodecTarget.MacosArm64
            "mac" in os && isX64 -> KiteCodecTarget.MacosX64
            "linux" in os && isX64 -> KiteCodecTarget.LinuxX64
            "linux" in os && isArm64 -> KiteCodecTarget.LinuxArm64
            else -> null
        }
    }
}
