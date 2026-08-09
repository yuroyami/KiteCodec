/* GENERATED FILE. Do not edit.
 *
 * Extracted from kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def by native/kitecodec-c/tools/extract_from_def.py.
 * scripts/verify-lift.sh re-runs the generator against a git revision of the def and
 * byte-compares the result with this file, so a hand edit fails the gate.
 *
 * The FFmpeg helper layer, one definition per declaration in kitecodec_helpers.h. */

#include "kitecodec_helpers.h"

/* ════════════ Errors & macros ════════════ */

const char* ffkmp_strerror(int errnum) {
    static __thread char buf[256];
    av_strerror(errnum, buf, sizeof(buf));
    return buf;
}
int ffkmp_averror_eagain(void) { return AVERROR(EAGAIN); }
int ffkmp_averror_eof(void)    { return AVERROR_EOF; }
int ffkmp_averror_einval(void) { return AVERROR(EINVAL); }
int64_t ffkmp_nopts_value(void) { return AV_NOPTS_VALUE; }

/* av_rescale_q uses a 128-bit intermediate, the only overflow-safe way to convert a
   timestamp between two time-bases. Exposed because Kotlin Long*Long would overflow. */
int64_t ffkmp_rescale_q(int64_t v, int sn, int sd, int dn, int dd) {
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    return av_rescale_q(v, s, d);
}

/* ════════════ AVFrame ════════════ */

AVFrame* ffkmp_frame_alloc(void)        { return av_frame_alloc(); }
void     ffkmp_frame_free(AVFrame *f)   { if (f) { AVFrame *p = f; av_frame_free(&p); } }
void     ffkmp_frame_unref(AVFrame *f)  { if (f) av_frame_unref(f); }
int      ffkmp_frame_ref(AVFrame *dst, AVFrame *src) { return av_frame_ref(dst, src); }
int64_t  ffkmp_frame_pts(AVFrame *f)         { return f ? f->pts : AV_NOPTS_VALUE; }
int64_t  ffkmp_frame_duration(AVFrame *f)    { return f ? f->duration : 0; }
int      ffkmp_frame_format(AVFrame *f)      { return f ? f->format : -1; }
int      ffkmp_frame_width(AVFrame *f)       { return f ? f->width : 0; }
int      ffkmp_frame_height(AVFrame *f)      { return f ? f->height : 0; }
int      ffkmp_frame_nb_samples(AVFrame *f)  { return f ? f->nb_samples : 0; }
int      ffkmp_frame_sample_rate(AVFrame *f) { return f ? f->sample_rate : 0; }
int      ffkmp_frame_channels(AVFrame *f)    { return f ? f->ch_layout.nb_channels : 0; }
int      ffkmp_frame_linesize(AVFrame *f, int p) { return (f && p>=0 && p<AV_NUM_DATA_POINTERS) ? f->linesize[p] : 0; }
void     ffkmp_frame_set_pts(AVFrame *f, int64_t pts)     { if (f) f->pts = pts; }
void     ffkmp_frame_set_format(AVFrame *f, int v)        { if (f) f->format = v; }
void     ffkmp_frame_set_width(AVFrame *f, int v)         { if (f) f->width = v; }
void     ffkmp_frame_set_height(AVFrame *f, int v)        { if (f) f->height = v; }
void     ffkmp_frame_set_sample_rate(AVFrame *f, int v)   { if (f) f->sample_rate = v; }
void     ffkmp_frame_set_nb_samples(AVFrame *f, int v)    { if (f) f->nb_samples = v; }
int      ffkmp_frame_get_buffer(AVFrame *f, int align)    { return av_frame_get_buffer(f, align); }
int      ffkmp_frame_make_writable(AVFrame *f)            { return av_frame_make_writable(f); }
void     ffkmp_frame_set_ch_layout_default(AVFrame *f, int ch) {
    if (!f) return;
    av_channel_layout_uninit(&f->ch_layout);
    av_channel_layout_default(&f->ch_layout, ch);
}
/* Decoders fill best_effort_timestamp even when pts is missing (e.g. AVI without pts);
   promoting it to pts is what ffmpeg.c itself does before filtering/encoding. */
void     ffkmp_frame_use_best_effort_ts(AVFrame *f) {
    if (f) f->pts = f->best_effort_timestamp;
}
/* Deep-copy via new references to the same (refcounted) buffers, so O(1), no pixel copy.
   The clone owns its references: safe to hold after the source frame is reused/unref'd. */
AVFrame* ffkmp_frame_clone(const AVFrame *f) { return f ? av_frame_clone(f) : NULL; }

/* One-shot pixel format conversion (e.g. yuv420p → rgb24 for PNG export). Returns a freshly
   allocated frame the caller must av_frame_free, or NULL on failure. */
AVFrame* ffkmp_frame_convert_pixfmt(const AVFrame *src, int dst_fmt) {
    if (!src || src->width <= 0 || src->height <= 0) return NULL;
    struct SwsContext *sws = sws_getContext(
        src->width, src->height, (enum AVPixelFormat)src->format,
        src->width, src->height, (enum AVPixelFormat)dst_fmt,
        SWS_BILINEAR, NULL, NULL, NULL);
    if (!sws) return NULL;
    AVFrame *dst = av_frame_alloc();
    if (!dst) { sws_freeContext(sws); return NULL; }
    dst->width = src->width; dst->height = src->height; dst->format = dst_fmt;
    dst->pts = src->pts;
    if (av_frame_get_buffer(dst, 0) < 0 ||
        sws_scale(sws, (const uint8_t * const *)src->data, src->linesize,
                  0, src->height, dst->data, dst->linesize) < 0) {
        av_frame_free(&dst); sws_freeContext(sws); return NULL;
    }
    sws_freeContext(sws);
    return dst;
}

int ffkmp_image_get_buffer_size(int fmt, int w, int h, int align) {
    return av_image_get_buffer_size(fmt, w, h, align);
}
int ffkmp_frame_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size) {
    if (!f || !dst) return AVERROR(EINVAL);
    return av_image_copy_to_buffer(
        dst, dst_size,
        (const uint8_t * const *)f->data, f->linesize,
        f->format, f->width, f->height, 1);
}

/* Audio twin of the above: how many bytes the frame's samples occupy, and a flat copy.
   Planar formats land plane-after-plane (ch0 samples, ch1 samples, …), packed stay packed. */
