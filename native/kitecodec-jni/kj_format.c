/* Category unit: format contexts and their borrowed views (methods.def section "format").
 * Streams are parented to their format token; codec parameters and dictionaries are parented to
 * the view that owns them, and dictionary entries to their dictionary. Closing any parent
 * invalidates its complete descendant tree in the handle table before FFmpeg releases memory. */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_fmt_open_input(JNIEnv *env, jclass cls, jstring path)
{
    char *c = kj_string_dup(env, path);
    kc_fmt_ctx *ctx = NULL;
    jlong token;
    int rc;
    (void)cls;
    if (c == NULL) { kj_throw_handle(env, "open refused: NULL path"); return 0; }
    rc = ffkmp_fmt_open_input(&ctx, c);
    free(c);
    if (rc < 0 || ctx == NULL) { kj_throw_ffmpeg(env, rc, "fmt_open_input"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) ffkmp_fmt_close_input(&ctx);
    return token;
}

JNIEXPORT void JNICALL kj_fmt_close_input(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    (void)env; (void)cls;
    if (ctx != NULL) ffkmp_fmt_close_input(&ctx);
}

JNIEXPORT void JNICALL kj_fmt_free_output(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    (void)env; (void)cls; if (ctx != NULL) ffkmp_fmt_free_output(&ctx);
}

JNIEXPORT jint JNICALL kj_fmt_find_stream_info(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_find_stream_info(ctx) : -1;
}

JNIEXPORT jint JNICALL kj_fmt_nb_streams(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_nb_streams(ctx) : 0;
}

JNIEXPORT jlong JNICALL kj_fmt_stream(JNIEnv *env, jclass cls, jlong token, jint index)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_stream *s;
    (void)cls;
    if (ctx == NULL) return 0;
    if (index < 0 || (unsigned)index >= ffkmp_fmt_nb_streams(ctx)) {
        kj_throw_handle(env, "stream index out of range");
        return 0;
    }
    s = ffkmp_fmt_stream(ctx, (unsigned)index);
    if (s == NULL) { kj_throw_handle(env, "stream lookup failed"); return 0; }
    return kj_handle_put_borrowed(env, KJ_KIND_STREAM, s, token);
}

JNIEXPORT jlong JNICALL kj_fmt_duration_micros(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jlong)ffkmp_fmt_duration(ctx) : 0;
}

JNIEXPORT jint JNICALL kj_fmt_read_frame(JNIEnv *env, jclass cls, jlong token, jlong packet_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_packet *p;
    (void)cls;
    if (ctx == NULL) return -1;
    p = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET);
    if (p == NULL) return -1;
    return (jint)ffkmp_fmt_read_frame(ctx, p);
}

JNIEXPORT jint JNICALL kj_fmt_seek_micros(JNIEnv *env, jclass cls, jlong token, jint stream_index, jlong micros)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_seek_micros(ctx, (int)stream_index, (int64_t)micros) : -1;
}

JNIEXPORT jint JNICALL kj_fmt_set_opt(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    char *k, *v;
    int rc;
    (void)cls;
    if (ctx == NULL) return -1;
    k = kj_string_dup(env, key);
    if (k == NULL) { kj_throw_handle(env, "set_opt refused: NULL key"); return -1; }
    v = kj_string_dup(env, value);
    if (value != NULL && v == NULL) {
        free(k);
        return -1; /* preserve the conversion/OOM exception already pending */
    }
    rc = ffkmp_fmt_set_opt(ctx, k, v);
    free(k);
    free(v);
    return (jint)rc;
}

JNIEXPORT jlong JNICALL kj_fmt_start_time(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? (jlong)ffkmp_fmt_start_time(ctx) : 0;
}

JNIEXPORT jstring JNICALL kj_fmt_input_name(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? kj_string_new(env, ffkmp_fmt_iformat_name(ctx)) : NULL;
}

