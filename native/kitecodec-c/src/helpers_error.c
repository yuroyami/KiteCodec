/* GENERATED FILE. Do not edit.
 *
 * Extracted from kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def by native/kitecodec-c/tools/extract_from_def.py.
 * scripts/verify-lift.sh re-runs the generator against a git revision of the def and
 * compares the result with this file, so a hand edit fails the gate.
 *
 * The error part of the FFmpeg helper layer: the def's 'Errors & macros' section(s). */

#include "kitecodec_helpers.h"

/* ════════════ Errors & macros ════════════ */

KC_API const char* ffkmp_strerror(int errnum) {
    static __thread char buf[256];
    av_strerror(errnum, buf, sizeof(buf));
    return buf;
}
KC_API int ffkmp_averror_eagain(void) { return AVERROR(EAGAIN); }
KC_API int ffkmp_averror_eof(void)    { return AVERROR_EOF; }

/* av_rescale_q uses a 128-bit intermediate, the only overflow-safe way to convert a
   timestamp between two time-bases. Exposed because Kotlin Long*Long would overflow. */
KC_API int64_t ffkmp_rescale_q(int64_t v, int sn, int sd, int dn, int dd) {
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    return av_rescale_q(v, s, d);
}

