package io.github.yuroyami.kitecodec.gradle

/**
 * FFmpeg licence flavour to provision. The [id] matches both the `native-libs/<id>/...` layout that
 * KiteCodec's build emits and the `<id>` segment of the Release asset names.
 */
enum class FFmpegLicense(val id: String) {
    /** Default. No `--enable-gpl`, no x264 / x265. App-Store and closed-source safe. */
    LGPL("lgpl"),

    /** Adds libx264 / libx265. Makes the linked binary GPL; open-source or server use only. */
    GPL("gpl"),
}

/** Where the plugin sources FFmpeg from. */
enum class FFmpegSource {
    /** Download a pinned prebuilt static build from KiteCodec's GitHub Releases. The default. */
    Prebuilt,

    /** Use a system FFmpeg already installed on the machine (Homebrew / apt). Dynamic linking. */
    System,

    /**
     * Build from source. Only available inside the KiteCodec checkout, which ships the
     * `:buildFFmpegFor<Target>` tasks. Not usable from a consumer project.
     */
    BuildFromSource,
}