JNIEXPORT jboolean JNICALL kj_fmt_is_seekable(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return (ctx && ffkmp_fmt_is_seekable(ctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL kj_fmt_seek_file(JNIEnv *env, jclass cls, jlong token, jint index,
                                        jlong min, jlong target, jlong max, jint flags)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? (jint)ffkmp_fmt_seek_file(ctx, index, min, target, max, flags) : -1;
}

JNIEXPORT jlong JNICALL kj_fmt_metadata(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_dict *dict; (void)cls; if (ctx == NULL) return 0;
    dict = ffkmp_fmt_metadata(ctx); return dict ? kj_handle_put_borrowed(env, KJ_KIND_DICT, dict, token) : 0;
}

JNIEXPORT jlong JNICALL kj_fmt_alloc_output(JNIEnv *env, jclass cls, jstring path, jstring format)
{
    char *p = NULL; char *f = NULL;
    kc_fmt_ctx *ctx = NULL; jlong token; int rc; (void)cls;
    p = kj_string_dup(env, path);
    if (path != NULL && p == NULL) return 0;
    f = kj_string_dup(env, format);
    if (format != NULL && f == NULL) { free(p); return 0; }
    rc = ffkmp_fmt_alloc_output2(&ctx, p, f); free(p); free(f);
    if (rc < 0 || ctx == NULL) { kj_throw_ffmpeg(env, rc, "fmt_alloc_output"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) ffkmp_fmt_free_output(&ctx);
    return token;
}

JNIEXPORT jlong JNICALL kj_fmt_new_stream(JNIEnv *env, jclass cls, jlong fmt_token, jlong codec_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, fmt_token, KJ_KIND_FMT_CTX);
    const kc_codec *codec = NULL; kc_stream *stream; (void)cls;
    if (ctx == NULL) return 0;
    if (codec_token != 0) {
        codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
        if (codec == NULL) return 0;
    }
    stream = ffkmp_fmt_new_stream(ctx, codec);
    if (stream == NULL) { kj_throw_handle(env, "new output stream failed"); return 0; }
    return kj_handle_put_borrowed(env, KJ_KIND_STREAM, stream, fmt_token);
}

JNIEXPORT jint JNICALL kj_fmt_io_open(JNIEnv *env, jclass cls, jlong token, jstring path)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    char *p; int rc; (void)cls; if (ctx == NULL) return -1;
    p = kj_string_dup(env, path); if (p == NULL) return -1;
    rc = ffkmp_fmt_io_open(ctx, p); free(p); return (jint)rc;
}

JNIEXPORT void JNICALL kj_fmt_avoid_negative_ts(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; if (ctx) ffkmp_fmt_avoid_negative_ts(ctx); }
JNIEXPORT jint JNICALL kj_fmt_write_header(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return ctx ? ffkmp_fmt_write_header(ctx) : -1; }
JNIEXPORT jint JNICALL kj_fmt_write_frame(JNIEnv *env, jclass cls, jlong token, jlong packet_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); kc_packet *packet = NULL; (void)cls;
    if (!ctx) return -1; if (packet_token != 0) { packet = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET); if (!packet) return -1; }
    return ffkmp_fmt_write_frame(ctx, packet);
}
JNIEXPORT jint JNICALL kj_fmt_write_trailer(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return ctx ? ffkmp_fmt_write_trailer(ctx) : -1; }
JNIEXPORT jboolean JNICALL kj_fmt_global_header(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return (ctx && ffkmp_oformat_global_header(ctx)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL kj_fmt_set_metadata(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); char *k, *v; int rc; (void)cls;
    if (!ctx) return -1;
    k = kj_string_dup(env, key);
    if (k == NULL) return -1;
    v = kj_string_dup(env, value);
    if (value != NULL && v == NULL) { free(k); return -1; }
    rc = ffkmp_fmt_set_metadata(ctx, k, v); free(k); free(v); return rc;
}

JNIEXPORT void JNICALL kj_borrowed_release(JNIEnv *env, jclass cls, jlong token, jint kind)
{ (void)env; (void)cls; kj_handle_release(token, (int)kind); }

JNIEXPORT jint JNICALL kj_stream_index(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s = (kc_stream *)kj_handle_get(env, token, KJ_KIND_STREAM); (void)cls; return s ? ffkmp_stream_index(s) : -1; }
JNIEXPORT jlong JNICALL kj_stream_codecpar(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s = (kc_stream *)kj_handle_get(env, token, KJ_KIND_STREAM); kc_codec_par *p; (void)cls; if (!s) return 0; p=ffkmp_stream_codecpar(s); return p ? kj_handle_put_borrowed(env,KJ_KIND_CODEC_PAR,p,token):0; }
JNIEXPORT jlong JNICALL kj_stream_duration(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_duration_micros(s):0; }
JNIEXPORT jlong JNICALL kj_stream_start_time(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_start_time(s):0; }
JNIEXPORT jlong JNICALL kj_stream_metadata(JNIEnv *env,jclass cls,jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);kc_dict*d;(void)cls;if(!s)return 0;d=ffkmp_stream_metadata(s);return d?kj_handle_put_borrowed(env,KJ_KIND_DICT,d,token):0; }
JNIEXPORT jlong JNICALL kj_stream_time_base(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);int n=0,d=1;(void)cls;if(s)ffkmp_stream_time_base(s,&n,&d);return ((jlong)(uint32_t)n<<32)|(uint32_t)d; }
JNIEXPORT jlong JNICALL kj_stream_frame_rate(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);int n=0,d=1;(void)cls;if(s)ffkmp_stream_avg_frame_rate(s,&n,&d);return ((jlong)(uint32_t)n<<32)|(uint32_t)d; }
JNIEXPORT void JNICALL kj_stream_set_time_base(JNIEnv *env,jclass cls,jlong token,jint n,jint d)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;if(s)ffkmp_stream_set_time_base(s,n,d); }
JNIEXPORT void JNICALL kj_stream_discard(JNIEnv *env,jclass cls,jlong token,jboolean discard)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;if(s){if(discard)ffkmp_stream_discard_all(s);else ffkmp_stream_discard_none(s);} }
JNIEXPORT jint JNICALL kj_stream_disposition(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_disposition(s):0; }
JNIEXPORT jint JNICALL kj_stream_rotation(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_rotation_degrees(s):0; }

