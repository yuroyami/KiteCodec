package io.github.yuroyami.kitecodec.gradle

/**
 * The extra `-l` linker flags a consumer's final link needs when linking one of KiteCodec's
 * PREBUILT FFmpeg zips or a validated [FFmpegSource.Local] tree.
 *
 * Every published profile is PORTABLE (2026-08-22): platform services only, no third-party
 * desktop stack. The klib's `ffmpeg.def` names only the six libav* archives, so the platform
 * libraries and frameworks a static libav* draws on must be named here.
 *
 * KEEP IN SYNC with `StaticLinkFlags.kt` in KiteCodec's buildSrc, which solves the same problem
 * for KiteCodec's own build. The two cannot share code (separate classpaths), so they are
 * deliberately kept structurally identical instead. The optional `-ldav1d` is added by the dav1d
 * contract in [KiteCodecPlugin], not here.
 *
 * [FFmpegSource.Prebuilt] and [FFmpegSource.Local] targets get these flags. System mode links
 * shared libav* libraries that resolve their own dependencies.
 */
internal object PrebuiltLinkFlags {

    private val APPLE_TARGETS = setOf(
        KiteCodecTarget.MacosArm64, KiteCodecTarget.MacosX64,
        KiteCodecTarget.IosArm64, KiteCodecTarget.IosSimulatorArm64, KiteCodecTarget.IosX64,
    )

    fun extraLinkerOpts(target: KiteCodecTarget): List<String> = when {
        target in APPLE_TARGETS -> listOf(
            "-lz",
            // Every Apple profile enables VideoToolbox (decode everywhere, encode outside the
            // simulators) and AudioToolbox, so the static archives carry undefined references
            // into the media frameworks. A shared build resolved these through its own dylib
            // dependencies; a static one must name them.
            "-framework", "CoreFoundation",
            "-framework", "CoreMedia",
            "-framework", "CoreVideo",
            "-framework", "VideoToolbox",
            "-framework", "AudioToolbox",
        )
        // iconv backs FFmpeg's subtitle charset conversion and msys2's sysroot has it, so the
        // mingw configure autodetects it and the link must name it, along with the Windows
        // sockets, media and time APIs FFmpeg's network and mpegts code reaches for.
        target == KiteCodecTarget.MingwX64 -> listOf(
            "-lz", "-liconv",
            "-lws2_32", "-lbcrypt", "-lsecur32", "-lmfplat", "-lole32", "-lstrmiids", "-luuid",
        )
        target == KiteCodecTarget.LinuxX64 || target == KiteCodecTarget.LinuxArm64 -> listOf(
            "-lz", "-lm", "-ldl", "-lpthread",
        )
        // Android: the klib's def file already names the platform libraries (zlib, mediandk, log).
        else -> emptyList()
    }
}
