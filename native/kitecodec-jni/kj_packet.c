/* Category unit: packets (methods.def section "packet"). Follows kj_abi.c's canonical pattern. */

#include "kj_internal.h"

JNIEXPORT jlong JNICALL kj_packet_alloc(JNIEnv *env, jclass cls)
{
    kc_packet *p = ffkmp_packet_alloc();
    (void)cls;
    if (p == NULL) { kj_throw_handle(env, "packet allocation failed"); return 0; }
    return kj_handle_put(KJ_KIND_PACKET, p);
}

JNIEXPORT void JNICALL kj_packet_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_close(token, KJ_KIND_PACKET);
    (void)env; (void)cls;
    ffkmp_packet_free(p); /* NULL-safe; double close resolved to NULL by the table */
}

JNIEXPORT jlong JNICALL kj_packet_clone(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *src = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    kc_packet *out;
    (void)cls;
    if (src == NULL) return 0;
    out = ffkmp_packet_clone(src);
    if (out == NULL) { kj_throw_handle(env, "packet clone failed"); return 0; }
    return kj_handle_put(KJ_KIND_PACKET, out);
}

JNIEXPORT jlong JNICALL kj_packet_pts(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_pts(p) : 0;
}

JNIEXPORT jlong JNICALL kj_packet_dts(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_dts(p) : 0;
}

JNIEXPORT jlong JNICALL kj_packet_duration(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_duration(p) : 0;
}

JNIEXPORT jint JNICALL kj_packet_stream_index(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jint)ffkmp_packet_stream_index(p) : -1;
}

JNIEXPORT jint JNICALL kj_packet_size(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jint)ffkmp_packet_size(p) : 0;
}

JNIEXPORT jboolean JNICALL kj_packet_is_keyframe(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return (p && ffkmp_packet_is_keyframe(p)) ? JNI_TRUE : JNI_FALSE;
}
