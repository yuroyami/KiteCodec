package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

internal const val DEFAULT_FFMPEG_VERSION = "n8.0"
internal const val DEFAULT_RELEASE_REPO = "yuroyami/KiteCodec"
private const val IOS_GPL_REFUSAL =
    "iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL."
private val IOS_TARGETS = setOf(
    KiteCodecTarget.IosArm64,
    KiteCodecTarget.IosSimulatorArm64,
    KiteCodecTarget.IosX64,
)
private val REQUIRED_LOCAL_ARCHIVES = listOf(
    "libavcodec.a",
    "libavformat.a",
    "libavutil.a",
    "libavfilter.a",
    "libswscale.a",
    "libswresample.a",
)

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
        ext.ffmpeg.dav1d.convention(false)
        ext.ffmpeg.libass.convention(false)
        // license has NO convention on purpose: the flavor decides the consumer's legal obligations,
        // so they must pick one themselves. Validated in validateLicenseChoice() after evaluation.
        ext.ffmpeg.repo.convention(DEFAULT_RELEASE_REPO)
        // Every KiteCodec release ships its full prebuilt set on its own version tag, so the
        // plugin's own version names the tag its assets live under (generated constant).
        ext.ffmpeg.releaseTag.convention("v$KITECODEC_PLUGIN_VERSION")
        ext.ffmpeg.pinnedSha256.convention(emptyMap())
        ext.cleanCacheOnClean.convention(false)

        // The clean lifecycle. `clean` wipes build/, but nothing ever wiped what this plugin
        // GRABBED: the downloaded FFmpeg archives live in the shared Gradle cache and outlived
        // every project clean invisibly. The task below is the visible handle; the extension
        // property hooks it into `clean` for consumers who want a cleared project to mean
        // cleared provisioning too. `ffmpeg.localRoot` is deliberately out of reach: the plugin
        // only reads that tree and must not delete what it did not create.
        val cleanCache = project.tasks.register("kitecodecCleanCache", Delete::class.java) { task ->
            task.group = "kitecodec"
            task.description =
                "Deletes every FFmpeg archive this plugin downloaded and unpacked " +
                    "(<gradle-user-home>/caches/kitecodec). Never touches ffmpeg.localRoot."
            task.delete(project.gradle.gradleUserHomeDir.resolve("caches/kitecodec"))
        }
        val cleanCacheOnClean = ext.cleanCacheOnClean
        project.tasks.matching { it.name == "clean" }.configureEach { clean ->
            // A provider over the captured Property, so the value is read after the consumer's
            // kitecodec { } block ran and no extension object rides into the task graph.
            clean.dependsOn(
                project.provider {
                    if (cleanCacheOnClean.get()) listOf(cleanCache) else emptyList()
                },
            )
        }

        // What gets grabbed, built against and linked, per target, on demand. The provisioning
        // decisions all happen at configuration time across lazy providers, which makes them
        // invisible; this prints them as one line per target so a consumer can SEE the answer
        // instead of reverse-engineering it from link failures.
        val infoLines = mutableListOf<Provider<String>>()
        project.tasks.register("kitecodecInfo") { task ->
            task.group = "kitecodec"
            task.description = "Prints the FFmpeg provisioning per wired Kotlin/Native target."
            task.doLast {
                if (infoLines.isEmpty()) {
                    println("kitecodec: no Kotlin/Native target this plugin recognises is wired.")
                }
                infoLines.forEach { line -> println(line.get()) }
            }
        }

        val wiredTriples = mutableSetOf<KiteCodecTarget>()
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { knTarget ->
                val triple = KiteCodecTarget.forKonan(knTarget.konanTarget.name) ?: return@configureEach
                wiredTriples += triple
                wireTarget(project, ext, knTarget, triple, infoLines)
            }
        }

        project.afterEvaluate {
            // Register item B1-03, and it runs FIRST. A consumer who asked for the wrong FFmpeg release
            // has a problem that makes every later message misleading: the licence and prebuilt-asset
            // checks below would talk about assets for a release these artifacts cannot use.
            validateFFmpegVersion(ext)
            validateLicenseChoice(project, ext, wiredTriples.filterNot { it.android }.toSet())
            validateLocalTrees(ext, wiredTriples)
            validatePrebuiltAvailability(ext, wiredTriples)
            validateSystemFFmpegMajors(project, ext)
        }
    }

    /** Validates every wired Local tree at configuration time without registering network work. */
    private fun validateLocalTrees(ext: KiteCodecExtension, wiredTriples: Set<KiteCodecTarget>) {
        if (ext.ffmpeg.source.get() != FFmpegSource.Local) return
        if (!ext.ffmpeg.localRoot.isPresent) {
            throw GradleException(
                "kitecodec: source = FFmpegSource.Local requires ffmpeg.localRoot. Expected " +
                    "<localRoot>/<license.id>/<target-triple>/{include,lib}.",
            )
        }

        val nonAndroidLicense = ext.ffmpeg.license.orNull
        val gplIos = wiredTriples.filter { it in IOS_TARGETS && nonAndroidLicense == FFmpegLicense.GPL }
        if (gplIos.isNotEmpty()) {
            throw GradleException(
                "$IOS_GPL_REFUSAL Local targets: " +
                    gplIos.joinToString { it.triple } +
                    ". Mobile Apple uses the LGPL standard software-playback profile.",
            )
        }

        val root = ext.ffmpeg.localRoot.asFile.get()
        val incomplete = wiredTriples.sortedBy { it.triple }.mapNotNull { triple ->
            val license = if (triple.android) FFmpegLicense.LGPL else requireNotNull(nonAndroidLicense)
            val tree = root.resolve("${license.id}/${triple.triple}")
            val missing = buildList {
                if (!tree.resolve("include/libavformat/avformat.h").isFile) {
                    add("include/libavformat/avformat.h")
                }
                REQUIRED_LOCAL_ARCHIVES.forEach { archive ->
                    if (!tree.resolve("lib/$archive").isFile) add("lib/$archive")
                }
            }
            missing.takeIf { it.isNotEmpty() }?.let {
                "  ${triple.triple}: $tree is missing ${it.joinToString()}"
            }
        }
        if (incomplete.isNotEmpty()) {
            throw GradleException(
                "kitecodec: Local FFmpeg tree is incomplete. Expected only " +
                    "<localRoot>/<license.id>/<target-triple>/{include,lib}:\n" +
                    incomplete.joinToString("\n") +
                    "\nBuild each missing target tree first or point ffmpeg.localRoot at a complete tree.",
            )
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
                |            // LGPL: closed-source-friendly. No x264/x265; desktop VideoToolbox
                |            //       and software stack, Android MediaCodec, iOS software playback.
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
            |  - source = FFmpegSource.Local uses a complete no-network tree at
            |    localRoot/<license>/<target>.
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
        infoLines: MutableList<Provider<String>>,
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

        // The asset carries the dav1d flavour in its NAME, because dav1d is compiled into
        // libavcodec and two trees that differ by it are genuinely different binaries. Publishing
        // one flavour only made `dav1d = true` unsatisfiable from a Release, which is precisely
        // what the contract check used to say: "no prebuilt dav1d flavour is published yet".
        val assetName = versionAndLicense.zip(ext.ffmpeg.dav1d) { (v, l), dav1d ->
            val flavour = if (dav1d) "-dav1d" else ""
            "ffmpeg-$v-${l.id}$flavour-${triple.triple}.zip"
        }
        val downloadUrl = ext.ffmpeg.repo.zip(ext.ffmpeg.releaseTag) { repo, tag ->
            "https://github.com/$repo/releases/download/$tag/"
        }.zip(assetName) { base, asset -> base + asset }
        // Keyed by flavour as well: without it, switching `dav1d` would silently reuse the other
        // flavour's unpacked tree and the link would contradict the build script.
        val cacheDir: Provider<File> = versionAndLicense.zip(ext.ffmpeg.dav1d) { (v, l), dav1d ->
            val flavour = if (dav1d) "dav1d" else "plain"
            gradleUserHome.resolve("caches/kitecodec/ffmpeg/$v/${l.id}/$flavour/${triple.triple}")
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

        val localTree: Provider<File> = ext.ffmpeg.localRoot.asFile.zip(license) { root, selected ->
            root.resolve("${selected.id}/${triple.triple}")
        }
        val libDir: Provider<File> = source.flatMap { src ->
            when (src) {
                FFmpegSource.Prebuilt -> cacheDir.map { it.resolve("lib") }

                FFmpegSource.Local -> localTree.map { it.resolve("lib") }

                FFmpegSource.System -> providers.provider {
                    systemLibDir(homebrewPrefix.orNull, triple)
                        ?: error(
                            "kitecodec: source = System but no system FFmpeg was found for ${triple.triple}. " +
                                "Install it (brew install ffmpeg / apt install the libav* dev packages), " +
                                "or switch to FFmpegSource.Prebuilt or FFmpegSource.Local.",
                        )
                }

                FFmpegSource.BuildFromSource -> providers.provider {
                    error(
                        "kitecodec: source = BuildFromSource is only available inside the KiteCodec " +
                            "checkout, which ships the :buildFFmpegFor<Target> tasks. In a consumer " +
                            "project use Prebuilt (the default), System or Local.",
                    )
                }
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
                // The dav1d contract, enforced BOTH ways (owner decision 2026-08-19, replacing
                // the earlier presence-is-truth rule). dav1d is compiled INTO libavcodec when
                // FFmpeg is built, so a consumer link can neither add nor subtract it; what the
                // toggle can do is refuse a mismatch loudly instead of silently linking a decoder
                // the build script says it does not want, or silently shipping without one it
                // says it wants. Silently linking is how Synkplay carried dav1d for two releases
                // without one line of its build saying so.
                val dav1dArchive = File(libDir.get(), "libdav1d.a")
                val dav1dRequested = ext.ffmpeg.dav1d.get()
                when {
                    source.get() == FFmpegSource.System -> if (dav1dRequested) {
                        project.logger.warn(
                            "kitecodec: ffmpeg.dav1d = true is ignored with FFmpegSource.System; " +
                                "a system FFmpeg decides its own decoders.",
                        )
                    }
                    dav1dRequested && !dav1dArchive.exists() -> when (source.get()) {
                        FFmpegSource.Prebuilt -> error(
                            "kitecodec: ffmpeg.dav1d = true, but the downloaded tree carries no " +
                                "libdav1d.a. A dav1d flavour IS published for the triples this " +
                                "project releases; ${triple.triple} is either not one of them or " +
                                "its asset predates the dav1d flavour. Check the release assets, " +
                                "or use FFmpegSource.Local with a locally built dav1d tree.",
                        )
                        else -> error(
                            "kitecodec: ffmpeg.dav1d = true, but ${dav1dArchive} does not exist. " +
                                "Rebuild the local tree with -Pkitecodec.ffmpeg.dav1d=true " +
                                "(after buildDav1dFor<Target>), or turn the toggle off.",
                        )
                    }
                    !dav1dRequested && dav1dArchive.exists() -> error(
                        "kitecodec: this FFmpeg tree carries dav1d (${dav1dArchive}) but " +
                            "ffmpeg.dav1d is not set to true. dav1d is compiled into libavcodec " +
                            "when FFmpeg itself is built, so it cannot be dropped at link time. " +
                            "Either state `kitecodec { ffmpeg { dav1d = true } }`, or point " +
                            "ffmpeg.localRoot at a tree built without dav1d.",
                    )
                    dav1dRequested -> binary.linkerOpts("-ldav1d")
                }
                // The libass chain toggle (phase L, decision D-7): links the OPTIONAL rendering
                // chain from the local tree's deps installs. Local-only by construction; the
                // check makes a missing chain a sentence, not a page of undefined symbols.
                if (ext.ffmpeg.libass.get()) {
                    check(source.get() == FFmpegSource.Local) {
                        "kitecodec: ffmpeg.libass = true requires FFmpegSource.Local; the chain " +
                            "has no prebuilt or system flavour."
                    }
                    val chainLib = ext.ffmpeg.localRoot.asFile.get()
                        .resolve("deps/${triple.triple}/ass-chain/lib")
                    check(chainLib.resolve("libass.a").isFile) {
                        "kitecodec: ffmpeg.libass = true, but ${chainLib}/libass.a does not exist. " +
                            "Run KiteCodec's :kitecodec-core:buildAssChainFor<Target> first."
                    }
                    binary.linkerOpts(
                        "-L${chainLib.absolutePath}",
                        "-lass", "-lharfbuzz", "-lfreetype", "-lfribidi", "-lz", "-lc++",
                    )
                    if (triple in IOS_TARGETS || triple == KiteCodecTarget.MacosArm64 || triple == KiteCodecTarget.MacosX64) {
                        // CoreText is the chain's font provider on Apple; Android provides
                        // fonts at runtime and needs no framework.
                        binary.linkerOpts(
                            "-liconv",
                            "-framework", "CoreText",
                            "-framework", "CoreFoundation",
                            "-framework", "CoreGraphics",
                        )
                    }
                }
                // One flag list per triple, whatever the tree's origin: since the portable
                // profiles a Local tree and a Prebuilt zip are the same shape, and the flags name
                // only platform libraries and frameworks (the klib's .def only names libav*).
                if (source.get() == FFmpegSource.Local || source.get() == FFmpegSource.Prebuilt) {
                    binary.linkerOpts(PrebuiltLinkFlags.extraLinkerOpts(triple))
                }
            }
        }

        // One report line for kitecodecInfo, composed lazily so it reads the consumer's final
        // DSL values. Everything in it is a plain value or a captured provider; the resolution
        // that can fail (a System source with no FFmpeg found) is folded into text instead of
        // thrown, because a report task must never be the thing that breaks the build.
        val versionProp = ext.ffmpeg.version
        val dav1dProp = ext.ffmpeg.dav1d
        val libassProp = ext.ffmpeg.libass
        infoLines += project.providers.provider {
            val src = source.get()
            val lic = license.orNull?.id ?: "(license unset)"
            val from = when (src) {
                FFmpegSource.Prebuilt -> downloadUrl.get()
                FFmpegSource.Local -> runCatching { libDir.get().absolutePath }
                    .getOrElse { "(localRoot unset)" }
                FFmpegSource.System -> runCatching { libDir.get().absolutePath }
                    .getOrElse { "(no system FFmpeg found)" }
                FFmpegSource.BuildFromSource -> "(built inside the KiteCodec checkout)"
            }
            val dav1d = when {
                src == FFmpegSource.System -> "system FFmpeg decides"
                dav1dProp.get() -> "true (links -ldav1d)"
                else -> "false"
            }
            "${triple.triple}: source=$src license=$lic version=${versionProp.get()} " +
                "dav1d=$dav1d libass=${libassProp.get()} from=$from"
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
