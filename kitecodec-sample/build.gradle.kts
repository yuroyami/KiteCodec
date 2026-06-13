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

    executables.forEach { (target, triple) ->
        val paths = runCatching { FFmpegPaths.resolve(project, triple) }.getOrNull()
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
