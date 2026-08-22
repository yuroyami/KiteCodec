package io.github.yuroyami.kitecodec.gradle

/**
 * Canonical target ids. [triple] matches the `native-libs/<license>/<triple>` layout and the Release
 * asset names produced by KiteCodec's binary CI; [konanName] matches `KonanTarget.name` so the plugin
 * can map a consumer's Kotlin/Native targets onto the right FFmpeg build.
 *
 * [hasPrebuiltAsset] marks the triples KiteCodec's own release (release-binaries.yml) actually
 * publishes prebuilt FFmpeg zips for. It must describe REALITY, not intent: it was once true for
 * triples while zero releases existed anywhere, so `Prebuilt` accepted them and then 404ed at
 * download instead of refusing at configuration, which is the exact failure the flag exists to
 * prevent. As of the v0.1.0 full-coverage release (2026-08-22) every triple has BOTH flavours
 * (plain and dav1d) published, verified against the live release before this flag was flipped.
 * [FFmpegSource.Prebuilt] against the default repo refuses at configuration for any triple whose
 * flag is false, with instructions, instead of 404-ing at fetch time.
 */
internal enum class KiteCodecTarget(
    val triple: String,
    val konanName: String,
    val android: Boolean = false,
    val hasPrebuiltAsset: Boolean = false,
) {
    MacosArm64("macos-arm64", "macos_arm64", hasPrebuiltAsset = true),
    MacosX64("macos-x64", "macos_x64", hasPrebuiltAsset = true),
    IosArm64("ios-arm64", "ios_arm64", hasPrebuiltAsset = true),
    IosSimulatorArm64("ios-simulator-arm64", "ios_simulator_arm64", hasPrebuiltAsset = true),
    IosX64("ios-x64", "ios_x64", hasPrebuiltAsset = true),
    LinuxX64("linux-x64", "linux_x64", hasPrebuiltAsset = true),
    LinuxArm64("linux-arm64", "linux_arm64", hasPrebuiltAsset = true),
    MingwX64("mingw-x64", "mingw_x64", hasPrebuiltAsset = true),
    AndroidArm64("android-arm64", "android_arm64", android = true, hasPrebuiltAsset = true),
    AndroidArm32("android-arm32", "android_arm32", android = true, hasPrebuiltAsset = true),
    AndroidX64("android-x64", "android_x64", android = true, hasPrebuiltAsset = true),
    ;

    companion object {
        fun forKonan(konanName: String): KiteCodecTarget? =
            entries.firstOrNull { it.konanName == konanName }
    }
}
