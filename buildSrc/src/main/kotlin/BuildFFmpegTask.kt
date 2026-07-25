package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * Cross-compiles a minimal FFmpeg from source for one [target] under one [license] and writes the
 * resulting static archives + headers into `native-libs/<license>/<target.dirName>/{include,lib}`.
 * The Kotlin cinterop step picks them up via [FFmpegPaths].
 *
 * Desktop targets (macOS / iOS / Linux / mingw) build in one of two licence flavours:
 *
 *   - [FFmpegLicense.LGPL] (default): no `--enable-gpl`, no x264 / x265. VideoToolbox hardware
 *     encode plus a permissive software stack (svtav1, vpx, aom, mp3lame, opus, webp, freetype /
 *     harfbuzz / fribidi / ass). App-Store and closed-source safe.
 *   - [FFmpegLicense.GPL]: the LGPL stack plus libx264 / libx265 (`--enable-gpl`). Quality-focused
 *     software encode; open-source / server use only.
 *
 * The **Android** profile is always LGPL regardless of [license]: nothing GPL, nothing external;
 * hardware video encode/decode via MediaCodec (`h264_mediacodec`, `hevc_mediacodec`) plus FFmpeg's
 * native software decoders and aac encoder. Cross-compiled with the NDK toolchain (env
 * `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` / `ANDROID_NDK_LATEST_HOME`, falling back to the SDK's
 * newest `ndk/<version>`).
 *
 * Every flavour shares the same demuxer/decoder/filter core: the editor-relevant subset, ~75%
 * smaller than a "full" build (25 MB vs 110 MB per ABI).
 *
 * Expects an FFmpeg source tree at [sourceDir] (`vendor/ffmpeg` by convention). Either a git
 * submodule or a plain clone:
 * `git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg`.
 * Up-to-date checking is input/output based: [sourceRef] pins the FFmpeg commit/tag the checkout is
 * expected to hold, so bumping it (or changing target/licence/configure flags) triggers a rebuild
 * while an already-populated output directory keeps the task UP-TO-DATE.
 */
