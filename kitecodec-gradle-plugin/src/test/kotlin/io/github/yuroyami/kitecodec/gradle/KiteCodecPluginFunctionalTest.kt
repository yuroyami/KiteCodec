package io.github.yuroyami.kitecodec.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test
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
                    // No prebuilt asset for ios-arm64 in v0.1; source keeps its Prebuilt convention.
                    iosArm64()
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
                "ios-arm64" in result.output &&
                    "no prebuilt FFmpeg asset" in result.output,
                "Expected the no-prebuilt-asset error naming ios-arm64. Output:\n${result.output}",
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
}
