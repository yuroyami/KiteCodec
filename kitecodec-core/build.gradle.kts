import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import io.github.yuroyami.kitecodec.buildtools.BuildFFmpegTask
import io.github.yuroyami.kitecodec.buildtools.CompareCodecContractTask
import io.github.yuroyami.kitecodec.buildtools.CompileKiteCodecCTask
import io.github.yuroyami.kitecodec.buildtools.FFmpegLicense
import io.github.yuroyami.kitecodec.buildtools.FFmpegPaths
import io.github.yuroyami.kitecodec.buildtools.StaticLinkFlags
import io.github.yuroyami.kitecodec.buildtools.TargetTriple
import io.github.yuroyami.kitecodec.buildtools.IOS_GPL_REFUSAL
import io.github.yuroyami.kitecodec.buildtools.KiteCodecJvmTestArgumentProvider
import io.github.yuroyami.kitecodec.buildtools.LinkKiteCodecJniTask
import io.github.yuroyami.kitecodec.buildtools.PrepareKiteCodecJniHarnessTask
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
// Named explicitly because inside a Gradle Kotlin script `java` resolves to the java extension,
// so `java.io.File(...)` does not compile.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// BCV 0.18.1 emits a second trailing LF for JVM dumps. Canonicalize the declared build output so
// apiDump, apiCheck and Git's blank-at-EOF whitespace gate all consume the same one-LF bytes.
tasks.configureEach {
    if (name == "jvmApiBuild") {
        doLast {
            val dump = outputs.files.singleFile
            val current = dump.readText()
            val canonical = current.trimEnd('\r', '\n') + "\n"
            if (current != canonical) {
                dump.writeText(canonical)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)

    // Keep Kotlin's normal native/Apple hierarchy while adding the two deliberate sharing edges
    // below. An explicit template is required once a project also configures dependsOn manually.
    applyDefaultHierarchyTemplate()

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
     *   -Pkitecodec.phoneTargetsOnly=true
     *                                        Register macosArm64, iosArm64, iosSimulatorArm64,
     *                                        JVM and the ordinary Android JVM library target on an
     *                                        arm64 Mac. Local publication only; remote publication
     *                                        always refuses this scope.
     */
    val stableTargetsOnly = providers.gradleProperty("kitecodec.stableTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val hostTargetsOnly = providers.gradleProperty("kitecodec.hostTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val applePhoneTargetsOnly = providers.gradleProperty("kitecodec.applePhoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val phoneTargetsOnly = providers.gradleProperty("kitecodec.phoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val selectedTargetScopes = listOf(
        "kitecodec.stableTargetsOnly" to stableTargetsOnly,
        "kitecodec.hostTargetsOnly" to hostTargetsOnly,
        "kitecodec.applePhoneTargetsOnly" to applePhoneTargetsOnly,
        "kitecodec.phoneTargetsOnly" to phoneTargetsOnly,
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
     * consumer-e2e smoke path), the arm64-Mac applePhoneTargetsOnly scope or the S1.c
     * phoneTargetsOnly superset. Remote publishes never accept an experimental scope.
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
    if (corePublishRequested && phoneTargetsOnly && !onlyLocalPublishes) {
        throw GradleException(
            "Phone-superset selector refusal: -Pkitecodec.phoneTargetsOnly=true may only " +
                "be used with publishToMavenLocal; remote publication is forbidden.",
        )
    }
    if (
        corePublishRequested &&
        !stableTargetsOnly &&
        !(hostTargetsOnly && onlyLocalPublishes) &&
        !(applePhoneTargetsOnly && onlyLocalPublishes) &&
        !(phoneTargetsOnly && onlyLocalPublishes)
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
            |-Pkitecodec.phoneTargetsOnly=true is accepted only for the one local
            |macOS/iPhone/simulator/JVM/Android phone-superset publication.
            """.trimMargin(),
        )
    }

    fun requireArm64Mac(selector: String) {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        if ("mac" !in osName || osArch !in setOf("aarch64", "arm64")) {
            throw GradleException("$selector=true requires an arm64 Mac; found $osName/$osArch.")
        }
    }

    if (phoneTargetsOnly) {
        requireArm64Mac("kitecodec.phoneTargetsOnly")
        jvm()
        android {
            namespace = "io.github.yuroyami.kitecodec"
            compileSdk = 36
            minSdk = 24
            withHostTest {}
            withDeviceTestBuilder {
                sourceSetTreeName = "test"
            }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            optimization {
                consumerKeepRules.apply {
                    publish = true
                    file("consumer-rules.pro")
                }
            }
        }
    }

    // Every Kotlin/Native target gets the same single consolidated cinterop. Each resolves its
    // own FFmpeg install via FFmpegPaths.resolve(...): vendored static if available, else system.
    val knTargetMap: Map<KotlinNativeTarget, TargetTriple> = if (phoneTargetsOnly || applePhoneTargetsOnly) {
        if (applePhoneTargetsOnly) requireArm64Mac("kitecodec.applePhoneTargetsOnly")
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
        .map { it.toBoolean() }.getOrElse(false) || corePublishRequested || phoneTargetsOnly

    if (phoneTargetsOnly) {
        listOf(
            TargetTriple.MacosArm64,
            TargetTriple.IosArm64,
            TargetTriple.IosSimulatorArm64,
            TargetTriple.AndroidArm64,
            TargetTriple.AndroidX64,
        ).forEach { triple ->
            val install = rootDir.resolve("native-libs/lgpl/${triple.dirName}")
            val missing = BuildFFmpegTask.REQUIRED_LIBS.filterNot { library ->
                install.resolve("lib/$library.a").isFile
            }
            val hasHeaders = install.resolve("include/libavformat/avformat.h").isFile
            val hasProvenance = install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH).isFile
            if (missing.isNotEmpty() || !hasHeaders || !hasProvenance) {
                throw GradleException(
                    "kitecodec.phoneTargetsOnly=true requires its complete vendored ${triple.dirName} FFmpeg tree. " +
                        "Run :kitecodec-core:buildFFmpegFor${triple.gradleSuffix} first.",
                )
            }
        }
    }

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
        if (phoneTargetsOnly) {
            val commonMain = getByName("commonMain")
            val commonTest = getByName("commonTest")
            val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply {
                dependsOn(commonMain)
            }
            getByName("jvmMain").dependsOn(jvmAndAndroidMain)
            getByName("androidMain").dependsOn(jvmAndAndroidMain)

            val codecContractTest = maybeCreate("codecContractTest").apply {
                dependsOn(commonTest)
            }
            getByName("macosArm64Test").dependsOn(codecContractTest)
            getByName("jvmTest").dependsOn(codecContractTest)
            getByName("androidDeviceTest").apply {
                dependsOn(codecContractTest)
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.androidx.test.core)
                    implementation(libs.androidx.test.runner)
                    implementation(libs.androidx.test.ext.junit)
                }
            }
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
        // Committed source patches, applied to the scratch copy before configure (window 2c).
        sourcePatches.from(fileTree(rootDir.resolve("native/patches/ffmpeg")) { include("*.patch") })
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

    val konanDataDirProvider = providers.environmentVariable("KONAN_DATA_DIR")
        .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
        .map(::File)
    val macosFfmpegInclude = rootDir.resolve("native-libs/lgpl/macos-arm64/include")
    val macosFfmpegLib = rootDir.resolve("native-libs/lgpl/macos-arm64/lib")
    val macosJniLinkFlags = listOf(
        "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        "-lSvtAv1Enc", "-lvpx", "-laom", "-lopus", "-lmp3lame",
        "-lwebpmux", "-lwebp", "-lsharpyuv",
        "-lass", "-lharfbuzz", "-lgraphite2", "-lfreetype", "-lfribidi", "-lpng16",
        "-lz", "-lbz2", "-llzma", "-liconv", "-lc++",
        "-framework", "CoreGraphics", "-framework", "CoreText",
        "-framework", "CoreFoundation", "-framework", "CoreMedia",
        "-framework", "CoreVideo", "-framework", "VideoToolbox",
        "-framework", "AudioToolbox",
    )
    val androidHelperTasks = LinkKiteCodecJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->
        val ffmpegInclude = rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/include")
        val ffmpegLib = rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/lib")
        tasks.register<CompileKiteCodecCTask>(arm.helperTaskName) {
            konanTargetName.set(arm.konanTargetName)
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(opaqueInclude)
            ffmpegIncludeDirs.set(listOf(ffmpegInclude.absolutePath))
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { library ->
                        listOf("version.h", "version_major.h").map { "$ffmpegInclude/$library/$it" }
                    } + "$ffmpegInclude/libavutil/ffversion.h",
            )
            buildDefines.set(
                mapOf(
                    CompileKiteCodecCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_DIR to ffmpegLib.absolutePath,
                ),
            )
            konanDataDir.fileProvider(konanDataDirProvider)
            outputDir.set(layout.buildDirectory.dir("kitecodec-c-jni/${arm.konanTargetName}"))
        }
    }

    val macosJniLink = tasks.register<LinkKiteCodecJniTask>(
        "linkKiteCodecJniMacosArm64",
    ) {
        group = "kitecodec"
        description = "Links the test-only macOS JNI dylib against the vendored LGPL FFmpeg"
        jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
        opaqueIncludeDir.set(opaqueInclude)
        val helperCompile = tasks.named<CompileKiteCodecCTask>("compileKiteCodecCForMacosArm64")
        dependsOn(helperCompile)
        helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) })
        ffmpegLibDir.set(macosFfmpegLib)
        compiler.set("/usr/bin/clang")
        extraIncludeDirs.set(
            javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
        )
        libSearchDirs.set(listOf("/opt/homebrew/lib"))
        linkFlags.set(macosJniLinkFlags)
        exportControlFile.set(jniDir.resolve("exports.macos"))
        exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
        outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni/macos-arm64"))
        outputLibrary.set(outputDirectory.file("libkitecodec_jni.dylib"))
    }

    // The two Android arms, exactly the S1.c.1 step 6 recipe. ANDROID_NDK_HOME is read at
    // configuration from the environment the S1.c commands pin; a missing NDK or FFmpeg tree
    // fails the arm at execution with the producer task named in the message.
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse("/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865")
    val androidJniLinks = LinkKiteCodecJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->
        val helperCompile = androidHelperTasks.getValue(arm)
        tasks.register<LinkKiteCodecJniTask>(arm.linkTaskName) {
            group = "kitecodec"
            description = "Links libkitecodec_jni.so for ${arm.abiDirectory}"
            jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(helperCompile)
            helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) })
            ffmpegLibDir.set(rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/lib"))
            compiler.set(
                ndkHome.map { "$it/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" },
            )
            extraIncludeDirs.set(emptyList())
            libSearchDirs.set(emptyList())
            linkFlags.set(LinkKiteCodecJniTask.androidLinkFlags(arm))
            exportControlFile.set(jniDir.resolve("exports.map"))
            exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni/${arm.ffmpegDirName}"))
            outputLibrary.set(outputDirectory.file("${arm.abiDirectory}/libkitecodec_jni.so"))
        }
    }

    val phoneTargetsOnly = providers.gradleProperty("kitecodec.phoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    if (phoneTargetsOnly) {
        val prepareMajorMismatchHeaders = tasks.register<PrepareKiteCodecJniHarnessTask>(
            "prepareKiteCodecJniMajorMismatchHeaders",
        ) {
            group = "verification"
            description = "Generates an unrenamed overlay from the hermetic major-mismatch fake header."
            sourceDirectory.set(opaqueInclude)
            mutationSourceFile.set(
                rootDir.resolve(
                    "native/kitecodec-c/tests/fake_headers/major_mismatch/kitecodec_ffmpeg_versions.h",
                ),
            )
            relativeFile.set("kitecodec_ffmpeg_versions.h")
            expectedText.set("#define KC_CASE kc_major_mismatch\n#include \"../kc_rename.h\"\n\n")
            replacementText.set("")
            outputDirectory.set(layout.buildDirectory.dir("generated/kitecodec-jni-harness/major-mismatch/include"))
        }
        val mismatchHelperCompile = tasks.register<CompileKiteCodecCTask>(
            "compileKiteCodecCForJniMacosArm64MajorMismatch",
        ) {
            konanTargetName.set("macos_arm64")
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(prepareMajorMismatchHeaders.flatMap { it.outputDirectory })
            // The generated fake header's include_next resolves the production copy second.
            ffmpegIncludeDirs.set(listOf(opaqueInclude.absolutePath, macosFfmpegInclude.absolutePath))
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { library ->
                        listOf("version.h", "version_major.h").map { "$macosFfmpegInclude/$library/$it" }
                    } + "$macosFfmpegInclude/libavutil/ffversion.h",
            )
            buildDefines.set(
                mapOf(
                    CompileKiteCodecCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                    CompileKiteCodecCTask.DEFINE_FFMPEG_DIR to macosFfmpegLib.absolutePath,
                ),
            )
            konanDataDir.fileProvider(konanDataDirProvider)
            outputDir.set(layout.buildDirectory.dir("kitecodec-c-jni-major-mismatch/macos_arm64"))
        }
        val mismatchJniLink = tasks.register<LinkKiteCodecJniTask>(
            "linkKiteCodecJniMacosArm64MajorMismatch",
        ) {
            group = "verification"
            description = "Links a test-only JNI dylib with a genuinely major-mismatched identity helper."
            jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(mismatchHelperCompile)
            helperArchive.from(
                mismatchHelperCompile.flatMap { it.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) },
            )
            ffmpegLibDir.set(macosFfmpegLib)
            compiler.set("/usr/bin/clang")
            extraIncludeDirs.set(
                javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
            )
            libSearchDirs.set(listOf("/opt/homebrew/lib"))
            linkFlags.set(macosJniLinkFlags)
            exportControlFile.set(jniDir.resolve("exports.macos"))
            exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni-harness/major-mismatch/macos-arm64"))
            outputLibrary.set(outputDirectory.file("libkitecodec_jni_major_mismatch.dylib"))
        }

        val prepareCorruptJni = tasks.register<PrepareKiteCodecJniHarnessTask>(
            "prepareKiteCodecJniCorruptDescriptorSources",
        ) {
            group = "verification"
            description = "Generates an isolated JNI tree with one invalid RegisterNatives descriptor."
            sourceDirectory.set(jniDir)
            mutationSourceFile.set(jniDir.resolve("methods.def"))
            relativeFile.set("methods.def")
            expectedText.set(
                """KJ_METHOD("io/github/yuroyami/kitecodec/Internals", "nativeAbiVersion",       "()I",                    kj_abi_version)""",
            )
            replacementText.set(
                """KJ_METHOD("io/github/yuroyami/kitecodec/Internals", "nativeAbiVersion",       "()J",                    kj_abi_version)""",
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/kitecodec-jni-harness/corrupt-descriptor"))
        }
        val normalHelperCompile = tasks.named<CompileKiteCodecCTask>("compileKiteCodecCForMacosArm64")
        val corruptJniLink = tasks.register<LinkKiteCodecJniTask>(
            "linkKiteCodecJniMacosArm64CorruptDescriptor",
        ) {
            group = "verification"
            description = "Links a test-only JNI dylib whose RegisterNatives descriptor must fail."
            jniSources.from(
                prepareCorruptJni.flatMap { it.outputDirectory }.map { directory -> directory.asFileTree },
            )
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(normalHelperCompile)
            helperArchive.from(
                normalHelperCompile.flatMap { it.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) },
            )
            ffmpegLibDir.set(macosFfmpegLib)
            compiler.set("/usr/bin/clang")
            extraIncludeDirs.set(
                javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
            )
            libSearchDirs.set(listOf("/opt/homebrew/lib"))
            linkFlags.set(macosJniLinkFlags)
            exportControlFile.set(prepareCorruptJni.flatMap { it.outputDirectory.file("exports.macos") })
            exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni-harness/corrupt-descriptor/macos-arm64"))
            outputLibrary.set(outputDirectory.file("libkitecodec_jni_corrupt_descriptor.dylib"))
        }

        val jvmTranscriptFile = layout.buildDirectory.file("contract-transcripts/jvm.txt")
        val macosTranscriptFile = layout.buildDirectory.file("contract-transcripts/macosArm64.txt")

        tasks.named<Test>("jvmTest") {
            dependsOn(macosJniLink, mismatchJniLink, corruptJniLink)
            val testRuntimeClasspath = classpath
            jvmArgumentProviders.add(
                objects.newInstance<KiteCodecJvmTestArgumentProvider>().apply {
                    normalJniLibrary.set(macosJniLink.flatMap { it.outputLibrary })
                    mismatchJniLibrary.set(mismatchJniLink.flatMap { it.outputLibrary })
                    corruptJniLibrary.set(corruptJniLink.flatMap { it.outputLibrary })
                    contractTranscript.set(jvmTranscriptFile)
                    probeClasspath.from(testRuntimeClasspath)
                },
            )
            outputs.file(jvmTranscriptFile).withPropertyName("codecContractTranscript")
        }
        tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("macosArm64Test") {
            environment(
                "KITECODEC_CONTRACT_TRANSCRIPT",
                macosTranscriptFile.get().asFile.absolutePath,
            )
            outputs.file(macosTranscriptFile).withPropertyName("codecContractTranscript")
            doFirst {
                val parent = macosTranscriptFile.get().asFile.parentFile
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw GradleException("Could not create codec-contract transcript directory: $parent")
                }
            }
        }
        tasks.register<CompareCodecContractTask>("compareJvmNativeContract") {
            group = "verification"
            description = "Compares JVM and macOS codec-contract transcripts byte for byte."
            dependsOn("jvmTest", "macosArm64Test")
            jvmTranscript.set(jvmTranscriptFile)
            macosArm64Transcript.set(macosTranscriptFile)
        }

        extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
            onVariants { variant ->
                val jniLibs = checkNotNull(variant.sources.jniLibs) {
                    "AGP did not expose jniLibs sources for Kotlin Multiplatform Android variant ${variant.name}."
                }
                androidJniLinks.values.forEach { linkTask ->
                    jniLibs.addGeneratedSourceDirectory(
                        linkTask,
                        LinkKiteCodecJniTask::outputDirectory,
                    )
                }
            }
        }
    }
}
