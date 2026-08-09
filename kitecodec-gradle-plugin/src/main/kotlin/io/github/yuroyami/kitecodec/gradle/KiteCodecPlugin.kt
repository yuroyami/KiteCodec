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
 * build time, which also keeps the FFmpeg license (LGPL / GPL) cleanly separate from KiteCodec's own
 * Apache-2.0 artifact.
 */
class KiteCodecPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("kitecodec", KiteCodecExtension::class.java)
        ext.ffmpeg.version.convention(DEFAULT_FFMPEG_VERSION)
        ext.ffmpeg.source.convention(FFmpegSource.Prebuilt)
        // license has NO convention on purpose: the flavor decides the consumer's legal obligations,
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
            // Register item B1-03, and it runs FIRST. A consumer who asked for the wrong FFmpeg release
            // has a problem that makes every later message misleading: the licence and prebuilt-asset
            // checks below would talk about assets for a release these artifacts cannot use.
            validateFFmpegVersion(ext)
            validateLicenseChoice(project, ext, wiredTriples.filterNot { it.android }.toSet())
            validatePrebuiltAvailability(ext, wiredTriples)
            validateSystemFFmpegMajors(project, ext)
        }
    }

    /**
     * Refuses a `ffmpeg { version = ... }` that these KiteCodec artifacts were not compiled against.
     *
     * Register item B1-03. See [FFmpegExpectations] for why a successful link is the dangerous outcome
     * here rather than the safe one, and why this check and the runtime identity gate are both needed.
     */
    private fun validateFFmpegVersion(ext: KiteCodecExtension) {
        // Conventions make these .get() calls safe after evaluation.
        val message = FFmpegExpectations.versionMismatchMessage(
            ext.ffmpeg.version.get(),
            ext.ffmpeg.source.get(),
        ) ?: return
        throw GradleException(message)
    }

    /**
     * With `source = FFmpegSource.System`, compares the system FFmpeg's majors with what these artifacts
     * were compiled against, and fails configuration when they differ.
     *
     * **It reads the system FFmpeg's own version headers, and not `pkg-config --modversion`.** The plan
     * that specified this check named pkg-config, and pkg-config does answer correctly for all six
     * libraries on the proving machine, but running it here is not possible: measured on this repository,
     * a KitePlayer build fails with "Starting an external process 'pkg-config --modversion libavutil'
     * during configuration time is unsupported" and the configuration cache entry is discarded with 12
     * problems. `afterEvaluate` is still configuration time, and how the process is started makes no
     * difference to that rule.
     *
     * Reading the version headers under the resolved include directory is better anyway, for a reason
     * that has nothing to do with the cache. Those headers are the ones the consumer's cinterop would
     * compile against, so they
     * are the thing this check is actually about; pkg-config reports metadata beside them, which is one
     * indirection further from the question. The read goes through `providers.fileContents`, so it is a
     * tracked configuration input: a consumer who upgrades their system FFmpeg gets the check re-run
     * rather than a stale cached pass.
     *
     * A prefix this plugin cannot resolve, or headers it cannot read, means no opinion and no failure;
     * see [FFmpegExpectations.systemMajorMismatchMessage] for why silence there is deliberate.
     */
    private fun validateSystemFFmpegMajors(project: Project, ext: KiteCodecExtension) {
        if (ext.ffmpeg.source.get() != FFmpegSource.System) return
        val homebrewPrefix = project.providers
            .gradleProperty("kitecodec.macos.homebrew.prefix").orNull
        val includeDir = hostTriple()
            ?.let { systemLibDir(homebrewPrefix, it) }
            ?.parentFile
            ?.resolve("include")
            ?: return
        val versions = FFmpegExpectations.EXPECTED_MAJORS.keys
            .mapNotNull { library ->
                readHeaderVersion(project, includeDir, library)?.let { library to it }
            }
            .toMap()
        if (versions.isEmpty()) {
            project.logger.info(
                "kitecodec: no libav* version headers under $includeDir, so the system FFmpeg's major " +
                    "version was not checked here. The runtime identity gate still checks it, at first " +
                    "playback.",
            )
            return
        }
        val message = FFmpegExpectations.systemMajorMismatchMessage(versions) ?: return
        throw GradleException(message)
    }

    /**
     * `MAJOR.MINOR.MICRO` from `<includeDir>/<library>/version.h` plus `version_major.h`, or null.
     *
     * Both files, for the reason [FFmpegExpectations.readVersionFromHeaders] records: FFmpeg keeps the
     * MAJOR of every library except libavutil in its own `version_major.h`.
     *
     * `layout.file(provider { ... })` and not `layout.projectDirectory.file(path)`: these are absolute
     * paths outside the project, and the second form is documented as resolving its argument relative to
     * the directory. Going through a `Provider<RegularFile>` is the API for a `java.io.File` that is
     * already absolute, and it keeps the read a tracked configuration input.
     */
    private fun readHeaderVersion(project: Project, includeDir: File, library: String): String? {
        val text = listOf("version.h", "version_major.h")
            .mapNotNull { name ->
                val file = includeDir.resolve("$library/$name")
                project.providers
                    .fileContents(project.layout.file(project.provider { file }))
                    .asText
                    .orNull
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?: return null
        return FFmpegExpectations.readVersionFromHeaders(text, library)
    }

    /**
     * The `kitecodec { ffmpeg { license = ... } }` choice is mandatory whenever a target other than
     * Android is wired (Android is always the LGPL MediaCodec build, so a purely-Android project has
     * nothing to choose). Failing at configuration time with instructions beats the opaque
     * "provider has no value" error the unset [Provider] would otherwise produce mid-build, and it
     * forces the consumer to make the license decision consciously rather than inherit a default.
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
                |kitecodec: no FFmpeg license flavor selected.
                |
                |The FFmpeg flavor you link decides your app's legal obligations, so KiteCodec does
                |not choose one for you. Add the block below to your build script:
                |
                |    kitecodec {
                |        ffmpeg {
                |            license = FFmpegLicense.LGPL
                |            // LGPL: closed-source-friendly. No x264/x265; hardware encoders
                |            //       (VideoToolbox/MediaCodec) + svtav1/opus/mp3lame instead.
                |            // GPL:  adds x264/x265, but your entire app becomes GPL-3.0.
                |            //       You must open-source it if you distribute it.
                |        }
                |    }
                |
                |Details: https://github.com/yuroyami/KiteCodec/blob/main/docs/licensing.md
                |(Targets needing the choice: ${nonAndroidTriples.joinToString { it.triple }})
                """.trimMargin(),
            )
        }

        if (ext.ffmpeg.license.get() == FFmpegLicense.GPL) {
            project.logger.warn(
                """
                |kitecodec: GPL FFmpeg flavor selected (x264/x265 enabled).
                |Warning: linking GPL FFmpeg makes your whole application GPL-3.0. If you distribute
                |the app, its complete source code must be available under a GPL-compatible
                |license. That rules out closed-source, proprietary and App Store distribution.
                |Server-side and internal use is fine, because GPL obligations trigger on
                |distribution.
                |If that is not what you want, switch to FFmpegLicense.LGPL.
                |Details: https://github.com/yuroyami/KiteCodec/blob/main/docs/licensing.md
                """.trimMargin(),
            )
        }
    }

    /**
     * KiteCodec v0.1 publishes prebuilt FFmpeg assets only for [KiteCodecTarget.hasPrebuiltAsset]
     * triples. When the consumer keeps the [FFmpegSource.Prebuilt] default (its convention) against
     * KiteCodec's own release repo and wires a target with no asset, the fetch would only fail with
     * an HTTP 404 mid-build. Fail configuration instead, with the actual options. A custom `repo`
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
            |  - source = FFmpegSource.System links a system FFmpeg (brew/apt) where one exists
            |    for the target. Desktop hosts only.
            |  - Self-host the asset: build FFmpeg for the target yourself, publish
            |    ffmpeg-<version>-<license>-<triple>.zip to your own repo's Releases, then set
            |    kitecodec { ffmpeg { repo = "you/yourrepo" } } and pin its checksum via
            |    pinnedSha256.put("<asset>.zip", "<sha256>").
            |  - Drop the target for now. Prebuilt coverage grows in later KiteCodec releases.
            """.trimMargin(),
        )
    }

    /**
     * Runs while the consumer's `kotlin { }` block executes, which can be before their
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
        // Plain values captured at configuration time: configuration-cache safe (no Project in lambdas).
        val gradleUserHome = project.gradle.gradleUserHomeDir
        val isOffline = project.gradle.startParameter.isOffline
        val homebrewPrefix = providers.gradleProperty("kitecodec.macos.homebrew.prefix")

        val source = ext.ffmpeg.source
        // Android has no GPL flavor, so its targets always link the LGPL MediaCodec build.
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
                // Multiarch dir for this host first: /usr/lib may hold a foreign-arch copy.
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
