package io.github.yuroyami.kitecodec

import kotlin.jvm.JvmInline

/** What kind of data flows on a stream / through a codec / out of a filter graph. */
enum class MediaType {
    Video, Audio, Subtitle, Data, Attachment, Unknown;

    val isAv: Boolean get() = this == Video || this == Audio
}

/**
 * The pixel format of a video frame. We use the FFmpeg name (`yuv420p`, `nv12`, …) directly —
 * the actual impl translates to `AV_PIX_FMT_*` integer values.
 */
@JvmInline
value class PixelFormat(val name: String) {
    companion object {
        val Yuv420p = PixelFormat("yuv420p")
        val Yuv422p = PixelFormat("yuv422p")
        val Yuv444p = PixelFormat("yuv444p")
        val Nv12    = PixelFormat("nv12")
        val Rgb24   = PixelFormat("rgb24")
        val Rgba    = PixelFormat("rgba")
        val Bgra    = PixelFormat("bgra")
        val Gray8   = PixelFormat("gray")
        val None    = PixelFormat("none")
    }
}

/**
 * Audio sample format. Planar variants store each channel in its own plane; packed variants
 * interleave channels. `s16` / `s16p` is the most common decoder output.
 */
@JvmInline
value class SampleFormat(val name: String) {
    val isPlanar: Boolean get() = name.endsWith("p")

    companion object {
        val U8   = SampleFormat("u8");    val U8p  = SampleFormat("u8p")
        val S16  = SampleFormat("s16");   val S16p = SampleFormat("s16p")
        val S32  = SampleFormat("s32");   val S32p = SampleFormat("s32p")
        val Flt  = SampleFormat("flt");   val FltP = SampleFormat("fltp")
        val Dbl  = SampleFormat("dbl");   val DblP = SampleFormat("dblp")
        val None = SampleFormat("none")
    }
}

/** Codec identifier — symbolic name (`h264`, `aac`, `libx264`). Matches `avcodec_find_*_by_name`. */
@JvmInline
value class CodecId(val name: String) {
    companion object {
        val H264   = CodecId("h264");        val Hevc   = CodecId("hevc")
        val Av1    = CodecId("av1");         val Vp9    = CodecId("vp9")
        val Vp8    = CodecId("vp8");         val Mjpeg  = CodecId("mjpeg")
        val Aac    = CodecId("aac");         val Mp3    = CodecId("mp3")
        val Opus   = CodecId("opus");        val Vorbis = CodecId("vorbis")
        val Flac   = CodecId("flac");        val PcmS16 = CodecId("pcm_s16le")
        val Libx264 = CodecId("libx264");    val Libx265 = CodecId("libx265")
        val LibOpus = CodecId("libopus");    val LibMp3 = CodecId("libmp3lame")
        val Png     = CodecId("png")

        /** Hardware encoders — resolve at runtime only on FFmpeg builds with the matching hwaccel. */
        val H264VideoToolbox = CodecId("h264_videotoolbox")
        val HevcVideoToolbox = CodecId("hevc_videotoolbox")
        val H264MediaCodec   = CodecId("h264_mediacodec")
        val HevcMediaCodec   = CodecId("hevc_mediacodec")
    }
}