int ffkmp_samples_get_buffer_size(AVFrame *f) {
    if (!f || f->nb_samples <= 0) return AVERROR(EINVAL);
    return av_samples_get_buffer_size(NULL, f->ch_layout.nb_channels, f->nb_samples, f->format, 1);
}
int ffkmp_samples_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size) {
    if (!f || !dst || f->nb_samples <= 0) return AVERROR(EINVAL);
    int ch = f->ch_layout.nb_channels;
    int needed = av_samples_get_buffer_size(NULL, ch, f->nb_samples, f->format, 1);
    if (needed < 0) return needed;
    if (needed > dst_size) return AVERROR(EINVAL);
    int planes = av_sample_fmt_is_planar(f->format) ? ch : 1;
    int plane_size = needed / planes;
    for (int p = 0; p < planes; p++) {
        if (!f->extended_data[p]) return AVERROR(EINVAL);
        memcpy(dst + (size_t)p * plane_size, f->extended_data[p], plane_size);
    }
    return needed;
}

/* Reverse of ffkmp_frame_copy_to_buffer: fill an allocated video frame's planes from a
   tightly-packed (align=1) buffer. Frame must already carry width/height/format and have
   buffers (av_frame_get_buffer). */
int ffkmp_frame_fill_video(AVFrame *f, const uint8_t *src, int src_size) {
    if (!f || !src || f->width <= 0 || f->height <= 0) return AVERROR(EINVAL);
    int needed = av_image_get_buffer_size(f->format, f->width, f->height, 1);
    if (needed < 0) return needed;
    if (src_size < needed) return AVERROR(EINVAL);
    uint8_t *tmp_data[4]; int tmp_linesize[4];
    int rc = av_image_fill_arrays(tmp_data, tmp_linesize, src, f->format, f->width, f->height, 1);
    if (rc < 0) return rc;
    av_image_copy(f->data, f->linesize, (const uint8_t **)tmp_data, tmp_linesize,
                  f->format, f->width, f->height);
    return 0;
}

/* Reverse of ffkmp_samples_copy_to_buffer: fill an allocated audio frame's planes from a
   flat buffer (planar: plane-after-plane; packed: interleaved). ≤ 8 channels (AVFrame.data). */
int ffkmp_frame_fill_audio(AVFrame *f, const uint8_t *src, int src_size) {
    if (!f || !src || f->nb_samples <= 0) return AVERROR(EINVAL);
    int ch = f->ch_layout.nb_channels;
    if (ch <= 0 || ch > AV_NUM_DATA_POINTERS) return AVERROR(EINVAL);
    int needed = av_samples_get_buffer_size(NULL, ch, f->nb_samples, f->format, 1);
    if (needed < 0) return needed;
    if (src_size < needed) return AVERROR(EINVAL);
    int planes = av_sample_fmt_is_planar(f->format) ? ch : 1;
    int plane_size = needed / planes;
    for (int p = 0; p < planes; p++) {
        if (!f->extended_data[p]) return AVERROR(EINVAL);
        memcpy(f->extended_data[p], src + (size_t)p * plane_size, plane_size);
    }
    return 0;
}

/* ════════════ Pixel/sample format names ════════════ */

const char* ffkmp_pix_fmt_name(int fmt)              { return av_get_pix_fmt_name(fmt); }
int         ffkmp_pix_fmt_from_name(const char *n)   { return av_get_pix_fmt(n); }
const char* ffkmp_sample_fmt_name(int fmt)           { return av_get_sample_fmt_name(fmt); }
int         ffkmp_sample_fmt_from_name(const char *n){ return av_get_sample_fmt(n); }

/* ════════════ AVDictionary iteration ════════════ */

AVDictionaryEntry* ffkmp_dict_get(AVDictionary *d, AVDictionaryEntry *prev) {
    return av_dict_get(d, "", prev, AV_DICT_IGNORE_SUFFIX);
}
const char* ffkmp_dict_entry_key(AVDictionaryEntry *e)   { return e ? e->key   : NULL; }
const char* ffkmp_dict_entry_value(AVDictionaryEntry *e) { return e ? e->value : NULL; }

/* ════════════ AVPacket ════════════ */

AVPacket* ffkmp_packet_alloc(void)        { return av_packet_alloc(); }
void      ffkmp_packet_free(AVPacket *p)  { if (p) { AVPacket *q = p; av_packet_free(&q); } }
void      ffkmp_packet_unref(AVPacket *p) { if (p) av_packet_unref(p); }
int       ffkmp_packet_ref(AVPacket *d, AVPacket *s) { return av_packet_ref(d, s); }
int64_t   ffkmp_packet_pts(AVPacket *p)           { return p ? p->pts : AV_NOPTS_VALUE; }
int64_t   ffkmp_packet_dts(AVPacket *p)           { return p ? p->dts : AV_NOPTS_VALUE; }
int       ffkmp_packet_stream_index(AVPacket *p)  { return p ? p->stream_index : -1; }
int       ffkmp_packet_flags(AVPacket *p)         { return p ? p->flags : 0; }
int       ffkmp_packet_size(AVPacket *p)          { return p ? p->size : 0; }
uint8_t*  ffkmp_packet_data(AVPacket *p)          { return p ? p->data : NULL; }
int64_t   ffkmp_packet_duration(AVPacket *p)      { return p ? p->duration : 0; }
int       ffkmp_packet_is_keyframe(AVPacket *p)   { return (p && (p->flags & AV_PKT_FLAG_KEY)) ? 1 : 0; }
void      ffkmp_packet_set_stream_index(AVPacket *p, int i) { if (p) p->stream_index = i; }
void      ffkmp_packet_set_pts(AVPacket *p, int64_t v) { if (p) p->pts = v; }
void      ffkmp_packet_set_dts(AVPacket *p, int64_t v) { if (p) p->dts = v; }
void      ffkmp_packet_rescale_ts(AVPacket *p, int sn, int sd, int dn, int dd) {
    if (!p) return;
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    av_packet_rescale_ts(p, s, d);
}

/* ════════════ AVCodecParameters ════════════ */