JNIEXPORT jlong JNICALL kj_dict_next(JNIEnv *env,jclass cls,jlong dict_token,jlong previous_token)
{
    kc_dict*d=(kc_dict*)kj_handle_get(env,dict_token,KJ_KIND_DICT);kc_dict_entry*prev=NULL,*next;(void)cls;
    if(!d)return 0;if(previous_token){prev=(kc_dict_entry*)kj_handle_get(env,previous_token,KJ_KIND_DICT_ENTRY);if(!prev)return 0;}
    next=ffkmp_dict_get(d,prev);
    if (previous_token) kj_handle_release(previous_token, KJ_KIND_DICT_ENTRY);
    return next?kj_handle_put_borrowed(env,KJ_KIND_DICT_ENTRY,next,dict_token):0;
}
JNIEXPORT jstring JNICALL kj_dict_key(JNIEnv *env,jclass cls,jlong token)
{kc_dict_entry*e=(kc_dict_entry*)kj_handle_get(env,token,KJ_KIND_DICT_ENTRY);(void)cls;return e?kj_string_new(env,ffkmp_dict_entry_key(e)):NULL;}
JNIEXPORT jstring JNICALL kj_dict_value(JNIEnv *env,jclass cls,jlong token)
{kc_dict_entry*e=(kc_dict_entry*)kj_handle_get(env,token,KJ_KIND_DICT_ENTRY);(void)cls;return e?kj_string_new(env,ffkmp_dict_entry_value(e)):NULL;}

JNIEXPORT jint JNICALL kj_codecpar_type(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_codec_type(p):-1;}
JNIEXPORT jint JNICALL kj_codecpar_id(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_codec_id(p):-1;}
JNIEXPORT jlong JNICALL kj_codecpar_bitrate(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_bit_rate(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_width(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_width(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_height(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_height(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_format(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_format(p):-1;}
JNIEXPORT jint JNICALL kj_codecpar_sample_rate(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_sample_rate(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_channels(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_channels(p):0;}
JNIEXPORT jlong JNICALL kj_codecpar_sar(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);int n=0,d=1;(void)cls;if(p)ffkmp_codecpar_sample_aspect_ratio(p,&n,&d);return((jlong)(uint32_t)n<<32)|(uint32_t)d;}
JNIEXPORT jlong JNICALL kj_codecpar_layout(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_ch_layout_mask(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_from_context(JNIEnv *env,jclass cls,jlong par_token,jlong ctx_token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,par_token,KJ_KIND_CODEC_PAR);kc_codec_ctx*c;(void)cls;if(!p)return-1;c=(kc_codec_ctx*)kj_handle_get(env,ctx_token,KJ_KIND_CODEC_CTX);return c?ffkmp_codecpar_from_context(p,c):-1;}
JNIEXPORT jint JNICALL kj_codecpar_copy(JNIEnv *env,jclass cls,jlong dst_token,jlong src_token)
{kc_codec_par*d=(kc_codec_par*)kj_handle_get(env,dst_token,KJ_KIND_CODEC_PAR);kc_codec_par*s;(void)cls;if(!d)return-1;s=(kc_codec_par*)kj_handle_get(env,src_token,KJ_KIND_CODEC_PAR);return s?ffkmp_codecpar_copy_for_mux(d,s):-1;}
