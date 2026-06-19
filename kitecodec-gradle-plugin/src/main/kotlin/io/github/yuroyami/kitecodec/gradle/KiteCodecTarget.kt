package io.github.yuroyami.kitecodec.gradle

/**
 * Canonical target ids. [triple] matches the `native-libs/<license>/<triple>` layout and the Release
 * asset names produced by KiteCodec's binary CI; [konanName] matches `KonanTarget.name` so the plugin
 * can map a consumer's Kotlin/Native targets onto the right FFmpeg build.
 */
internal enum class KiteCodecTarget(
    val triple: String,
    val konanName: String,
    val android: Boolean = false,
) {
    MacosArm64("macos-arm64", "macos_arm64"),
    MacosX64("macos-x64", "macos_x64"),
    IosArm64("ios-arm64", "ios_arm64"),
    IosSimulatorArm64("ios-simulator-arm64", "ios_simulator_arm64"),
    IosX64("ios-x64", "ios_x64"),
    LinuxX64("linux-x64", "linux_x64"),
    LinuxArm64("linux-arm64", "linux_arm64"),
    MingwX64("mingw-x64", "mingw_x64"),
    AndroidArm64("android-arm64", "android_arm64", android = true),
    AndroidArm32("android-arm32", "android_arm32", android = true),
    AndroidX64("android-x64", "android_x64", android = true),
    ;

    companion object {
        fun forKonan(konanName: String): KiteCodecTarget? =
            entries.firstOrNull { it.konanName == konanName }
    }
}
