/* GENERATED FILE. Do not edit.
 *
 * Extracted from kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def by native/kitecodec-c/tools/extract_from_def.py.
 * scripts/verify-lift.sh re-runs the generator against a git revision of the def and
 * compares the result with this file, so a hand edit fails the gate.
 *
 * The codecpar part of the FFmpeg helper layer: the def's 'AVCodecParameters' section(s). */

#include "kitecodec_helpers.h"
/* ════════════ AVCodecParameters ════════════ */

KC_API int     ffkmp_codecpar_codec_type(AVCodecParameters *p) { return p ? (int)p->codec_type : -1; }
KC_API int     ffkmp_codecpar_codec_id(AVCodecParameters *p)   { return p ? (int)p->codec_id : 0; }
KC_API int64_t ffkmp_codecpar_bit_rate(AVCodecParameters *p)   { return p ? p->bit_rate : 0; }
KC_API int     ffkmp_codecpar_width(AVCodecParameters *p)      { return p ? p->width : 0; }
KC_API int     ffkmp_codecpar_height(AVCodecParameters *p)     { return p ? p->height : 0; }
KC_API int     ffkmp_codecpar_format(AVCodecParameters *p)     { return p ? p->format : -1; }
KC_API int     ffkmp_codecpar_sample_rate(AVCodecParameters *p){ return p ? p->sample_rate : 0; }
KC_API int     ffkmp_codecpar_channels(AVCodecParameters *p)   { return p ? p->ch_layout.nb_channels : 0; }
KC_API void    ffkmp_codecpar_sample_aspect_ratio(AVCodecParameters *p, int *num, int *den) {
    if (!p || !num || !den) return;
    *num = p->sample_aspect_ratio.num;
    *den = p->sample_aspect_ratio.den ? p->sample_aspect_ratio.den : 1;
}
KC_API int ffkmp_codecpar_from_context(AVCodecParameters *par, AVCodecContext *ctx) {
    return avcodec_parameters_from_context(par, ctx);
}
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}

