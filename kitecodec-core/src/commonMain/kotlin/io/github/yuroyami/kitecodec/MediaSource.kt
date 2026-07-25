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

    /**
     * Where this container's timeline begins, in microseconds — 0 for most mp4, commonly around
     * 1.4s for MPEG-TS.
     *
     * This is the offset between the two timelines KiteCodec deals in. Timestamps it reports
     * ([StreamInfo], [FrameInfo.pts]) are absolute and include this value. Timestamps it accepts
     * ([seekMicros], [extractFrame]'s `atMicros`, [Transcoder.transcode] and [Remuxer.remux] trim
     * bounds) are relative to the start of the content, so `10_000_000` always means ten seconds
     * in. Subtract this from a frame's own pts to move it onto the timeline those parameters use.
     */
    public val startTimeMicros: Long

    public val primaryVideo: StreamInfo?
    public val primaryAudio: StreamInfo?

    /**
     * Decode this stream and emit each decoded frame, owned by the collector.
     *
     * Only one decode flow may collect at a time, because the demuxer is a single cursor.
     * Starting a second concurrent collection throws [IllegalStateException]; use
     * [decodeStreams] to read several streams together. The decoder is freed when collection
     * completes or is cancelled.
     *
     * @see Frame for the ownership rule every collected frame is subject to
     */
    public fun decodedFrames(stream: StreamInfo): Flow<Frame>

    /**
     * Decode several streams in one demuxer pass, emitting frames interleaved in container order
     * and tagged by [FrameInfo.streamIndex]. This is the only correct way to transcode video and
     * audio together: two concurrent [decodedFrames] flows would race the underlying demuxer, so
     * that is rejected with [IllegalStateException].
     *
     * @see Frame for the ownership rule every collected frame is subject to
     */
    public fun decodeStreams(streams: List<StreamInfo>): Flow<Frame>

    /**
     * Seek the demuxer to [micros], measured from the start of the content. Lands on the keyframe
     * at or before that point, so the next decode flow resumes from there. Not allowed while a
     * decode flow is collecting, since the demuxer cursor is shared.
     *
     * Precision is the container's, not this library's. Indexless formats such as MPEG-TS resolve
     * a seek by searching byte positions and can land slightly off. Decode a little and check
     * [FrameInfo.pts] if you need to know exactly where you ended up; [extractFrame] and
     * [Transcoder.transcode]'s trim already do this for you.
     *
     * @param micros where to seek to, relative to the start of the content — see [startTimeMicros]
     * @throws FFmpegException when the seek fails
     */
    public suspend fun seekMicros(micros: Long)

    /**
     * Decode and return the frame at (or first after) [atMicros] — thumbnail extraction. Seeks to
     * the preceding keyframe and decodes forward to the exact target. Pair with
     * [Frame.encodeImage] for jpg/png bytes.
     *
     * @param atMicros where to read, relative to the start of the content — see [startTimeMicros]
     * @param stream which stream to read; default = primary video
     * @return an owned frame — hold it as long as you like, close it when done
     * @throws FFmpegException when the seek or decode fails
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
