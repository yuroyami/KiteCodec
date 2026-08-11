package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

const val IOS_GPL_REFUSAL =
    "iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL."

private val TargetTriple.isIos: Boolean
    get() = this == TargetTriple.IosArm64 ||
        this == TargetTriple.IosSimulatorArm64 ||
        this == TargetTriple.IosX64

/**
 * Resolves where libav* headers and link libraries live for a given Kotlin/Native target.
 *
 * Two resolution modes:
 *
 *   - **System / Homebrew (default)**: for developer machines, just use whatever the OS package
 *     manager dropped on the box. Fastest path to a working build; users of the resulting library
 *     need their own FFmpeg installed.
 *
 *   - **Vendored static**: for releases. We expect a directory tree like
 *     `<repoRoot>/native-libs/<license>/<targetTriple>/{include,lib}` populated by the
 *     `:buildFFmpegFor<Target>` tasks (and desktop-only `Gpl` variants; see `BuildFFmpegTask.kt`).
 *     iOS supports only the LGPL standard software-playback profile. The resulting binaries fully
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
            if (target.isIos && license == FFmpegLicense.GPL) {
                throw GradleException(IOS_GPL_REFUSAL)
            }
            val vendored = project.rootDir.resolve("native-libs/${license.dirName}/${target.dirName}")
            if (vendored.resolve("include").isDirectory && vendored.resolve("lib").isDirectory) {
                return FFmpegPaths(
                    includeDir = vendored.resolve("include").absolutePath,
                    libDir = vendored.resolve("lib").absolutePath,
                    isStaticVendored = true,
                )
            }
            val host = hostTriple()
            return resolveSystem(project, target)
                ?: throw GradleException(
                    buildString {
                        append("No FFmpeg install found for $target (${license.dirName}). ")
                        append("Run :buildFFmpegFor${target.gradleSuffix}${license.taskSuffix} to vendor a ")
                        append("static build into native-libs/${license.dirName}/${target.dirName}/")
                        if (target == host) {
                            append(", or install FFmpeg system-wide (brew install ffmpeg / apt install the ")
                            append("libav*-dev packages).")
                        } else {
                            append(". A system FFmpeg cannot be used here: this host is ")
                            append("${host ?: "an unrecognised platform"}, so its libraries are built for ")
                            append("the wrong architecture. Only a vendored cross-build is valid for $target.")
                        }
                    },
                )
        }

        /**
         * A system FFmpeg is only ever valid for the HOST's own target.
         *
         * The paths below (`/opt/homebrew`, `/usr/lib/x86_64-linux-gnu`, …) are matched by
         * existence, not by architecture, so without this gate `resolve(project, MacosX64)` on an
         * Apple-silicon Mac happily returns the arm64 Homebrew prefix and `resolve(project,
         * LinuxArm64)` on an x64 box returns the x86_64 libraries: cinterop then parses headers
         * for one architecture and the linker is handed archives for another. Cross targets have
         * exactly one correct answer: a vendored build under `native-libs/`.
         */
        private fun hostTriple(): TargetTriple? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val isArm64 = arch in setOf("aarch64", "arm64")
            val isX64 = arch in setOf("amd64", "x86_64")
            return when {
                "mac" in os && isArm64 -> TargetTriple.MacosArm64
                "mac" in os && isX64 -> TargetTriple.MacosX64
                "linux" in os && isX64 -> TargetTriple.LinuxX64
                "linux" in os && isArm64 -> TargetTriple.LinuxArm64
                else -> null
            }
        }

        private fun resolveSystem(project: Project, target: TargetTriple): FFmpegPaths? {
            // iOS / mingw / Android never have a system install; every other target only counts
            // when it IS this host (see hostTriple).
            if (target != hostTriple()) return null
            return when (target) {
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
                    // Multiarch dir for this host first: /usr/lib may hold a foreign-arch copy.
                    val multiarch = if (target == TargetTriple.LinuxArm64) {
                        "/usr/lib/aarch64-linux-gnu"
                    } else {
                        "/usr/lib/x86_64-linux-gnu"
                    }
                    val libCandidates = listOf(multiarch, "/usr/lib", "/usr/local/lib")
                    val lib = libCandidates.firstOrNull { File("$it/libavformat.so").exists() } ?: return null
                    FFmpegPaths(include, lib, isStaticVendored = false)
                }
                else -> null
            }
        }
    }
}

/**
 * Which FFmpeg license profile a vendored build was produced under.
 *
 *   - [LGPL] is the default: no `--enable-gpl`, no x264 / x265. Desktop builds include their
 *     permissive encoder/text stack, Android uses MediaCodec, and iOS uses the standard software
 *     playback core plus SDK zlib. Safe for the App Store and closed-source distribution.
 *   - [GPL] is desktop-only and adds libx264 / libx265 for quality-focused software encode.
 *     Open-source / server use only; it makes the linked binary GPL. iOS rejects it.
 *
 * The [dirName] segment keeps the two flavours apart under `native-libs/`; [taskSuffix] disambiguates
 * the desktop `:buildFFmpegFor<Target>Gpl` Gradle tasks.
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
