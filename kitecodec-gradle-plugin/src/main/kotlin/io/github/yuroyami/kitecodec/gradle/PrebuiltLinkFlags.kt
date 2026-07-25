package io.github.yuroyami.kitecodec.gradle

/**
 * The extra `-l` linker flags a consumer's final link needs when linking one of KiteCodec's
 * PREBUILT desktop FFmpeg zips.
 *
 * The desktop zips bundle the static third-party encoder/text libs (svt-av1, vpx, aom, opus,
 * mp3lame, webp, freetype/harfbuzz/fribidi/ass; the set BuildFFmpegTask's desktop profile links)
 * in their `lib/` dir, but the klib's `ffmpeg.def` only names the six libav* archives, so those
 * third-party archives must be named explicitly at the consumer's link.
 *
 * KEEP IN SYNC with `.github/scripts/package-ffmpeg.sh`, which bundles the matching `.a` files and
 * writes this same list into each zip's `lib/LINK-FLAGS.txt`. The list is hardcoded here (rather
 * than read from LINK-FLAGS.txt) because linkerOpts are fixed at configuration time, before the
 * fetch task has downloaded the zip.
 *
 * Only [FFmpegSource.Prebuilt] desktop targets get flags: the Android assets are self-contained
 * (no third-party libs; MediaCodec is a platform service), and System mode links shared libav*
 * libs that resolve their own dependencies.
 */
internal object PrebuiltLinkFlags {

    /**
     * Shared desktop LGPL set, dependents before dependencies (GNU ld resolves static archives
     * left→right). webp is split across three archives since libwebp 1.3 (libwebpmux backs
     * FFmpeg's anim encoder; libsharpyuv is libwebp's own dependency).
     */
    private val DESKTOP_LGPL = listOf(
        "-lSvtAv1Enc", "-lvpx", "-laom", "-lopus", "-lmp3lame",
        "-lwebpmux", "-lwebp", "-lsharpyuv",
        "-lass", "-lharfbuzz", "-lfreetype", "-lfribidi",
        // Transitive dependencies of the text stack, and they must come AFTER it: freetype's
        // embedded-PNG bitmap support calls into libpng, harfbuzz's shaping backend into
        // graphite2. A shared FFmpeg hid these; a static one fails on _png_set_tRNS_to_alpha
        // and _gr_*.
        "-lpng16", "-lgraphite2",
    )

    /** GPL flavour adds the x264/x265 archives (both C++ inside; see the runtime flags below). */
    private val DESKTOP_GPL_EXTRA = listOf("-lx264", "-lx265")

    fun extraLinkerOpts(target: KiteCodecTarget, license: FFmpegLicense): List<String> {
        val isMacos = target == KiteCodecTarget.MacosArm64 || target == KiteCodecTarget.MacosX64
        val isLinux = target == KiteCodecTarget.LinuxX64 || target == KiteCodecTarget.LinuxArm64
        // Android: self-contained. ios/mingw: no prebuilt desktop-profile assets exist (yet);
        // validatePrebuiltAvailability already rejects Prebuilt for them against the default repo.
        if (!isMacos && !isLinux) return emptyList()

        return buildList {
            addAll(DESKTOP_LGPL)
            if (license == FFmpegLicense.GPL) addAll(DESKTOP_GPL_EXTRA)
            // Platform basics, from the OS/SDK rather than the zip.
            add("-lz")
            add("-lbz2")
            if (isMacos) {
                add("-liconv")
                // harfbuzz is C++ (as is x265), and harfbuzz's CoreText backend plus FFmpeg's
                // videotoolbox encoders pull in the Apple frameworks. A shared build resolved
                // these through its own dylib dependencies; a static one must name them.
                add("-lc++")
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
            } else if (license == FFmpegLicense.GPL) {
                add("-lnuma") // x265's Ubuntu build links libnuma (bundled in the zip)
                add("-lstdc++")
            }
        }
    }
}
