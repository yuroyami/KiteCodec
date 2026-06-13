package io.github.yuroyami.kitecodec

import kotlinx.coroutines.flow.Flow

/**
 * An opened input — local file or URL. Owns the AVFormatContext and per-stream resources;
 * closing tears everything down. Confined to one coroutine context — libav* objects are not
 * safe to call concurrently.
 */
expect class MediaSource : AutoCloseable {

    val streams: List<StreamInfo>
    val durationMicros: Long?
    val formatName: String
    val metadata: Map<String, String>

    val primaryVideo: StreamInfo?
    val primaryAudio: StreamInfo?

    /**
     * Decode this stream and emit each decoded frame as a [Frame]. The frame is owned by the
     * downstream collector; once the next frame is emitted, the previous one's buffers may be
     * recycled, so copy with [Frame.copyPlanesToByteArray] if you need to hold onto pixels.
     *
     * The decoder + AVFrame are freed in `finally` when collection completes or is cancelled.
     */
    fun decodedFrames(stream: StreamInfo): Flow<Frame>

    /**
     * Decode several streams in ONE demuxer pass, emitting frames interleaved in container
     * order (tag: [FrameInfo.streamIndex]). This is the only correct way to transcode
     * video + audio together — two concurrent [decodedFrames] flows would race on the
     * underlying demuxer.
     */
    fun decodeStreams(streams: List<StreamInfo>): Flow<Frame>

    /** Seek the demuxer to [micros] (microseconds). Next [decodedFrames] resumes from there. */
    suspend fun seekMicros(micros: Long)

    /**
     * Decode and return the frame at (or first after) [atMicros] — thumbnail extraction.
     * Seeks to the preceding keyframe, decodes forward to the exact target, and returns an
     * OWNED copy: hold it as long as you like, close it when done. Pair with
     * [Frame.encodeImage] for jpg/png bytes.
     *
     * @param stream which stream to read; default = primary video
     */
    suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): Frame

    override fun close()

    companion object {
        /** Open a local file or URL. */
        fun open(path: String): MediaSource
    }
}
