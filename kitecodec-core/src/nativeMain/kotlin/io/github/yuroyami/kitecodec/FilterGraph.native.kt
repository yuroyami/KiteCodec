package io.github.yuroyami.kitecodec

import ffmpeg.AVFilterContext
import ffmpeg.AVFilterGraph
import ffmpeg.ffkmp_buffersink_set_frame_size
import ffmpeg.ffkmp_buffersink_time_base
import ffmpeg.ffkmp_graph_build_audio
import ffmpeg.ffkmp_graph_build_audio_multi
import ffmpeg.ffkmp_graph_build_video
import ffmpeg.ffkmp_graph_build_video_multi
import ffmpeg.ffkmp_graph_free
import ffmpeg.ffkmp_graph_receive
import ffmpeg.ffkmp_graph_send
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class FilterGraph internal constructor(
    private val graph: CPointer<AVFilterGraph>,
    private val srcs: List<CPointer<AVFilterContext>>,
    private val sink: CPointer<AVFilterContext>,
    private val inputType: MediaType,
) : AutoCloseable {

    private val closed = atomic(false)

    actual val inputCount: Int get() = srcs.size

    actual val outputTimeBase: Rational = memScoped {
        val n = alloc<IntVar>(); val d = alloc<IntVar>()
        ffkmp_buffersink_time_base(sink, n.ptr, d.ptr)
        Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
    }

    /** Reusable landing frame for buffersink output; allocated on first use, freed in [close]. */
    private var outFrameHolder: Frame? = null
    private fun outFrame(): Frame {
        check(!closed.value) { "FilterGraph is closed" }
        return outFrameHolder
            ?: FrameOps.acquire(streamIndex = -1, streamType = inputType, timeBase = outputTimeBase)
                .also { outFrameHolder = it }
    }

    actual fun setOutputFrameSize(samples: Int) {
        check(!closed.value) { "FilterGraph is closed" }
        require(samples > 0) { "frame size must be positive" }
        ffkmp_buffersink_set_frame_size(sink, samples.toUInt())
    }

    actual fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit) {
        val src = srcs.getOrNull(index)
            ?: throw IllegalArgumentException("Input $index out of range (graph has ${srcs.size} inputs)")
        val sendRc = ffkmp_graph_send(src, frame.nativeFrame)
        frame.close()
        if (sendRc < 0 && sendRc != FFErrors.EAGAIN) throw FFmpegException(avError(sendRc))
        drainTo(onOutput)
    }

    actual fun flushInput(index: Int, onOutput: (Frame) -> Unit) {
        val src = srcs.getOrNull(index)
            ?: throw IllegalArgumentException("Input $index out of range (graph has ${srcs.size} inputs)")
        val rc = ffkmp_graph_send(src, null)
        if (rc < 0 && rc != FFErrors.EOF) throw FFmpegException(avError(rc))
        drainTo(onOutput)
    }

    /** Single-input convenience used by Transcoder. */
    internal fun feedFrame(frame: Frame, onOutput: (Frame) -> Unit) = feedInput(0, frame, onOutput)

    /** Flush EVERY input, then drain — after this the graph is spent. */
    internal fun flushInto(onOutput: (Frame) -> Unit) {
        for (i in srcs.indices) flushInput(i, onOutput)
    }

    private fun drainTo(onOutput: (Frame) -> Unit) {
        val landing = outFrame()
        while (true) {
            val rc = ffkmp_graph_receive(sink, landing.nativeFrame)
            if (rc == FFErrors.EAGAIN || rc == FFErrors.EOF) return
            if (rc < 0) throw FFmpegException(avError(rc))
            onOutput(FrameOps.wrap(landing.nativeFrame, -1, inputType, outputTimeBase))
        }
    }

    actual fun process(input: Flow<Frame>): Flow<Frame> = flow {
        check(!closed.value) { "FilterGraph is closed" }
        check(srcs.size == 1) { "process() drives single-input graphs; use feedInput for multi-input" }
        val eagain = FFErrors.EAGAIN
        val eof    = FFErrors.EOF
        val src = srcs[0]
        try {
            val landing = outFrame()
            input.collect { srcFrame ->
                val sendRc = ffkmp_graph_send(src, srcFrame.nativeFrame)
                srcFrame.close()  // buffersrc copied / reffed the buffer; we can release our hold.
                if (sendRc < 0 && sendRc != eagain) throw FFmpegException(avError(sendRc))

                while (true) {
                    val recRc = ffkmp_graph_receive(sink, landing.nativeFrame)
                    if (recRc == eagain || recRc == eof) break
                    if (recRc < 0) throw FFmpegException(avError(recRc))
                    emit(FrameOps.wrap(landing.nativeFrame, -1, inputType, outputTimeBase))
                }
            }
            // Flush.
            val flushRc = ffkmp_graph_send(src, null)
            if (flushRc < 0 && flushRc != eof) throw FFmpegException(avError(flushRc))
            while (true) {
                val rc = ffkmp_graph_receive(sink, landing.nativeFrame)
                if (rc == eagain || rc == eof) break
                if (rc < 0) throw FFmpegException(avError(rc))
                emit(FrameOps.wrap(landing.nativeFrame, -1, inputType, outputTimeBase))
            }
        } finally {
            close()  // The graph is spent after EOF — release it with the flow.
        }
    }

    actual override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        outFrameHolder?.close()
        outFrameHolder = null
        val a = Arena()
        try {
            val gp = a.alloc<CPointerVar<AVFilterGraph>>().also { it.value = graph }
            ffkmp_graph_free(gp.ptr)
        } finally { a.clear() }
    }

    actual companion object {
        actual fun buildVideo(
            description: String,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            timeBase: Rational,
            frameRate: Rational,
            sampleAspectRatio: Rational,
        ): FilterGraph {
            val arena = Arena()
            val graphVar = arena.allocPointerTo<AVFilterGraph>()
            val srcVar = arena.allocPointerTo<AVFilterContext>()
            val sinkVar = arena.allocPointerTo<AVFilterContext>()

            val rc = ffkmp_graph_build_video(
                graphVar.ptr, srcVar.ptr, sinkVar.ptr,
                description,
                width, height, pixelFormatToAv(pixelFormat),
                timeBase.num, timeBase.den,
                frameRate.num, frameRate.den,
                sampleAspectRatio.num, sampleAspectRatio.den,
            )
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

            val graph = graphVar.value!!
            val src = srcVar.value!!
            val sink = sinkVar.value!!
            arena.clear()
            return FilterGraph(graph, listOf(src), sink, MediaType.Video)
        }

        actual fun buildAudio(
            description: String,
            sampleRate: Int,
            sampleFormat: SampleFormat,
            channels: Int,
            timeBase: Rational,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph {
            val arena = Arena()
            val graphVar = arena.allocPointerTo<AVFilterGraph>()
            val srcVar = arena.allocPointerTo<AVFilterContext>()
            val sinkVar = arena.allocPointerTo<AVFilterContext>()

            val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)
            val rc = ffkmp_graph_build_audio(
                graphVar.ptr, srcVar.ptr, sinkVar.ptr,
                description,
                sampleRate, sampleFormatToAv(sampleFormat), channels,
                timeBase.num, timeBase.den,
                outFmtAv, outputSampleRate, outputChannels,
            )
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

            val graph = graphVar.value!!
            val src = srcVar.value!!
            val sink = sinkVar.value!!
            arena.clear()
            return FilterGraph(graph, listOf(src), sink, MediaType.Audio)
        }

        actual fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph {
            require(inputs.isNotEmpty()) { "Need at least one input" }
            memScoped {
                val n = inputs.size
                val graphVar = allocPointerTo<AVFilterGraph>()
                val sinkVar = allocPointerTo<AVFilterContext>()
                val srcsArr = allocArray<CPointerVar<AVFilterContext>>(n)
                val widths = allocArray<IntVar>(n); val heights = allocArray<IntVar>(n)
                val pixFmts = allocArray<IntVar>(n)
                val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
                val frN = allocArray<IntVar>(n); val frD = allocArray<IntVar>(n)
                val sarN = allocArray<IntVar>(n); val sarD = allocArray<IntVar>(n)
                inputs.forEachIndexed { i, inp ->
                    widths[i] = inp.width; heights[i] = inp.height
                    pixFmts[i] = pixelFormatToAv(inp.pixelFormat)
                    tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
                    frN[i] = inp.frameRate.num; frD[i] = inp.frameRate.den
                    sarN[i] = inp.sampleAspectRatio.num; sarD[i] = inp.sampleAspectRatio.den
                }

                val rc = ffkmp_graph_build_video_multi(
                    graphVar.ptr, srcsArr, sinkVar.ptr,
                    description, n,
                    widths, heights, pixFmts, tbN, tbD, frN, frD, sarN, sarD,
                )
                if (rc < 0) throw FFmpegException(avError(rc))

                val srcs = (0 until n).map { srcsArr[it]!! }
                return FilterGraph(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Video)
            }
        }

        actual fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph {
            require(inputs.isNotEmpty()) { "Need at least one input" }
            memScoped {
                val n = inputs.size
                val graphVar = allocPointerTo<AVFilterGraph>()
                val sinkVar = allocPointerTo<AVFilterContext>()
                val srcsArr = allocArray<CPointerVar<AVFilterContext>>(n)
                val rates = allocArray<IntVar>(n); val fmts = allocArray<IntVar>(n)
                val chans = allocArray<IntVar>(n)
                val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
                inputs.forEachIndexed { i, inp ->
                    rates[i] = inp.sampleRate
                    fmts[i] = sampleFormatToAv(inp.sampleFormat)
                    chans[i] = inp.channels
                    tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
                }
                val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)

                val rc = ffkmp_graph_build_audio_multi(
                    graphVar.ptr, srcsArr, sinkVar.ptr,
                    description, n,
                    rates, fmts, chans, tbN, tbD,
                    outFmtAv, outputSampleRate, outputChannels,
                )
                if (rc < 0) throw FFmpegException(avError(rc))

                val srcs = (0 until n).map { srcsArr[it]!! }
                return FilterGraph(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Audio)
            }
        }
    }
}
