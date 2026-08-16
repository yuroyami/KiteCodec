package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files

/**
 * Cross-compiles dav1d (VideoLAN's SIMD AV1 software decoder) as a static library for one
 * [target] and installs it into `native-libs/deps/<target>/{include,lib}` where
 * [BuildFFmpegTask] picks it up when its dav1d switch is on (KPKMP 17.12 D-7: ACCEPTED,
 * demand-driven, optional; register row KC-AV1SW).
 *
 * Needs meson, ninja and (for x86 asm) nasm on the host, and a dav1d source checkout at
 * [sourceDir]:  `git clone --depth 1 --branch <ref> https://code.videolan.org/videolan/dav1d
 * vendor/dav1d`. Like the FFmpeg task, the checkout is tracked by [sourceRef], not content.
 */
abstract class BuildDav1dTask : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** The dav1d tag the [sourceDir] checkout is pinned to, an input so bumping it rebuilds. */
    @get:Input
    abstract val sourceRef: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Cross-compile dav1d as a static library for the given target."
    }

    @TaskAction
    fun run() {
        val target = target.get()
        val source = sourceDir.get().asFile
        val output = outputDir.get().asFile
        require(source.resolve("meson.build").isFile) {
            "dav1d source not found at $source. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} " +
                "https://code.videolan.org/videolan/dav1d ${source.relativeTo(project.rootDir)}"
        }
        val meson = which("meson") ?: throw GradleException("meson not found. brew install meson ninja nasm")
        val ninja = which("ninja") ?: throw GradleException("ninja not found. brew install ninja")

        val scratch = Files.createTempDirectory("kitecodec-dav1d").toFile()
        try {
            val build = scratch.resolve("build")
            val install = scratch.resolve("install")
            val setup = mutableListOf(
                meson, "setup", build.absolutePath, source.absolutePath,
                "--prefix", install.absolutePath,
                "--libdir", "lib",
                "--buildtype", "release",
                "--default-library", "static",
                "-Denable_tools=false", "-Denable_tests=false", "-Denable_examples=false",
            )
            crossFileFor(target, scratch)?.let { setup += listOf("--cross-file", it.absolutePath) }
            runIn(source, setup)
            runIn(build, listOf(ninja))
            runIn(build, listOf(ninja, "install"))

            check(install.resolve("lib/libdav1d.a").isFile) {
                "meson install produced no lib/libdav1d.a under $install"
            }
            output.deleteRecursively()
            output.mkdirs()
            install.resolve("include").copyRecursively(output.resolve("include"), overwrite = true)
            install.resolve("lib").copyRecursively(output.resolve("lib"), overwrite = true)
            logger.lifecycle("[KiteCodec] dav1d ${sourceRef.get()} (${target.dirName}) installed into $output")
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Null means a native host build (macOS arm64 on this machine). */
    private fun crossFileFor(target: TargetTriple, scratch: File): File? {
        val text = when (target) {
            TargetTriple.MacosArm64 -> return null
            TargetTriple.AndroidArm64, TargetTriple.AndroidX64 -> {
                val (cpuFamily, cpu, ccPrefix) = when (target) {
                    TargetTriple.AndroidArm64 -> Triple("aarch64", "aarch64", "aarch64-linux-android")
                    else -> Triple("x86_64", "x86_64", "x86_64-linux-android")
                }
                val bin = ndkToolchainBin()
                val cc = bin.resolve("$ccPrefix${BuildFFmpegTask.ANDROID_API}-clang")
                require(cc.exists()) { "NDK compiler not found: $cc" }
                """
                [binaries]
                c = '${cc.absolutePath}'
                ar = '${bin.resolve("llvm-ar").absolutePath}'
                strip = '${bin.resolve("llvm-strip").absolutePath}'

                [host_machine]
                system = 'android'
                cpu_family = '$cpuFamily'
                cpu = '$cpu'
                endian = 'little'
                """.trimIndent()
            }
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> {
                val sdkName = if (target == TargetTriple.IosArm64) "iphoneos" else "iphonesimulator"
                val minFlag = if (target == TargetTriple.IosArm64) "-mios-version-min=14.0" else "-mios-simulator-version-min=14.0"
                val sdk = xcrunSdkPath(sdkName)
                """
                [binaries]
                c = 'clang'
                ar = 'ar'
                strip = 'strip'

                [built-in options]
                c_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                c_link_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']

                [host_machine]
                system = 'darwin'
                cpu_family = 'aarch64'
                cpu = 'aarch64'
                endian = 'little'
                """.trimIndent()
            }
            else -> throw GradleException(
                "BuildDav1dTask supports macOS arm64, Android arm64/x64 and iOS arm64/simulator today; " +
                    "$target needs its cross file written first.",
            )
        }
        val file = scratch.resolve("cross-${target.dirName}.txt")
        file.writeText(text)
        return file
    }

    private fun ndkToolchainBin(): File {
        val fromEnv = sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
            .mapNotNull { System.getenv(it) }
            .map(::File)
            .firstOrNull { it.isDirectory }
        val ndk = fromEnv ?: run {
            val sdkNdk = File(System.getProperty("user.home"), "Library/Android/sdk/ndk")
                .takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"), "Android/Sdk/ndk").takeIf { it.isDirectory }
            sdkNdk?.listFiles { f: File -> f.isDirectory }?.maxByOrNull { it.name }
                ?: throw GradleException("Android NDK not found. Set ANDROID_NDK_HOME or install one via the SDK manager.")
        }
        val prebuilt = ndk.resolve("toolchains/llvm/prebuilt")
        val hostDir = prebuilt.listFiles { f: File -> f.isDirectory }?.firstOrNull()
            ?: throw GradleException("No prebuilt toolchain under $prebuilt")
        return hostDir.resolve("bin")
    }

    private fun xcrunSdkPath(sdkName: String): String {
        val proc = ProcessBuilder("xcrun", "--sdk", sdkName, "--show-sdk-path")
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && output.isNotEmpty()) { "xcrun --sdk $sdkName --show-sdk-path failed: $output" }
        return output
    }

    private fun which(tool: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, tool) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private fun runIn(workDir: File, command: List<String>) {
        logger.lifecycle("[KiteCodec dav1d] " + command.joinToString(" "))
        val proc = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /** The dav1d release this repo builds by default; mpv-android ships the same series. */
        const val DEFAULT_SOURCE_REF = "1.5.4"
    }
}
