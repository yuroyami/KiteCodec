package io.github.yuroyami.kitecodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamMetadataTest {
    @Test
    fun vp9MetadataUsesTypedKnownValuesAndNullForUnknownValues() {
        assertEquals(Vp9Profile.Profile0, Vp9Profile.fromNumber(0))
        assertEquals(Vp9Profile.Profile3, Vp9Profile.fromNumber(3))
        assertNull(Vp9Profile.fromNumber(-99))
        assertNull(Vp9Profile.fromNumber(4))

        assertEquals(Vp9Level.Level1, Vp9Level.fromCode(10))
        assertEquals(Vp9Level.Level6_2, Vp9Level.fromCode(62))
        assertNull(Vp9Level.fromCode(-99))
        assertNull(Vp9Level.fromCode(42))

        assertEquals(Vp9BitDepth.Eight, Vp9BitDepth.fromBits(8))
        assertEquals(Vp9BitDepth.Twelve, Vp9BitDepth.fromBits(12))
        assertNull(Vp9BitDepth.fromBits(9))

        assertEquals(Vp9ChromaSubsampling.Yuv420, Vp9ChromaSubsampling.fromCode(420))
        assertEquals(Vp9ChromaSubsampling.Yuv444, Vp9ChromaSubsampling.fromCode(444))
        assertNull(Vp9ChromaSubsampling.fromCode(440))
    }

    @Test
    fun colorDeclarationDistinguishesAbsentAndExplicitLimitedRange() {
        assertTrue(ColorInfo.Unspecified.isUnspecified)
        assertFalse(ColorInfo(transfer = ColorTransfer.SmpteSt2084).isUnspecified)
        assertFalse(ColorInfo(chromaLocation = ChromaLocation.Center).isUnspecified)

        val limited = ColorInfo(fullRange = false, rangeSpecified = true)
        assertFalse(limited.fullRange)
        assertTrue(limited.rangeSpecified)
        assertFalse(limited.isUnspecified)
    }
}
