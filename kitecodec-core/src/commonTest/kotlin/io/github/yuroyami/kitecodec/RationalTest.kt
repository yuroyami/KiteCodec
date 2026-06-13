package io.github.yuroyami.kitecodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RationalTest {

    @Test
    fun normalizesOnConstruction() {
        assertEquals(Rational(1, 2), Rational(2, 4))
        assertEquals(Rational(1, 2), Rational(-2, -4))
        assertEquals(Rational(-1, 2), Rational(1, -2))
        assertEquals(1, Rational(2, 4).num)
        assertEquals(2, Rational(2, 4).den)
    }

    @Test
    fun zeroDenominatorRejected() {
        assertFailsWith<IllegalArgumentException> { Rational(1, 0) }
    }

    @Test
    fun multiplicationReduces() {
        // 30000/1001 * 1001/30000 = 1 — naive Int math would overflow before reducing.
        assertEquals(Rational(1, 1), Rational.Fps2997 * Rational(1001, 30_000))
        // Two large co-factors that overflow Int when multiplied naively (worst case ~4.6e18 fits Long).
        val a = Rational(1_000_000, 7)
        val b = Rational(7, 1_000_000)
        assertEquals(Rational(1, 1), a * b)
    }

    @Test
    fun scalarTimesAvoidsPrematureOverflow() {
        // A pts value in 1/90000 tick-space converted with a microsecond time-base:
        // scalar * num would overflow if computed before dividing by den.
        val pts = 4_000_000_000L  // > Int.MAX, plausible large tick count
        assertEquals(4_000_000_000L / 1_000_000, Rational.Tb_us * pts)
        // Exactness when den divides scalar after gcd reduction.
        assertEquals(2_000L, Rational(1, 2) * 4_000L)
    }

    @Test
    fun inverseSwapsNumDen() {
        assertEquals(Rational(30, 1), Rational(1, 30).inverse)
        assertEquals(Rational(1001, 30_000), Rational.Fps2997.inverse)
    }

    @Test
    fun doubleConversion() {
        assertTrue(kotlin.math.abs(Rational.Fps2997.asDouble - 29.97) < 0.01)
        assertEquals(0.5, Rational(1, 2).asDouble)
    }

    @Test
    fun stringForm() {
        assertEquals("30", Rational(30, 1).toString())
        assertEquals("1/25", Rational(1, 25).toString())
    }
}
