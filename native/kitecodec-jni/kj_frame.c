/* Category unit: frames (methods.def section "frame"). Follows kj_abi.c's canonical pattern. */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_frame_alloc(JNIEnv *env, jclass cls)
{
    kc_frame *f = ffkmp_frame_alloc();
    (void)cls;
    if (f == NULL) { kj_throw_handle(env, "frame allocation failed"); return 0; }
    return kj_handle_put(KJ_KIND_FRAME, f);
}

JNIEXPORT void JNICALL kj_frame_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_close(token, KJ_KIND_FRAME);
    (void)env; (void)cls;
    ffkmp_frame_free(f);
}

JNIEXPORT jlong JNICALL kj_frame_pts(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jlong)ffkmp_frame_pts(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_width(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_width(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_height(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_height(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_format(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_format(f) : -1;
}

JNIEXPORT jboolean JNICALL kj_frame_is_keyframe(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return (f && ffkmp_frame_is_keyframe(f)) ? JNI_TRUE : JNI_FALSE;
}

/* The safe copied-plane surface S1.c.3's JVM SoftwareConverter is built on: one exact copy of
 * the frame's tightly packed planes as a byte array. Video frames size through
 * ffkmp_image_get_buffer_size at align 1 (tightly packed is the documented Frame.kt layout);
 * audio frames size through ffkmp_samples_get_buffer_size. */
JNIEXPORT jbyteArray JNICALL kj_frame_copy_planes(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    int size, rc;
    uint8_t *tmp;
    jbyteArray out;
    (void)cls;
    if (f == NULL) return NULL;
    if (ffkmp_frame_width(f) > 0) {
        size = ffkmp_image_get_buffer_size(ffkmp_frame_format(f),
                                           ffkmp_frame_width(f), ffkmp_frame_height(f), 1);
    } else {
        size = ffkmp_samples_get_buffer_size(f);
    }
    if (size <= 0) { kj_throw_ffmpeg(env, size, "frame_copy_planes size"); return NULL; }
    tmp = (uint8_t *)malloc((size_t)size);
    if (tmp == NULL) { kj_throw_handle(env, "out of memory copying frame planes"); return NULL; }
    rc = ffkmp_frame_width(f) > 0
        ? ffkmp_frame_copy_to_buffer(f, tmp, size)
        : ffkmp_samples_copy_to_buffer(f, tmp, size);
    if (rc < 0) {
        free(tmp);
        kj_throw_ffmpeg(env, rc, "frame_copy_planes copy");
        return NULL;
    }
    out = kj_bytes_new(env, tmp, size);
    free(tmp);
    return out;
}