int     ffkmp_codecpar_codec_type(AVCodecParameters *p) { return p ? (int)p->codec_type : -1; }
int     ffkmp_codecpar_codec_id(AVCodecParameters *p)   { return p ? (int)p->codec_id : 0; }
int64_t ffkmp_codecpar_bit_rate(AVCodecParameters *p)   { return p ? p->bit_rate : 0; }
int     ffkmp_codecpar_width(AVCodecParameters *p)      { return p ? p->width : 0; }
int     ffkmp_codecpar_height(AVCodecParameters *p)     { return p ? p->height : 0; }
int     ffkmp_codecpar_format(AVCodecParameters *p)     { return p ? p->format : -1; }
int     ffkmp_codecpar_sample_rate(AVCodecParameters *p){ return p ? p->sample_rate : 0; }
int     ffkmp_codecpar_channels(AVCodecParameters *p)   { return p ? p->ch_layout.nb_channels : 0; }
int     ffkmp_codecpar_video_delay(AVCodecParameters *p){ return p ? p->video_delay : 0; }
void    ffkmp_codecpar_sample_aspect_ratio(AVCodecParameters *p, int *num, int *den) {
    if (!p || !num || !den) return;
    *num = p->sample_aspect_ratio.num;
    *den = p->sample_aspect_ratio.den ? p->sample_aspect_ratio.den : 1;
}
int ffkmp_codecpar_from_context(AVCodecParameters *par, AVCodecContext *ctx) {
    return avcodec_parameters_from_context(par, ctx);
}
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}

/* ════════════ AVCodec / AVCodecContext ════════════ */

AVCodecContext* ffkmp_codecctx_alloc(const AVCodec *c) { return avcodec_alloc_context3(c); }
void  ffkmp_codecctx_free(AVCodecContext *c) { if (c) { AVCodecContext *q = c; avcodec_free_context(&q); } }
int   ffkmp_codecctx_open(AVCodecContext *c, const AVCodec *codec) { return avcodec_open2(c, codec, NULL); }
int   ffkmp_codecctx_from_par(AVCodecContext *c, AVCodecParameters *p) { return avcodec_parameters_to_context(c, p); }
void  ffkmp_codecctx_set_video(
    AVCodecContext *c, int width, int height, int pix_fmt,
    int fr_num, int fr_den, int tb_num, int tb_den, int64_t bit_rate, int gop_size
) {
    if (!c) return;
    c->width = width; c->height = height;
    c->pix_fmt = (enum AVPixelFormat)pix_fmt;
    c->framerate.num = fr_num; c->framerate.den = fr_den ? fr_den : 1;
    c->time_base.num = tb_num; c->time_base.den = tb_den ? tb_den : 1;
    c->bit_rate = bit_rate; c->gop_size = gop_size;
}
void  ffkmp_codecctx_set_audio(
    AVCodecContext *c, int sample_rate, int sample_fmt, int channels, int64_t bit_rate
) {
    if (!c) return;
    c->sample_rate = sample_rate;
    c->sample_fmt = (enum AVSampleFormat)sample_fmt;
    av_channel_layout_uninit(&c->ch_layout);
    av_channel_layout_default(&c->ch_layout, channels);
    c->time_base.num = 1; c->time_base.den = sample_rate ? sample_rate : 1;
    c->bit_rate = bit_rate;
}
/* First sample format the encoder supports, used to pick a sane default (e.g. aac → fltp).
   avcodec_get_supported_config landed in FFmpeg 7.1 (lavc 61.13); distro FFmpeg 6.x still
   exposes the deprecated AVCodec.sample_fmts array, so version-gate to support both. */
int   ffkmp_codec_first_sample_fmt(const AVCodec *codec) {
    if (!codec) return -1;
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 13, 100)
    const enum AVSampleFormat *fmts = NULL;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                                     (const void **)&fmts, NULL) < 0 || !fmts) return -1;
    return fmts[0] == AV_SAMPLE_FMT_NONE ? -1 : (int)fmts[0];
#else
    if (!codec->sample_fmts || codec->sample_fmts[0] == AV_SAMPLE_FMT_NONE) return -1;
    return (int)codec->sample_fmts[0];
#endif
}
/* Pixel-format twin of the sample-format query above, same 7.1 version gate. */
static const enum AVPixelFormat* ffkmp_codec_pix_fmts_(const AVCodec *codec) {
    if (!codec) return NULL;
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 13, 100)
    const enum AVPixelFormat *fmts = NULL;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_PIX_FORMAT, 0,
                                     (const void **)&fmts, NULL) < 0) return NULL;
    return fmts;
#else
    return codec->pix_fmts;
#endif
}
int ffkmp_codec_first_pix_fmt(const AVCodec *codec) {
    const enum AVPixelFormat *fmts = ffkmp_codec_pix_fmts_(codec);
    if (!fmts || fmts[0] == AV_PIX_FMT_NONE) return -1;
    return (int)fmts[0];
}
int ffkmp_codec_supports_pix_fmt(const AVCodec *codec, int fmt) {
    const enum AVPixelFormat *fmts = ffkmp_codec_pix_fmts_(codec);
    if (!fmts) return 0;
    for (int i = 0; fmts[i] != AV_PIX_FMT_NONE; i++) {
        if ((int)fmts[i] == fmt) return 1;
    }
    return 0;
}
int   ffkmp_codecctx_frame_size(AVCodecContext *c)  { return c ? c->frame_size : 0; }
int   ffkmp_codecctx_sample_rate(AVCodecContext *c) { return c ? c->sample_rate : 0; }
int   ffkmp_codecctx_sample_fmt(AVCodecContext *c)  { return c ? (int)c->sample_fmt : -1; }
int   ffkmp_codecctx_channels(AVCodecContext *c)    { return c ? c->ch_layout.nb_channels : 0; }
void  ffkmp_codecctx_time_base(AVCodecContext *c, int *n, int *d) {
    if (!c || !n || !d) return;
    *n = c->time_base.num; *d = c->time_base.den ? c->time_base.den : 1;
}
void  ffkmp_codecctx_set_global_header(AVCodecContext *c) {
    if (c) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
}
/* Codec-private + generic option setter ("preset"=veryfast, "crf"=23, "allow_sw"=1 …).
   SEARCH_CHILDREN reaches priv_data, so both generic and per-encoder options resolve. */
int   ffkmp_codecctx_set_opt(AVCodecContext *c, const char *key, const char *value) {
    if (!c || !key) return AVERROR(EINVAL);
    return av_opt_set(c, key, value, AV_OPT_SEARCH_CHILDREN);
}
/* FFmpeg ≥7 dropped the yuvj* formats: the mjpeg encoder takes plain yuv420p but refuses to
   open unless the context declares full (JPEG) color range. */
