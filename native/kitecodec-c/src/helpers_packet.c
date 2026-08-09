/* GENERATED FILE. Do not edit.
 *
 * Extracted from kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def by native/kitecodec-c/tools/extract_from_def.py.
 * scripts/verify-lift.sh re-runs the generator against a git revision of the def and
 * compares the result with this file, so a hand edit fails the gate.
 *
 * The packet part of the FFmpeg helper layer: the def's 'AVPacket' section(s). */

#include "kitecodec_helpers.h"
/* ════════════ AVPacket ════════════ */

KC_API AVPacket* ffkmp_packet_alloc(void)        { return av_packet_alloc(); }
KC_API void      ffkmp_packet_free(AVPacket *p)  { if (p) { AVPacket *q = p; av_packet_free(&q); } }
KC_API void      ffkmp_packet_unref(AVPacket *p) { if (p) av_packet_unref(p); }
KC_API int64_t   ffkmp_packet_pts(AVPacket *p)           { return p ? p->pts : AV_NOPTS_VALUE; }
KC_API int64_t   ffkmp_packet_dts(AVPacket *p)           { return p ? p->dts : AV_NOPTS_VALUE; }
KC_API int       ffkmp_packet_stream_index(AVPacket *p)  { return p ? p->stream_index : -1; }
KC_API int       ffkmp_packet_size(AVPacket *p)          { return p ? p->size : 0; }
KC_API uint8_t*  ffkmp_packet_data(AVPacket *p)          { return p ? p->data : NULL; }
KC_API int64_t   ffkmp_packet_duration(AVPacket *p)      { return p ? p->duration : 0; }
KC_API int       ffkmp_packet_is_keyframe(AVPacket *p)   { return (p && (p->flags & AV_PKT_FLAG_KEY)) ? 1 : 0; }
KC_API void      ffkmp_packet_set_stream_index(AVPacket *p, int i) { if (p) p->stream_index = i; }
KC_API void      ffkmp_packet_set_pts(AVPacket *p, int64_t v) { if (p) p->pts = v; }
KC_API void      ffkmp_packet_set_dts(AVPacket *p, int64_t v) { if (p) p->dts = v; }
KC_API void      ffkmp_packet_rescale_ts(AVPacket *p, int sn, int sd, int dn, int dd) {
    if (!p) return;
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    av_packet_rescale_ts(p, s, d);
}

