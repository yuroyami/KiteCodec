package io.github.yuroyami.kitecodec.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TestKit coverage for the plugin's lazy DSL wiring: the `kitecodec { }` block is configured AFTER
 * the `kotlin { }` block (the natural consumer layout), and the fetch task must still see the
 * configured version/licence. An eager implementation reads the extension while `kotlin { }` is
 * still executing and silently falls back to the conventions.
 *
 * The plugin is resolved from a local repository (published by the `test` task's dependency on
 * `publishAllPublicationsToTestLocalRepository`) rather than TestKit classpath injection, so it
 * shares a classloader with the Kotlin Multiplatform plugin it reacts to.
 */
class KiteCodecPluginFunctionalTest {

    @Test
    fun kitecodecDslConfiguredAfterKotlinBlockIsSeenByTasks() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))

        val projectDir = Files.createTempDirectory("kitecodec-functional").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        maven(url = uri("$repo"))
                        mavenCentral()
                        gradlePluginPortal()
                        google()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                        google()
                    }
                }
                rootProject.name = "kitecodec-consumer"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FetchFFmpegTask

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }

                kotlin {
                    macosArm64 {
                        binaries.executable()
                    }
                }

                // Deliberately AFTER kotlin { }: the plugin wires targets while kotlin { } is still
                // executing, so it must defer reading these values until tasks are realised.
                //
                // `version` used to be the sentinel here, set to "n9.9-test" so the URL below could only
                // be right if the DSL had been read lazily. B1.6 made that impossible: register item
                // B1-03 refuses any release these artifacts were not compiled against, so a made-up
                // version now fails configuration and this test would fail for the checker's reason
                // instead of its own. `license` carries the laziness proof instead, and carries it
                // better: it has NO convention, so a plugin that read the extension eagerly would see an
                // unset property rather than a different value, and the -gpl- in the URL below is
                // observable only if the block was read after kotlin { } ran.
                kitecodec {
                    ffmpeg {
                        version = "n8.0"
                        license = FFmpegLicense.GPL
                    }
                }

                tasks.register("printFetchInputs") {
                    doLast {
                        val fetch = tasks.getByName("fetchFFmpegMacosArm64") as FetchFFmpegTask
                        println("fetchUrl=" + fetch.downloadUrl.get())
                    }
                }

                tasks.register("printLinkerOpts") {
                    doLast {
                        // Realise the link task first: the plugin adds the linker flags in
                        // linkTaskProvider.configure { }, which only runs on task realisation.
                        tasks.getByName("linkDebugExecutableMacosArm64")
                        val binary = kotlin.macosArm64().binaries.first { it.name == "debugExecutable" }
                        println("linkerOpts=" + binary.linkerOpts.joinToString(" "))
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("printFetchInputs", "printLinkerOpts", "--stacktrace")
                .forwardOutput()
                .build()

            val expectedUrl = "fetchUrl=https://github.com/yuroyami/KiteCodec/releases/download/" +
                "ffmpeg-n8.0/ffmpeg-n8.0-gpl-macos-arm64.zip"
            assertTrue(
                expectedUrl in result.output,
                "The fetch task did not observe the kitecodec { } DSL values configured after " +
                    "kotlin { }. Expected '$expectedUrl' in:\n${result.output}",
            )
            assertTrue(
                "GPL FFmpeg flavour selected" in result.output,
                "Selecting FFmpegLicense.GPL must log the GPL-obligations warning. Output:\n" +
                    result.output,
            )
            // Prebuilt desktop zips bundle static third-party encoder libs; the plugin must add
            // the matching -l flags (PrebuiltLinkFlags, kept in sync with package-ffmpeg.sh).
            val linkerOptsLine = result.output.lineSequence().firstOrNull { it.startsWith("linkerOpts=") }
            assertTrue(
                linkerOptsLine != null &&
                    "-lSvtAv1Enc" in linkerOptsLine &&
                    "-lx265" in linkerOptsLine &&
                    "-lc++" in linkerOptsLine,
                "A macosArm64 Prebuilt/GPL consumer's link must carry the bundled third-party " +
                    "static-lib flags (-lSvtAv1Enc, -lx265, -lc++). Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /**
     * v0.1 ships prebuilt FFmpeg assets for five triples only. A consumer wiring a target outside
     * that set with the default `source = Prebuilt` must fail configuration with the options
     * (System source / self-hosted repo / drop the target), not 404 mid-build at fetch time.
     */
    @Test
    fun prebuiltSourceForTripleWithoutAssetFailsConfigurationWithOptions() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))

        val projectDir = Files.createTempDirectory("kitecodec-functional").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        maven(url = uri("$repo"))
                        mavenCentral()
                        gradlePluginPortal()
                        google()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                        google()
                    }
                }
                rootProject.name = "kitecodec-consumer"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }

                kotlin {
                    // mingw-x64 has no prebuilt asset and no CI job, and needs a cross-built
                    // third-party stack before it could have one. ios-arm64 was this row's example
                    // until 2026-08-21, when iOS got its first release job and stopped qualifying.
                    mingwX64()
                }

                kitecodec {
                    ffmpeg {
                        license = FFmpegLicense.LGPL
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--stacktrace")
                .forwardOutput()
                .buildAndFail()

            assertTrue(
                "mingw-x64" in result.output &&
                    "no prebuilt FFmpeg asset" in result.output,
                "Expected the no-prebuilt-asset error naming mingw-x64. Output:\n${result.output}",
            )
            assertTrue(
                "FFmpegSource.System" in result.output &&
                    "repo = \"you/yourrepo\"" in result.output,
                "Expected the error to list the fallback options (System source, self-hosted repo). " +
                    "Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /**
     * The licence flavour has no convention: a consumer who wires a non-Android native target
     * without choosing one must get a configuration-time failure that shows the DSL to add, not an
     * opaque "provider has no value" error at link time.
     */
    @Test
    fun missingLicenseChoiceFailsConfigurationWithInstructions() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))

        val projectDir = Files.createTempDirectory("kitecodec-functional").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        maven(url = uri("$repo"))
                        mavenCentral()
                        gradlePluginPortal()
                        google()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                        google()
                    }
                }
                rootProject.name = "kitecodec-consumer"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }

                kotlin {
                    macosArm64()
                }
                // No kitecodec { } block: the plugin must refuse to configure.
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--stacktrace")
                .forwardOutput()
                .buildAndFail()

            assertTrue(
                "no FFmpeg licence flavour selected" in result.output &&
                    "license = FFmpegLicense.LGPL" in result.output,
                "Expected the mandatory-licence error with the DSL snippet. Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /**
     * Register item B1-03: a `version` these artifacts were not built for must fail CONFIGURATION, with a
     * message naming both refs and the two ways out.
     *
     * Why configuration and not later. The default source is Prebuilt, so `version = "n7.1"` downloads
     * FFmpeg 7.1 archives and links them against a klib whose C was compiled against 8.0 headers. Every
     * symbol the def needs exists in 7.1, a static archive has no SONAME, so the link SUCCEEDS and the
     * reads land at struct field offsets that moved. That is register item B1-02's memory corruption
     * arrived at by a configuration mistake, which is the most likely route a real consumer takes to it.
     * The runtime identity gate catches it too, but at the consumer's user's first playback; this catches
     * it at the consumer's own prompt.
     */
    @Test
    fun mismatchedFFmpegVersionFailsConfigurationNamingBothRefs() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))

        val projectDir = Files.createTempDirectory("kitecodec-functional").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        maven(url = uri("$repo"))
                        mavenCentral()
                        gradlePluginPortal()
                        google()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                        google()
                    }
                }
                rootProject.name = "kitecodec-consumer"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }

                kotlin {
                    macosArm64()
                }

                kitecodec {
                    ffmpeg {
                        // A real FFmpeg release, and not the one these artifacts were compiled against.
                        version = "n7.1"
                        license = FFmpegLicense.LGPL
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--stacktrace")
                .forwardOutput()
                .buildAndFail()

            val output = result.output
            assertTrue(
                "n7.1" in output && FFmpegExpectations.SUPPORTED_REFS.first() in output,
                "Expected the message to name BOTH refs. Output:\n$output",
            )
            assertTrue(
                "Two ways out" in output &&
                    "Build KiteCodec yourself" in output,
                "Expected the message to name the two ways out. Output:\n$output",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun localSourceRequiresACompleteTreeForEveryWiredTarget() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-local-incomplete").toFile()
        val localRoot = projectDir.resolve("local-ffmpeg")
        try {
            writeSettings(projectDir, repo)
            localRoot.resolve("lgpl/ios-arm64/include/libavformat/avformat.h").apply {
                parentFile.mkdirs()
                writeText("fixture")
            }
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin { iosArm64() }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                        localRoot = layout.projectDirectory.dir("local-ffmpeg")
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--offline", "--stacktrace")
                .buildAndFail()

            assertTrue(
                "Local FFmpeg tree is incomplete" in result.output &&
                    "ios-arm64" in result.output &&
                    "lib/libavcodec.a" in result.output &&
                    "<localRoot>/<license.id>/<target-triple>/{include,lib}" in result.output,
                "Expected an actionable Local layout failure. Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun localSourceRequiresLocalRoot() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-local-no-root").toFile()
        try {
            writeSettings(projectDir, repo)
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin { macosArm64() }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--offline", "--stacktrace")
                .buildAndFail()

            assertTrue(
                "FFmpegSource.Local requires ffmpeg.localRoot" in result.output,
                "Expected Local to require its root. Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun localGplIsRejectedForIosBeforeTreeResolution() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-local-gpl-ios").toFile()
        try {
            writeSettings(projectDir, repo)
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin { iosSimulatorArm64() }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.GPL
                        localRoot = layout.projectDirectory.dir("missing-is-fine-for-this-ordering-proof")
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help", "--offline", "--stacktrace")
                .buildAndFail()

            assertTrue(
                "iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL." in result.output &&
                    "ios-simulator-arm64" in result.output,
                "Expected the iOS GPL refusal before missing-tree diagnostics. Output:\n${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun localAppleTargetsUseOnlyTheirValidatedTreesAndPlatformLinkSets() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-local-apple").toFile()
        val localRoot = projectDir.resolve("local-ffmpeg")
        try {
            writeSettings(projectDir, repo)
            listOf("macos-arm64", "ios-arm64", "ios-simulator-arm64").forEach { triple ->
                createCompleteLocalTree(localRoot, "lgpl", triple)
            }
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin {
                    macosArm64 { binaries.executable() }
                    iosArm64 { binaries.framework() }
                    iosSimulatorArm64 { binaries.framework() }
                }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                        localRoot = layout.projectDirectory.dir("local-ffmpeg")
                    }
                }
                tasks.register("printLocalLinkerOpts") {
                    doLast {
                        tasks.getByName("linkDebugExecutableMacosArm64")
                        tasks.getByName("linkDebugFrameworkIosArm64")
                        tasks.getByName("linkDebugFrameworkIosSimulatorArm64")
                        println("mac=" + kotlin.macosArm64().binaries.first { it.name == "debugExecutable" }.linkerOpts.joinToString(" "))
                        println("ios=" + kotlin.iosArm64().binaries.first { it.name == "debugFramework" }.linkerOpts.joinToString(" "))
                        println("sim=" + kotlin.iosSimulatorArm64().binaries.first { it.name == "debugFramework" }.linkerOpts.joinToString(" "))
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(
                    "printLocalLinkerOpts",
                    "--offline",
                    "-Pkitecodec.macos.homebrew.prefix=/test-host",
                    "--stacktrace",
                )
                .build()
            val mac = requireNotNull(result.output.lineSequence().firstOrNull { it.startsWith("mac=") })
            val ios = requireNotNull(result.output.lineSequence().firstOrNull { it.startsWith("ios=") })
            val sim = requireNotNull(result.output.lineSequence().firstOrNull { it.startsWith("sim=") })
            val localPath = localRoot.canonicalFile.invariantSeparatorsPath

            // One portable Apple link set for macOS and iOS alike (2026-08-22): zlib plus the
            // media frameworks, no Homebrew -L, no third-party stack.
            val appleFlags = "-lz -framework CoreFoundation -framework CoreMedia " +
                "-framework CoreVideo -framework VideoToolbox -framework AudioToolbox"
            assertEquals("mac=-L$localPath/lgpl/macos-arm64/lib $appleFlags", mac)
            assertEquals("ios=-L$localPath/lgpl/ios-arm64/lib $appleFlags", ios)
            assertEquals("sim=-L$localPath/lgpl/ios-simulator-arm64/lib $appleFlags", sim)
            assertTrue("fetchFFmpeg" !in result.output, "Local source must execute no fetch task: ${result.output}")
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun localLinuxX64UsesOnlyItsTreeAndTheExactDesktopLinkSet() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-local-linux").toFile()
        val localRoot = projectDir.resolve("local-ffmpeg")
        try {
            writeSettings(projectDir, repo)
            createCompleteLocalTree(localRoot, "lgpl", "linux-x64")
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin { linuxX64 { binaries.executable() } }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                        localRoot = layout.projectDirectory.dir("local-ffmpeg")
                    }
                }
                tasks.register("printLocalLinuxLinkerOpts") {
                    doLast {
                        tasks.getByName("linkDebugExecutableLinuxX64")
                        val binary = kotlin.linuxX64().binaries.first { it.name == "debugExecutable" }
                        println("linux=" + binary.linkerOpts.joinToString(" "))
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(
                    "printLocalLinuxLinkerOpts",
                    "--offline",
                    "-Pkitecodec.macos.homebrew.prefix=/must-not-appear",
                    "--stacktrace",
                )
                .build()
            val localPath = localRoot.canonicalFile.invariantSeparatorsPath
            // The REDUCED desktop profile of KPKMP.md 17.13 (decision W-D4): Linux and Windows
            // cross-build no third-party encoder or text stack, so naming those archives here
            // would fail every consumer link with `unable to find library -lass`. What is left is
            // what the konan linux sysroot actually carries. This list is the twin of
            // StaticLinkFlags' portable-desktop branch and moves with it.
            assertEquals(
                "linux=-L$localPath/lgpl/linux-x64/lib " +
                    listOf("-lz", "-lm", "-ldl", "-lpthread").joinToString(" "),
                result.output.lineSequence().firstOrNull { it.startsWith("linux=") },
            )
            assertTrue("-L/must-not-appear/lib" !in result.output)
            assertTrue(
                result.task(":fetchFFmpegLinuxX64") == null,
                "Local source must not put fetchFFmpegLinuxX64 in the task graph: ${result.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /**
     * The dav1d contract is authoritative BOTH ways since 0.0.11 (owner decision 2026-08-19).
     * Before it, `if (archive.exists()) linkerOpts("-ldav1d")` meant the tree decided and the
     * toggle only validated one direction: Synkplay linked dav1d for two releases without one
     * line of its build saying so, and `dav1d = false` silently linked it anyway.
     */
    @Test
    fun dav1dToggleIsAuthoritativeBothWays() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-dav1d-contract").toFile()
        try {
            writeSettings(projectDir, repo)
            writeCompleteLocalTree(projectDir, dav1d = true)
            fun buildFile(dav1dLine: String) = projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin {
                    macosArm64 { binaries.executable() }
                }
                kitecodec {
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                        localRoot = layout.projectDirectory.dir("local-ffmpeg")
                        $dav1dLine
                    }
                }
                tasks.register("printLinkerOpts") {
                    doLast {
                        tasks.getByName("linkDebugExecutableMacosArm64")
                        val binary = kotlin.macosArm64().binaries.first { it.name == "debugExecutable" }
                        println("linkerOpts=" + binary.linkerOpts.joinToString(" "))
                    }
                }
                """.trimIndent(),
            )

            // Direction one: the tree carries dav1d and the build script does not say so. That
            // used to link dav1d silently; it must now refuse with the one-line fix in the text.
            buildFile(dav1dLine = "")
            val refused = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("printLinkerOpts", "--offline", "--stacktrace")
                .buildAndFail()
            assertTrue(
                "carries dav1d" in refused.output &&
                    "cannot be dropped at link time" in refused.output &&
                    "dav1d = true" in refused.output,
                "A dav1d tree with the toggle unset must refuse and name the fix. Output:\n" +
                    refused.output,
            )

            // Direction two: stating the truth links it, and kitecodecInfo reports the whole
            // provisioning decision per target.
            buildFile(dav1dLine = "dav1d = true")
            val linked = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("printLinkerOpts", "kitecodecInfo", "--offline", "--stacktrace")
                .build()
            val opts = linked.output.lineSequence().firstOrNull { it.startsWith("linkerOpts=") }
            assertTrue(
                opts != null && "-ldav1d" in opts,
                "dav1d = true against a dav1d tree must link -ldav1d. Output:\n${linked.output}",
            )
            val info = linked.output.lineSequence().firstOrNull { it.startsWith("macos-arm64:") }
            assertTrue(
                info != null && "source=Local" in info && "license=lgpl" in info &&
                    "dav1d=true (links -ldav1d)" in info,
                "kitecodecInfo must print the per-target provisioning. Output:\n${linked.output}",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /**
     * The clean lifecycle. `clean` wipes build/ but never touched what the plugin GRABBED into
     * the shared Gradle cache, so a cleared project silently kept its provisioning. The task is
     * the visible handle; the opt-in property hooks it into `clean`; and plain `clean` without
     * the opt-in must NOT delete a cache other projects share.
     */
    @Test
    fun cleanCacheTaskDeletesTheDownloadCacheAndCleanHooksInOnlyWhenAsked() {
        val repo = requireNotNull(System.getProperty("kitecodec.test.repo"))
        val pluginVersion = requireNotNull(System.getProperty("kitecodec.test.pluginVersion"))
        val kotlinVersion = requireNotNull(System.getProperty("kitecodec.test.kotlinVersion"))
        val projectDir = Files.createTempDirectory("kitecodec-clean-cache").toFile()
        try {
            writeSettings(projectDir, repo)
            writeCompleteLocalTree(projectDir, dav1d = false)
            projectDir.resolve("build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
                import io.github.yuroyami.kitecodec.gradle.FFmpegSource

                plugins {
                    id("org.jetbrains.kotlin.multiplatform") version "$kotlinVersion"
                    id("io.github.yuroyami.kitecodec") version "$pluginVersion"
                }
                kotlin { macosArm64() }
                kitecodec {
                    cleanCacheOnClean = providers.gradleProperty("hookClean")
                        .map(String::toBoolean).orElse(false).get()
                    ffmpeg {
                        source = FFmpegSource.Local
                        license = FFmpegLicense.LGPL
                        localRoot = layout.projectDirectory.dir("local-ffmpeg")
                    }
                }
                val marker = File(gradle.gradleUserHomeDir, "caches/kitecodec/functional-marker.txt")
                tasks.register("seedCache") {
                    doLast {
                        marker.parentFile.mkdirs()
                        marker.writeText("grabbed")
                        println("marker=" + marker.absolutePath)
                    }
                }
                tasks.register("assertMarker") {
                    doLast { println("markerExists=" + marker.exists()) }
                }
                """.trimIndent(),
            )
            fun run(vararg tasks: String) = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(*tasks, "--offline")
                .build()

            run("seedCache")
            // Plain clean, no opt-in: the shared cache must survive.
            run("clean")
            assertTrue(
                "markerExists=true" in run("assertMarker").output,
                "clean without cleanCacheOnClean must not touch the shared cache",
            )
            // The explicit task deletes it.
            run("kitecodecCleanCache")
            assertTrue(
                "markerExists=false" in run("assertMarker").output,
                "kitecodecCleanCache must delete the downloaded-FFmpeg cache",
            )
            // Opted in, clean carries it.
            run("seedCache")
            run("clean", "-PhookClean=true")
            assertTrue(
                "markerExists=false" in run("assertMarker").output,
                "clean with cleanCacheOnClean = true must also drop the cache",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    /** A complete fake Local tree for macos-arm64: the layout check wants the header and six archives. */
    private fun writeCompleteLocalTree(projectDir: java.io.File, dav1d: Boolean) {
        val lib = projectDir.resolve("local-ffmpeg/lgpl/macos-arm64/lib").apply { mkdirs() }
        projectDir.resolve("local-ffmpeg/lgpl/macos-arm64/include/libavformat").apply { mkdirs() }
            .resolve("avformat.h").writeText("fixture")
        listOf(
            "libavcodec.a", "libavformat.a", "libavutil.a",
            "libavfilter.a", "libswscale.a", "libswresample.a",
        ).forEach { lib.resolve(it).writeText("fixture") }
        if (dav1d) lib.resolve("libdav1d.a").writeText("fixture")
    }

    private fun writeSettings(projectDir: java.io.File, repo: String) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven(url = uri("$repo"))
                    mavenCentral()
                    gradlePluginPortal()
                    google()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                }
            }
            rootProject.name = "kitecodec-local-consumer"
            """.trimIndent(),
        )
    }

    private fun createCompleteLocalTree(root: java.io.File, license: String, triple: String) {
        val tree = root.resolve("$license/$triple")
        tree.resolve("include/libavformat/avformat.h").apply {
            parentFile.mkdirs()
            writeText("fixture")
        }
        listOf(
            "libavcodec.a",
            "libavformat.a",
            "libavutil.a",
            "libavfilter.a",
            "libswscale.a",
            "libswresample.a",
        ).forEach { archive ->
            tree.resolve("lib/$archive").apply {
                parentFile.mkdirs()
                writeText("fixture")
            }
        }
    }
}
