package io.github.yuroyami.kitecodec.gradle

/**
 * Canonical target ids. [triple] matches the `native-libs/<license>/<triple>` layout and the Release
 * asset names produced by KiteCodec's binary CI; [konanName] matches `KonanTarget.name` so the plugin
 * can map a consumer's Kotlin/Native targets onto the right FFmpeg build.
 *
 * [hasPrebuiltAsset] marks the triples KiteCodec's own release (release-binaries.yml) actually
 * publishes prebuilt FFmpeg zips for. It must describe REALITY, not intent: it was true for
 * macos-arm64 and linux-x64 while zero releases existed anywhere, so `Prebuilt` accepted those
 * targets and then 404ed at download instead of refusing at configuration, which is the exact
 * failure the flag exists to prevent. Both are false until their CI job passes. As of 0.1.0: the Android triples (LGPL only; Android has no GPL
 * flavour, enforced elsewhere) plus macos-arm64 and linux-x64 (both lgpl and gpl). Everything else
 * (ios-*, macos-x64, linux-arm64, mingw-x64) has NO asset yet; [FFmpegSource.Prebuilt] against the
 * default repo fails configuration for those with instructions instead of 404-ing at fetch time.
 */
internal enum class KiteCodecTarget(
    val triple: String,
    val konanName: String,
    val android: Boolean = false,
    val hasPrebuiltAsset: Boolean = false,
) {
    MacosArm64("macos-arm64", "macos_arm64"),
    MacosX64("macos-x64", "macos_x64"),
    IosArm64("ios-arm64", "ios_arm64", hasPrebuiltAsset = true),
    IosSimulatorArm64("ios-simulator-arm64", "ios_simulator_arm64", hasPrebuiltAsset = true),
    IosX64("ios-x64", "ios_x64"),
    LinuxX64("linux-x64", "linux_x64"),
    LinuxArm64("linux-arm64", "linux_arm64"),
    MingwX64("mingw-x64", "mingw_x64"),
    AndroidArm64("android-arm64", "android_arm64", android = true, hasPrebuiltAsset = true),
    AndroidArm32("android-arm32", "android_arm32", android = true, hasPrebuiltAsset = true),
    AndroidX64("android-x64", "android_x64", android = true, hasPrebuiltAsset = true),
    ;

    companion object {
        fun forKonan(konanName: String): KiteCodecTarget? =
            entries.firstOrNull { it.konanName == konanName }
    }
}
