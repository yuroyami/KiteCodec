package io.github.yuroyami.kitecodec

/**
 * One decoded frame (video or audio). Wraps an `AVFrame*` — close to release the buffers.
 *
 * **Ownership rule:** the [Frame] emitted by a Flow is valid only until the next emission or the
 * flow's close. To keep a frame past that, snapshot it with [info] + [copyPlanesToByteArray].
 *
 * The native pointer is intentionally not exposed in commonMain; pipeline operators
 * ([FilterGraph], encoders) accept Frames directly and pull the pointer through `internal`
 * accessors on the native side.
 */
expect class Frame : AutoCloseable {

    val info: FrameInfo

    /**
     * Copy the frame's pixel planes (video) or samples (audio) into a flat ByteArray —
     * planar formats stay planar, packed stay packed. For yuv420p at WxH this returns
     * `W*H*3/2` bytes (Y plane, then U, then V); for fltp stereo it returns the left
     * plane followed by the right.
     *
     * @throws FFmpegException if the copy fails; returns an empty array only for frames
     *         that genuinely carry no data (e.g. an unreferenced frame).
     */
    fun copyPlanesToByteArray(): ByteArray

    /**
     * An owned snapshot of this frame: escape hatch from the valid-until-next-emission rule.
     * O(1) — takes new references to the same refcounted buffers, no pixel copy. The returned
     * frame survives the source being recycled; close it yourself.
     */
    fun copy(): Frame

    /**
     * Encode this (video) frame as a standalone compressed image — default MJPEG (`.jpg`),
     * or [CodecId.Png]. Converts pixel format automatically when the image codec doesn't
     * accept the frame's own (e.g. yuv420p → rgb24 for PNG).
     */
    fun encodeImage(codec: CodecId = CodecId.Mjpeg): ByteArray

    override fun close()
}