void  ffkmp_codecctx_set_full_range(AVCodecContext *c) {
    if (c) c->color_range = AVCOL_RANGE_JPEG;
}
const char* ffkmp_codec_name(const AVCodec *c) { return c ? c->name : NULL; }
const AVCodec* ffkmp_find_decoder_by_id(int id) { return avcodec_find_decoder((enum AVCodecID)id); }
/* The CODEC's canonical name ("av1", "opus", "mov_text"), independent of which decoder
   implementation this build happens to register for it; avcodec_find_decoder would answer
   "libdav1d"/"libopus" and would answer NOTHING at all for streams with no decoder compiled
   in (subtitles, attachments, data). Never returns NULL; unknown ids yield "none". */
const char* ffkmp_codec_id_name(int id) { return avcodec_get_name((enum AVCodecID)id); }
int ffkmp_codecctx_pix_fmt(AVCodecContext *c) { return c ? (int)c->pix_fmt : -1; }
int ffkmp_codecctx_width(AVCodecContext *c)   { return c ? c->width : 0; }
int ffkmp_codecctx_height(AVCodecContext *c)  { return c ? c->height : 0; }

/* ════════════ AVFormatContext (input + output) ════════════ */

int  ffkmp_fmt_open_input(AVFormatContext **out, const char *path) {
    AVFormatContext *c = NULL;
    int rc = avformat_open_input(&c, path, NULL, NULL);
    if (rc < 0) { *out = NULL; return rc; }
    *out = c; return 0;
}
void ffkmp_fmt_close_input(AVFormatContext **ctx) {
    if (ctx && *ctx) { AVFormatContext *p = *ctx; avformat_close_input(&p); *ctx = NULL; }
}
int  ffkmp_fmt_find_stream_info(AVFormatContext *c) { return avformat_find_stream_info(c, NULL); }
int  ffkmp_fmt_seek_micros(AVFormatContext *ctx, int stream_index, int64_t micros) {
    if (!ctx) return AVERROR(EINVAL);
    int64_t target = stream_index < 0 ? micros : av_rescale_q(micros, AV_TIME_BASE_Q, ctx->streams[stream_index]->time_base);
    return av_seek_frame(ctx, stream_index, target, AVSEEK_FLAG_BACKWARD);
}
int  ffkmp_fmt_read_frame(AVFormatContext *c, AVPacket *p) { return av_read_frame(c, p); }

int64_t       ffkmp_fmt_duration(AVFormatContext *c)   { return c ? c->duration : 0; }
/* Where the media's timeline BEGINS, in microseconds (AV_TIME_BASE units), i.e. the earliest
   start_time across streams. MPEG-TS commonly reports ~1.4s; mp4 usually 0. Every timestamp the
   demuxer hands out is absolute (includes this), while KiteCodec's public API, meaning seeks, trim
   bounds and extractFrame, is media-RELATIVE, so this is the offset between the two. Returns 0
   when the container doesn't declare one. */
int64_t       ffkmp_fmt_start_time(AVFormatContext *c) {
    if (!c || c->start_time == AV_NOPTS_VALUE || c->start_time <= 0) return 0;
    return c->start_time;
}
int64_t       ffkmp_fmt_bit_rate(AVFormatContext *c)   { return c ? c->bit_rate : 0; }
unsigned      ffkmp_fmt_nb_streams(AVFormatContext *c) { return c ? c->nb_streams : 0; }
AVStream*     ffkmp_fmt_stream(AVFormatContext *c, unsigned i) {
    return (c && i < c->nb_streams) ? c->streams[i] : NULL;
}
const char*   ffkmp_fmt_iformat_name(AVFormatContext *c) { return (c && c->iformat) ? c->iformat->name : NULL; }
AVDictionary* ffkmp_fmt_metadata(AVFormatContext *c)     { return c ? c->metadata : NULL; }

/* Output */
int  ffkmp_fmt_alloc_output(AVFormatContext **out, const char *path) {
    AVFormatContext *c = NULL;
    int rc = avformat_alloc_output_context2(&c, NULL, NULL, path);
    if (rc < 0 || !c) { *out = NULL; return rc < 0 ? rc : AVERROR_UNKNOWN; }
    *out = c; return 0;
}
/* Like ffkmp_fmt_alloc_output but with an explicit container short name ("mp4", "matroska");
   NULL/empty format falls back to extension inference. */
int  ffkmp_fmt_alloc_output2(AVFormatContext **out, const char *path, const char *format) {
    AVFormatContext *c = NULL;
    int rc = avformat_alloc_output_context2(&c, NULL, (format && format[0]) ? format : NULL, path);
    if (rc < 0 || !c) { *out = NULL; return rc < 0 ? rc : AVERROR_UNKNOWN; }
    *out = c; return 0;
}
/* Muxer private options (movflags, …): AV_OPT_SEARCH_CHILDREN reaches oformat priv_data. */
int  ffkmp_fmt_set_opt(AVFormatContext *c, const char *k, const char *v) {
    if (!c) return AVERROR(EINVAL);
    return av_opt_set(c, k, v, AV_OPT_SEARCH_CHILDREN);
}
void ffkmp_fmt_free_output(AVFormatContext **ctx) {
    if (ctx && *ctx) {
        if (!((*ctx)->oformat && ((*ctx)->oformat->flags & AVFMT_NOFILE)) && (*ctx)->pb) {
            avio_closep(&(*ctx)->pb);
        }
        avformat_free_context(*ctx);
        *ctx = NULL;
    }
}
AVStream* ffkmp_fmt_new_stream(AVFormatContext *ctx, const AVCodec *codec) {
    return ctx ? avformat_new_stream(ctx, codec) : NULL;
}
int ffkmp_fmt_io_open(AVFormatContext *ctx, const char *path) {
    if (!ctx) return AVERROR(EINVAL);
    if (ctx->oformat && (ctx->oformat->flags & AVFMT_NOFILE)) return 0;
    return avio_open(&ctx->pb, path, AVIO_FLAG_WRITE);
}
/* Timestamps handed to the muxer are rebased against a base SHARED by every stream of the sink
   (MediaSink.claimBaseMicros), which keeps the relative A/V offset intact but lets a stream that
   starts earlier than the claiming one go negative (AAC priming samples are the common case).
   Pin the policy instead of inheriting each muxer's default: MAKE_ZERO shifts the whole output
   up so nothing is negative, applying the SAME shift to every stream, so the offset survives. */
