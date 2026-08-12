/* Category unit: codecs and decoder contexts (methods.def section "codec").
 * Follows kj_abi.c's canonical pattern. A kc_codec is FFmpeg-owned static data, so its token is
 * never closed; a kc_codec_ctx is caller-owned and closed exactly once through the table. */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_find_decoder_by_id(JNIEnv *env, jclass cls, jint id)
{
    const kc_codec *c = ffkmp_find_decoder_by_id((int)id);
    (void)env; (void)cls;
    if (c == NULL) return 0; /* legitimate "not found": Kotlin decides whether to throw */
    return kj_handle_put(KJ_KIND_CODEC, (void *)c);
}

JNIEXPORT jlong JNICALL kj_find_decoder_by_name(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    const kc_codec *codec;
    (void)cls;
    if (c == NULL) return 0;
    codec = ffkmp_find_decoder_by_name(c);
    free(c);
    if (codec == NULL) return 0;
    return kj_handle_put(KJ_KIND_CODEC, (void *)codec);
}

JNIEXPORT jlong JNICALL kj_codecctx_alloc(JNIEnv *env, jclass cls, jlong codec_token)
{
    const kc_codec *codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
    kc_codec_ctx *ctx;
    (void)cls;
    if (codec == NULL) return 0;
    ctx = ffkmp_codecctx_alloc(codec);
    if (ctx == NULL) { kj_throw_handle(env, "codec context allocation failed"); return 0; }
    return kj_handle_put(KJ_KIND_CODEC_CTX, ctx);
}

JNIEXPORT void JNICALL kj_codecctx_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_close(token, KJ_KIND_CODEC_CTX);
    (void)env; (void)cls;
    ffkmp_codecctx_free(ctx);
}

JNIEXPORT jint JNICALL kj_codecctx_from_par(JNIEnv *env, jclass cls, jlong ctx_token, jlong par_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_codec_par *par;
    (void)cls;
    if (ctx == NULL) return -1;
    par = (kc_codec_par *)kj_handle_get(env, par_token, KJ_KIND_CODEC_PAR);
    if (par == NULL) return -1;
    return (jint)ffkmp_codecctx_from_par(ctx, par);
}

JNIEXPORT jint JNICALL kj_codecctx_open(JNIEnv *env, jclass cls, jlong ctx_token, jlong codec_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    const kc_codec *codec;
    (void)cls;
    if (ctx == NULL) return -1;
    codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
    if (codec == NULL) return -1;
    return (jint)ffkmp_codecctx_open(ctx, codec);
}

JNIEXPORT jint JNICALL kj_codecctx_set_opt(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    char *k, *v;
    int rc;
    (void)cls;
    if (ctx == NULL) return -1;
    k = kj_string_dup(env, key);
    if (k == NULL) { kj_throw_handle(env, "set_opt refused: NULL key"); return -1; }
    v = kj_string_dup(env, value);
    rc = ffkmp_codecctx_set_opt(ctx, k, v);
    free(k);
    free(v);
    return (jint)rc;
}

JNIEXPORT jint JNICALL kj_codecctx_send_packet(JNIEnv *env, jclass cls, jlong ctx_token, jlong packet_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_packet *p;
    (void)cls;
    if (ctx == NULL) return -1;
    /* A zero packet token is the documented drain packet: FFmpeg's own NULL-send convention. */
    if (packet_token == 0) return (jint)ffkmp_codecctx_send_packet(ctx, NULL);
    p = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET);
    if (p == NULL) return -1;
    return (jint)ffkmp_codecctx_send_packet(ctx, p);
}

JNIEXPORT jint JNICALL kj_codecctx_receive_frame(JNIEnv *env, jclass cls, jlong ctx_token, jlong frame_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_frame *f;
    (void)cls;
    if (ctx == NULL) return -1;
    f = (kc_frame *)kj_handle_get(env, frame_token, KJ_KIND_FRAME);
    if (f == NULL) return -1;
    return (jint)ffkmp_codecctx_receive_frame(ctx, f);
}

JNIEXPORT void JNICALL kj_codecctx_flush(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    (void)cls;
    if (ctx != NULL) ffkmp_codecctx_flush(ctx);
}
