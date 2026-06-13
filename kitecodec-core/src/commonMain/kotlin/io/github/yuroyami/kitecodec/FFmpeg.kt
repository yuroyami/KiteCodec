package io.github.yuroyami.kitecodec

/**
 * Static facade for global FFmpeg state — version info, build flags, capability probing.
 * In v0.1 this is the only surface the library exposes; future versions add MediaSource /
 * Decoder / FilterGraph backed by C-side struct accessors (see README roadmap).
 */
expect object FFmpeg {

    /** Comma-separated configure flags the bound FFmpeg was built with. */
    val buildConfiguration: String

    /** Per-library version triplets — useful for compatibility checks. */
    val versions: Versions

    /** Whether the bound FFmpeg has a given encoder compiled in. Pass an FFmpeg codec name. */
    fun hasEncoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given decoder compiled in. */
    fun hasDecoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given filter compiled in. */
    fun hasFilter(name: String): Boolean
}

data class Versions(
    val avutil:     String,
    val avcodec:    String,
    val avformat:   String,
    val avfilter:   String,
    val swscale:    String,
    val swresample: String,
)