void ffkmp_fmt_avoid_negative_ts(AVFormatContext *ctx) {
    if (ctx) ctx->avoid_negative_ts = AVFMT_AVOID_NEG_TS_MAKE_ZERO;
}
int ffkmp_fmt_write_header(AVFormatContext *ctx)         { return ctx ? avformat_write_header(ctx, NULL) : AVERROR(EINVAL); }
int ffkmp_fmt_write_frame(AVFormatContext *ctx, AVPacket *p) { return av_interleaved_write_frame(ctx, p); }
int ffkmp_fmt_write_trailer(AVFormatContext *ctx)         { return ctx ? av_write_trailer(ctx) : AVERROR(EINVAL); }
int ffkmp_oformat_global_header(AVFormatContext *c) {
    return (c && c->oformat && (c->oformat->flags & AVFMT_GLOBALHEADER)) ? 1 : 0;
}
/* Container-level metadata (title, artist, …). Must run before avformat_write_header. */
int ffkmp_fmt_set_metadata(AVFormatContext *c, const char *key, const char *value) {
    if (!c || !key) return AVERROR(EINVAL);
    return av_dict_set(&c->metadata, key, value, 0);
}

/* ════════════ AVStream ════════════ */

int                  ffkmp_stream_index(AVStream *s)    { return s ? s->index : -1; }
AVCodecParameters*   ffkmp_stream_codecpar(AVStream *s) { return s ? s->codecpar : NULL; }
int64_t              ffkmp_stream_duration(AVStream *s) { return s ? s->duration : 0; }
/* Stream duration converted from the stream's own time-base into microseconds.
   Returns -1 when the container doesn't declare it (AV_NOPTS_VALUE / non-positive). */
