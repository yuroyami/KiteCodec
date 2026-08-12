import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import io.github.yuroyami.kitecodec.buildtools.BuildFFmpegTask
import io.github.yuroyami.kitecodec.buildtools.CompileKiteCodecCTask
import io.github.yuroyami.kitecodec.buildtools.FFmpegLicense
import io.github.yuroyami.kitecodec.buildtools.FFmpegPaths
import io.github.yuroyami.kitecodec.buildtools.StaticLinkFlags
import io.github.yuroyami.kitecodec.buildtools.TargetTriple
import io.github.yuroyami.kitecodec.buildtools.IOS_GPL_REFUSAL
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
// Named explicitly because inside a Gradle Kotlin script `java` resolves to the java extension,
// so `java.io.File(...)` does not compile.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(21)

    // Published library: every public declaration states its visibility and return type.
    explicitApi()

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xcontext-parameters",
        )
    }

    /*
     * v0.1 target tiers.
     *
     * STABLE: the targets with prebuilt FFmpeg Release assets and CI coverage; the ONLY targets
     * published in v0.1: macosArm64, linuxX64, androidNativeArm64/Arm32/X64.
     * EXPERIMENTAL: builds locally (given an FFmpeg tree) but is NOT part of the published set:
     * ios*, macosX64, linuxArm64, mingwX64.
     *
     *   -Pkitecodec.stableTargetsOnly=true  Register only the stable targets, so publications
     *                                        contain exactly the v0.1 support set. Default false:
     *                                        local dev and the CI matrix (incl. the Windows/mingw
     *                                        job) see every target.
     *   -Pkitecodec.hostTargetsOnly=true    Register only THIS host's own desktop target
     *                                        (macosArm64 on an arm64 Mac, linuxX64 on x64 Linux).
     *                                        Used exclusively by the CI consumer-e2e smoke job to
     *                                        publishToMavenLocal with just a system FFmpeg present.
     *                                        Mutually exclusive with every other target scope.
     *   -Pkitecodec.applePhoneTargetsOnly=true
     *                                        Register macosArm64, iosArm64 and
     *                                        iosSimulatorArm64 on an arm64 Mac. Local publication
     *                                        only; remote publication always refuses this scope.
     */
    val stableTargetsOnly = providers.gradleProperty("kitecodec.stableTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val hostTargetsOnly = providers.gradleProperty("kitecodec.hostTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val applePhoneTargetsOnly = providers.gradleProperty("kitecodec.applePhoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val selectedTargetScopes = listOf(
        "kitecodec.stableTargetsOnly" to stableTargetsOnly,
        "kitecodec.hostTargetsOnly" to hostTargetsOnly,
        "kitecodec.applePhoneTargetsOnly" to applePhoneTargetsOnly,
    ).filter { it.second }.map { it.first }
    if (selectedTargetScopes.size > 1) {
        throw GradleException(
            "KiteCodec target-set properties are mutually exclusive; selected: " +
                selectedTargetScopes.joinToString(),
        )
    }

    /*
     * Publish guard. Publishing kitecodec-core (anything whose task name starts with "publish",
     * except tasks addressed to :kitecodec-gradle-plugin, which publishes independently via
     * publishPlugins) requires BOTH:
     *   (a) -Pkitecodec.stableTargetsOnly=true, because experimental targets must not leak into
     *       publications, and
     *   (b) an FFmpeg tree for EVERY configured target, enforced below by treating a publish
     *       run as if kitecodec.requireAllTargets=true, so a publication can never silently
     *       drop a target.
     * Exceptions: publishToMavenLocal also accepts -Pkitecodec.hostTargetsOnly=true (the CI
     * consumer-e2e smoke path) or the arm64-Mac applePhoneTargetsOnly scope. Remote publishes
     * never accept either experimental scope.
     * Checked against gradle.startParameter.taskNames, which is simple and configuration-cache safe.
     */
    val corePublishTaskNames = gradle.startParameter.taskNames.filter { name ->
        val simpleName = name.substringAfterLast(':')
        simpleName.startsWith("publish") &&
            // Plugin Portal upload, which exists only in :kitecodec-gradle-plugin, never touches core.
            simpleName != "publishPlugins" &&
            !name.startsWith(":kitecodec-gradle-plugin:")
    }
    val corePublishRequested = corePublishTaskNames.isNotEmpty()
    val onlyLocalPublishes = corePublishRequested && corePublishTaskNames.all { it.contains("MavenLocal") }
    if (corePublishRequested && applePhoneTargetsOnly && !onlyLocalPublishes) {
        throw GradleException(
            "Experimental phone selector refusal: -Pkitecodec.applePhoneTargetsOnly=true may only " +
                "be used with publishToMavenLocal; remote publication is forbidden.",
        )
    }
    if (
        corePublishRequested &&
        !stableTargetsOnly &&
        !(hostTargetsOnly && onlyLocalPublishes) &&
        !(applePhoneTargetsOnly && onlyLocalPublishes)
    ) {
        throw GradleException(
            """
            |kitecodec-core: publishing requested (${corePublishTaskNames.joinToString()}) without the release target scope.
            |
            |Publishing kitecodec-core requires BOTH:
            |  1. -Pkitecodec.stableTargetsOnly=true
            |     v0.1 publishes exactly the stable target set (macosArm64, linuxX64,
            |     androidNativeArm64/Arm32/X64). Experimental targets (ios*, macosX64, linuxArm64,
            |     mingwX64) must not leak into publications.
            |  2. an FFmpeg tree present for EVERY configured target.
            |     While publishing, a missing FFmpeg build is a hard failure instead of the usual
            |     skip-with-warning (kitecodec.requireAllTargets is implied true), so a publication
            |     can never silently drop a target.
            |
            |Note: `./gradlew publishToMavenLocal` from the root hits this guard too because it
            |includes kitecodec-core's publications. Satisfy it the same way: pass
            |-Pkitecodec.stableTargetsOnly=true with all five stable FFmpeg trees present under
            |native-libs/lgpl/ (or system installs for the desktop ones). For a host-only local
            |smoke publish, -Pkitecodec.hostTargetsOnly=true is accepted for publishToMavenLocal.
            |On an arm64 Mac, -Pkitecodec.applePhoneTargetsOnly=true is accepted only for a local
            |macOS/iPhone/simulator proof and only with publishToMavenLocal.
            """.trimMargin(),
        )
    }

    // Every Kotlin/Native target gets the same single consolidated cinterop. Each resolves its
    // own FFmpeg install via FFmpegPaths.resolve(...): vendored static if available, else system.
    val knTargetMap: Map<KotlinNativeTarget, TargetTriple> = if (applePhoneTargetsOnly) {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        if ("mac" !in osName || osArch !in setOf("aarch64", "arm64")) {
            throw GradleException(
                "kitecodec.applePhoneTargetsOnly=true requires an arm64 Mac; found $osName/$osArch.",
            )
        }
        mapOf(
            macosArm64() to TargetTriple.MacosArm64,
            iosArm64() to TargetTriple.IosArm64,
            iosSimulatorArm64() to TargetTriple.IosSimulatorArm64,
        )
    } else if (hostTargetsOnly) {
        // Consumer-e2e smoke scope: just the host's own desktop target.
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        when {
            "mac" in osName && osArch == "aarch64" -> mapOf(macosArm64() to TargetTriple.MacosArm64)
            "mac" in osName -> mapOf(macosX64() to TargetTriple.MacosX64)
            "linux" in osName && osArch in setOf("amd64", "x86_64") -> mapOf(linuxX64() to TargetTriple.LinuxX64)
            "linux" in osName && osArch == "aarch64" -> mapOf(linuxArm64() to TargetTriple.LinuxArm64)
            else -> throw GradleException(
                "kitecodec.hostTargetsOnly=true has no desktop target mapping for host $osName/$osArch.",
            )
        }
    } else {
        buildMap {
            // v0.1 STABLE targets.
            put(macosArm64(), TargetTriple.MacosArm64)
            put(linuxX64(), TargetTriple.LinuxX64)
            // Android NDK targets (LGPL FFmpeg profile w/ MediaCodec, see BuildFFmpegTask).
            // Vendored-only: run :kitecodec-core:buildFFmpegForAndroid<Abi> first.
            put(androidNativeArm64(), TargetTriple.AndroidArm64)
            put(androidNativeArm32(), TargetTriple.AndroidArm32)
            put(androidNativeX64(), TargetTriple.AndroidX64)
            if (!stableTargetsOnly) {
                // EXPERIMENTAL targets (not published in v0.1).
                put(macosX64(), TargetTriple.MacosX64)
                put(iosArm64(), TargetTriple.IosArm64)
                put(iosSimulatorArm64(), TargetTriple.IosSimulatorArm64)
                put(iosX64(), TargetTriple.IosX64)
                put(linuxArm64(), TargetTriple.LinuxArm64)
                put(mingwX64(), TargetTriple.MingwX64)
            }
        }
    }

    val homebrewPrefix = providers.gradleProperty("kitecodec.macos.homebrew.prefix")
        .getOrElse(BuildFFmpegTask.DEFAULT_HOMEBREW_PREFIX)

    // Which FFmpeg flavour to link against locally: -Pkitecodec.ffmpeg.license=gpl for the GPL
    // build, else the LGPL default. Android always links its LGPL MediaCodec profile.
    val selectedLicense =
        if (providers.gradleProperty("kitecodec.ffmpeg.license").orNull?.equals("gpl", ignoreCase = true) == true) {
            FFmpegLicense.GPL
        } else {
            FFmpegLicense.LGPL
        }

    if (
        selectedLicense == FFmpegLicense.GPL &&
        knTargetMap.values.any {
            it == TargetTriple.IosArm64 ||
                it == TargetTriple.IosSimulatorArm64 ||
                it == TargetTriple.IosX64
        }
    ) {
        throw GradleException(IOS_GPL_REFUSAL)
    }

    // Targets whose FFmpeg is missing are skipped with a warning by default; releases/publishing
    // must not silently drop targets, so -Pkitecodec.requireAllTargets=true makes it fail instead.
    // A publish run implies it (see the publish guard above): every target the publication claims
    // must actually have compiled against a real FFmpeg.
    val requireAllTargets = providers.gradleProperty("kitecodec.requireAllTargets")
        .map { it.toBoolean() }.getOrElse(false) || corePublishRequested

    knTargetMap.forEach { (target, triple) ->
        val license = if (triple.isAndroid) FFmpegLicense.LGPL else selectedLicense
        val paths = try {
            FFmpegPaths.resolve(project, triple, license)
        } catch (e: GradleException) {
            if (requireAllTargets) throw e
            logger.lifecycle(
                "warning: [KiteCodec] SKIPPING FFmpeg cinterop/link setup for target '${triple.dirName}' " +
                    "(${license.dirName}) because no FFmpeg build found. ${e.message} " +
                    "Set -Pkitecodec.requireAllTargets=true to fail the build instead.",
            )
            null
        } ?: return@forEach

        /*
         * The FFmpeg helper layer, compiled for THIS target into its own directory and embedded in
         * the cinterop klib by ffmpeg.def's `staticLibraries = libkitecodec.a`. It sits after the
         * FFmpeg path resolution above on purpose: the helper units include 16 libav headers, so
         * a target with no FFmpeg tree cannot compile it and is skipped here exactly as it is
         * skipped for the cinterop.
         *
         * The output directory is keyed by the konan target name and shared with nothing, which is
         * register item B1-11: a wrong-architecture archive is embedded without complaint and fails
         * only at the consumer's final link.
         */
        val compileC = tasks.register<CompileKiteCodecCTask>("compileKiteCodecCFor${triple.gradleSuffix}") {
            konanTargetName.set(target.konanTarget.name)
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(rootDir.resolve("native/kitecodec-c/include"))
            ffmpegIncludeDirs.set(listOf(paths.includeDir))
            // The version headers the archive freezes, tracked by CONTENT (interlude item I-07):
            // a path string survives a brew upgrade that rewrites every file under it.
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { lib ->
                        listOf("version.h", "version_major.h").map { "${paths.includeDir}/$lib/$it" }
                    } + "${paths.includeDir}/libavutil/ffversion.h",
            )
            /*
             * What this archive was built for, read by the FFmpeg identity gate in
             * native/kitecodec-c/src/kitecodec_abi.c and reported in every rejection and every
             * diagnostic dump (register items B1-02 and B1-21). They are reported, never compared:
             * the comparison is between the six LIB*_VERSION_INT macros the same compile froze and
             * the six *_version() functions the linked runtime answers with. What these three add is
             * the other half of an actionable sentence, which is what the build decided to provision.
             *
             * The licence one is the one that matters most today. This build declares a flavour here
             * while the linked Homebrew runtime's avutil_license() returns "GPL version 3 or later",
             * so both strings ride in the report and the contradiction is visible instead of latent.
             */
            buildDefines.set(
                mapOf(
                    CompileKiteCodecCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_LICENSE to license.dirName,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_DIR to paths.libDir,
                ),
            )
            // java.io.File and not project.file(...): the latter captures a reference to this
            // script inside the provider, which the configuration cache refuses to serialize with
            // "cannot serialize Gradle script object references".
            konanDataDir.fileProvider(
                providers.environmentVariable("KONAN_DATA_DIR")
                    .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
                    .map { path -> File(path) },
            )
            outputDir.set(layout.buildDirectory.dir("kitecodec-c/${target.konanTarget.name}"))
        }

        target.compilations.getByName("main").cinterops {
            // One cinterop module for all six libav* libraries, which keeps AVCodec, AVFrame,
            // AVPacket etc. as a SINGLE Kotlin type across every binding (each cinterop module
            // otherwise generates its own duplicate copy of identical C types).
            create("ffmpeg") {
                defFile(project.file("src/nativeInterop/cinterop/ffmpeg.def"))
                includeDirs.allHeaders(paths.includeDir)
                extraOpts("-libraryPath", paths.libDir)
                compilerOpts("-I${paths.includeDir}")
                // The helper layer: its header for the `headers` entry, and its archive directory as
                // a SECOND -libraryPath beside FFmpeg's. Two independent -libraryPath entries
                // coexist. This is not a libraryPaths line in the def because a def-relative path
                // resolves against the Gradle project directory rather than against the def, and
                // because there is one archive directory per konan target.
                val cIncludeDir = rootDir.resolve("native/kitecodec-c/include")
                val cArchiveDir = layout.buildDirectory.dir("kitecodec-c/${target.konanTarget.name}")
                    .get().asFile
                includeDirs.allHeaders(cIncludeDir)
                compilerOpts("-I${cIncludeDir.absolutePath}")
                extraOpts("-libraryPath", cArchiveDir.absolutePath)
            }
        }
        /*
         * cinterop embeds the archive, so it has to exist first, AND the archive has to be a
         * declared input of the cinterop task.
         *
         * The dependency alone is not enough, and the plan's section 15.0 said otherwise on the
         * strength of a different prototype. Measured here at B1.3, in a checkout with no copied
         * Gradle state: editing only a helper source re-executes the C compile and writes a
         * new archive, and `cinteropFfmpegMacosArm64` then reports UP-TO-DATE and keeps the STALE
         * archive inside the klib, with or without the configuration cache. Gradle says why under
         * `--info`: "Caching disabled for task ':kitecodec-core:cinteropFfmpegMacosArm64' because:
         * CInterop task uses custom Up-To-Date check for content of headers instead of Gradle
         * mechanisms." That check covers the def file and the headers, not a library the def merely
         * names. A clean build and CI were always correct; local incremental development was not,
         * and every sub-phase from B1.4 onward edits C bodies.
         *
         * `inputs.files` on the archive fixes it: an input change makes a task out of date no
         * matter what its own predicate says. The missing-archive direction never needed this,
         * because cinterop fails loudly with a non-zero exit when `staticLibraries` cannot be
         * found.
         *
         * `matching { }.configureEach { }` rather than `named(...)`: the cinterop task is registered
         * by the Kotlin plugin after this block runs, and a filtered live collection covers tasks
         * added later while `named` would fail on a task that does not exist yet.
         */
        val cinteropTaskName = "cinteropFfmpeg${target.name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == cinteropTaskName }.configureEach {
            dependsOn(compileC)
            inputs.files(compileC.map { c -> c.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) })
                .withPropertyName("kiteCodecCArchive")
                .withPathSensitivity(PathSensitivity.NAME_ONLY)
        }
        target.binaries.all {
            linkerOpts("-L${paths.libDir}")
            // ffmpeg.def names only the six libav* archives. That is enough for a shared/system
            // FFmpeg, whose dylibs resolve their own dependencies, but a STATIC libavcodec.a
            // resolves nothing, so every third-party archive it draws symbols from must be named
            // here too or the final link fails on svt_av1_*, vpx_*, ass_* and friends.
            linkerOpts(StaticLinkFlags.forTarget(triple, license, paths.isStaticVendored))
            // Searched AFTER the vendored lib/, so a bundled archive always wins; this only
            // catches dependencies the host package manager ships shared-only (see StaticLinkFlags).
            linkerOpts(StaticLinkFlags.hostFallbackSearchFlags(triple, homebrewPrefix, paths.isStaticVendored))
            if (!paths.isStaticVendored && triple in setOf(TargetTriple.MacosArm64, TargetTriple.MacosX64)) {
                // Embed Homebrew rpath for dev convenience; release builds use static vendored libs.
                linkerOpts("-rpath", paths.libDir)
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Register the :buildFFmpegFor<Target>[Gpl] tasks. Users run these to populate
// native-libs/<license>/<target> with static .a files; subsequent Gradle syncs pick them up.
fun registerBuildFFmpeg(triple: TargetTriple, flavour: FFmpegLicense) =
    tasks.register<BuildFFmpegTask>("buildFFmpegFor${triple.gradleSuffix}${flavour.taskSuffix}") {
        target = triple
        license = flavour
        sourceRef = BuildFFmpegTask.DEFAULT_SOURCE_REF
        // Where the desktop macOS profile's third-party libs live. Several of them (lame above all)
        // ship no pkg-config file, so configure cannot find them without an explicit -I/-L.
        hostPrefix.set(
            providers.gradleProperty("kitecodec.macos.homebrew.prefix")
                .orElse(BuildFFmpegTask.DEFAULT_HOMEBREW_PREFIX),
        )
        // Release builds must produce a tree that links on a machine with none of these installed.
        requireSelfContained.set(
            providers.gradleProperty("kitecodec.ffmpeg.selfContained").map { it.toBoolean() }.orElse(false),
        )
        sourceDir.set(rootDir.resolve("vendor/ffmpeg"))
        outputDir.set(rootDir.resolve("native-libs/${flavour.dirName}/${triple.dirName}"))
    }

TargetTriple.entries.forEach { triple ->
    // LGPL flavour for every target (the default).
    registerBuildFFmpeg(triple, FFmpegLicense.LGPL)
    // GPL flavour (libx264 / libx265) for desktop targets only; Android and iOS are LGPL-only.
    if (
        !triple.isAndroid &&
        triple !in setOf(TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64)
    ) {
        registerBuildFFmpeg(triple, FFmpegLicense.GPL)
    }
}

tasks.register("buildFFmpegForAll") {
    group = "kitecodec"
    description = "Cross-compile the LGPL FFmpeg for every supported Kotlin/Native target."
    dependsOn(TargetTriple.entries.map { "buildFFmpegFor${it.gradleSuffix}" })
}

tasks.register("buildFFmpegForAllGpl") {
    group = "kitecodec"
    description = "Cross-compile the GPL FFmpeg (x264 / x265) for every desktop target."
    dependsOn(
        TargetTriple.entries
            .filterNot { it.isAndroid }
            .filterNot { it in setOf(TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64) }
            .map { "buildFFmpegFor${it.gradleSuffix}Gpl" },
    )
}

/*
 * Publishing to Maven Central (Central Portal). Signing only activates when in-memory GPG keys are
 * present in the environment (ORG_GRADLE_PROJECT_signingInMemoryKey /
 * ORG_GRADLE_PROJECT_signingInMemoryKeyPassword), the vanniktech plugin's default, so local builds
 * without keys are unaffected.
 */
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    // Coordinates come from the project defaults: GROUP / VERSION in gradle.properties (applied to
    // allprojects at the root) + this module's name -> io.github.yuroyami:kitecodec-core:<VERSION>.
    // (An explicit coordinates() call is not possible here: another applied plugin already reads them,
    // and thereby finalises them, during configuration.)

    pom {
        name = "KiteCodec"
        description = providers.gradleProperty("DESCRIPTION").get()
        url = "https://github.com/yuroyami/KiteCodec"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "yuroyami"
                name = "yuroyami"
                url = "https://github.com/yuroyami"
            }
        }
        scm {
            url = "https://github.com/yuroyami/KiteCodec"
            connection = "scm:git:git://github.com/yuroyami/KiteCodec.git"
            developerConnection = "scm:git:ssh://git@github.com/yuroyami/KiteCodec.git"
        }
    }

    // Last: configure() finalises the coordinates above.
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
}

