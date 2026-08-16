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
 * Cross-compiles the libass rendering chain (fribidi, freetype, harfbuzz, libass) as static
 * libraries for one [target] and installs them into `native-libs/deps/<target>/ass-chain/`
 * (KPKMP 17.12 phase L, pulled forward by owner order 2026-08-16). The chain is OPTIONAL by
 * decision D-7: nothing here enters a default artifact; the `kiteplayer-libass` module and the
 * plugin's libass toggle are its only consumers.
 *
 * Fontconfig and libunibreak are deliberately absent from this first chain: libass renders
 * without both (CoreText provides fonts on Apple; Android supplies fonts at runtime through
 * `ass_add_font`, which is how every Android libass consumer works), and each would be its own
 * cross-build with its own maintenance duty.
 *
 * Needs meson, ninja, autotools (libass builds with autoconf) and source checkouts at
 * `vendor/{fribidi,freetype,harfbuzz,libass}`. Builds happen in a scratch directory because
 * this repo lives under `#Kite`, a path pkg-config and autotools cannot be trusted with.
 */
abstract class BuildAssChainTask : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** One combined pin: bumping any vendored checkout means bumping this, which rebuilds. */
    @get:Input
    abstract val sourceRefs: Property<String>

    @get:Internal
    abstract val vendorDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Cross-compile the libass chain (fribidi, freetype, harfbuzz, libass) for one target."
    }

    @TaskAction
    fun run() {
        val target = target.get()
        val vendor = vendorDir.get().asFile
        val output = outputDir.get().asFile
        listOf("fribidi", "freetype", "harfbuzz", "libass").forEach { name ->
            require(vendor.resolve(name).isDirectory) {
                "missing checkout vendor/$name; clone it first (see BuildAssChainTask's KDoc)"
            }
        }
        val meson = which("meson") ?: throw GradleException("meson not found. brew install meson ninja")
        val ninja = which("ninja") ?: throw GradleException("ninja not found. brew install ninja")

        val scratch = Files.createTempDirectory("kitecodec-asschain").toFile()
        try {
            val install = scratch.resolve("install")
            val pkgconfig = install.resolve("lib/pkgconfig")
            val cross = crossFileFor(target, scratch)
            val env = mutableMapOf("PKG_CONFIG_LIBDIR" to pkgconfig.absolutePath)

            fun mesonBuild(name: String, options: List<String>) {
                val source = scratch.resolve("src-$name")
                vendor.resolve(name).copyRecursively(source, overwrite = true)
                val build = scratch.resolve("build-$name")
                val setup = mutableListOf(
                    meson, "setup", build.absolutePath, source.absolutePath,
                    "--prefix", install.absolutePath,
                    "--libdir", "lib",
                    "--buildtype", "release",
                    "--default-library", "static",
                )
                setup.addAll(options)
                // Cross builds ignore PKG_CONFIG_LIBDIR from the environment for host-machine
                // dependencies; the built-in option reaches them either way.
                setup.add("-Dpkg_config_path=${pkgconfig.absolutePath}")
                cross?.let { setup.addAll(listOf("--cross-file", it.absolutePath)) }
                runIn(source, setup, env)
                runIn(build, listOf(ninja), env)
                runIn(build, listOf(ninja, "install"), env)
            }

            mesonBuild("fribidi", listOf("-Ddocs=false", "-Dtests=false"))
            // freetype first WITHOUT harfbuzz: the two reference each other, and libass'
            // shaping needs harfbuzz-with-freetype, not the reverse.
            mesonBuild(
                "freetype",
                listOf(
                    "-Dharfbuzz=disabled", "-Dbrotli=disabled", "-Dbzip2=disabled",
                    "-Dpng=disabled", "-Dzlib=system", "-Dtests=disabled",
                ),
            )
            mesonBuild(
                "harfbuzz",
                listOf(
                    "--auto-features=disabled", "-Dfreetype=enabled",
                    "-Dtests=disabled", "-Ddocs=disabled", "-Dutilities=disabled",
                ),
            )

            buildLibass(target, vendor, scratch, install, env)

            listOf("libfribidi.a", "libfreetype.a", "libharfbuzz.a", "libass.a").forEach { archive ->
                check(install.resolve("lib/$archive").isFile) { "the chain build produced no lib/$archive" }
            }
            output.deleteRecursively()
            output.mkdirs()
            install.resolve("include").copyRecursively(output.resolve("include"), overwrite = true)
            install.resolve("lib").copyRecursively(output.resolve("lib"), overwrite = true)
            // Every installed .pc gets the pcfiledir prefix: the '#' in this repo's path has no
            // pkg-config escape (the dav1d task documents the same rewrite).
            output.resolve("lib/pkgconfig").listFiles { f: File -> f.extension == "pc" }?.forEach { pc ->
                pc.writeText(
                    pc.readText().replace(Regex("^prefix=.*$", RegexOption.MULTILINE)) { "prefix=\${pcfiledir}/../.." },
                )
            }
            logger.lifecycle("[KiteCodec] ass chain (${sourceRefs.get()}) for ${target.dirName} installed into $output")
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun buildLibass(
        target: TargetTriple,
        vendor: File,
        scratch: File,
        install: File,
        env: Map<String, String>,
    ) {
        val source = scratch.resolve("src-libass")
        vendor.resolve("libass").copyRecursively(source, overwrite = true)
        val fullEnv = env + toolchainEnv(target)
        runIn(source, listOf("autoreconf", "-ivf"), fullEnv)
        val configure = mutableListOf(
            source.resolve("configure").absolutePath,
            "--prefix=${install.absolutePath}",
            "--enable-static", "--disable-shared",
            "--disable-fontconfig",
        )
        if (target.isAndroid) {
            // No system font provider exists on Android; fonts arrive through ass_add_font.
            configure += "--disable-require-system-font-provider"
        }
        hostTripleFor(target)?.let { configure += "--host=$it" }
        val build = scratch.resolve("build-libass").also(File::mkdirs)
        runIn(build, configure, fullEnv)
        runIn(build, listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), fullEnv)
        runIn(build, listOf("make", "install"), fullEnv)
    }

    /** CC/CXX/AR/RANLIB for autotools cross builds; empty for the host. */
    private fun toolchainEnv(target: TargetTriple): Map<String, String> = when (target) {
        TargetTriple.MacosArm64 -> emptyMap()
        TargetTriple.AndroidArm64, TargetTriple.AndroidX64 -> {
            val bin = ndkToolchainBin()
            val prefix = if (target == TargetTriple.AndroidArm64) "aarch64-linux-android" else "x86_64-linux-android"
            val cc = bin.resolve("$prefix${BuildFFmpegTask.ANDROID_API}-clang")
            require(cc.exists()) { "NDK compiler not found: $cc" }
            mapOf(
                "CC" to cc.absolutePath,
                "CXX" to "${cc.absolutePath}++",
                "AR" to bin.resolve("llvm-ar").absolutePath,
                "RANLIB" to bin.resolve("llvm-ranlib").absolutePath,
                "STRIP" to bin.resolve("llvm-strip").absolutePath,
            )
        }
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> {
            val sdkName = if (target == TargetTriple.IosArm64) "iphoneos" else "iphonesimulator"
            val minFlag = if (target == TargetTriple.IosArm64) "-mios-version-min=14.0" else "-mios-simulator-version-min=14.0"
            val sdk = xcrunSdkPath(sdkName)
            val flags = "-arch arm64 -isysroot $sdk $minFlag"
            mapOf(
                "CC" to "clang $flags",
                "CXX" to "clang++ $flags",
            )
        }
        else -> throw GradleException("BuildAssChainTask has no toolchain for $target yet.")
    }

    private fun hostTripleFor(target: TargetTriple): String? = when (target) {
        TargetTriple.MacosArm64 -> null
        TargetTriple.AndroidArm64 -> "aarch64-linux-android"
        TargetTriple.AndroidX64 -> "x86_64-linux-android"
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> "arm64-apple-darwin"
        else -> null
    }

    /** Null means a native host build. The same shape the dav1d task generates. */
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
                cpp = '${cc.absolutePath}++'
                ar = '${bin.resolve("llvm-ar").absolutePath}'
                strip = '${bin.resolve("llvm-strip").absolutePath}'
                pkg-config = 'pkg-config'

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
                cpp = 'clang++'
                ar = 'ar'
                strip = 'strip'
                pkg-config = 'pkg-config'

                [built-in options]
                c_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                c_link_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                cpp_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                cpp_link_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']

                [host_machine]
                system = 'darwin'
                cpu_family = 'aarch64'
                cpu = 'aarch64'
                endian = 'little'
                """.trimIndent()
            }
            else -> throw GradleException("BuildAssChainTask has no cross file for $target yet.")
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

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String>) {
        logger.lifecycle("[KiteCodec ass-chain] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /** The chain this repo builds by default; mpv-android ships the same series. */
        const val DEFAULT_SOURCE_REFS = "fribidi-1.0.16 freetype-2.14.3 harfbuzz-14.2.1 libass-0.17.4"
    }
}
