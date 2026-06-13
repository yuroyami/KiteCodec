package io.github.yuroyami.kitecodec

import ffmpeg.ffkmp_packet_pts
import ffmpeg.ffkmp_rescale_q

actual object Transcoder {

    actual suspend fun transcode(
        input: String,
        output: String,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ) {
        require(!(audioCopy && (audioSpec != null || audioFilter != null))) {
            "audioCopy is mutually exclusive with audioSpec/audioFilter — copied packets never touch a decoder, so they can't be filtered or re-encoded"
        }
        require(spec != null || videoFilter == null) { "videoFilter requires a video encoder spec" }
        require(spec != null || audioSpec != null || audioCopy) { "Nothing to output: no video spec, no audio" }
        require(startMicros >= 0 && endMicros > startMicros) { "Invalid trim window [$startMicros, $endMicros]" }

        MediaSource.open(input).use { source ->
            val videoStream = if (spec != null) {
                source.primaryVideo
                    ?: throw FFmpegException(FFmpegError.Internal("No video stream in $input (use spec = null for audio-only)"))
            } else null
            val vinfo = videoStream?.video
            val audioStream = if (audioSpec != null || audioCopy) source.primaryAudio else null
            val ainfo = audioStream?.audio
            val subtitleStreams = if (subtitleCopy) source.streams.filter { it.type == MediaType.Subtitle } else emptyList()

            // The stream whose pts drives the end-of-trim stop: video when present, else audio.
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
                val acopy = if (audioCopy && audioStream != null) sink.addCopyStream(source, audioStream) else null
                val subCopies = subtitleStreams.associate { it.index to sink.addCopyStream(source, it) }

                val videoGraph = if (videoFilter != null && vinfo != null && videoStream != null) {
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
                val audioGraph = if (aenc != null && audioStream != null && ainfo != null) {
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

                try {
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
                            fun encodeVideo(frame: Frame) {
                                venc!!.core.encode(videoPacket, frame)
                                reportMaybe()
                            }
                            fun encodeAudio(frame: Frame) {
                                aenc!!.core.encode(audioPacket, frame)
                                if (venc == null) reportMaybe()
                            }

                            /** Frame pts → micros in its stream's time-base. */
                            fun ptsMicros(frame: Frame, tb: Rational): Long =
                                if (frame.info.hasPts) ffkmp_rescale_q(frame.info.pts, tb.num, tb.den, 1, 1_000_000)
                                else Long.MIN_VALUE  // treat as "always inside the window"

                            val decodeList = listOfNotNull(
                                videoStream,
                                audioStream.takeIf { audioGraph != null },
                            )
                            val copyList = listOfNotNull(audioStream.takeIf { acopy != null }) + subtitleStreams

                            source.demuxRouted(
                                decode = decodeList,
                                copy = copyList,
                                onFrame = { frame ->
                                    val streamTb = when (frame.streamIndex) {
                                        videoStream?.index -> videoStream.timeBase
                                        audioStream?.index -> audioStream.timeBase
                                        else -> Rational.Tb_us
                                    }
                                    val micros = ptsMicros(frame, streamTb)
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
                                                if (videoGraph != null) videoGraph.feedFrame(frame, ::encodeVideo)
                                                else encodeVideo(frame)
                                            audioStream?.index ->
                                                audioGraph!!.feedFrame(frame, ::encodeAudio)
                                            else -> frame.close()
                                        }
                                    }
                                },
                                onPacket = { packet, info ->
                                    // Copied packets: keyframe-snapped at start (video copy needs
                                    // its keyframe), pts-filtered for audio/subtitles, end-bounded.
                                    val pktPts = ffkmp_packet_pts(packet)
                                    val micros = if (pktPts != FrameInfo.NOPTS) {
                                        ffkmp_rescale_q(pktPts, info.timeBase.num, info.timeBase.den, 1, 1_000_000)
                                    } else Long.MIN_VALUE
                                    val pastEnd = micros != Long.MIN_VALUE && micros > endMicros
                                    val beforeStart = startMicros > 0 && micros != Long.MIN_VALUE && micros < startMicros
                                    when {
                                        pastEnd -> if (info.index == leadStream.index) throw StopDemux()
                                        beforeStart -> {}  // drop
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
