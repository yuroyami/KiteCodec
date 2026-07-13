package io.github.yuroyami.kitecodec

/**
 * Static facade for global FFmpeg state — version info, build flags, capability probing.
 * Use it to feature-detect the bound FFmpeg before opening codecs or filters (builds differ:
 * a system FFmpeg may lack `libx264`, a vendored LGPL build definitely does).
 */
public expect object FFmpeg {

    /** Comma-separated configure flags the bound FFmpeg was built with. */
    public val buildConfiguration: String

    /** Per-library version triplets — useful for compatibility checks. */
    public val versions: Versions

    /** Whether the bound FFmpeg has a given encoder compiled in. Pass an FFmpeg codec name. */
    public fun hasEncoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given decoder compiled in. */
    public fun hasDecoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given filter compiled in. */
    public fun hasFilter(name: String): Boolean
}

public data class Versions(
    val avutil:     String,
    val avcodec:    String,
    val avformat:   String,
    val avfilter:   String,
    val swscale:    String,
    val swresample: String,
)
