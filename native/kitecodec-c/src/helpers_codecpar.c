/* Ordinary maintained source since the interlude (I-12). Lifted at B1.3 from the def body of
 * kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The codecpar part of the FFmpeg helper layer: the def's 'AVCodecParameters' section(s). */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>

#include <string.h>

/* ════════════ AVCodecParameters ════════════ */

KC_API int     ffkmp_codecpar_codec_type(AVCodecParameters *p) { return p ? (int)p->codec_type : -1; }
KC_API int     ffkmp_media_type_video(void)      { return (int)AVMEDIA_TYPE_VIDEO; }
KC_API int     ffkmp_media_type_audio(void)      { return (int)AVMEDIA_TYPE_AUDIO; }
KC_API int     ffkmp_media_type_subtitle(void)   { return (int)AVMEDIA_TYPE_SUBTITLE; }
KC_API int     ffkmp_media_type_data(void)       { return (int)AVMEDIA_TYPE_DATA; }
KC_API int     ffkmp_media_type_attachment(void) { return (int)AVMEDIA_TYPE_ATTACHMENT; }
KC_API int     ffkmp_codecpar_codec_id(AVCodecParameters *p)   { return p ? (int)p->codec_id : 0; }
KC_API int64_t ffkmp_codecpar_bit_rate(AVCodecParameters *p)   { return p ? p->bit_rate : 0; }
KC_API int     ffkmp_codecpar_width(AVCodecParameters *p)      { return p ? p->width : 0; }
KC_API int     ffkmp_codecpar_height(AVCodecParameters *p)     { return p ? p->height : 0; }
KC_API int     ffkmp_codecpar_format(AVCodecParameters *p)     { return p ? p->format : -1; }
KC_API int     ffkmp_codecpar_sample_rate(AVCodecParameters *p){ return p ? p->sample_rate : 0; }
KC_API int     ffkmp_codecpar_channels(AVCodecParameters *p)   { return p ? p->ch_layout.nb_channels : 0; }
KC_API int     ffkmp_codecpar_extradata(AVCodecParameters *p, uint8_t *dst, int dst_size) {
    int copied;
    if (!p || dst_size < 0) return AVERROR(EINVAL);
    if (!p->extradata || p->extradata_size <= 0) return 0;
    if (!dst) return p->extradata_size;
    copied = p->extradata_size < dst_size ? p->extradata_size : dst_size;
    if (copied > 0) memcpy(dst, p->extradata, (size_t)copied);
    return copied;
}
KC_API void    ffkmp_codecpar_sample_aspect_ratio(AVCodecParameters *p, int *num, int *den) {
    if (!p || !num || !den) return;
    *num = p->sample_aspect_ratio.num;
    *den = p->sample_aspect_ratio.den ? p->sample_aspect_ratio.den : 1;
}
KC_API int ffkmp_codecpar_from_context(AVCodecParameters *par, AVCodecContext *ctx) {
    if (!par || !ctx) return AVERROR(EINVAL);
    return avcodec_parameters_from_context(par, ctx);
}
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    if (!dst || !src) return AVERROR(EINVAL);
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}
