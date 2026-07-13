package io.github.yuroyami.kitecodec

import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_pts
import ffmpeg.ffkmp_rescale_q

public actual object Transcoder {

    public actual suspend fun transcode(
        input: String,
        output: String,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        videoCopy: Boolean,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ) {
        require(!(videoCopy && (spec != null || videoFilter != null))) {
            "videoCopy is mutually exclusive with spec/videoFilter — copied packets never touch a decoder, so they can't be filtered or re-encoded"
        }
        require(!(audioCopy && (audioSpec != null || audioFilter != null))) {
            "audioCopy is mutually exclusive with audioSpec/audioFilter — copied packets never touch a decoder, so they can't be filtered or re-encoded"
        }
        require(spec != null || videoFilter == null) { "videoFilter requires a video encoder spec" }
        require(spec != null || videoCopy || audioSpec != null || audioCopy) { "Nothing to output: no video spec/copy, no audio" }
        require(startMicros >= 0 && endMicros > startMicros) { "Invalid trim window [$startMicros, $endMicros]" }

        MediaSource.open(input).use { source ->
            val videoStream = if (spec != null || videoCopy) {
                source.primaryVideo
                    ?: throw FFmpegException(FFmpegError.Internal("No video stream in $input (use spec = null for audio-only)"))
            } else null
            val vinfo = videoStream?.video
            val audioStream = if (audioSpec != null || audioCopy) source.primaryAudio else null
            val ainfo = audioStream?.audio
            val subtitleStreams = if (subtitleCopy) source.streams.filter { it.type == MediaType.Subtitle } else emptyList()

            // The stream whose timestamps drive the end-of-trim stop: video when present, else audio.
            val leadStream = videoStream ?: audioStream
                ?: throw FFmpegException(FFmpegError.Internal("Input has neither requested stream"))

            // Progress denominator: trim window clamped by what the container declares.
            val totalWindowMicros: Long? = run {
                val dur = source.durationMicros
                val end = if (endMicros == Long.MAX_VALUE) dur else minOf(endMicros, dur ?: endMicros)
                end?.let { (it - startMicros).coerceAtLeast(1) }
            }

            if (startMicros > 0) source.seekMicros(startMicros)

            MediaSink.open(output).use { sink ->
                // All encoders + copy mappings + metadata must exist before the header.
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                val venc = if (spec != null) sink.addVideoEncoder(spec) else null
                val aenc = if (audioSpec != null && ainfo != null) sink.addAudioEncoder(audioSpec) else null
                val vcopy = if (videoCopy && videoStream != null) sink.addCopyStream(source, videoStream) else null
                val acopy = if (audioCopy && audioStream != null) sink.addCopyStream(source, audioStream) else null
                val subCopies = subtitleStreams.associate { it.index to sink.addCopyStream(source, it) }

                // Graphs are built INSIDE the try so a failing audio-graph build can't leak an
                // already-built video graph (encoders are recovered by sink.close(), graphs are not).
                var videoGraph: FilterGraph? = null
                var audioGraph: FilterGraph? = null
                try {
                    videoGraph = if (videoFilter != null && vinfo != null && videoStream != null) {
                        FilterGraph.buildVideo(
                            description = videoFilter,
                            width = vinfo.width,
                            height = vinfo.height,
                            pixelFormat = vinfo.pixelFormat,
                            timeBase = videoStream.timeBase,
                            frameRate = vinfo.frameRate,
                            sampleAspectRatio = vinfo.sampleAspectRatio,
                        )
                    } else null
                    // Re-encoded audio always runs through a graph: it resamples/reformats to what
                    // the encoder negotiated and chunks output to the codec's fixed frame size.
                    audioGraph = if (aenc != null && audioStream != null && ainfo != null) {
                        FilterGraph.buildAudio(
                            description = audioFilter ?: "anull",
                            sampleRate = ainfo.sampleRate,
                            sampleFormat = ainfo.sampleFormat,
                            channels = ainfo.channels,
                            timeBase = audioStream.timeBase,
                            outputSampleRate = aenc.sampleRate,
                            outputSampleFormat = aenc.sampleFormat,
                            outputChannels = aenc.channels,
                        ).also { graph ->
                            if (aenc.frameSize > 0) graph.setOutputFrameSize(aenc.frameSize)
                        }
                    } else null

                    withPacket { videoPacket ->
                        withPacket { audioPacket ->
                            val progressEvery = if (venc != null) 30L else 100L
                            var sinceReport = 0L
                            val primaryCore = venc?.core ?: aenc?.core
                            fun reportMaybe(force: Boolean = false) {
                                if (onProgress == null) return
                                sinceReport += 1
                                if (!force && sinceReport < progressEvery) return
                                sinceReport = 0
                                val outMicros = primaryCore?.outputMicros ?: 0
                                onProgress(
                                    TranscodeProgress(
                                        framesEncoded = venc?.core?.framesEncoded ?: 0,
                                        outputMicros = outMicros,
                                        percent = totalWindowMicros?.let {
                                            (outMicros.toDouble() / it).coerceIn(0.0, 1.0)
                                        },
                                    )
                                )
                            }

                            /** Frame pts → micros in the frame's OWN time-base (graph output frames
                             *  carry the graph's time-base, decoder frames the stream's). */
                            fun ptsMicros(frame: Frame): Long =
                                if (frame.info.hasPts) {
                                    val tb = frame.streamTimeBase
                                    ffkmp_rescale_q(frame.info.pts, tb.num, tb.den, 1, 1_000_000)
                                } else Long.MIN_VALUE  // treat as "always inside the window"

                            // End-bound is re-checked at the encoder door: filter graphs buffer
                            // frames, so their flush can emit frames past the trim end that the
                            // demux-side check never saw.
                            fun encodeVideo(frame: Frame) {
                                val micros = ptsMicros(frame)
                                if (micros != Long.MIN_VALUE && micros > endMicros) { frame.close(); return }
                                venc!!.core.encode(videoPacket, frame)
                                reportMaybe()
                            }
                            fun encodeAudio(frame: Frame) {
                                val micros = ptsMicros(frame)
                                if (micros != Long.MIN_VALUE && micros > endMicros) { frame.close(); return }
                                aenc!!.core.encode(audioPacket, frame)
                                if (venc == null) reportMaybe()
                            }

                            val decodeList = listOfNotNull(
                                videoStream?.takeIf { venc != null },
                                audioStream?.takeIf { audioGraph != null },
                            )
                            val copyList = listOfNotNull(
                                videoStream?.takeIf { vcopy != null },
                                audioStream?.takeIf { acopy != null },
                            ) + subtitleStreams

                            source.demuxRouted(
                                decode = decodeList,
                                copy = copyList,
                                onFrame = { frame ->
                                    val micros = ptsMicros(frame)
                                    val isLead = frame.streamIndex == leadStream.index
                                    when {
                                        micros != Long.MIN_VALUE && micros > endMicros -> {
                                            frame.close()
                                            if (isLead) throw StopDemux()
                                            // Non-lead frames past the end are just dropped;
                                            // the lead stream decides when to stop demuxing.
                                        }
                                        // Only filter the head when actually trimming — at
                                        // start=0, negative pts (audio priming) must pass.
                                        startMicros > 0 && micros != Long.MIN_VALUE && micros < startMicros ->
                                            frame.close()  // decode-discard up to the exact start
                                        else -> when (frame.streamIndex) {
                                            videoStream?.index ->
                                                if (videoGraph != null) videoGraph!!.feedFrame(frame, ::encodeVideo)
                                                else encodeVideo(frame)
                                            audioStream?.index ->
                                                audioGraph!!.feedFrame(frame, ::encodeAudio)
                                            else -> frame.close()
                                        }
                                    }
                                },
                                onPacket = { packet, info ->
                                    // Copied packets: keyframe-snapped at start (a copied video
                                    // stream keeps everything from the seek keyframe on — dropping
                                    // "before start" packets would break decode until the next
                                    // keyframe), pts-filtered for audio/subtitles, end-bounded.
                                    // End detection gates on dts (monotonic in demux order); pts
                                    // reorders around B-frames and would stop the demux early.
                                    val pktDts = ffkmp_packet_dts(packet)
                                    val pktPts = ffkmp_packet_pts(packet)
                                    val gateTs = if (pktDts != FrameInfo.NOPTS) pktDts else pktPts
                                    val gateMicros = if (gateTs != FrameInfo.NOPTS) {
                                        ffkmp_rescale_q(gateTs, info.timeBase.num, info.timeBase.den, 1, 1_000_000)
                                    } else Long.MIN_VALUE
                                    val ptsMs = if (pktPts != FrameInfo.NOPTS) {
                                        ffkmp_rescale_q(pktPts, info.timeBase.num, info.timeBase.den, 1, 1_000_000)
                                    } else Long.MIN_VALUE
                                    val pastEnd = gateMicros != Long.MIN_VALUE && gateMicros > endMicros
                                    val isVideoCopy = info.index == vcopy?.sourceIndex
                                    val beforeStart = !isVideoCopy && startMicros > 0 &&
                                        ptsMs != Long.MIN_VALUE && ptsMs < startMicros
                                    when {
                                        pastEnd -> if (info.index == leadStream.index) throw StopDemux()
                                        beforeStart -> {}  // drop
                                        isVideoCopy -> vcopy!!.writeCopyPacket(packet)
                                        info.index == acopy?.sourceIndex -> acopy.writeCopyPacket(packet)
                                        else -> subCopies[info.index]?.writeCopyPacket(packet)
                                    }
                                },
                            )

                            // Drain filter graphs, then flush encoders.
                            videoGraph?.flushInto(::encodeVideo)
                            audioGraph?.flushInto(::encodeAudio)
                            venc?.core?.finish(videoPacket)
                            aenc?.core?.finish(audioPacket)
                            reportMaybe(force = true)
                        }
                    }
                } finally {
                    videoGraph?.close()
                    audioGraph?.close()
                    venc?.close()
                    aenc?.close()
                }
            }
        }
    }
}
