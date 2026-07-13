import io.github.yuroyami.kitecodec.buildtools.FFmpegPaths
import io.github.yuroyami.kitecodec.buildtools.TargetTriple

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    val executables = mapOf(
        macosArm64() to TargetTriple.MacosArm64,
        macosX64()   to TargetTriple.MacosX64,
        linuxX64()   to TargetTriple.LinuxX64,
        linuxArm64() to TargetTriple.LinuxArm64,
        mingwX64()   to TargetTriple.MingwX64,
    )

    // Targets whose FFmpeg is missing are skipped with a warning by default; releases must not
    // silently drop targets, so -Pkitecodec.requireAllTargets=true makes it fail instead.
    val requireAllTargets = providers.gradleProperty("kitecodec.requireAllTargets")
        .map { it.toBoolean() }.getOrElse(false)

    executables.forEach { (target, triple) ->
        val paths = try {
            FFmpegPaths.resolve(project, triple)
        } catch (e: GradleException) {
            if (requireAllTargets) throw e
            logger.lifecycle(
                "warning: [KiteCodec] SKIPPING FFmpeg link setup for sample target '${triple.dirName}' " +
                    "— no FFmpeg build found. ${e.message} " +
                    "Set -Pkitecodec.requireAllTargets=true to fail the build instead.",
            )
            null
        }
        target.binaries {
            executable {
                entryPoint = "io.github.yuroyami.kitecodec.sample.main"
                if (paths != null) {
                    linkerOpts("-L${paths.libDir}")
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
