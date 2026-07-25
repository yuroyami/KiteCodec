package io.github.yuroyami.kitecodec

/**
 * Static facade for global FFmpeg state — version info, build flags, capability probing.
 *
 * Use it to feature-detect the bound FFmpeg before opening a codec or a filter, because builds
 * differ in what they contain. A system FFmpeg may or may not have `libx264`; KiteCodec's
 * vendored LGPL profile never does, and asking for it there throws [FFmpegException] from
 * [MediaSink.addVideoEncoder].
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
