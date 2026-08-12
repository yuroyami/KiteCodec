import io.github.yuroyami.kitecodec.buildtools.FFmpegLicense
import io.github.yuroyami.kitecodec.buildtools.FFmpegPaths
import io.github.yuroyami.kitecodec.buildtools.StaticLinkFlags
import io.github.yuroyami.kitecodec.buildtools.TargetTriple

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    // Same flavour selection as :kitecodec-core. Without this the sample always resolved the LGPL
    // tree while the library it links was built against the GPL one, and the Windows CI job passes
    // -Pkitecodec.ffmpeg.license=gpl and got away with it only because the DLLs were on PATH.
    val selectedLicense =
        if (providers.gradleProperty("kitecodec.ffmpeg.license").orNull?.equals("gpl", ignoreCase = true) == true) {
            FFmpegLicense.GPL
        } else {
            FFmpegLicense.LGPL
        }

    // The phone-superset gate uses the core module's Apple selector together with
    // requireAllTargets. The sample must mirror that scope: its only executable in this mode is
    // the macOS host proof, while iOS remains a library target with no command-line executable.
    val applePhoneTargetsOnly = providers.gradleProperty("kitecodec.applePhoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val executables = if (applePhoneTargetsOnly) {
        mapOf(macosArm64() to TargetTriple.MacosArm64)
    } else {
        mapOf(
            macosArm64() to TargetTriple.MacosArm64,
            macosX64() to TargetTriple.MacosX64,
            linuxX64() to TargetTriple.LinuxX64,
            linuxArm64() to TargetTriple.LinuxArm64,
            mingwX64() to TargetTriple.MingwX64,
        )
    }

    val homebrewPrefix = providers.gradleProperty("kitecodec.macos.homebrew.prefix")
        .getOrElse(io.github.yuroyami.kitecodec.buildtools.BuildFFmpegTask.DEFAULT_HOMEBREW_PREFIX)

    // Targets whose FFmpeg is missing are skipped with a warning by default; releases must not
    // silently drop targets, so -Pkitecodec.requireAllTargets=true makes it fail instead.
    val requireAllTargets = providers.gradleProperty("kitecodec.requireAllTargets")
        .map { it.toBoolean() }.getOrElse(false)

    executables.forEach { (target, triple) ->
        val paths = try {
            FFmpegPaths.resolve(project, triple, selectedLicense)
        } catch (e: GradleException) {
            if (requireAllTargets) throw e
            logger.lifecycle(
                "warning: [KiteCodec] SKIPPING FFmpeg link setup for sample target '${triple.dirName}' " +
                    "because no FFmpeg build found. ${e.message} " +
                    "Set -Pkitecodec.requireAllTargets=true to fail the build instead.",
            )
            null
        }
        target.binaries {
            executable {
                entryPoint = "io.github.yuroyami.kitecodec.sample.main"
                if (paths != null) {
                    linkerOpts("-L${paths.libDir}")
                    // A static vendored FFmpeg needs its third-party archives named explicitly at
                    // the final link, see StaticLinkFlags.
                    linkerOpts(StaticLinkFlags.forTarget(triple, selectedLicense, paths.isStaticVendored))
                    linkerOpts(
                        StaticLinkFlags.hostFallbackSearchFlags(triple, homebrewPrefix, paths.isStaticVendored),
                    )
                    if (!paths.isStaticVendored && target.name.startsWith("macos")) {
                        linkerOpts("-rpath", paths.libDir)
                    }
                }
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        commonMain.dependencies {
            implementation(project(":kitecodec-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
