package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * Cross-compiles a minimal FFmpeg from source for one [target] and writes the resulting static
 * archives + headers into `native-libs/<target.dirName>/{include,lib}`. The Kotlin cinterop step
 * picks them up via [FFmpegPaths].
 *
 * Two profiles:
 *
 *   - **Desktop (GPL)** — `--enable-gpl --enable-version3` + libx264 / libx265 / libsvtav1 /
 *     libvpx / libaom / lame / opus / webp / freetype stack. Quality-focused software encode.
 *
 *   - **Android (LGPL, no third-party libs)** — nothing GPL, nothing external; hardware video
 *     encode/decode via MediaCodec (`h264_mediacodec`, `hevc_mediacodec`) plus FFmpeg's native
 *     software decoders and aac encoder. Safe for Play Store / closed-source distribution.
 *     Cross-compiled with the NDK toolchain (env `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` /
 *     `ANDROID_NDK_LATEST_HOME`, falling back to the SDK's newest `ndk/<version>`).
 *
 * Both share the same demuxer/decoder/filter core — the editor-relevant subset, ~75% smaller
 * than a "full" build (25 MB vs 110 MB per ABI).
 *
 * Expects an FFmpeg source tree at `vendor/ffmpeg`. Either a git submodule or a plain clone:
 * `git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg`.
 * The task is idempotent — it skips when the output dir is already populated.
 */
abstract class BuildFFmpegTask @Inject constructor() : DefaultTask() {

    @get:Input
    abstract var target: TargetTriple

    @get:Internal
    val sourceDir: File get() = project.rootDir.resolve("vendor/ffmpeg")

    @get:OutputDirectory
    val outputDir: File get() = project.rootDir.resolve("native-libs/${target.dirName}")

    init {
        group = "kitecodec"
        description = "Cross-compile FFmpeg for the given Kotlin/Native target."
    }

    @TaskAction
    fun run() {
        require(sourceDir.exists()) {
            "FFmpeg source not found at $sourceDir. Run:\n" +
                "  git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg\n" +
                "(or add it as a git submodule for reproducible builds)"
        }
        if (outputDir.resolve("lib/libavformat.a").exists()) {
            logger.lifecycle("Vendored FFmpeg already present at $outputDir — skipping rebuild.")
            return
        }
        outputDir.mkdirs()
        val buildDir = sourceDir.resolve("build/${target.dirName}").also { it.mkdirs() }

        val configureArgs = sharedCoreArgs() +
            (if (target.isAndroid) androidArgs() else desktopExtraArgs() + desktopTargetArgs()) +
            listOf("--prefix=${outputDir.absolutePath}")
        runIn(buildDir, listOf(sourceDir.resolve("configure").absolutePath) + configureArgs)
        runIn(buildDir, listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"))
        runIn(buildDir, listOf("make", "install"))
    }

    /** The codec/filter core both profiles share. */
    private fun sharedCoreArgs(): List<String> = listOf(
        // Static-only, no shared, no programs (`ffmpeg`/`ffprobe`/`ffplay`).
        "--enable-static", "--disable-shared",
        "--disable-programs", "--disable-doc", "--disable-debug",
        "--disable-htmlpages", "--disable-manpages", "--disable-podpages", "--disable-txtpages",

        // Codecs / muxers — opinionated, editor-relevant subset.
        "--disable-everything",
        "--enable-protocol=file,pipe,data",
        "--enable-demuxer=mov,mp4,m4v,matroska,webm,mp3,wav,aac,flac,ogg,opus,image2,png_pipe,jpeg_pipe",
        "--enable-muxer=mp4,mov,webm,matroska,mp3,wav,flac,ogg,opus,image2",
        "--enable-decoder=h264,hevc,vp8,vp9,av1,aac,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg,webp",
        "--enable-parser=h264,hevc,vp8,vp9,av1,aac,mpegaudio,opus,vorbis,flac,png",

        // buffer/buffersink/abuffer/abuffersink are how KiteCodec feeds and drains every graph —
        // without them ffkmp_graph_build_* returns AVERROR_FILTER_NOT_FOUND.
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,scale,pad,overlay,eq,hue,boxblur,unsharp,vignette,drawtext,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",

        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

    /** Desktop quality stack — GPL ladder + third-party encoders. */
    private fun desktopExtraArgs(): List<String> = listOf(
        "--enable-gpl", "--enable-version3",
        "--enable-encoder=libx264,libx265,libsvtav1,aac,libmp3lame,libopus,png,mjpeg",
        "--enable-libx264", "--enable-libx265",
        "--enable-libsvtav1",
        "--enable-libvpx", "--enable-libaom",
        "--enable-libmp3lame", "--enable-libopus",
        "--enable-libwebp",
        "--enable-libfreetype", "--enable-libharfbuzz", "--enable-libfribidi",
        "--enable-libass",
        "--enable-zlib", "--enable-bzlib",
    )

    private fun desktopTargetArgs(): List<String> = when (target) {
        TargetTriple.MacosArm64 -> listOf(
            "--arch=arm64", "--target-os=darwin",
            "--cc=clang -arch arm64",
            "--enable-cross-compile",
            "--enable-videotoolbox",
        )
        TargetTriple.MacosX64 -> listOf(
            "--arch=x86_64", "--target-os=darwin",
            "--cc=clang -arch x86_64",
            "--enable-cross-compile",
            "--enable-videotoolbox",
        )
        TargetTriple.IosArm64 -> {
            val sdk = "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
            listOf(
                "--arch=arm64", "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot $sdk -mios-version-min=14.0",
                "--enable-cross-compile",
                "--enable-videotoolbox",
                "--disable-asm",
            )
        }
        TargetTriple.IosSimulatorArm64 -> {
            val sdk = "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
            listOf(
                "--arch=arm64", "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot $sdk -mios-simulator-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
            )
        }
        TargetTriple.IosX64 -> {
            val sdk = "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
            listOf(
                "--arch=x86_64", "--target-os=darwin",
                "--cc=clang -arch x86_64 -isysroot $sdk -mios-simulator-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
            )
        }
        TargetTriple.LinuxX64 -> listOf("--arch=x86_64", "--target-os=linux", "--cc=clang")
        TargetTriple.LinuxArm64 -> listOf(
            "--arch=aarch64", "--target-os=linux",
            "--cc=aarch64-linux-gnu-gcc",
            "--enable-cross-compile",
        )
        TargetTriple.MingwX64 -> listOf(
            "--arch=x86_64", "--target-os=mingw32",
            "--cc=x86_64-w64-mingw32-gcc",
            "--enable-cross-compile",
        )
        else -> error("desktopTargetArgs called for non-desktop target $target")
    }

    /**
     * Android profile: LGPL (no `--enable-gpl`, no third-party libs), NDK clang toolchain,
     * MediaCodec hardware video encode/decode. `--enable-jni` is required by the MediaCodec
     * wrapper; at runtime the app must hand FFmpeg its JavaVM via `av_jni_set_java_vm` before
     * using `*_mediacodec` codecs (the surrounding KiteCodec Android substrate will own that call).
     */
    private fun androidArgs(): List<String> {
        val (arch, cpu, ccPrefix) = when (target) {
            TargetTriple.AndroidArm64 -> Triple("aarch64", "armv8-a", "aarch64-linux-android")
            TargetTriple.AndroidArm32 -> Triple("arm", "armv7-a", "armv7a-linux-androideabi")
            TargetTriple.AndroidX64 -> Triple("x86_64", null, "x86_64-linux-android")
            else -> error("androidArgs called for non-android target $target")
        }
        val toolchainBin = ndkToolchainBin()
        val cc = toolchainBin.resolve("$ccPrefix$ANDROID_API-clang")
        require(cc.exists()) { "NDK compiler not found: $cc" }

        return listOf(
            "--target-os=android", "--arch=$arch",
            "--enable-cross-compile",
            "--cc=${cc.absolutePath}",
            "--cxx=${cc.absolutePath}++",
            "--ar=${toolchainBin.resolve("llvm-ar").absolutePath}",
            "--ranlib=${toolchainBin.resolve("llvm-ranlib").absolutePath}",
            "--nm=${toolchainBin.resolve("llvm-nm").absolutePath}",
            "--strip=${toolchainBin.resolve("llvm-strip").absolutePath}",
            "--enable-mediacodec", "--enable-jni",
            "--enable-encoder=aac,png,mjpeg,h264_mediacodec,hevc_mediacodec",
            "--enable-decoder=h264_mediacodec,hevc_mediacodec",  // adds to the shared sw set
            "--enable-zlib",
        ) +
            (cpu?.let { listOf("--cpu=$it") } ?: emptyList()) +
            // x86_64 inline asm needs nasm in the env; arm32 asm is fragile with clang. Keep
            // arm64 asm (it matters for sw decode speed), disable elsewhere.
            (if (target != TargetTriple.AndroidArm64) listOf("--disable-asm") else emptyList())
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
                ?: error(
                    "Android NDK not found. Set ANDROID_NDK_HOME or install one via the SDK manager."
                )
        }
        val prebuilt = ndk.resolve("toolchains/llvm/prebuilt")
        val hostDir = prebuilt.listFiles { f: File -> f.isDirectory }?.firstOrNull()
            ?: error("No prebuilt toolchain under $prebuilt")
        return hostDir.resolve("bin")
    }

    private fun runIn(workDir: File, command: List<String>) {
        logger.lifecycle("[KiteCodec build] " + command.joinToString(" "))
        val proc = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { logger.lifecycle("  $it") }
        }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /** minSdk the native libs are built against — AMediaCodec needs 21+, 24 is a safe floor. */
        const val ANDROID_API = 24
    }
}
