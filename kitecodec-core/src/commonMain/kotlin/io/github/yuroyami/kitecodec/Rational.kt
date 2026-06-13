package io.github.yuroyami.kitecodec

/**
 * FFmpeg's `AVRational` — exact fraction. Used for time-bases, frame rates, sample aspect ratios.
 * Float conversion is lossy; do all arithmetic on the rational form when possible.
 *
 * Always stored normalized: reduced by gcd, denominator positive. `Rational(2, 4) == Rational(1, 2)`.
 */
class Rational private constructor(val num: Int, val den: Int) {

    val asDouble: Double get() = num.toDouble() / den.toDouble()
    val asFloat: Float   get() = asDouble.toFloat()

    /** Reciprocal — `Rational(1, 30).inverse == Rational(30, 1)`. */
    val inverse: Rational get() = Rational(den, num)

    operator fun times(other: Rational): Rational =
        of(num.toLong() * other.num, den.toLong() * other.den)

    /**
     * `scalar * num / den` with a Long intermediate. Multiplies after reducing
     * [scalar] against [den] first, so e.g. `pts * Rational(1, 1_000_000)` stays exact
     * far beyond what naive `scalar * num` would overflow at. For timestamp conversion
     * between two arbitrary time-bases prefer the native `av_rescale_q` path.
     */
    operator fun times(scalar: Long): Long {
        val g = gcd(if (scalar < 0) -scalar else scalar, den.toLong())
        return (scalar / g) * num / (den / g)
    }

    override fun equals(other: Any?): Boolean =
        other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = num * 31 + den

    override fun toString(): String = if (den == 1) "$num" else "$num/$den"

    companion object {
        operator fun invoke(num: Int, den: Int): Rational {
            require(den != 0) { "Rational denominator can't be zero" }
            val sign = if (den < 0) -1 else 1
            val g = gcd(num.toLong(), den.toLong()).toInt().coerceAtLeast(1)
            return Rational(sign * num / g, sign * den / g)
        }

        /** Build from a Long-valued fraction, scaling down (with precision loss) only if it can't fit Int. */
        internal fun of(num: Long, den: Long): Rational {
            require(den != 0L) { "Rational denominator can't be zero" }
            val sign = if (den < 0) -1 else 1
            var n = sign * num; var d = sign * den
            val g = gcd(n, d)
            if (g > 1) { n /= g; d /= g }
            while (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || d > Int.MAX_VALUE) {
                n /= 2; d /= 2
            }
            return Rational(n.toInt(), d.toInt().coerceAtLeast(1))
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = if (a < 0) -a else a
            var y = if (b < 0) -b else b
            while (y != 0L) { val t = x % y; x = y; y = t }
            return if (x == 0L) 1L else x
        }

        val Zero    = Rational(0, 1)
        val Tb_us   = Rational(1, 1_000_000)
        val Fps24   = Rational(24, 1)
        val Fps25   = Rational(25, 1)
        val Fps30   = Rational(30, 1)
        val Fps60   = Rational(60, 1)
        val Fps2997 = Rational(30_000, 1001)
        val Fps2398 = Rational(24_000, 1001)
    }
}
