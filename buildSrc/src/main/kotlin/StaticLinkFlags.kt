package io.github.yuroyami.kitecodec.buildtools

/**
 * What a final native link needs when KiteCodec is built against a VENDORED STATIC FFmpeg
 * (`native-libs/<license>/<target>/`).
 *
 * `ffmpeg.def` names only the six libav* libraries. That is enough for a shared/system FFmpeg,
 * whose dylibs resolve their own dependencies at load time, but a static `libavcodec.a` resolves
 * nothing. Every symbol it draws from the third-party encoder and text stack (svt-av1, vpx, aom,
 * opus, mp3lame, webp, freetype/harfbuzz/fribidi/ass, plus x264/x265 under GPL) has to be satisfied
 * by naming that archive explicitly, or the link dies with pages of `symbol(s) not found`.
 *
 * Two halves, and both matter:
 *  - [thirdPartyArchives]: the `.a` files [BuildFFmpegTask] copies into the vendored tree, so
 *    `native-libs/<license>/<target>/lib` is self-contained and has the same layout as the
 *    published release zip.
 *  - [forTarget]: the `-l` flags naming those archives plus the handful the OS/SDK provides.
 *
 * KEEP IN SYNC with `PrebuiltLinkFlags.kt` in `kitecodec-gradle-plugin` and the bundling step in
 * `.github/scripts/package-ffmpeg.sh`, which solve the identical problem for consumers of the
 * published artifact. The three cannot share code (separate classpaths, and one is bash), so they
 * are deliberately kept structurally identical instead.
 */
object StaticLinkFlags {

    /**
     * Shared desktop LGPL set, dependents before dependencies (GNU ld resolves static archives
     * left→right). webp is split across three archives since libwebp 1.3 (libwebpmux backs
     * FFmpeg's anim encoder; libsharpyuv is libwebp's own dependency).
     */
    private val DESKTOP_LGPL = listOf(
        "SvtAv1Enc", "vpx", "aom", "opus", "mp3lame",
        "webpmux", "webp", "sharpyuv",
        "ass", "harfbuzz", "freetype", "fribidi",
        // Transitive dependencies of the text stack, and they must come AFTER it: freetype's
        // embedded-PNG bitmap support calls into libpng, harfbuzz's shaping backend into graphite2.
        // A shared FFmpeg hides these; a static one fails with `_png_set_tRNS_to_alpha` and `_gr_*`.
        "png16", "graphite2",
    )

    /** GPL flavour adds the x264/x265 archives (x265 is C++; see the runtime flags below). */
    private val DESKTOP_GPL_EXTRA = listOf("x264", "x265")

    /**
     * Whether this (target, source) combination links a static third-party stack at all.
     *
     * Android's profile links no third-party libraries (MediaCodec is a platform service), and
     * `mingw-x64` is populated from BtbN's shared build: import libraries that already carry their
     * own dependencies.
     */
    private fun needsStaticStack(target: TargetTriple, isStaticVendored: Boolean): Boolean {
        if (!isStaticVendored) return false
        return when (target) {
            TargetTriple.MacosArm64, TargetTriple.MacosX64,
            TargetTriple.LinuxX64, TargetTriple.LinuxArm64,
            -> true
            // Mobile Apple uses only FFmpeg's shared software profile and SDK zlib.
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64,
            TargetTriple.MingwX64,
            TargetTriple.AndroidArm64, TargetTriple.AndroidArm32, TargetTriple.AndroidX64,
            -> false
        }
    }

    /**
     * Archive FILENAMES to bundle into the vendored tree's `lib/`, so it is self-contained.
     * OS/SDK-provided libraries (zlib, bz2, iconv, the C++ runtime) are deliberately excluded. They
     * must come from the platform, not from a copy.
     */
    fun thirdPartyArchives(target: TargetTriple, license: FFmpegLicense): List<String> {
        if (!needsStaticStack(target, isStaticVendored = true)) return emptyList()
        val names = buildList {
            addAll(DESKTOP_LGPL)
            if (license == FFmpegLicense.GPL) {
                addAll(DESKTOP_GPL_EXTRA)
                // x265's Linux builds link libnuma; bundle it so the tree stays self-contained.
                if (target == TargetTriple.LinuxX64 || target == TargetTriple.LinuxArm64) add("numa")
            }
        }
        return names.map { "lib$it.a" }
    }

