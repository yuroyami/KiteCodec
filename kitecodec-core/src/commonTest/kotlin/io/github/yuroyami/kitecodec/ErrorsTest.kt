package io.github.yuroyami.kitecodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorsTest {

    @Test
    fun ffErrTagValuesMatchFFmpeg() {
        // FFERRTAG('E','O','F',' ') and the related tags, computed the same way avutil/error.h does.
        assertEquals(-0x20464f45, FFmpegError.AVERROR_EOF)
        assertEquals(-0x41444e49, FFmpegError.AVERROR_INVALIDDATA)
    }

    @Test
    fun errnoCodesClassify() {
        assertIs<FFmpegError.FileNotFound>(FFmpegError.fromCode(-2, "no such file"))
        assertIs<FFmpegError.PermissionDenied>(FFmpegError.fromCode(-13, "denied"))
        assertIs<FFmpegError.OutOfMemory>(FFmpegError.fromCode(-12, "oom"))
        assertIs<FFmpegError.InvalidArgument>(FFmpegError.fromCode(-22, "einval"))
        assertIs<FFmpegError.Io>(FFmpegError.fromCode(-5, "eio"))
    }

    @Test
    fun tagCodesClassify() {
        assertIs<FFmpegError.EndOfFile>(FFmpegError.fromCode(FFmpegError.AVERROR_EOF, "eof"))
        assertIs<FFmpegError.InvalidData>(FFmpegError.fromCode(FFmpegError.AVERROR_INVALIDDATA, "bad"))
        assertIs<FFmpegError.EncoderNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_ENCODER_NOT_FOUND, "enc"))
        assertIs<FFmpegError.DecoderNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_DECODER_NOT_FOUND, "dec"))
        assertIs<FFmpegError.MuxerNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_MUXER_NOT_FOUND, "mux"))
        assertIs<FFmpegError.FilterNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_FILTER_NOT_FOUND, "fil"))
        assertIs<FFmpegError.ProtocolNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_PROTOCOL_NOT_FOUND, "pro"))
    }

    @Test
    fun unmappedCodeFallsBackToAvError() {
        val e = FFmpegError.fromCode(-9999, "mystery")
        assertIs<FFmpegError.AvError>(e)
        assertEquals(-9999, e.code)
        assertEquals("mystery", e.message)
    }

    @Test
    fun exceptionExposesErrorAndCode() {
        val ex = FFmpegException(FFmpegError.fromCode(-2, "gone"))
        assertEquals(-2, ex.code)
        assertIs<FFmpegError.FileNotFound>(ex.error)
        assertTrue(ex.message!!.contains("gone"))
    }

    @Test
    fun internalHasZeroCode() {
        assertEquals(0, FFmpegError.Internal("invariant").code)
    }
}