/*
 * ── The JNI adapter link tasks (S1.c.1 step 6) ──────────────────────────────────────────────
 *
 * Scaffolded 2026-08-12 by the planner from a hand-proved link on this machine; see KPKMP.md
 * 17.4.3's scaffold layer. Three arms:
 *
 *   linkKiteCodecJniMacosArm64   test-only dylib jvmTest loads via the kitecodec.jni.path
 *                                system property. Links the vendored macOS LGPL FFmpeg plus the
 *                                Homebrew SvtAv1Enc/graphite2 the vendored archives reference,
 *                                exactly as the hand proof measured, and exports only JNI_OnLoad
 *                                through -exported_symbols_list (Mach-O has no version script).
 *   linkKiteCodecJniAndroidArm64 / linkKiteCodecJniAndroidX64
 *                                the AAR's jniLibs inputs. NDK r29 clang, 16 KiB page flags and
 *                                the ELF version script per the S1.c.1 recipe. They require the
 *                                Android FFmpeg trees the producer tasks vendor first.
 */
run {
    val jniDir = rootDir.resolve("native/kitecodec-jni")
    val opaqueInclude = rootDir.resolve("native/kitecodec-c/include")
    val javaHome = javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile }

    tasks.register<io.github.yuroyami.kitecodec.buildtools.LinkKiteCodecJniTask>(
        "linkKiteCodecJniMacosArm64",
    ) {
        group = "kitecodec"
        description = "Links the test-only macOS JNI dylib against the vendored LGPL FFmpeg"
        jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def", "exports.map") })
        opaqueIncludeDir.set(opaqueInclude)
        helperArchive.from(
            tasks.named("compileKiteCodecCForMacosArm64")
                .map { it.outputs.files.singleFile.resolve("libkitecodec.a") },
        )
        ffmpegLibDir.set(rootDir.resolve("native-libs/lgpl/macos-arm64/lib"))
        compiler.set("/usr/bin/clang")
        extraIncludeDirs.set(
            javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
        )
        libSearchDirs.set(listOf("/opt/homebrew/lib"))
        linkFlags.set(
            listOf(
                "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
                "-lSvtAv1Enc", "-lvpx", "-laom", "-lopus", "-lmp3lame",
                "-lwebpmux", "-lwebp", "-lsharpyuv",
                "-lass", "-lharfbuzz", "-lgraphite2", "-lfreetype", "-lfribidi", "-lpng16",
                "-lz", "-lbz2", "-llzma", "-liconv", "-lc++",
                "-framework", "CoreGraphics", "-framework", "CoreText",
                "-framework", "CoreFoundation", "-framework", "CoreMedia",
                "-framework", "CoreVideo", "-framework", "VideoToolbox",
                "-framework", "AudioToolbox",
                "-Wl,-exported_symbols_list,${jniDir.resolve("exports.macos").absolutePath}",
            ),
        )
        outputLibrary.set(layout.buildDirectory.file("kitecodec-jni/macos-arm64/libkitecodec_jni.dylib"))
    }

    // The two Android arms, exactly the S1.c.1 step 6 recipe. ANDROID_NDK_HOME is read at
    // configuration from the environment the S1.c commands pin; a missing NDK or FFmpeg tree
    // fails the arm at execution with the producer task named in the message.
    data class JniArm(val taskName: String, val ffmpegDir: String, val cTarget: String, val ndkTriple: String, val abiDir: String)
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse("/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865")
    listOf(
        JniArm("linkKiteCodecJniAndroidArm64", "android-arm64", "AndroidArm64", "aarch64-linux-android24", "arm64-v8a"),
        JniArm("linkKiteCodecJniAndroidX64", "android-x64", "AndroidX64", "x86_64-linux-android24", "x86_64"),
    ).forEach { arm ->
        tasks.register<io.github.yuroyami.kitecodec.buildtools.LinkKiteCodecJniTask>(arm.taskName) {
            group = "kitecodec"
            description = "Links libkitecodec_jni.so for ${arm.abiDir}"
            jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def", "exports.map") })
            opaqueIncludeDir.set(opaqueInclude)
            helperArchive.from(
                tasks.named("compileKiteCodecCFor${arm.cTarget}")
                    .map { it.outputs.files.singleFile.resolve("libkitecodec.a") },
            )
            ffmpegLibDir.set(rootDir.resolve("native-libs/lgpl/${arm.ffmpegDir}/lib"))
            compiler.set(
                ndkHome.map { "$it/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" }.get(),
            )
            extraIncludeDirs.set(emptyList())
            libSearchDirs.set(emptyList())
            linkFlags.set(
                listOf(
                    "--target=${arm.ndkTriple}",
                    "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
                    "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
                    "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
                    "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
                    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                    "-Wl,--version-script=${jniDir.resolve("exports.map").absolutePath}",
                ),
            )
            outputLibrary.set(
                layout.buildDirectory.file("kitecodec-jni/${arm.ffmpegDir}/${arm.abiDir}/libkitecodec_jni.so"),
            )
        }
    }
}
