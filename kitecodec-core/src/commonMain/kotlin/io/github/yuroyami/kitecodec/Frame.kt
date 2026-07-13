package io.github.yuroyami.kitecodec

/**
 * One decoded frame (video or audio). Wraps an `AVFrame*` — close to release the buffers.
 *
 * **Ownership rule:** frames emitted by the public `Flow` APIs ([MediaSource.decodedFrames],
 * [MediaSource.decodeStreams], [FilterGraph.process]) are OWNED by the collector — each one
 * stays valid until you [close] it, so buffering operators (`buffer()`, `toList()`) are safe.
 * Close every collected frame or its native buffers leak. Frames handed to callback-style
 * APIs ([FilterGraph.feedInput]'s `onOutput`) are valid only for the duration of the callback;
 * [copy] to keep one.
 *
 * The native pointer is intentionally not exposed in commonMain; pipeline operators
 * ([FilterGraph], encoders) accept Frames directly and pull the pointer through `internal`
 * accessors on the native side.
 */
public expect class Frame : AutoCloseable {

    public val info: FrameInfo

    /**
     * Copy the frame's pixel planes (video) or samples (audio) into a flat ByteArray —
     * planar formats stay planar, packed stay packed, with **no linesize padding**
     * (`align=1`, tightly packed). For yuv420p at WxH this returns `W*H*3/2` bytes
     * (Y plane, then U, then V); for fltp stereo it returns the left plane followed by
     * the right. [Frame.ofVideo] / [Frame.ofAudio] accept exactly this layout back.
     *
     * @throws FFmpegException if the copy fails; returns an empty array only for frames
     *         that genuinely carry no data (e.g. an unreferenced frame).
     */
    @Throws(FFmpegException::class)
    public fun copyPlanesToByteArray(): ByteArray

    /**
     * An owned snapshot of this frame: escape hatch from the callback-scope validity rule.
     * O(1) — takes new references to the same refcounted buffers, no pixel copy. The returned
     * frame survives the source being recycled; close it yourself.
     */
    @Throws(FFmpegException::class)
    public fun copy(): Frame

    /**
     * Encode this (video) frame as a standalone compressed image — default MJPEG (`.jpg`),
     * or [CodecId.Png]. Converts pixel format automatically when the image codec doesn't
     * accept the frame's own (e.g. yuv420p → rgb24 for PNG).
     *
     * @throws FFmpegException on audio frames, frames without image data, or encode failure
     */
    @Throws(FFmpegException::class)
    public fun encodeImage(codec: CodecId = CodecId.Mjpeg): ByteArray

    override fun close()

    public companion object {
        /**
         * Build a video frame from raw pixel bytes — the entry point for generative use:
         * images-to-video, procedural frames, pixels produced by other libraries.
         *
         * [bytes] must be tightly packed planes in [pixelFormat]'s layout — exactly what
         * [copyPlanesToByteArray] produces (yuv420p: Y then U then V, no padding; rgba:
         * interleaved). Size must be at least the format's buffer size for [width]x[height].
         *
         * The frame owns its buffers (close it or hand it to an encoder/filter, which closes
         * it for you). [ptsMicros] sets the timestamp on a 1/1_000_000 time-base;
         * [FrameInfo.NOPTS] leaves it unset (encoders then fall back to a frame counter).
         *
         * @throws FFmpegException on unknown pixel format, allocation failure, or short [bytes]
         */
        @Throws(FFmpegException::class)
        public fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long = FrameInfo.NOPTS,
        ): Frame

        /**
         * Build an audio frame from raw sample bytes. [bytes] layout: planar formats are
         * plane-after-plane (all of channel 0, then channel 1, …), packed formats are
         * interleaved — matching [copyPlanesToByteArray]. Channels beyond 8 are unsupported.
         *
         * @throws FFmpegException on unknown sample format, allocation failure, or short [bytes]
         */
        @Throws(FFmpegException::class)
        public fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long = FrameInfo.NOPTS,
        ): Frame
    }
}
