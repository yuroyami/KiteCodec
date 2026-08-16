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
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import javax.inject.Inject

private val TargetTriple.isIos: Boolean
    get() = this == TargetTriple.IosArm64 ||
        this == TargetTriple.IosSimulatorArm64 ||
        this == TargetTriple.IosX64

/**
 * Cross-compiles a minimal FFmpeg from source for one [target] under one [license] and writes the
 * resulting static archives + headers into `native-libs/<license>/<target.dirName>/{include,lib}`.
 * The Kotlin cinterop step picks them up via [FFmpegPaths].
 *
 * Desktop targets (macOS / Linux / mingw) build in one of two licence flavours:
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
 * The **iOS** profile is also LGPL-only: the shared software playback core plus SDK zlib, with no
 * desktop encoder/text stack and no VideoToolbox profile.
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

    /** Licence flavour. Android and iOS are LGPL-only; only desktop targets honour [FFmpegLicense.GPL]. */
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
     * Committed source patches from `native/patches/ffmpeg`, applied in name order to the SCRATCH
     * copy of the source before configure. The vendored checkout itself stays pristine at
     * [sourceRef], which is what keeps its tracked-by-ref identity honest. Content-tracked, so
     * editing a patch rebuilds. Each application is `patch -p1` with `--forward` and a rejected
     * hunk fails the build loudly. The applied list and each patch's SHA-256 are written beside
     * the configure evidence in the install tree (`lib/kitecodec/ffmpeg-patches.txt`), so a
     * provenance question about a prebuilt tree has a one-file answer. First patch and the reason
     * it exists: KPKMP hotfix window 2c, the h264_mp4toannexb 4-byte start codes the Goldfish
     * API 36 MediaCodec decoder requires (measured 2026-08-12).
     */
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sourcePatches: org.gradle.api.file.ConfigurableFileCollection

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

    /**
     * Compile FFmpeg's libdav1d AV1 software decoder in (KPKMP 17.12 D-7, register row
     * KC-AV1SW). Off by default: dav1d is an OPTIONAL dependency, and a build that never asked
     * for it must stay byte-identical to one from before this switch existed. When true, the
     * cross-built static dav1d from [BuildDav1dTask] must already sit in
     * `native-libs/deps/<target>/`; the build fails with the task to run otherwise.
     */
    @get:Input
    @get:Optional
    abstract val enableDav1d: Property<Boolean>

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
        require(!(target.isIos && license == FFmpegLicense.GPL)) {
            IOS_GPL_REFUSAL
        }
        require(sourceDir.exists()) {
            "FFmpeg source not found at $sourceDir. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} https://github.com/FFmpeg/FFmpeg vendor/ffmpeg\n" +
                "(or add it as a git submodule for reproducible builds)"
        }
        val scratch = createScratchWorkspace(Path.of(System.getProperty("java.io.tmpdir")))
        var succeeded = false
        try {
            val scratchSource = scratch.resolve("source")
            val scratchBuild = scratch.resolve("build").also(Files::createDirectories)
            val scratchInstall = scratch.resolve("install")
            copySourceTree(sourceDir.toPath(), scratchSource)

            val patches = sourcePatches.files.filter { it.name.endsWith(".patch") }.sortedBy { it.name }
            patches.forEach { patchFile ->
                runIn(
                    scratchSource.toFile(),
                    listOf("/usr/bin/patch", "-p1", "--forward", "--fuzz=0", "-i", patchFile.absolutePath),
                )
            }

            val dav1dRoot = dav1dRootOrNull(target)
            val configureArgs = configureArguments(
                target = target,
                license = license,
                installPrefix = scratchInstall.toAbsolutePath().toString(),
                dav1dRoot = dav1dRoot,
            )
            val env = configureEnv(target, dav1dRoot)
            runIn(
                scratchBuild.toFile(),
                listOf(scratchSource.resolve("configure").toAbsolutePath().toString()) + configureArgs,
                env,
            )
            runIn(scratchBuild.toFile(), listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), env)
            runIn(scratchBuild.toFile(), listOf("make", "install"), env)

            writeConfigureEvidence(scratchBuild.resolve("ffbuild/config.log"), scratchInstall)
            run {
                val evidenceDir = scratchInstall.resolve("lib/kitecodec").also(Files::createDirectories)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val lines = buildString {
                    appendLine("# Source patches applied to the scratch FFmpeg before configure, in order.")
                    if (patches.isEmpty()) appendLine("(none)")
                    patches.forEach { p ->
                        val sha = digest.digest(p.readBytes()).joinToString("") { "%02x".format(it) }
                        appendLine("${p.name}  sha256=$sha")
                        digest.reset()
                    }
                }
                Files.writeString(evidenceDir.resolve("ffmpeg-patches.txt"), lines, UTF_8)
            }
            verifyInstall(scratchInstall)
            bundleThirdPartyArchives(target, license, scratchInstall.toFile())
            verifyInstall(scratchInstall)
            replaceOutputTree(scratchInstall, outputDir.toPath())
            succeeded = true
            logger.lifecycle(
                "[KiteCodec] FFmpeg ${sourceRef.get()} (${license.dirName}) installed into $outputDir",
            )
        } catch (failure: Throwable) {
            logger.error("[KiteCodec] FFmpeg scratch retained after failure: $scratch")
            throw failure
        } finally {
            if (succeeded) scratch.toFile().deleteRecursively()
        }
    }

    internal fun configureArguments(
        target: TargetTriple,
        license: FFmpegLicense,
        installPrefix: String,
        sdkPath: (String) -> String = ::xcrunSdkPath,
        ndkToolchainBin: () -> File = ::ndkToolchainBin,
        dav1dRoot: File? = null,
    ): List<String> {
        require(!(target.isAndroid && license == FFmpegLicense.GPL))
        require(!(target.isIos && license == FFmpegLicense.GPL)) { IOS_GPL_REFUSAL }
        val profileArgs = when {
            target.isAndroid -> androidArgs(target, ndkToolchainBin())
            target.isIos -> mobileAppleArgs(target, sdkPath)
            else -> desktopBaseArgs() +
                (if (license == FFmpegLicense.GPL) desktopGplArgs() else emptyList()) +
                desktopTargetArgs(target)
        }
        // The optional dav1d switch (D-7). configure discovers dav1d ONLY through pkg-config,
        // so the host pkg-config is forced even on cross builds; configureEnv points it at the
        // deps tree and nothing else. The decoder pin makes the intent survive class policy
        // changes exactly like the hwaccel pins do.
        val dav1dArgs = if (dav1dRoot != null) {
            listOf("--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config")
        } else {
            emptyList()
        }
        return sharedCoreArgs() + profileArgs + dav1dArgs + listOf("--prefix=$installPrefix")
    }

    /** The cross-built dav1d install for [target], or null when the switch is off. */
    private fun dav1dRootOrNull(target: TargetTriple): File? {
        if (!enableDav1d.getOrElse(false)) return null
        val root = outputDir.get().asFile.parentFile.parentFile.resolve("deps/${target.dirName}")
        require(root.resolve("lib/libdav1d.a").isFile) {
            "dav1d was requested but native-libs/deps/${target.dirName}/lib/libdav1d.a does not " +
                "exist. Run :kitecodec-core:buildDav1dFor${target.gradleSuffix} first."
        }
        return root
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
        val wanted = StaticLinkFlags.thirdPartyArchives(target, license, dav1d = enableDav1d.getOrElse(false))
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
                "the tree, which silently stops it being self-contained."
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
    private fun thirdPartySearchDirs(target: TargetTriple): List<File> = listOfNotNull(
        // The cross-built deps tree first: a bundled dav1d must win over any host copy.
        outputDir.get().asFile.parentFile.parentFile.resolve("deps/${target.dirName}/lib")
            .takeIf { enableDav1d.getOrElse(false) },
    ) + when (target) {
        TargetTriple.MacosArm64, TargetTriple.MacosX64 -> {
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
    private fun configureEnv(target: TargetTriple, dav1dRoot: File? = null): Map<String, String> {
        val dav1dPkg = dav1dRoot?.resolve("lib/pkgconfig")?.absolutePath
        if (target == TargetTriple.MacosArm64 || target == TargetTriple.MacosX64) {
            val prefix = hostPrefix.getOrElse(DEFAULT_HOMEBREW_PREFIX)
            val existing = System.getenv("PKG_CONFIG_PATH").orEmpty()
            val paths = listOfNotNull(dav1dPkg) + listOf("$prefix/lib/pkgconfig", "$prefix/share/pkgconfig")
                .plus(if (existing.isNotEmpty()) listOf(existing) else emptyList())
            return mapOf("PKG_CONFIG_PATH" to paths.joinToString(":"))
        }
        // Cross targets: PKG_CONFIG_LIBDIR (not PATH) so the host's own .pc files can never
        // leak into an Android or iOS link. The deps tree is the whole pkg-config universe.
        return if (dav1dPkg != null) mapOf("PKG_CONFIG_LIBDIR" to dav1dPkg) else emptyMap()
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

        // The READ side is wide by class (KitePlayer KPKMP.md 17.4.9, owner order 2026-08-13):
        // decoders, demuxers, parsers, bitstream filters and hwaccels compile whole, so a
        // consumer plays what FFmpeg can play and an FFmpeg bump widens coverage without
        // touching this file. Bitstream filters ride whole with the demuxers because
        // libavformat inserts them ITSELF during stream copy; leaving one out corrupts output
        // silently instead of failing loudly. Only the WRITE side and the protocol list stay
        // curated: encoders, muxers and filters are the deliberate editor subset re-enabled by
        // name below, and protocols stay small because they are the attack surface (playlist
        // demuxers dial out through whatever protocols exist). Capture and playback devices
        // stay off entirely.
        "--disable-encoders", "--disable-muxers", "--disable-filters",
        "--disable-devices", "--disable-protocols",
        // file/pipe/data always; http+tcp so MediaSource.open() can actually take the URLs its
        // KDoc advertises. https is deliberately absent: it needs a TLS backend (openssl/gnutls/
        // mbedtls) cross-built for every target, which is a dependency escalation this profile
        // does not take on. See docs/platforms.md. --enable-network is explicit rather than
        // inherited so a target whose configure probe defaults it off fails loudly here. The
        // wide demuxer class includes members that SELECT network protocols (rtsp and its
        // relatives); the class disable above must win, so the configure banner's protocol
        // line is checked against exactly this five-name list after every profile change.
        // `fd` is what makes an Android content:// URI playable. The picker hands back a
        // descriptor, and the usual escape of re-opening `/proc/self/fd/N` through the `file`
        // protocol fails with EACCES on a modern device, because re-opening rechecks permissions
        // against the PATH while the descriptor itself stays perfectly valid (measured on a real
        // phone, 2026-08-13). The `fd` protocol takes the descriptor as a pre-open option and
        // `dup()`s it, so nothing is re-opened, and its fstat sets is_streamed correctly, which
        // keeps a regular file SEEKABLE. `pipe:<fd>` is not a substitute: it dups too, but hard
        // codes is_streamed = 1, so seeking dies and with it any sync.
        "--enable-network",
        "--enable-protocol=file,fd,pipe,data,http,tcp",
        // Fixed point 5 of 17.4.9, observed on the first wide configure: the rtsp/sdp demuxers
        // SELECT udp and rtp, and configure's select is stronger than a class disable, so the
        // banner grew both. A named disable is stronger than a select: with these two hard-off,
        // configure drops the demuxers that need them instead of resurrecting the protocols.
        "--disable-protocol=udp,rtp",
        // Several everyday extensions map to their OWN muxer rather than to the obvious one, and
        // `avformat_alloc_output_context2` simply fails to find a format when that muxer is absent:
        //   .mka → matroska_audio (not matroska)   .m4a → ipod (not mp4)
        // mpegts is what every "trim a broadcast capture" path needs, and it is the format whose
        // nonzero container start time the timestamp code is written against.
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        // Encoders that need no third-party library, so every profile (desktop LGPL, desktop GPL,
        // Android) has them. mpeg4 is the dependency-free video baseline: without it an LGPL build
        // can only encode video via libsvtav1 (slow) or mjpeg (intra-only), and the library's own
        // round-trip tests and sample have nothing portable to write with. flac and the pcm_* set
        // are what make the already-enabled flac/wav MUXERS able to write anything at all.
        "--enable-encoder=mpeg4,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",

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
     * VideoToolbox hardware encode on macOS desktop targets.
     *
     * `--enable-videotoolbox` alone only turns on the framework dependency. Under
     * `--disable-encoders` every encoder must additionally be named in `--enable-encoder=`, or
     * the resulting build advertises VideoToolbox support and then has no `h264_videotoolbox`
     * encoder to find at runtime. The Android profile avoids that mistake by listing
     * `h264_mediacodec` explicitly. Simulator targets are excluded: VideoToolbox encode is not
     * available there.
     */
    private fun appleHardwareArgs(): List<String> = listOf(
        "--enable-videotoolbox",
        "--enable-encoder=h264_videotoolbox,hevc_videotoolbox",
    ) + appleHwaccelDecodeArgs()

    /**
     * VideoToolbox hardware DECODE (KiteCodec window 3, KPKMP 17.4.8 S2.a). Unlike MediaCodec
     * there is no named decoder to enable: VideoToolbox decode is an hwaccel behind the ordinary
     * `h264`/`hevc` decoders. Since the 17.4.9 wide profile the hwaccel class compiles whole, so
     * this list is a PIN rather than the sole source: it guarantees the two hwaccels the player's
     * D-2 route depends on exist even if the class policy above ever changes. Decode is enabled
     * for EVERY Apple target including the simulator (decode works there on Apple silicon; it is
     * encode that does not), and a runtime refusal on any particular machine is FFmpeg's own
     * typed answer through `ffkmp_codecctx_use_videotoolbox`, which D-2's measured fallback
     * treats as one more fallback cause.
     */
    private fun appleHwaccelDecodeArgs(): List<String> = listOf(
        "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox",
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

    /** Mobile Apple playback profile: shared software codecs plus SDK zlib, and no desktop stack. */
    private fun mobileAppleArgs(target: TargetTriple, sdkPath: (String) -> String): List<String> {
        val crossArgs = when (target) {
            TargetTriple.IosArm64 -> {
                val sdk = sdkPath("iphoneos")
                listOf(
                    "--arch=arm64", "--target-os=darwin",
                    "--cc=clang -arch arm64 -isysroot $sdk -mios-version-min=14.0",
                    "--enable-cross-compile",
                    "--disable-asm",
                )
            }
            TargetTriple.IosSimulatorArm64 -> {
                val sdk = sdkPath("iphonesimulator")
                listOf(
                    "--arch=arm64", "--target-os=darwin",
                    "--cc=clang -arch arm64 -isysroot $sdk -mios-simulator-version-min=14.0",
                    "--enable-cross-compile",
                    "--disable-asm",
                )
            }
            TargetTriple.IosX64 -> {
                val sdk = sdkPath("iphonesimulator")
                listOf(
                    "--arch=x86_64", "--target-os=darwin",
                    "--cc=clang -arch x86_64 -isysroot $sdk -mios-simulator-version-min=14.0",
                    "--enable-cross-compile",
                    "--disable-asm",
                )
            }
            else -> error("mobileAppleArgs called for non-iOS target $target")
        }
        return listOf("--disable-autodetect", "--enable-zlib", "--enable-videotoolbox") +
            appleHwaccelDecodeArgs() + crossArgs
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
    private fun androidArgs(target: TargetTriple, toolchainBin: File): List<String> {
        val (arch, cpu, ccPrefix) = when (target) {
            TargetTriple.AndroidArm64 -> Triple("aarch64", "armv8-a", "aarch64-linux-android")
            TargetTriple.AndroidArm32 -> Triple("arm", "armv7-a", "armv7a-linux-androideabi")
            TargetTriple.AndroidX64 -> Triple("x86_64", null, "x86_64-linux-android")
            else -> error("androidArgs called for non-android target $target")
        }
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
            // Adds to the shared sw set. av1/vp9/vp8 ride along because FFmpeg has NO native
            // software AV1 decoder (av1dec.c is a hwaccel shell): on Android the MediaCodec
            // wrappers are the only AV1 route this profile can offer, and most devices carry
            // an AV1 MediaCodec from Android 10 on. Software AV1 needs vendored dav1d, which
            // is recorded in KPKMP 17.11 rather than pretended here.
            "--enable-decoder=h264_mediacodec,hevc_mediacodec,av1_mediacodec,vp9_mediacodec,vp8_mediacodec",
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

    /**
     * One place where the expected FFmpeg release is written down, and where that is.
     *
     * Register item B1-04: the `n8.0` expectation lives in three files bound only by a comment in the
     * third one asking the reader to keep them in sync. Nothing enforced it, and nothing checked any of
     * them against the vendored checkout. [assertFFmpegRefsAgree] is the enforcement, and this is what
     * it compares. Declared at class level and not inside the companion, because a class nested in a
     * companion is spelled `BuildFFmpegTask.Companion.FFmpegRefSite` at the call site, which is not a
     * name anyone should have to write.
     */
    data class FFmpegRefSite(val where: String, val ref: String)

    companion object {
        /**
         * NDK API level the native libs are built against. The published minSdk is 26 (Android
         * 8.0), but the FFmpeg trees deliberately stay at 24: binaries built against a lower API
         * level run unchanged on newer devices, and raising this forces a full rebuild of every
         * Android FFmpeg tree for zero functional gain. Raise it only when a libav feature
         * actually needs a newer NDK symbol.
         */
        const val ANDROID_API = 24

        /** The FFmpeg tag `vendor/ffmpeg` is expected to be checked out at. */
        const val DEFAULT_SOURCE_REF = "n8.0"

        /**
         * Normalises an FFmpeg release reference so a git tag and a release file can be compared.
         *
         * FFmpeg's git tags are `n8.0`; the `RELEASE` file in a checkout of that tag says `8.0`. Both
         * name the same release, so the leading `n` is dropped and the string is trimmed. Nothing else
         * is normalised: `8.0` and `8.0.1` must stay different, because they are.
         */
        fun normaliseFFmpegRef(ref: String): String = ref.trim().removePrefix("n")

        /**
         * Fails when the places that record the expected FFmpeg release do not agree.
         *
         * Pure, so `BuildFFmpegRefsTest` can hand it agreeing and disagreeing inputs without a build.
         * The message names every site and its value rather than only the mismatch, because the question
         * a reader has at that moment is "which one is wrong", and that needs all of them.
         *
         * [vendorRelease] is the `RELEASE` file of `vendor/ffmpeg` when the checkout is present, and null
         * when it is not. A missing checkout is not a failure: the vendored path is optional and most
         * builds use a prebuilt or system FFmpeg. A PRESENT checkout at the wrong release is a failure,
         * because that is the case where a build would compile against headers nobody declared.
         */
        fun assertFFmpegRefsAgree(sites: List<FFmpegRefSite>, vendorRelease: String?) {
            require(sites.isNotEmpty()) { "assertFFmpegRefsAgree needs at least one site" }
            val all = sites + listOfNotNull(
                vendorRelease?.let { FFmpegRefSite("vendor/ffmpeg/RELEASE", it) },
            )
            val distinct = all.map { normaliseFFmpegRef(it.ref) }.distinct()
            if (distinct.size == 1) return
            throw GradleException(
                buildString {
                    appendLine(
                        "The expected FFmpeg release is recorded in ${all.size} place(s) and they do " +
                            "not agree (register item B1-04). Found ${distinct.size} distinct values:",
                    )
                    for (site in all) {
                        appendLine("  ${site.where}: ${site.ref} (normalised ${normaliseFFmpegRef(site.ref)})")
                    }
                    appendLine()
                    append(
                        "A consumer that downloads one release and links it against a klib whose C was " +
                            "compiled against another gets a successful static link and wrong struct " +
                            "field offsets, which is register item B1-02. Make all of them name one " +
                            "release, or check the vendored tree out at the release they name.",
                    )
                },
            )
        }

        /**
         * Reads `FFMPEG_VERSION: <ref>` out of a GitHub Actions workflow's `env:` block.
         *
         * Returns null when the key is absent, which the caller reports as its own failure: a workflow
         * that stopped pinning the release is a drift this check exists to catch, and silently treating
         * it as agreement would be the wrong answer.
         */
        fun readWorkflowFFmpegVersion(workflowText: String): String? =
            workflowText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("FFMPEG_VERSION:") }
                ?.substringAfter(':')
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf { it.isNotEmpty() }

        /**
         * Reads `DEFAULT_FFMPEG_VERSION = "<ref>"` out of `KiteCodecPlugin.kt`'s source text.
         *
         * Out of the TEXT, and not by referencing the constant, because it cannot be referenced from
         * here: `kitecodec-gradle-plugin` is a separate Gradle project and its classes are not on the
         * root build script's classpath, while `buildSrc`'s are. That asymmetry is the reason the value
         * was duplicated in the first place, so reading the source is the check register item B1-04 needs
         * rather than a workaround for one it does not.
         */
        fun readPluginDefaultFFmpegVersion(pluginSourceText: String): String? =
            Regex("""DEFAULT_FFMPEG_VERSION\s*=\s*"([^"]+)"""")
                .find(pluginSourceText)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotEmpty() }

        /** Reads `LIBAVUTIL_VERSION_MAJOR` out of a vendored `libavutil/version.h`, or null. */
        fun readVendoredAvutilMajor(versionHeaderText: String): Int? =
            Regex("""^\s*#define\s+LIBAVUTIL_VERSION_MAJOR\s+(\d+)""", RegexOption.MULTILINE)
                .find(versionHeaderText)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** Homebrew's prefix on Apple silicon; Intel Macs use /usr/local (override via the property). */
        const val DEFAULT_HOMEBREW_PREFIX = "/opt/homebrew"

        /** The six libav* archives every profile must produce; a partial install must never ship. */
        val REQUIRED_LIBS = listOf(
            "libavcodec", "libavformat", "libavutil",
            "libavfilter", "libswscale", "libswresample",
        )

        /** Stable installed provenance path consumed by packaging and release evidence. */
        const val CONFIGURE_EVIDENCE_RELATIVE_PATH = "lib/kitecodec/ffmpeg-configure.txt"

        /** Creates the unique hash-free workspace that is the only path configure and make see. */
        internal fun createScratchWorkspace(temporaryRoot: Path): Path {
            Files.createDirectories(temporaryRoot)
            val workspace = Files.createTempDirectory(temporaryRoot, "kitecodec-ffmpeg-")
            require('#' !in workspace.toAbsolutePath().toString()) {
                "java.io.tmpdir must resolve to a path without '#': $workspace"
            }
            return workspace
        }

        /** Copies FFmpeg source while excluding repository metadata and every stale build subtree. */
        internal fun copySourceTree(source: Path, destination: Path) {
            copyTree(source, destination, excludeBuildState = true)
        }

        private fun copyTree(source: Path, destination: Path, excludeBuildState: Boolean) {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (
                            dir != source &&
                            excludeBuildState &&
                            (dir.fileName.toString() == ".git" || dir.fileName.toString() == "build")
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        Files.createDirectories(destination.resolve(source.relativize(dir)))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val target = destination.resolve(source.relativize(file))
                        Files.createDirectories(target.parent)
                        Files.copy(
                            file,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        /**
         * Installs the first configure invocation recorded by FFmpeg into the output tree.
         *
         * FFmpeg prefixes that line with `# ` in `ffbuild/config.log`; only that exact marker is
         * removed. The command itself is otherwise byte-for-byte stable and the installed record
         * is always one UTF-8 line terminated by exactly one newline.
         */
        internal fun writeConfigureEvidence(configLog: Path, install: Path) {
            check(Files.isRegularFile(configLog)) {
                "FFmpeg configure provenance is missing: $configLog"
            }
            val firstLine = Files.newBufferedReader(configLog, UTF_8).use { reader -> reader.readLine() }
                ?.removePrefix("# ")
            check(!firstLine.isNullOrBlank()) {
                "FFmpeg configure provenance has no nonblank first line: $configLog"
            }
            val evidence = install.resolve(CONFIGURE_EVIDENCE_RELATIVE_PATH)
            Files.createDirectories(evidence.parent)
            Files.writeString(evidence, "$firstLine\n", UTF_8)
        }

        internal fun verifyInstall(install: Path) {
            val missing = REQUIRED_LIBS.filterNot { Files.isRegularFile(install.resolve("lib/$it.a")) }
            check(missing.isEmpty()) {
                "FFmpeg reported a successful install but $install is missing " +
                    "${missing.joinToString { "lib/$it.a" }}. Check the configure/make output; " +
                    "the scratch install prefix may not have been honoured."
            }
            check(Files.isRegularFile(install.resolve("include/libavformat/avformat.h"))) {
                "FFmpeg installed libraries into $install but no headers; cinterop needs both."
            }
            val evidence = install.resolve(CONFIGURE_EVIDENCE_RELATIVE_PATH)
            check(Files.isRegularFile(evidence)) {
                "FFmpeg installed libraries into $install but no configure provenance record at " +
                    CONFIGURE_EVIDENCE_RELATIVE_PATH + "."
            }
            val evidenceText = Files.readString(evidence, UTF_8)
            check(
                evidenceText.endsWith('\n') &&
                    evidenceText.count { it == '\n' } == 1 &&
                    '\r' !in evidenceText &&
                    evidenceText.removeSuffix("\n").isNotBlank()
            ) {
                "FFmpeg configure provenance at $evidence must be one nonblank UTF-8 line " +
                    "terminated by exactly one newline."
            }
        }

        /** Verifies a sibling staging copy before swapping it into the declared output location. */
        internal fun replaceOutputTree(scratchInstall: Path, output: Path) {
            val absoluteOutput = output.toAbsolutePath().normalize()
            val parent = requireNotNull(absoluteOutput.parent) {
                "FFmpeg output has no parent directory: $output"
            }
            Files.createDirectories(parent)
            val nonce = UUID.randomUUID().toString()
            val staging = parent.resolve(".${absoluteOutput.fileName}.staging-$nonce")
            val backup = parent.resolve(".${absoluteOutput.fileName}.backup-$nonce")
            var movedOldOutput = false
            try {
                copyTree(scratchInstall, staging, excludeBuildState = false)
                verifyInstall(staging)
                if (Files.exists(absoluteOutput)) {
                    moveDirectory(absoluteOutput, backup)
                    movedOldOutput = true
                }
                try {
                    moveDirectory(staging, absoluteOutput)
                } catch (failure: Throwable) {
                    if (movedOldOutput) moveDirectory(backup, absoluteOutput)
                    throw failure
                }
                if (movedOldOutput) backup.toFile().deleteRecursively()
            } finally {
                staging.toFile().deleteRecursively()
                if (!Files.exists(absoluteOutput) && movedOldOutput && Files.exists(backup)) {
                    moveDirectory(backup, absoluteOutput)
                }
            }
        }

        private fun moveDirectory(source: Path, destination: Path) {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination)
            }
        }
    }
}
