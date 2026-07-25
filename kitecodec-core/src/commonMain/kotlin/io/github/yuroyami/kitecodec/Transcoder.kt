package io.github.yuroyami.kitecodec

/** Progress snapshot delivered during [Transcoder.transcode]. */
public data class TranscodeProgress(
    /** Video frames encoded so far (0 for audio-only transcodes). */
    val framesEncoded: Long,
    /** Where the output timeline currently ends, in microseconds. */
    val outputMicros: Long,
    /** 0.0–1.0 against the trim window / input duration, or null when the duration is unknown. */
    val percent: Double?,
)

/**
 * High-level pipeline: open input → decode → optionally filter → encode → mux. The equivalent of
 * `ffmpeg -ss 12 -to 45 -i in.mp4 -vf "scale=…" -c:v libx264 -c:a aac out.mp4` as one
 * native-Kotlin call with proper cancellation and typed errors.
 *
 * All selected streams are demuxed in a single pass and their packets interleaved into the
 * output as they are produced — constant memory regardless of input length.
 */
public expect object Transcoder {

    /**
     * Run a transcode end-to-end. Suspends until done.
     *
     * @param input  input file path
     * @param output output file path
     * @param spec video encoder spec — null (with [videoCopy] false) for audio-only output
     *             (input video, if any, is dropped). When set but the input has no video
     *             stream, this throws.
     * @param videoFilter filter graph description applied to the video stream — null means
     *                    passthrough of decoded frames straight into the encoder. Requires [spec].
     * @param videoCopy stream-copy the video instead of re-encoding (`-c:v copy`): bit-exact,
     *                  near-free — the classic "keep video, fix audio" move combined with
     *                  [audioSpec]. Mutually exclusive with [spec]/[videoFilter]. Trimming a
     *                  copied video stream is keyframe-snapped, not frame-exact.
     * @param audioSpec audio encoder spec — null (with [audioCopy] false) drops audio. When set
     *                  but the input has no audio stream, the output is silently video-only.
     * @param audioFilter filter chain for the audio stream (e.g. `volume=0.5,atempo=1.25`) —
     *                    null means plain resample/reformat to what the encoder needs
     * @param audioCopy stream-copy the audio instead of re-encoding (`-c:a copy`): bit-exact,
     *                  near-free. Mutually exclusive with [audioSpec]/[audioFilter].
     * @param subtitleCopy stream-copy every subtitle stream into the output. Works for
     *                     subtitle codecs the output container accepts (mkv: almost all;
     *                     mp4: mov_text) — otherwise the muxer raises a typed error.
     * @param startMicros trim start, relative to the start of the content (see
     *                    [MediaSource.startTimeMicros]). Output begins at the first frame at or
     *                    after this point: frame-exact for re-encoded streams, at the preceding
     *                    keyframe for copied ones. Output timestamps are rebased to zero.
     * @param endMicros trim end, on the same content-relative scale — demuxing stops once the
     *                  lead stream passes this.
     * @param metadata container tags written into the output header (`title`, `artist`, …)
     * @param onProgress invoked every ~30 encoded video frames (or ~100 audio frames when
     *                   audio-only) with a [TranscodeProgress]
     */
    public suspend fun transcode(
        input: String,
        output: String,
        spec: VideoEncoderSpec? = null,
        videoFilter: String? = null,
        videoCopy: Boolean = false,
        audioSpec: AudioEncoderSpec? = null,
        audioFilter: String? = null,
        audioCopy: Boolean = false,
        subtitleCopy: Boolean = false,
        startMicros: Long = 0L,
        endMicros: Long = Long.MAX_VALUE,
        metadata: Map<String, String> = emptyMap(),
        onProgress: ((TranscodeProgress) -> Unit)? = null,
    )
}
