package io.github.yuroyami.kitecodec

import kotlinx.coroutines.flow.Flow

/**
 * An opened input: a local file or a URL. It owns the container cursor and per-stream resources,
 * and closing it releases them all. Confine it to one coroutine context, because the underlying
 * media objects are not safe to call concurrently.
 */
public expect class MediaSource : AutoCloseable {

    public val streams: List<StreamInfo>
    public val durationMicros: Long?
    public val formatName: String
    public val metadata: Map<String, String>

    /** The container's chapter table (KD-5). Empty when the container declares none. */
    public val chapters: List<Chapter>

    /**
     * The pre-open option keys FFmpeg did NOT consume on this open (KD-4). Always empty for the
     * plain [open]; a non-empty list after an options open is a caller mistake worth reading
     * back, and the S4 diagnostics dump prints it.
     */
    public val unusedOpenOptions: List<String>

    /**
     * Where this container's timeline begins, in microseconds. It is 0 for most mp4 files, and
     * commonly around 1.4s for MPEG-TS.
     *
     * This is the offset between the two timelines KiteCodec deals in. Timestamps it reports
     * ([StreamInfo], [FrameInfo.pts]) are absolute and include this value. Timestamps it accepts
     * ([seekMicros], [extractFrame]'s `atMicros`, [Transcoder.transcode] and [Remuxer.remux] trim
     * bounds) are relative to the start of the content, so `10_000_000` always means ten seconds
     * in. Subtract this from a frame's own pts to move it onto the timeline those parameters use.
     */
    public val startTimeMicros: Long

    /**
     * Whether this input can seek at all, read from the input rather than assumed.
     *
     * False for a pipe, a capture device and anything else whose bytes only move forward. A player
     * must ask before it offers a seek bar, because on such an input every [seekMicros] and every
     * `PacketReader.seek` fails.
     *
     * It is conservative in one direction: a demuxer that implements its own seek without a
     * seekable byte stream reports false here, because since FFmpeg 7 nothing in the public
     * headers proves otherwise. It never reports true for an input that cannot seek.
     */
    public val isSeekable: Boolean

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
     * @param micros where to seek to, relative to the start of the content (see [startTimeMicros])
     * @throws FFmpegException when the seek fails
     */
    public suspend fun seekMicros(micros: Long)

    /**
     * Decode and return the frame at (or first after) [atMicros]. Use this to extract a
     * thumbnail. It seeks to the preceding keyframe and decodes forward to the exact target.
     * Pair with [Frame.encodeImage] for jpg/png bytes.
     *
     * @param atMicros where to read, relative to the start of the content (see [startTimeMicros])
     * @param stream which stream to read; default = primary video
     * @return an owned frame: hold it as long as you like, then close it
     * @throws FFmpegException when the seek or decode fails
     */
    public suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): Frame

    /**
     * Opens the low-level demux cursor for exactly [streams]. The returned reader owns the source's
     * cursor until it is closed; no batch decode or source-level seek may run concurrently.
     */
    @KiteCodecLowLevelApi
    public fun openPacketReader(streams: List<StreamInfo>): PacketReader

    /**
     * Opens one independently driven decoder for [stream].
     *
     * A null [decoder] lets FFmpeg choose its default implementation by codec id. A non-null value
     * selects that exact decoder name and refuses it unless it can decode this stream's codec. This
     * is the selection seam used for FFmpeg-owned hardware decoders such as
     * `h264_mediacodec`; it does not call a platform decoder API directly.
     */
    @KiteCodecLowLevelApi
    @Throws(FFmpegException::class)
    public fun openDecoder(
        stream: StreamInfo,
        threadCount: Int = 0,
        lowDelay: Boolean = false,
        decoder: CodecId? = null,
        options: io.github.yuroyami.kitecodec.dsl.DecoderOptions? = null,
    ): StreamDecoder

    override fun close()

    public companion object {
        /**
         * Open a local file or URL.
         *
         * This is a blocking call. Network URLs perform I/O inside the native open, so call it
         * from a background dispatcher (`Dispatchers.IO` or your media dispatcher). Never call it
         * on the UI thread.
         */
        @Throws(FFmpegException::class)
        public fun open(path: String): MediaSource

        /**
         * Open with pre-open options (KD-4): pairs applied between allocation and open, the only
         * moment probesize, fflags and format forcing can act. Keys FFmpeg does not consume are
         * reported through [unusedOpenOptions], never silently dropped.
         */
        @Throws(FFmpegException::class)
        public fun open(path: String, options: Map<String, String>): MediaSource
    }
}