int64_t              ffkmp_stream_duration_micros(AVStream *s) {
    if (!s || s->duration == AV_NOPTS_VALUE || s->duration <= 0) return -1;
    return av_rescale_q(s->duration, s->time_base, AV_TIME_BASE_Q);
}
int64_t              ffkmp_stream_start_time(AVStream *s){return s ? s->start_time : 0; }
int64_t              ffkmp_stream_nb_frames(AVStream *s){ return s ? s->nb_frames : 0; }
AVDictionary*        ffkmp_stream_metadata(AVStream *s) { return s ? s->metadata : NULL; }
void ffkmp_stream_time_base(AVStream *s, int *n, int *d) {
    if (!s || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    *n = s->time_base.num; *d = s->time_base.den ? s->time_base.den : 1;
}
void ffkmp_stream_avg_frame_rate(AVStream *s, int *n, int *d) {
    if (!s || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    *n = s->avg_frame_rate.num; *d = s->avg_frame_rate.den ? s->avg_frame_rate.den : 1;
}
void ffkmp_stream_set_time_base(AVStream *s, int n, int d) {
    if (s) { s->time_base.num = n; s->time_base.den = d ? d : 1; }
}

/* ════════════ Filter graphs (single-input video / audio) ════════════ */

/* Shared tail of graph construction: wire a configured src/sink pair around the parsed
   `description` chain, then config the whole graph. Frees the graph on any failure. */
static int ffkmp_graph_finish_(
    AVFilterGraph *graph, AVFilterContext *src_ctx, AVFilterContext *sink_ctx,
    const char *description
) {
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();
    if (!outputs || !inputs) {
        avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
        return AVERROR(ENOMEM);
    }
    outputs->name = av_strdup("in");  outputs->filter_ctx = src_ctx;  outputs->pad_idx = 0; outputs->next = NULL;
    inputs->name  = av_strdup("out"); inputs->filter_ctx  = sink_ctx; inputs->pad_idx  = 0; inputs->next  = NULL;

    int rc = avfilter_graph_parse_ptr(graph, description, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
    if (rc < 0) return rc;
    return avfilter_graph_config(graph, NULL);
}

int ffkmp_graph_build_video(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int width, int height, int pix_fmt,
    int tb_num, int tb_den, int fr_num, int fr_den, int sar_num, int sar_den
) {
    *out_graph = NULL; *out_src = NULL; *out_sink = NULL;
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("buffer");
    const AVFilter *sink = avfilter_get_by_name("buffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    /* FFmpeg 8: the `buffer` filter wants pix_fmt as a NAME, not an int. */
    const char *pix_fmt_name = av_get_pix_fmt_name((enum AVPixelFormat)pix_fmt);
    if (!pix_fmt_name) pix_fmt_name = "yuv420p";

    char args[512];
    snprintf(args, sizeof(args),
        "video_size=%dx%d:pix_fmt=%s:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
        width, height, pix_fmt_name, tb_num, tb_den ? tb_den : 1,
        sar_num ? sar_num : 1, sar_den ? sar_den : 1,
        fr_num ? fr_num : 30, fr_den ? fr_den : 1);

    AVFilterContext *src_ctx = NULL, *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&src_ctx, src, "in", args, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Don't constrain the buffersink output format, let the graph auto-negotiate. Converting
       whatever it emits into the encoder's pixel format is EncoderCore's job (see conversionFor
       in MediaSink.native.kt), which is what makes an unpinned graph safe here. Pinning is still
       cheaper: put `format=yuv420p` last in the description and the conversion becomes a no-op. */

    rc = ffkmp_graph_finish_(graph, src_ctx, sink_ctx, description);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    *out_graph = graph; *out_src = src_ctx; *out_sink = sink_ctx;
    return 0;
}

int ffkmp_graph_build_audio(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int sample_rate, int sample_fmt, int channels,
    int tb_num, int tb_den,
    /* Pin the graph's output so frames arrive encoder-ready. Pass -1/-1/0 to leave free.
       Implemented by appending an `aformat` filter rather than buffersink options, because the
       option names were renamed across FFmpeg 7→8, the filter-string syntax never changes. */
    int out_sample_fmt, int out_sample_rate, int out_channels
) {
    *out_graph = NULL; *out_src = NULL; *out_sink = NULL;
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("abuffer");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    const char *fmt_name = av_get_sample_fmt_name((enum AVSampleFormat)sample_fmt);
    if (!fmt_name) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }

    AVChannelLayout in_layout;
    av_channel_layout_default(&in_layout, channels > 0 ? channels : 2);
    char layout_str[128];
    int rc = av_channel_layout_describe(&in_layout, layout_str, sizeof(layout_str));
    av_channel_layout_uninit(&in_layout);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    char args[512];
    snprintf(args, sizeof(args),
        "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
        tb_num, tb_den ? tb_den : 1, sample_rate, fmt_name, layout_str);

    AVFilterContext *src_ctx = NULL, *sink_ctx = NULL;
    rc = avfilter_graph_create_filter(&src_ctx, src, "in", args, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Compose `<description>,aformat=…` with only the requested pins. `Nc` is the
       layout-from-channel-count syntax, which avoids names like "5.1(side)" whose parens
       would fight the filter-string parser.

       The description is public API input of any length, and snprintf returns the length it
       WOULD have written, not what it wrote. So the running total must be checked against the
       buffer after EVERY append and before the next one computes `full_desc + n`: past the end
       that pointer leaves the array and `sizeof(full_desc) - n` wraps to a huge size_t. A
       description that does not leave room for the pins is refused, never truncated. */
    char full_desc[2048];
    int n = snprintf(full_desc, sizeof(full_desc), "%s",
                     (description && description[0]) ? description : "anull");
    if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
    if (out_sample_fmt >= 0 || out_sample_rate > 0 || out_channels > 0) {
        n += snprintf(full_desc + n, sizeof(full_desc) - n, ",aformat=");
        if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        int first = 1;
        if (out_sample_fmt >= 0) {
            const char *ofmt = av_get_sample_fmt_name((enum AVSampleFormat)out_sample_fmt);
            if (!ofmt) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "sample_fmts=%s", ofmt);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            first = 0;
        }
        if (out_sample_rate > 0) {
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "%ssample_rates=%d", first ? "" : ":", out_sample_rate);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            first = 0;
        }
        if (out_channels > 0) {
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "%schannel_layouts=%dc", first ? "" : ":", out_channels);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        }
    }

    rc = ffkmp_graph_finish_(graph, src_ctx, sink_ctx, full_desc);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    *out_graph = graph; *out_src = src_ctx; *out_sink = sink_ctx;
    return 0;
}

/* ── Multi-input variants ──
   The description references inputs as [in0]…[inN-1] and the output as [out], e.g.
   "[in0][in1]overlay=10:10[out]" or "[in0][in1]amix=inputs=2[out]". Caller passes parallel
   arrays of per-input parameters and a caller-allocated out_srcs array of length n. */

static int ffkmp_graph_finish_multi_(
    AVFilterGraph *graph, AVFilterContext **src_ctxs, int n, AVFilterContext *sink_ctx,
    const char *description
) {
    AVFilterInOut *outputs = NULL, *outputs_tail = NULL;
    for (int i = 0; i < n; i++) {
        AVFilterInOut *io = avfilter_inout_alloc();
        char name[16];
        snprintf(name, sizeof(name), "in%d", i);
        if (!io) { avfilter_inout_free(&outputs); return AVERROR(ENOMEM); }
        io->name = av_strdup(name);
        io->filter_ctx = src_ctxs[i];
        io->pad_idx = 0; io->next = NULL;
        if (!outputs) outputs = io; else outputs_tail->next = io;
        outputs_tail = io;
    }
    AVFilterInOut *inputs = avfilter_inout_alloc();
    if (!inputs) { avfilter_inout_free(&outputs); return AVERROR(ENOMEM); }
    inputs->name = av_strdup("out");
    inputs->filter_ctx = sink_ctx;
    inputs->pad_idx = 0; inputs->next = NULL;

    int rc = avfilter_graph_parse_ptr(graph, description, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
    if (rc < 0) return rc;
    return avfilter_graph_config(graph, NULL);
}

int ffkmp_graph_build_video_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *widths, const int *heights, const int *pix_fmts,
    const int *tb_nums, const int *tb_dens,
    const int *fr_nums, const int *fr_dens,
    const int *sar_nums, const int *sar_dens
) {
    *out_graph = NULL; *out_sink = NULL;
    if (n <= 0) return AVERROR(EINVAL);
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("buffer");
    const AVFilter *sink = avfilter_get_by_name("buffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    for (int i = 0; i < n; i++) {
        const char *pf = av_get_pix_fmt_name((enum AVPixelFormat)pix_fmts[i]);
        if (!pf) pf = "yuv420p";
        char args[512], name[16];
        snprintf(args, sizeof(args),
            "video_size=%dx%d:pix_fmt=%s:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
            widths[i], heights[i], pf, tb_nums[i], tb_dens[i] ? tb_dens[i] : 1,
            sar_nums[i] ? sar_nums[i] : 1, sar_dens[i] ? sar_dens[i] : 1,
            fr_nums[i] ? fr_nums[i] : 30, fr_dens[i] ? fr_dens[i] : 1);
        snprintf(name, sizeof(name), "in%d", i);
        out_srcs[i] = NULL;
        int rc = avfilter_graph_create_filter(&out_srcs[i], src, name, args, NULL, graph);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    }
    AVFilterContext *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    rc = ffkmp_graph_finish_multi_(graph, out_srcs, n, sink_ctx, description);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    *out_graph = graph; *out_sink = sink_ctx;
    return 0;
}

int ffkmp_graph_build_audio_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *sample_rates, const int *sample_fmts, const int *channels,
    const int *tb_nums, const int *tb_dens,
    int out_sample_fmt, int out_sample_rate, int out_channels
) {
    *out_graph = NULL; *out_sink = NULL;
    if (n <= 0) return AVERROR(EINVAL);
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("abuffer");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    for (int i = 0; i < n; i++) {
        const char *fmt = av_get_sample_fmt_name((enum AVSampleFormat)sample_fmts[i]);
        if (!fmt) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        AVChannelLayout lay;
        av_channel_layout_default(&lay, channels[i] > 0 ? channels[i] : 2);
        char lay_str[128];
        int rc = av_channel_layout_describe(&lay, lay_str, sizeof(lay_str));
        av_channel_layout_uninit(&lay);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
        char args[512], name[16];
        snprintf(args, sizeof(args),
            "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
            tb_nums[i], tb_dens[i] ? tb_dens[i] : 1, sample_rates[i], fmt, lay_str);
        snprintf(name, sizeof(name), "in%d", i);
        out_srcs[i] = NULL;
        rc = avfilter_graph_create_filter(&out_srcs[i], src, name, args, NULL, graph);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    }
    AVFilterContext *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Same aformat pinning trick as the single-input audio builder, and the same rule about
       checking the running length after every append. See that builder for why. */
    char full_desc[2048];
    int len = snprintf(full_desc, sizeof(full_desc), "%s",
                       (description && description[0]) ? description : "anull");
    if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
    if (out_sample_fmt >= 0 || out_sample_rate > 0 || out_channels > 0) {
        /* The pinned chain must hang off [out]'s producer; description already routes to [out],
           so splice aformat between. Renaming the caller's terminal label would be intrusive,
           so instead we rely on the sink accepting anything and pin via aformat appended INSIDE
           the chain only when the description has no explicit [out] label. With an explicit
           [out] the caller controls formats; Transcoder always appends its own aformat before
           [out]. */
        if (!strstr(full_desc, "[out]")) {
            int first = 1;
            len += snprintf(full_desc + len, sizeof(full_desc) - len, ",aformat=");
            if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            if (out_sample_fmt >= 0) {
                const char *of = av_get_sample_fmt_name((enum AVSampleFormat)out_sample_fmt);
                if (!of) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "sample_fmts=%s", of);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                first = 0;
            }
            if (out_sample_rate > 0) {
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "%ssample_rates=%d", first ? "" : ":", out_sample_rate);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                first = 0;
            }
            if (out_channels > 0) {
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "%schannel_layouts=%dc", first ? "" : ":", out_channels);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            }
        }
    }

    rc = ffkmp_graph_finish_multi_(graph, out_srcs, n, sink_ctx, full_desc);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    *out_graph = graph; *out_sink = sink_ctx;
    return 0;
}

