package io.github.yuroyami.kitecodec

/** Read-only snapshot of an input stream's metadata. */
public data class StreamInfo(
    val index: Int,
    val type: MediaType,
    val codec: CodecId,
    val timeBase: Rational,
    val durationMicros: Long?,
    val bitrateBps: Long?,
    val video: VideoStreamInfo? = null,
    val audio: AudioStreamInfo? = null,
    /** Per-stream tags: `language` (`eng`, `jpn`, …), `title`, `handler_name`, … */
    val metadata: Map<String, String> = emptyMap(),
)

public data class VideoStreamInfo(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val frameRate: Rational,
    val sampleAspectRatio: Rational,
)

public data class AudioStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
)

/** Immutable per-frame metadata snapshot: no native handle, safe to hold forever. */
public data class FrameInfo(
    val streamIndex: Int,
    val type: MediaType,
    val pts: Long,
    val timeBase: Rational,
    val width: Int = 0,
    val height: Int = 0,
    val pixelFormat: PixelFormat = PixelFormat.None,
    val sampleCount: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val sampleFormat: SampleFormat = SampleFormat.None,
) {
    /** False when the frame carries no timestamp (`AV_NOPTS_VALUE`). [ptsSeconds] is meaningless then. */
    val hasPts: Boolean get() = pts != NOPTS

    val ptsSeconds: Double get() = if (hasPts) pts * timeBase.asDouble else Double.NaN

    public companion object {
        /** FFmpeg's `AV_NOPTS_VALUE` sentinel. */
        public const val NOPTS: Long = Long.MIN_VALUE
    }
}
