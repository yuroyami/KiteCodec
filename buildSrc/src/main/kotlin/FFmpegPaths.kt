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
 *     `<repoRoot>/native-libs/<license>/<targetTriple>/{include,lib}` populated by the
 *     `:buildFFmpegFor<Target>[Gpl]` tasks (see `BuildFFmpegTask.kt`). The resulting binaries fully
 *     embed FFmpeg. The `<license>` segment (`lgpl` / `gpl`) keeps the two flavours from colliding.
 *
 * Layered: vendored static wins if present; otherwise fall back to the system install.
 */
data class FFmpegPaths(
    val includeDir: String,
    val libDir: String,
    val isStaticVendored: Boolean,
) {
    companion object {
        fun resolve(
            project: Project,
            target: TargetTriple,
            license: FFmpegLicense = FFmpegLicense.LGPL,
        ): FFmpegPaths {
            val vendored = project.rootDir.resolve("native-libs/${license.dirName}/${target.dirName}")
            if (vendored.resolve("include").isDirectory && vendored.resolve("lib").isDirectory) {
                return FFmpegPaths(
                    includeDir = vendored.resolve("include").absolutePath,
                    libDir = vendored.resolve("lib").absolutePath,
                    isStaticVendored = true,
                )
            }
            return resolveSystem(project, target)
                ?: throw GradleException(
                    "No FFmpeg install found for $target (${license.dirName}). Either install FFmpeg " +
                        "system-wide (brew/apt/pkg-config) or run " +
                        ":buildFFmpegFor${target.gradleSuffix}${license.taskSuffix} to vendor a static " +
                        "build into native-libs/${license.dirName}/${target.dirName}/.",
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

/**
 * Which FFmpeg license profile a vendored build was produced under.
 *
 *   - [LGPL] is the default: no `--enable-gpl`, no x264 / x265. Hardware encoders (VideoToolbox /
 *     MediaCodec) plus permissive software encoders (svtav1, vpx, aom, opus, mp3lame). Safe for the
 *     App Store and closed-source distribution.
 *   - [GPL] adds libx264 / libx265 for quality-focused software encode. Open-source / server use
 *     only; it makes the linked binary GPL.
 *
 * The [dirName] segment keeps the two flavours apart under `native-libs/`; [taskSuffix] disambiguates
 * the `:buildFFmpegFor<Target>` Gradle tasks.
 */
enum class FFmpegLicense(val dirName: String, val taskSuffix: String) {
    LGPL("lgpl", ""),
    GPL("gpl", "Gpl"),
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
