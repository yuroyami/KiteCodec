package io.github.yuroyami.kitecodec

import kotlinx.coroutines.flow.Flow

/**
 * An opened input — local file or URL. Owns the AVFormatContext and per-stream resources;
 * closing tears everything down. Confined to one coroutine context — libav* objects are not
 * safe to call concurrently.
 */
public expect class MediaSource : AutoCloseable {

    public val streams: List<StreamInfo>
    public val durationMicros: Long?
    public val formatName: String
    public val metadata: Map<String, String>

    public val primaryVideo: StreamInfo?
    public val primaryAudio: StreamInfo?

    /**
     * Decode this stream and emit each decoded frame as an OWNED [Frame]: every emitted frame
     * stays valid until you close it, so buffering operators (`buffer()`, `toList()`) are safe.
     * Close each collected frame — unclosed frames leak native buffers.
     *
     * Only one decode flow may collect at a time (the demuxer is a single cursor); starting a
     * second concurrent collection throws [IllegalStateException] — use [decodeStreams] to read
     * several streams together. The decoder is freed when collection completes or is cancelled.
     */
    public fun decodedFrames(stream: StreamInfo): Flow<Frame>

    /**
     * Decode several streams in ONE demuxer pass, emitting frames interleaved in container
     * order (tag: [FrameInfo.streamIndex]). This is the only correct way to transcode
     * video + audio together — two concurrent [decodedFrames] flows would race on the
     * underlying demuxer, so that is rejected with [IllegalStateException].
     */
    public fun decodeStreams(streams: List<StreamInfo>): Flow<Frame>

    /**
     * Seek the demuxer to [micros] (microseconds). The next decode flow resumes from there.
     * Not allowed while a decode flow is collecting (the demuxer cursor is shared).
     *
     * @throws FFmpegException when the seek fails
     */
    public suspend fun seekMicros(micros: Long)

    /**
     * Decode and return the frame at (or first after) [atMicros] — thumbnail extraction.
     * Seeks to the preceding keyframe, decodes forward to the exact target, and returns an
     * OWNED copy: hold it as long as you like, close it when done. Pair with
     * [Frame.encodeImage] for jpg/png bytes.
     *
     * @param stream which stream to read; default = primary video
     */
    public suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): Frame

    override fun close()

    public companion object {
        /**
         * Open a local file or URL.
         *
         * Blocking call — network URLs run I/O inside `avformat_open_input`, so call from a
         * background dispatcher (`Dispatchers.IO` or your media dispatcher), never the UI thread.
         */
        @Throws(FFmpegException::class)
        public fun open(path: String): MediaSource
    }
}