abstract class BuildFFmpegTask @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** Licence flavour. Android always builds LGPL; only desktop targets honour [FFmpegLicense.GPL]. */
    @get:Input
    abstract val license: Property<FFmpegLicense>

    /**
     * The FFmpeg tag/commit the [sourceDir] checkout is pinned to (for example `n8.0`). Declared as
     * an input so bumping the vendored FFmpeg invalidates previously built outputs. Hashing the
     * whole FFmpeg source tree as an input directory would be prohibitively slow.
     */
    @get:Input
    abstract val sourceRef: Property<String>

    /** The FFmpeg source checkout, `<repoRoot>/vendor/ffmpeg`. Tracked via [sourceRef], not by content. */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /**
     * Where the host package manager installed the third-party encoder/text libraries the desktop
     * macOS profile links (Homebrew's prefix: `/opt/homebrew` on Apple silicon, `/usr/local` on
     * Intel). Override with the `kitecodec.macos.homebrew.prefix` Gradle property. Unused on Linux
     * (system paths are already searched) and on the cross targets, which need their own
     * cross-built dependency stack rather than the host's.
     */
    @get:Input
    @get:Optional
    abstract val hostPrefix: Property<String>

    /**
     * Whether the produced tree must be fully self-contained: every third-party archive FFmpeg
     * links present as a `.a` inside it. False (the default) for local development, where linking
     * a dependency from the host is fine. True for anything that becomes a Release asset, where a
     * missing archive means the zip cannot link on a consumer's machine.
     *
     * Set with `-Pkitecodec.ffmpeg.selfContained=true`.
     */
    @get:Input
    @get:Optional
    abstract val requireSelfContained: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Cross-compile FFmpeg for the given Kotlin/Native target."
        license.convention(FFmpegLicense.LGPL)
    }

    @TaskAction
    fun run() {
        val target = target.get()
        val license = license.get()
        val sourceDir = sourceDir.get().asFile
        val outputDir = outputDir.get().asFile

        require(!(target.isAndroid && license == FFmpegLicense.GPL)) {
            "Android uses the LGPL MediaCodec profile only; there is no GPL Android build " +
                "(libx264 / libx265 are not part of the Android profile)."
        }
        require(sourceDir.exists()) {
            "FFmpeg source not found at $sourceDir. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} https://github.com/FFmpeg/FFmpeg vendor/ffmpeg\n" +
                "(or add it as a git submodule for reproducible builds)"
        }
        // FFmpeg builds with GNU make, and make starts a COMMENT at an unescaped '#'. A checkout
        // under a path containing one configures fine and then installs nothing: `prefix` is
        // silently truncated at the '#', `libdir` comes out empty, and `make install` still exits
        // 0. Catch it here rather than let the build "succeed" into an empty output directory.
        listOf(sourceDir.absolutePath, outputDir.absolutePath).forEach { path ->
            require('#' !in path) {
                "FFmpeg cannot be built under a path containing '#': $path\n" +
                    "GNU make treats it as a comment, so the install prefix is truncated and " +
                    "`make install` writes nothing while still reporting success. Move the " +
                    "checkout (and vendor/ffmpeg) to a path without '#'."
            }
        }
        outputDir.mkdirs()
        val buildDir = sourceDir.resolve("build/${license.dirName}/${target.dirName}").also { it.mkdirs() }

        val licenseArgs = if (target.isAndroid) {
            androidArgs(target)
        } else {
            desktopBaseArgs() +
                (if (license == FFmpegLicense.GPL) desktopGplArgs() else emptyList()) +
                desktopTargetArgs(target)
        }
        val configureArgs = sharedCoreArgs() + licenseArgs +
            listOf("--prefix=${outputDir.absolutePath}")
        val env = configureEnv(target)
        runIn(buildDir, listOf(sourceDir.resolve("configure").absolutePath) + configureArgs, env)
        runIn(buildDir, listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), env)
        runIn(buildDir, listOf("make", "install"), env)

        // `make install` exiting 0 is not proof it installed anything. FFmpeg's install rules are
        // driven by variables from config.mak, and a prefix make cannot parse yields a silent
        // no-op. An empty output directory here would then fall through to FFmpegPaths' system
        // lookup, which is exactly the "publication silently drops a target" failure the publish
        // guard exists to prevent. Verify the artefacts instead of trusting the exit code.
        val missing = REQUIRED_LIBS.filterNot { outputDir.resolve("lib/$it.a").isFile }
        check(missing.isEmpty()) {
            "FFmpeg reported a successful install but $outputDir is missing " +
                "${missing.joinToString { "lib/$it.a" }}. Check the configure/make output above; " +
                "the install prefix may not have been honoured."
        }
        check(outputDir.resolve("include/libavformat/avformat.h").isFile) {
            "FFmpeg installed libraries into $outputDir but no headers — cinterop needs both."
        }
        bundleThirdPartyArchives(target, license, outputDir)
        logger.lifecycle("[KiteCodec] FFmpeg ${sourceRef.get()} (${license.dirName}) installed into $outputDir")
    }

    /**
     * Copy the third-party static archives FFmpeg was linked against into the vendored tree's
     * `lib/`, so `native-libs/<license>/<target>` is self-contained and has exactly the layout the
     * published release zip does.
     *
     * `make install` only installs FFmpeg's own libraries. The static `libavcodec.a` it leaves
     * behind still carries undefined references to svt-av1, vpx, aom, opus, mp3lame, webp and the
     * text stack, which on the build machine live in the package manager's prefix. Without this the
     * tree links only on a machine that happens to have all of them installed. Vendoring exists to
     * avoid exactly that.
     */
    private fun bundleThirdPartyArchives(target: TargetTriple, license: FFmpegLicense, outputDir: File) {
        val wanted = StaticLinkFlags.thirdPartyArchives(target, license)
        if (wanted.isEmpty()) return

        val libDir = outputDir.resolve("lib").also { it.mkdirs() }
        val searchDirs = thirdPartySearchDirs(target).filter { it.isDirectory }
        val missing = mutableListOf<String>()
        wanted.forEach { archive ->
            if (libDir.resolve(archive).isFile) return@forEach
            val found = searchDirs.map { it.resolve(archive) }.firstOrNull { it.isFile }
            if (found == null) missing += archive else found.copyTo(libDir.resolve(archive), overwrite = true)
        }
        if (missing.isEmpty()) {
            logger.lifecycle("[KiteCodec] bundled ${wanted.size} third-party static archives into $libDir")
            return
        }

        // Some package managers ship a few of these as shared libraries only. Homebrew's svt-av1
        // is the standing example. A dev build can still link against the host's dylib, so warn
        // rather than block; a build destined for a Release asset cannot, so make it fatal there.
        val explanation =
            "These static third-party archives are not installed on this machine: " +
                "${missing.joinToString()}.\n" +
                "They are linked INTO libavcodec.a. Searched: ${searchDirs.joinToString()}.\n" +
                "Their package likely ships shared libraries only; the fix is to build those " +
                "dependencies statically from source. Never substitute the shared library into " +
                "the tree — that silently stops it being self-contained."
        if (requireSelfContained.getOrElse(false)) {
            throw GradleException(
                "$explanation\n" +
                    "-Pkitecodec.ffmpeg.selfContained=true was set (a distributable build), so " +
                    "this is fatal: the resulting zip would not link on a consumer's machine.",
            )
        }
        logger.warn(
            "warning: [KiteCodec] $outputDir is NOT self-contained.\n" +
                "$explanation\n" +
                "Local builds link these from the host instead, which is fine for development. " +
                "Pass -Pkitecodec.ffmpeg.selfContained=true to make this a hard failure.",
        )
    }

    /** Where the host package manager keeps the static archives, most specific first. */
    private fun thirdPartySearchDirs(target: TargetTriple): List<File> = when (target) {
        TargetTriple.MacosArm64, TargetTriple.MacosX64,
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64,
        -> {
            val prefix = File(hostPrefix.getOrElse(DEFAULT_HOMEBREW_PREFIX))
            // Homebrew symlinks into <prefix>/lib, but keg-only formulas stay under opt/<name>/lib.
            listOf(prefix.resolve("lib")) +
                (prefix.resolve("opt").listFiles()?.map { it.resolve("lib") }?.sortedBy { it.path } ?: emptyList())
        }
        TargetTriple.LinuxX64 -> listOf(
            File("/usr/lib/x86_64-linux-gnu"), File("/usr/lib"), File("/usr/local/lib"),
        )
        TargetTriple.LinuxArm64 -> listOf(
            File("/usr/lib/aarch64-linux-gnu"), File("/usr/lib"), File("/usr/local/lib"),
        )
        else -> emptyList()
    }

    /**
     * Environment for configure/make. Only macOS needs anything: Homebrew installs under a prefix
     * that is NOT on the compiler's default search path, and while FFmpeg finds most dependencies
     * through pkg-config, some (libmp3lame above all) are probed with a bare compile-and-link test
     * that has no way to learn the prefix. Exporting PKG_CONFIG_PATH covers the first group and
     * [macosPrefixArgs] the second.
     */
    private fun configureEnv(target: TargetTriple): Map<String, String> =
        if (target == TargetTriple.MacosArm64 || target == TargetTriple.MacosX64) {
            val prefix = hostPrefix.getOrElse(DEFAULT_HOMEBREW_PREFIX)
            val existing = System.getenv("PKG_CONFIG_PATH").orEmpty()
            val paths = listOf("$prefix/lib/pkgconfig", "$prefix/share/pkgconfig")
                .plus(if (existing.isNotEmpty()) listOf(existing) else emptyList())
            mapOf("PKG_CONFIG_PATH" to paths.joinToString(":"))
        } else {
            emptyMap()
        }

    /**
     * `--extra-cflags` / `--extra-ldflags` pointing at the Homebrew prefix. Without these the
     * desktop macOS build cannot configure at all. It dies on `ERROR: libmp3lame >= 3.98.3 not
     * found` even with `brew install lame` done, because lame ships no pkg-config file.
     */
    private fun macosPrefixArgs(): List<String> {
        val prefix = hostPrefix.getOrElse(DEFAULT_HOMEBREW_PREFIX)
        return listOf(
            "--extra-cflags=-I$prefix/include",
            "--extra-ldflags=-L$prefix/lib",
        )
    }

    /** The codec/filter core both profiles share. */
    private fun sharedCoreArgs(): List<String> = listOf(
        // Static-only, no shared, no programs (`ffmpeg`/`ffprobe`/`ffplay`).
        "--enable-static", "--disable-shared",
        "--disable-programs", "--disable-doc", "--disable-debug",
        "--disable-htmlpages", "--disable-manpages", "--disable-podpages", "--disable-txtpages",

        // Codecs / muxers: opinionated, editor-relevant subset.
        "--disable-everything",
        // file/pipe/data always; http+tcp so MediaSource.open() can actually take the URLs its
        // KDoc advertises. https is deliberately absent: it needs a TLS backend (openssl/gnutls/
        // mbedtls) cross-built for every target, which is a dependency escalation this profile
        // does not take on. See docs/platforms.md. --enable-network is explicit rather than
        // inherited so a target whose configure probe defaults it off fails loudly here.
        "--enable-network",
        "--enable-protocol=file,pipe,data,http,tcp",
        // Several everyday extensions map to their OWN muxer rather than to the obvious one, and
        // `avformat_alloc_output_context2` simply fails to find a format when that muxer is absent:
        //   .mka → matroska_audio (not matroska)   .m4a → ipod (not mp4)
        // mpegts is what every "trim a broadcast capture" path needs, and it is the format whose
        // nonzero container start time the timestamp code is written against.
        "--enable-demuxer=mov,mp4,m4v,matroska,webm,mp3,wav,aac,flac,ogg,opus,mpegts,image2,png_pipe,jpeg_pipe",
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        "--enable-decoder=h264,hevc,vp8,vp9,av1,mpeg4,aac,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg,webp",
        // Encoders that need no third-party library, so every profile (desktop LGPL, desktop GPL,
        // Android) has them. mpeg4 is the dependency-free video baseline: without it an LGPL build
        // can only encode video via libsvtav1 (slow) or mjpeg (intra-only), and the library's own
        // round-trip tests and sample have nothing portable to write with. flac and the pcm_* set
        // are what make the already-enabled flac/wav MUXERS able to write anything at all.
        "--enable-encoder=mpeg4,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",
        "--enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video,aac,mpegaudio,opus,vorbis,flac,png",

        // Bitstream filters. libavformat inserts these ITSELF during stream copy, so leaving them
        // out does not produce a clear error. It produces a corrupt output file. Copying h264 out
        // of MPEG-TS into mp4 needs extract_extradata (TS carries parameter sets in band, mp4 wants
        // them in the header) and copying AAC out of TS/ADTS needs aac_adtstoasc. Without them the
        // muxer writes a file that ffprobe rejects with "No start code is found".
        "--enable-bsf=extract_extradata,h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,vp9_superframe,null",

        // buffer/buffersink/abuffer/abuffersink are how KiteCodec feeds and drains every graph.
        // Without them ffkmp_graph_build_* returns AVERROR_FILTER_NOT_FOUND.
        // Only filters EVERY profile can actually provide. Two kinds of exception live elsewhere,
        // because configure silently drops a filter whose dependencies are unmet. Listing one
        // here would make this a promise some builds quietly break:
        //   - drawtext needs libfreetype/libharfbuzz → desktopBaseArgs (Android links neither).
        //   - eq and boxblur are `deps="gpl"` in FFmpeg's own configure → desktopGplArgs.
        // `hue` (which has a brightness parameter), `colorlevels` and `curves` cover most of what
        // `eq` is reached for, and are available everywhere.
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,scale,pad,overlay,hue,unsharp,vignette,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",

        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

    /**
     * Desktop LGPL stack: permissive software encoders + the text/subtitle rendering libs. No
     * `--enable-gpl`, no x264 / x265. The default flavour.
     */
    private fun desktopBaseArgs(): List<String> = listOf(
        // Extends the dependency-free set in sharedCoreArgs: configure accumulates --enable-encoder.
        "--enable-encoder=libsvtav1,aac,libmp3lame,libopus",
        "--enable-libsvtav1",
        "--enable-libvpx", "--enable-libaom",
        "--enable-libmp3lame", "--enable-libopus",
        "--enable-libwebp",
        "--enable-libfreetype", "--enable-libharfbuzz", "--enable-libfribidi",
        "--enable-libass",
        // Needs the text stack above, so it is desktop-only (see the note in sharedCoreArgs).
        "--enable-filter=drawtext",
        "--enable-zlib", "--enable-bzlib",
    )

    /**
     * Desktop GPL add-on: libx264 / libx265 software encoders. Layered on top of [desktopBaseArgs]
     * only when [license] is [FFmpegLicense.GPL]. FFmpeg's configure accumulates repeated
     * `--enable-encoder` flags, so this extends rather than replaces the base encoder list.
     */
    private fun desktopGplArgs(): List<String> = listOf(
        "--enable-gpl", "--enable-version3",
        "--enable-encoder=libx264,libx265",
        "--enable-libx264", "--enable-libx265",
        // FFmpeg marks these `deps="gpl"`, so they exist ONLY in this flavour. They were listed in
        // the shared set for a long time, where configure silently dropped them, which is how
        // `eq=brightness=…`, the filter every example reaches for, ended up unavailable in the
        // LGPL build the project actually ships.
        "--enable-filter=eq,boxblur",
    )

    /**
     * VideoToolbox hardware encode on Apple targets.
     *
     * `--enable-videotoolbox` alone only turns on the framework dependency. Under
     * `--disable-everything` every encoder must additionally be named in `--enable-encoder=`, or
     * the resulting build advertises VideoToolbox support and then has no `h264_videotoolbox`
     * encoder to find at runtime. The Android profile avoids that mistake by listing
     * `h264_mediacodec` explicitly. Simulator targets are excluded: VideoToolbox encode is not
     * available there.
     */
    private fun appleHardwareArgs(): List<String> = listOf(
        "--enable-videotoolbox",
        "--enable-encoder=h264_videotoolbox,hevc_videotoolbox",
    )

    private fun desktopTargetArgs(target: TargetTriple): List<String> = when (target) {
        TargetTriple.MacosArm64 -> listOf(
            "--arch=arm64", "--target-os=darwin",
            "--cc=clang -arch arm64",
            "--enable-cross-compile",
        ) + appleHardwareArgs() + macosPrefixArgs()
        TargetTriple.MacosX64 -> listOf(
            "--arch=x86_64", "--target-os=darwin",
            "--cc=clang -arch x86_64",
            "--enable-cross-compile",
        ) + appleHardwareArgs() + macosPrefixArgs()
        TargetTriple.IosArm64 -> {
            val sdk = xcrunSdkPath("iphoneos")
            listOf(
                "--arch=arm64", "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot $sdk -mios-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
            ) + appleHardwareArgs()
        }
        TargetTriple.IosSimulatorArm64 -> {
            val sdk = xcrunSdkPath("iphonesimulator")
            listOf(
                "--arch=arm64", "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot $sdk -mios-simulator-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
            )
        }
        TargetTriple.IosX64 -> {
            val sdk = xcrunSdkPath("iphonesimulator")
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

    /** Resolves an Apple SDK sysroot via `xcrun`, so the path tracks the installed Xcode. */
    private fun xcrunSdkPath(sdkName: String): String {
        val proc = ProcessBuilder("xcrun", "--sdk", sdkName, "--show-sdk-path")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && output.isNotEmpty()) {
            "xcrun --sdk $sdkName --show-sdk-path failed: $output"
        }
        return output
    }

    /**
     * Android profile: LGPL (no `--enable-gpl`, no third-party libs), NDK clang toolchain,
     * MediaCodec hardware video encode/decode. `--enable-jni` is required by the MediaCodec
     * wrapper; at runtime the app must hand FFmpeg its JavaVM via `av_jni_set_java_vm` before
     * using `*_mediacodec` codecs (the surrounding KiteCodec Android substrate will own that call).
     */
    private fun androidArgs(target: TargetTriple): List<String> {
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
            // Adds to the dependency-free encoder set in sharedCoreArgs.
            "--enable-encoder=aac,h264_mediacodec,hevc_mediacodec",
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

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String> = emptyMap()) {
        logger.lifecycle("[KiteCodec build] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { logger.lifecycle("  $it") }
        }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /** minSdk the native libs are built against: AMediaCodec needs 21+, 24 is a safe floor. */
        const val ANDROID_API = 24

        /** The FFmpeg tag `vendor/ffmpeg` is expected to be checked out at. */
        const val DEFAULT_SOURCE_REF = "n8.0"

        /** Homebrew's prefix on Apple silicon; Intel Macs use /usr/local (override via the property). */
        const val DEFAULT_HOMEBREW_PREFIX = "/opt/homebrew"

        /** The six libav* archives every profile must produce; a partial install must never ship. */
        val REQUIRED_LIBS = listOf(
            "libavcodec", "libavformat", "libavutil",
            "libavfilter", "libswscale", "libswresample",
        )
    }
}
