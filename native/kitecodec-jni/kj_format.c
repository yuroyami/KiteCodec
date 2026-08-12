/* Category unit: format contexts and streams (methods.def section "format").
 * Follows kj_abi.c's canonical pattern. Streams are minted as handles WITHOUT ownership: a
 * kc_stream belongs to its format context, so nativeFmtStream tokens are resolved for reads and
 * invalidated in bulk semantics by Kotlin discipline (the owner closes readers before the
 * context; the S1.c.2 contract test drives use-after-close through the table's staleness). */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_fmt_open_input(JNIEnv *env, jclass cls, jstring path)
{
    char *c = kj_string_dup(env, path);
    kc_fmt_ctx *ctx = NULL;
    int rc;
    (void)cls;
    if (c == NULL) { kj_throw_handle(env, "open refused: NULL path"); return 0; }
    rc = ffkmp_fmt_open_input(&ctx, c);
    free(c);
    if (rc < 0 || ctx == NULL) { kj_throw_ffmpeg(env, rc, "fmt_open_input"); return 0; }
    return kj_handle_put(KJ_KIND_FMT_CTX, ctx);
}

JNIEXPORT void JNICALL kj_fmt_close_input(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    (void)env; (void)cls;
    if (ctx != NULL) ffkmp_fmt_close_input(&ctx);
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
    return kj_handle_put(KJ_KIND_STREAM, s);
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
    rc = ffkmp_fmt_set_opt(ctx, k, v);
    free(k);
    free(v);
    return (jint)rc;
}
