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
    /** What the container says this stream is for. Needed to mark or auto-select forced subtitles. */
    val disposition: Disposition = Disposition.None,
    /**
     * Clockwise rotation a renderer must apply, in degrees, from the container's display matrix.
     *
     * Phones write this into every recording. Ignoring it plays portrait video on its side, which
     * is the single most common visible bug in a first video pipeline.
     */
    val rotationDegrees: Int = 0,
    /** Where this stream's own timeline starts, in microseconds. May differ from the container's. */
    val startTimeMicros: Long = 0,
) {
    /** BCP 47 or the raw three letter code, whichever the container provided. Null when absent. */
    val language: String? get() = metadata["language"]

    val title: String? get() = metadata["title"]
}

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
    /** The decoder's own duration for this frame, in [timeBase] units. 0 when it gave none. */
    val duration: Long = 0,
    /** True when this frame can be decoded without any earlier frame. */
    val isKeyframe: Boolean = false,
    /**
     * The colour metadata a renderer must honour. Ignoring it produces a picture that is present
     * and wrong, which is worse than one that is absent: hues shift, or black turns grey.
     */
    val color: ColorInfo = ColorInfo.Unspecified,
    /** Non-square pixel aspect, when the stream declares one. 1:1 otherwise. */
    val sampleAspectRatio: Rational = Rational(1, 1),
    /**
     * True when the pixels live in GPU or hardware memory rather than in main memory.
     *
     * Such a frame has no readable planes. It is presented by the renderer that matches the decoder
     * which produced it, or downloaded first, which costs the copy the hardware path existed to
     * avoid.
     */
    val isHardware: Boolean = false,
) {
    /** False when the frame carries no timestamp (`AV_NOPTS_VALUE`). [ptsSeconds] is meaningless then. */
    val hasPts: Boolean get() = pts != NOPTS

    val ptsSeconds: Double get() = if (hasPts) pts * timeBase.asDouble else Double.NaN

    public companion object {
        /** FFmpeg's `AV_NOPTS_VALUE` sentinel. */
        public const val NOPTS: Long = Long.MIN_VALUE
    }
}
