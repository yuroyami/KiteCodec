package io.github.yuroyami.kitecodec

import kotlinx.coroutines.flow.Flow

/**
 * An open output file (muxer). Add every encoder first (video and/or audio — the muxer's
 * header freezes the stream list), then push frames through the encoders; close to write
 * the trailer and flush buffers.
 */
expect class MediaSink : AutoCloseable {

    /** Add a video encoder. Must be called before any frame is written. */
    fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder

    /** Add an audio encoder. Must be called before any frame is written. */
    fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder

    /**
     * Add an output stream that copies [stream]'s packets verbatim from [source] — no decode,
     * no re-encode, only timestamp rescaling into the output's time-base (`ffmpeg -c copy`).
     * Bitstream filters are not applied, so format pairs that need one (e.g. h264 in mp4 →
     * MPEG-TS Annex B) are not yet supported.
     *
     * Must be called before any frame/packet is written. Packets are pulled by [Remuxer] or
     * [Transcoder]; this only declares the mapping.
     */
    fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream

    /**
     * Container-level metadata tags (`title`, `artist`, `comment`, …). Call before any
     * frame/packet is written — tags ride in the header.
     */
    fun setMetadata(metadata: Map<String, String>)

    override fun close()

    companion object {
        /** Open a sink writing to [path]. Format inferred from extension. */
        fun open(path: String): MediaSink
    }
}

/** An output stream fed by stream-copy. Opaque handle; packets flow through [Remuxer]/[Transcoder]. */
expect class CopyStream

data class VideoEncoderSpec(
    val codec: CodecId,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat = PixelFormat.Yuv420p,
    val frameRate: Rational,
    val bitrateBps: Long = 4_000_000L,
    val keyframeIntervalFrames: Int = (frameRate.asDouble * 2).toInt().coerceAtLeast(1),
    /**
     * Encoder-specific options, passed through as `av_opt_set` strings — `"preset" to "veryfast"`,
     * `"crf" to "23"` (libx264), `"allow_sw" to "1"` (videotoolbox), etc.
     */
    val options: Map<String, String> = emptyMap(),
)

data class AudioEncoderSpec(
    val codec: CodecId,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    /** [SampleFormat.None] picks the encoder's preferred format (e.g. fltp for aac). */
    val sampleFormat: SampleFormat = SampleFormat.None,
    val bitrateBps: Long = 128_000L,
    /** Encoder-specific options, passed through as `av_opt_set` strings. */
    val options: Map<String, String> = emptyMap(),
)

/**
 * One configured + opened video encoder. Pull from a `Flow<Frame>` via [drive] — it pushes each
 * frame into the encoder, pulls packets, hands them to the sink's muxer, and flushes when the
 * flow completes.
 *
 * Incoming frame pts are rescaled from the frame's own time-base onto the encoder's; frames
 * without pts fall back to a frame counter. Either way output timestamps stay monotonic.
 */
expect class VideoEncoder : AutoCloseable {
    /**
     * Drain [input] into this encoder + the parent muxer. Returns when the flow completes.
     * Reports progress every [progressEveryNFrames] via [onProgress] (the encoded-frame count).
     */
    suspend fun drive(input: Flow<Frame>, onProgress: ((framesEncoded: Long) -> Unit)? = null, progressEveryNFrames: Int = 30)
    override fun close()
}

/**
 * One configured + opened audio encoder. Frame-size-constrained codecs (AAC: 1024 samples)
 * need input chunked accordingly — route frames through [FilterGraph.buildAudio] and call
 * [FilterGraph.setOutputFrameSize] with [frameSize] (Transcoder does this automatically).
 */
expect class AudioEncoder : AutoCloseable {
    /** Samples the codec wants per frame; 0 when the codec takes arbitrary chunk sizes. */
    val frameSize: Int
    /** The sample format actually negotiated (resolves [AudioEncoderSpec.sampleFormat] = None). */
    val sampleFormat: SampleFormat
    val sampleRate: Int
    val channels: Int

    /** Drain [input] into this encoder + the parent muxer. Returns when the flow completes. */
    suspend fun drive(input: Flow<Frame>)
    override fun close()
}
