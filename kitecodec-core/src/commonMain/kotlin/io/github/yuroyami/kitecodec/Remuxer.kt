package io.github.yuroyami.kitecodec

/**
 * Lossless container rewrite — `ffmpeg -c copy`. Packets move from input to output without
 * touching a decoder or encoder, so a full-length movie remuxes in seconds and bit-exact
 * quality is preserved. Use it to change containers (mp4 → mkv), strip streams, cut on
 * keyframes, or re-wrap after a download.
 */
expect object Remuxer {

    /**
     * Copy [streamIndices] (default: every stream the demuxer understands) from [input] into a
     * fresh container at [output]. Format inferred from the output extension.
     *
     * Trimming ([startMicros] / [endMicros]) is keyframe-snapped: the cut starts at the last
     * keyframe at or before [startMicros] (the price of not re-encoding) and stops once the
     * first selected stream passes [endMicros]. Output timestamps are rebased to start at zero.
     *
     * Limitations: bitstream filters are not applied yet, so pairs that need one (h264-in-mp4 →
     * MPEG-TS Annex B) fail with a muxer error rather than producing a broken file.
     *
     * @param metadata container tags written into the output header (`title`, `artist`, …)
     * @param onProgress invoked every ~100 packets with the running packet count
     */
    suspend fun remux(
        input: String,
        output: String,
        streamIndices: List<Int>? = null,
        startMicros: Long = 0L,
        endMicros: Long = Long.MAX_VALUE,
        metadata: Map<String, String> = emptyMap(),
        onProgress: ((packetsWritten: Long) -> Unit)? = null,
    )
}
