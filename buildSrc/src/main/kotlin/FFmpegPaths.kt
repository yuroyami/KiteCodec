package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Resolves where libav* headers and link libraries live for a given Kotlin/Native target.
 *
 * Two resolution modes:
 *
 *   - **System / Homebrew (default)** — for developer machines, just use whatever the OS package
 *     manager dropped on the box. Fastest path to a working build; users of the resulting library
 *     need their own FFmpeg installed.
 *
 *   - **Vendored static** — for releases. We expect a directory tree like
 *     `<repoRoot>/native-libs/<targetTriple>/{include,lib}` populated by the `:buildFFmpegFor<Target>`
 *     tasks (see `BuildFFmpegTask.kt`). The resulting binaries fully embed FFmpeg.
 *
 * Layered: vendored static wins if present; otherwise fall back to the system install.
 */
data class FFmpegPaths(
    val includeDir: String,
    val libDir: String,
    val isStaticVendored: Boolean,
) {
    companion object {
        fun resolve(project: Project, target: TargetTriple): FFmpegPaths {
            val vendored = project.rootDir.resolve("native-libs/${target.dirName}")
            if (vendored.resolve("include").isDirectory && vendored.resolve("lib").isDirectory) {
                return FFmpegPaths(
                    includeDir = vendored.resolve("include").absolutePath,
                    libDir = vendored.resolve("lib").absolutePath,
                    isStaticVendored = true,
                )
            }
            return resolveSystem(project, target)
                ?: throw GradleException(
                    "No FFmpeg install found for $target. Either install FFmpeg system-wide " +
                        "(brew/apt/pkg-config) or run :buildFFmpegFor${target.gradleSuffix} " +
                        "to vendor a static build into native-libs/${target.dirName}/.",
                )
        }

        private fun resolveSystem(project: Project, target: TargetTriple): FFmpegPaths? = when (target) {
            TargetTriple.MacosArm64, TargetTriple.MacosX64 -> {
                val configured = project.providers.gradleProperty("kitecodec.macos.homebrew.prefix").orNull
                val prefixCandidates = listOfNotNull(configured, "/opt/homebrew", "/usr/local")
                val prefix = prefixCandidates.firstOrNull { File("$it/include/libavformat/avformat.h").exists() }
                    ?: return null
                FFmpegPaths("$prefix/include", "$prefix/lib", isStaticVendored = false)
            }
            TargetTriple.LinuxX64, TargetTriple.LinuxArm64 -> {
                val candidates = listOf("/usr/include", "/usr/local/include")
                val include = candidates.firstOrNull { File("$it/libavformat/avformat.h").exists() }
                    ?: return null
                val libCandidates = listOf("/usr/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu")
                val lib = libCandidates.firstOrNull { File("$it/libavformat.so").exists() } ?: "/usr/lib"
                FFmpegPaths(include, lib, isStaticVendored = false)
            }
            // iOS / mingw / Android all *require* a vendored build — there's no system install path.
            else -> null
        }
    }
}

enum class TargetTriple(val dirName: String, val gradleSuffix: String) {
    MacosArm64("macos-arm64", "MacosArm64"),
    MacosX64("macos-x64", "MacosX64"),
    IosArm64("ios-arm64", "IosArm64"),
    IosSimulatorArm64("ios-simulator-arm64", "IosSimulatorArm64"),
    IosX64("ios-x64", "IosX64"),
    LinuxX64("linux-x64", "LinuxX64"),
    LinuxArm64("linux-arm64", "LinuxArm64"),
    MingwX64("mingw-x64", "MingwX64"),
    AndroidArm64("android-arm64", "AndroidArm64"),
    AndroidArm32("android-arm32", "AndroidArm32"),
    AndroidX64("android-x64", "AndroidX64"),
    ;

    val isAndroid: Boolean get() = this == AndroidArm64 || this == AndroidArm32 || this == AndroidX64
}