    /**
     * Extra `-L` search paths for a LOCAL vendored build, appended after the vendored `lib/`.
     *
     * [BuildFFmpegTask] copies the third-party archives into the tree, but a package manager that
     * only ships some of them shared (Homebrew's svt-av1 today) leaves gaps. On a developer machine
     * resolving those from the host prefix is exactly right; a release build closes the gap instead
     * by refusing to produce a tree that is not self-contained
     * (`-Pkitecodec.ffmpeg.selfContained=true`). Ordering matters: the vendored `lib/` is searched
     * first, so a bundled archive always wins over the host's copy.
     */
    fun hostFallbackSearchFlags(
        target: TargetTriple,
        hostPrefix: String,
        isStaticVendored: Boolean,
    ): List<String> = when {
        !needsStaticStack(target, isStaticVendored) -> emptyList()
        target == TargetTriple.MacosArm64 || target == TargetTriple.MacosX64 -> listOf("-L$hostPrefix/lib")
        target == TargetTriple.LinuxX64 -> listOf("-L/usr/lib/x86_64-linux-gnu", "-L/usr/lib", "-L/usr/local/lib")
        target == TargetTriple.LinuxArm64 -> listOf("-L/usr/lib/aarch64-linux-gnu", "-L/usr/lib", "-L/usr/local/lib")
        // Mobile Apple deliberately has no desktop stack or host fallback.
        else -> emptyList()
    }

    /** The `-l` flags the final link needs, third-party archives first, then platform basics. */
    fun forTarget(
        target: TargetTriple,
        license: FFmpegLicense,
        isStaticVendored: Boolean,
    ): List<String> {
        if (
            isStaticVendored &&
            target in setOf(TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64)
        ) {
            // The mobile profile gained VideoToolbox DECODE at the S2.a window, so the static
            // FFmpeg archives now carry undefined references into the media frameworks on iOS
            // too, exactly as the desktop encoder note below explains for macOS.
            return listOf(
                "-lz",
                "-framework", "CoreFoundation",
                "-framework", "CoreMedia",
                "-framework", "CoreVideo",
                "-framework", "VideoToolbox",
            )
        }
        if (!needsStaticStack(target, isStaticVendored)) return emptyList()

        val isApple = target == TargetTriple.MacosArm64 || target == TargetTriple.MacosX64
        return buildList {
            addAll(DESKTOP_LGPL.map { "-l$it" })
            if (license == FFmpegLicense.GPL) addAll(DESKTOP_GPL_EXTRA.map { "-l$it" })
            // Platform basics, from the OS/SDK rather than the vendored tree.
            add("-lz")
            add("-lbz2")
            if (isApple) {
                add("-liconv")
                // harfbuzz's CoreText shaping backend and libass' CoreGraphics rasterisation are
                // compiled into the static archives, and FFmpeg's videotoolbox encoders need the
                // media frameworks. A shared build resolved these through its own dylib
                // dependencies; a static one has to name them.
                addAll(
                    listOf(
                        "-framework", "CoreGraphics",
                        "-framework", "CoreText",
                        "-framework", "CoreFoundation",
                        "-framework", "CoreMedia",
                        "-framework", "CoreVideo",
                        "-framework", "VideoToolbox",
                        "-framework", "AudioToolbox",
                    ),
                )
                // x265 is C++; so is harfbuzz, which every flavour links.
                add("-lc++")
            } else {
                // NOTE: the Linux text stack may additionally want fontconfig/expat/libunibreak
                // behind libass. There is no vendored-static Linux job yet to prove it either
                // way. When one is added, read the undefined symbols and extend this list and
                // [thirdPartyArchives] together, exactly as the Apple entries above were derived.
                add("-lstdc++")
                if (license == FFmpegLicense.GPL) add("-lnuma")
            }
        }
    }
}
