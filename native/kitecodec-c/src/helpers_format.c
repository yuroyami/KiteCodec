/* Ordinary maintained source since the interlude (I-12). Lifted at B1.3 from the def body of
 * kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The format part of the FFmpeg helper layer: the def's 'AVFormatContext (input + output)' section(s). */

#include "kitecodec_helpers.h"
/* ════════════ AVFormatContext (input + output) ════════════ */

KC_API int  ffkmp_fmt_open_input(AVFormatContext **out, const char *path) {
    AVFormatContext *c = NULL;
    int rc = avformat_open_input(&c, path, NULL, NULL);
    if (rc < 0) { *out = NULL; return rc; }
    *out = c; return 0;
}
KC_API void ffkmp_fmt_close_input(AVFormatContext **ctx) {
    if (ctx && *ctx) { AVFormatContext *p = *ctx; avformat_close_input(&p); *ctx = NULL; }
}
KC_API int  ffkmp_fmt_find_stream_info(AVFormatContext *c) { return avformat_find_stream_info(c, NULL); }
KC_API int  ffkmp_fmt_seek_micros(AVFormatContext *ctx, int stream_index, int64_t micros) {
    if (!ctx) return AVERROR(EINVAL);
    /* Interlude guard (I-12): an index at or past nb_streams used to index ctx->streams[]
     * unchecked, reproduced as signal 11 through this exported entry point. -1 keeps its
     * documented meaning, any stream; every other out of range index is refused. */
    if (stream_index < -1 || (stream_index >= 0 && (unsigned)stream_index >= ctx->nb_streams)) return AVERROR(EINVAL);
    int64_t target = stream_index < 0 ? micros : av_rescale_q(micros, AV_TIME_BASE_Q, ctx->streams[stream_index]->time_base);
    return av_seek_frame(ctx, stream_index, target, AVSEEK_FLAG_BACKWARD);
}
KC_API int  ffkmp_fmt_read_frame(AVFormatContext *c, AVPacket *p) { return av_read_frame(c, p); }

KC_API int64_t       ffkmp_fmt_duration(AVFormatContext *c)   { return c ? c->duration : 0; }
/* Where the media's timeline BEGINS, in microseconds (AV_TIME_BASE units), i.e. the earliest
   start_time across streams. MPEG-TS commonly reports ~1.4s; mp4 usually 0. Every timestamp the
   demuxer hands out is absolute (includes this), while KiteCodec's public API, meaning seeks, trim
   bounds and extractFrame, is media-RELATIVE, so this is the offset between the two. Returns 0
   when the container doesn't declare one. */
KC_API int64_t       ffkmp_fmt_start_time(AVFormatContext *c) {
    if (!c || c->start_time == AV_NOPTS_VALUE || c->start_time <= 0) return 0;
    return c->start_time;
}
KC_API unsigned      ffkmp_fmt_nb_streams(AVFormatContext *c) { return c ? c->nb_streams : 0; }
KC_API AVStream*     ffkmp_fmt_stream(AVFormatContext *c, unsigned i) {
    return (c && i < c->nb_streams) ? c->streams[i] : NULL;
}
KC_API const char*   ffkmp_fmt_iformat_name(AVFormatContext *c) { return (c && c->iformat) ? c->iformat->name : NULL; }
KC_API AVDictionary* ffkmp_fmt_metadata(AVFormatContext *c)     { return c ? c->metadata : NULL; }

/* Output */
/* Allocates an output context with an explicit container short name ("mp4", "matroska");
   NULL/empty format falls back to extension inference from the path. */
KC_API int  ffkmp_fmt_alloc_output2(AVFormatContext **out, const char *path, const char *format) {
    AVFormatContext *c = NULL;
    int rc = avformat_alloc_output_context2(&c, NULL, (format && format[0]) ? format : NULL, path);
    if (rc < 0 || !c) { *out = NULL; return rc < 0 ? rc : AVERROR_UNKNOWN; }
    *out = c; return 0;
}
/* Muxer private options (movflags, …): AV_OPT_SEARCH_CHILDREN reaches oformat priv_data. */
KC_API int  ffkmp_fmt_set_opt(AVFormatContext *c, const char *k, const char *v) {
    /* Interlude guard (I-12): a NULL key used to reach av_opt_set's name comparison and crash,
     * reproduced as signal 11 through this exported entry point. Refused like a NULL context. */
    if (!c || !k) return AVERROR(EINVAL);
    return av_opt_set(c, k, v, AV_OPT_SEARCH_CHILDREN);
}
KC_API void ffkmp_fmt_free_output(AVFormatContext **ctx) {
    if (ctx && *ctx) {
        if (!((*ctx)->oformat && ((*ctx)->oformat->flags & AVFMT_NOFILE)) && (*ctx)->pb) {
            avio_closep(&(*ctx)->pb);
        }
        avformat_free_context(*ctx);
        *ctx = NULL;
    }
}
KC_API AVStream* ffkmp_fmt_new_stream(AVFormatContext *ctx, const AVCodec *codec) {
    return ctx ? avformat_new_stream(ctx, codec) : NULL;
}
KC_API int ffkmp_fmt_io_open(AVFormatContext *ctx, const char *path) {
    if (!ctx) return AVERROR(EINVAL);
    if (ctx->oformat && (ctx->oformat->flags & AVFMT_NOFILE)) return 0;
    return avio_open(&ctx->pb, path, AVIO_FLAG_WRITE);
}
/* Timestamps handed to the muxer are rebased against a base SHARED by every stream of the sink
   (MediaSink.claimBaseMicros), which keeps the relative A/V offset intact but lets a stream that
   starts earlier than the claiming one go negative (AAC priming samples are the common case).
   Pin the policy instead of inheriting each muxer's default: MAKE_ZERO shifts the whole output
   up so nothing is negative, applying the SAME shift to every stream, so the offset survives. */
KC_API void ffkmp_fmt_avoid_negative_ts(AVFormatContext *ctx) {
    if (ctx) ctx->avoid_negative_ts = AVFMT_AVOID_NEG_TS_MAKE_ZERO;
}
KC_API int ffkmp_fmt_write_header(AVFormatContext *ctx)         { return ctx ? avformat_write_header(ctx, NULL) : AVERROR(EINVAL); }
KC_API int ffkmp_fmt_write_frame(AVFormatContext *ctx, AVPacket *p) { return av_interleaved_write_frame(ctx, p); }
KC_API int ffkmp_fmt_write_trailer(AVFormatContext *ctx)         { return ctx ? av_write_trailer(ctx) : AVERROR(EINVAL); }
KC_API int ffkmp_oformat_global_header(AVFormatContext *c) {
    return (c && c->oformat && (c->oformat->flags & AVFMT_GLOBALHEADER)) ? 1 : 0;
}
/* Container-level metadata (title, artist, …). Must run before avformat_write_header. */
KC_API int ffkmp_fmt_set_metadata(AVFormatContext *c, const char *key, const char *value) {
    if (!c || !key) return AVERROR(EINVAL);
    return av_dict_set(&c->metadata, key, value, 0);
}