void ffkmp_graph_free(AVFilterGraph **g) { if (g && *g) avfilter_graph_free(g); }
int  ffkmp_graph_send(AVFilterContext *src, AVFrame *frame) {
    return av_buffersrc_add_frame_flags(src, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
}
int  ffkmp_graph_receive(AVFilterContext *sink, AVFrame *frame) {
    return av_buffersink_get_frame(sink, frame);
}
/* Fixed-frame-size pull: AAC & friends require exactly frame_size samples per encode call.
   Setting this makes the buffersink chunk its output accordingly (last frame may be short). */
void ffkmp_buffersink_set_frame_size(AVFilterContext *sink, unsigned n) {
    if (sink && n > 0) av_buffersink_set_frame_size(sink, n);
}
/* The time-base frames carry when they leave the graph. Filters like fps/atempo change it,
   so the consumer must read it from the sink rather than assume the input time-base. */
void ffkmp_buffersink_time_base(AVFilterContext *sink, int *n, int *d) {
    if (!sink || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    AVRational tb = av_buffersink_get_time_base(sink);
    *n = tb.num; *d = tb.den ? tb.den : 1;
}

/* ════════════ Playback additions ════════════
   Everything below exists for KitePlayer. A batch transcoder reads a file once, front to back, and
   needs none of it. A player needs to drive demuxing and decoding as separate stages, to flush a
   decoder after a seek, to know a frame's colour metadata before it can draw it correctly, and to
   reach a frame's pixels without copying them. */

/* --- Decoder control --- */

/* Discards a decoder's internal state. Required after every seek: without it the decoder emits
   frames reconstructed from packets belonging to the position the viewer just left. */
void ffkmp_codecctx_flush(AVCodecContext *c) { if (c) avcodec_flush_buffers(c); }

/* Frame-level threading for video, and low delay for audio. A player wants the first and not the
   second; a transcoder does not care. 0 lets libavcodec choose. */
void ffkmp_codecctx_set_threads(AVCodecContext *c, int count, int frame_level) {
    if (!c) return;
    c->thread_count = count;
    c->thread_type = frame_level ? FF_THREAD_FRAME : FF_THREAD_SLICE;
}
void ffkmp_codecctx_set_low_delay(AVCodecContext *c, int on) {
    if (c) { if (on) c->flags |= AV_CODEC_FLAG_LOW_DELAY; else c->flags &= ~AV_CODEC_FLAG_LOW_DELAY; }
}

/* --- Seeking with the full flag set --- */

int ffkmp_avseek_flag_backward(void) { return AVSEEK_FLAG_BACKWARD; }
int ffkmp_avseek_flag_any(void)      { return AVSEEK_FLAG_ANY; }
int ffkmp_avseek_flag_byte(void)     { return AVSEEK_FLAG_BYTE; }
int ffkmp_avseek_flag_frame(void)    { return AVSEEK_FLAG_FRAME; }

/* avformat_seek_file, which av_seek_frame cannot express: a bounded window rather than a single
   target. A player uses it to say "land at or before here, but no earlier than there", which is
   what makes a retry ladder cheap instead of a fixed pessimistic backoff. */
int ffkmp_fmt_seek_file(AVFormatContext *ctx, int stream_index,
                                     int64_t min_ts, int64_t ts, int64_t max_ts, int flags) {
    if (!ctx) return AVERROR(EINVAL);
    return avformat_seek_file(ctx, stream_index, min_ts, ts, max_ts, flags);
}

/* Whether this input can seek at all, read from the input itself instead of assumed. A player
   that assumes yes offers a seek bar that does nothing on a pipe, a capture device or a live
   stream, and hands libavformat a request it answers with an error on every drag.
   Two things must hold. The demuxer must not have declared the input unseekable, which is what
   AVFMTCTX_UNSEEKABLE means, and the byte stream must report AVIO_SEEKABLE_NORMAL. A
   protocol-less input (AVFMT_NOFILE, so a device or a capture) has no byte stream at all and
   answers 0. That last case is conservative: a demuxer may implement its own seek without a
   seekable byte stream, but since FFmpeg 7 the function pointer that would prove it lives in a
   private struct, so it cannot be read from the public headers. Under-promising is the safe
   direction for a capability. */
int ffkmp_fmt_is_seekable(AVFormatContext *c) {
    if (!c) return 0;
    if (c->ctx_flags & AVFMTCTX_UNSEEKABLE) return 0;
    if (!c->pb) return 0;
    return (c->pb->seekable & AVIO_SEEKABLE_NORMAL) ? 1 : 0;
}

/* --- Stream selection at the demuxer --- */

/* AVDISCARD_ALL on an unselected stream makes libavformat skip its packets instead of the caller
   reading and throwing them away. On a file with ten audio tracks that is most of the read work. */
void ffkmp_stream_discard_all(AVStream *s)  { if (s) s->discard = AVDISCARD_ALL; }
void ffkmp_stream_discard_none(AVStream *s) { if (s) s->discard = AVDISCARD_DEFAULT; }

/* --- Stream metadata a track menu and a renderer need --- */

int ffkmp_stream_disposition(AVStream *s) { return s ? s->disposition : 0; }
int ffkmp_disposition_default(void)           { return AV_DISPOSITION_DEFAULT; }
int ffkmp_disposition_forced(void)            { return AV_DISPOSITION_FORCED; }
int ffkmp_disposition_hearing_impaired(void)  { return AV_DISPOSITION_HEARING_IMPAIRED; }
int ffkmp_disposition_visual_impaired(void)   { return AV_DISPOSITION_VISUAL_IMPAIRED; }
int ffkmp_disposition_attached_pic(void)      { return AV_DISPOSITION_ATTACHED_PIC; }

/* Rotation, in degrees, from the display matrix a phone writes into its recordings. Without this
   every video shot in portrait plays on its side. av_display_rotation_get returns the angle the
   image must be rotated by counter-clockwise, as a double; the sign is flipped here so the result
   is the clockwise rotation a renderer should apply. */
int ffkmp_stream_rotation_degrees(AVStream *s) {
    if (!s) return 0;
    const AVPacketSideData *sd = av_packet_side_data_get(s->codecpar->coded_side_data,
                                                         s->codecpar->nb_coded_side_data,
                                                         AV_PKT_DATA_DISPLAYMATRIX);
    if (!sd || sd->size < 9 * 4) return 0;
    double theta = -av_display_rotation_get((const int32_t *)sd->data);
    int deg = (int)(theta < 0 ? theta - 0.5 : theta + 0.5);
    deg %= 360;
    if (deg < 0) deg += 360;
    return deg;
}

/* --- Packet ownership --- */

/* Moves the reference rather than copying the payload, so a player can queue a packet without a
   memcpy. The source is left blank and reusable. */
void ffkmp_packet_move_ref(AVPacket *dst, AVPacket *src) {
    if (dst && src) av_packet_move_ref(dst, src);
}
int64_t ffkmp_packet_pos(AVPacket *p) { return p ? p->pos : -1; }

/* --- Frame metadata a renderer cannot be correct without --- */

/* Getting any of these wrong is visible. The matrix decides hue, the range decides whether black
   is black, and the chroma location decides whether colour bleeds at a sharp edge. All four are
   already on the frame; only the accessor was missing. */
int ffkmp_frame_color_range(AVFrame *f)     { return f ? (int)f->color_range : 0; }
int ffkmp_frame_colorspace(AVFrame *f)      { return f ? (int)f->colorspace : 2; }
int ffkmp_frame_color_primaries(AVFrame *f) { return f ? (int)f->color_primaries : 2; }
int ffkmp_frame_color_trc(AVFrame *f)       { return f ? (int)f->color_trc : 2; }
int ffkmp_frame_chroma_location(AVFrame *f) { return f ? (int)f->chroma_location : 0; }
int ffkmp_frame_is_keyframe(AVFrame *f) {
    return (f && (f->flags & AV_FRAME_FLAG_KEY)) ? 1 : 0;
}
void ffkmp_frame_sample_aspect_ratio(AVFrame *f, int *n, int *d) {
    if (!f || !n || !d) { if (n) *n = 1; if (d) *d = 1; return; }
    *n = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.num : 1;
    *d = f->sample_aspect_ratio.den ? f->sample_aspect_ratio.den : 1;
}

/* --- Which speaker each channel belongs to --- */

/* A channel count is not a layout. Six channels are 5.1 with side surrounds or 5.1 with back
   surrounds, and a downmix that guesses wrong sends the surround content to the wrong speakers.
   The mask carries one bit per speaker, so it answers the question a count cannot.
   0 means there is no mask to report: the layout is unspecified (the container never said), a
   custom order (per-channel positions, which only the extended layout describes) or ambisonic.
   A caller that gets 0 must fall back to the count and say that it did. */
static int64_t ffkmp_ch_layout_mask_(const AVChannelLayout *l) {
    if (!l || l->order != AV_CHANNEL_ORDER_NATIVE) return 0;
    return (int64_t)l->u.mask;
}
int64_t ffkmp_frame_ch_layout_mask(AVFrame *f) {
    return f ? ffkmp_ch_layout_mask_(&f->ch_layout) : 0;
}
int64_t ffkmp_codecpar_ch_layout_mask(AVCodecParameters *p) {
    return p ? ffkmp_ch_layout_mask_(&p->ch_layout) : 0;
}

/* --- Reaching a frame's pixels without copying them --- */

/* A plane pointer and its row pitch. This is what a renderer uploads from. The alternative, the
   copy that av_image_copy_to_buffer performs, is 3.11 MB for one 1080p frame and 24.9 MB for one
   4K 10-bit frame, which at 60 fps is between 187 MB/s and 1.5 GB/s of pointless work. */
uint8_t* ffkmp_frame_plane(AVFrame *f, int p) {
    return (f && p >= 0 && p < AV_NUM_DATA_POINTERS) ? f->data[p] : NULL;
}

/* How many planes this frame's format actually has. */
int ffkmp_frame_plane_count(AVFrame *f) {
    if (!f) return 0;
    if (f->nb_samples > 0) {
        const AVChannelLayout *cl = &f->ch_layout;
        return av_sample_fmt_is_planar((enum AVSampleFormat)f->format) ? cl->nb_channels : 1;
    }
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    return d ? av_pix_fmt_count_planes((enum AVPixelFormat)f->format) : 0;
}

/* The height of plane p in rows, which for a chroma plane of a subsampled format is not the
   frame height. Uploading with the wrong value is the other half of the stride mistake.
   A frame with no width is not a picture: on an audio frame `format` holds a sample format, so
   reading it as a pixel format would answer with the subsampling of whatever pixel format shares
   that ordinal. 0 rows is the only honest answer. */
int ffkmp_frame_plane_height(AVFrame *f, int p) {
    if (!f || p < 0 || f->width == 0) return 0;
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    if (!d) return 0;
    if (p == 1 || p == 2) return AV_CEIL_RSHIFT(f->height, d->log2_chroma_h);
    return f->height;
}

/* Non-NULL when the frame lives in GPU or hardware memory. For VideoToolbox this is the
   CVPixelBuffer, for MediaCodec the output buffer, for VA-API the surface id. The renderer that
   matches the decoder knows what to do with it; nobody else may touch it. */
void* ffkmp_frame_hw_surface(AVFrame *f) {
    if (!f || !f->hw_frames_ctx) return NULL;
    return (void *)f->data[3];
}
int ffkmp_frame_is_hardware(AVFrame *f) {
    return (f && f->hw_frames_ctx) ? 1 : 0;
}
