/* JNI string, array and exception conversion (S1.c.1 step 5). This is the ONLY unit that may
 * construct JVM objects or throw; category units call these and return. Both exception classes
 * are Kotlin classes owned by the bridge (see methods.def's header block): keeping their binary
 * names in one place here and in the consumer keep rule is what lets R8 shrink everything else. */

#include "kj_internal.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define KJ_HANDLE_EXCEPTION "io/github/yuroyami/kitecodec/JniHandleException"
#define KJ_FFMPEG_EXCEPTION "io/github/yuroyami/kitecodec/JniNativeException"

static void kj_throw_named(JNIEnv *env, const char *class_name, const char *msg)
{
    jclass cls;
    if ((*env)->ExceptionCheck(env)) return; /* first throw wins */
    cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) {
        /* The bridge class set is broken; fall back to something that always exists so the
         * caller still sees a typed failure rather than a silent NULL. */
        (*env)->ExceptionClear(env);
        cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (cls == NULL) return;
    }
    (*env)->ThrowNew(env, cls, msg);
    (*env)->DeleteLocalRef(env, cls);
}

void kj_throw_handle(JNIEnv *env, const char *msg)
{
    kj_throw_named(env, KJ_HANDLE_EXCEPTION, msg);
}

void kj_throw_ffmpeg(JNIEnv *env, int averror, const char *context)
{
    /* ffkmp_strerror returns a thread-local buffer the next call overwrites; snprintf copies it
     * into msg before anything else can run on this thread, which is the documented discipline. */
    const char *text = ffkmp_strerror(averror);
    char msg[384];
    snprintf(msg, sizeof msg, "%d|%s|%s",
             averror, context ? context : "", text ? text : "unknown FFmpeg error");
    kj_throw_named(env, KJ_FFMPEG_EXCEPTION, msg);
}

char *kj_string_dup(JNIEnv *env, jstring s)
{
    const char *utf;
    char *copy;
    jsize len;
    if (s == NULL) return NULL;
    utf = (*env)->GetStringUTFChars(env, s, NULL);
    if (utf == NULL) return NULL; /* OOM already thrown */
    len = (*env)->GetStringUTFLength(env, s);
    copy = (char *)malloc((size_t)len + 1);
    if (copy == NULL) {
        (*env)->ReleaseStringUTFChars(env, s, utf);
        kj_throw_handle(env, "out of memory copying a string across the JNI boundary");
        return NULL;
    }
    memcpy(copy, utf, (size_t)len);
    copy[len] = '\0';
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return copy;
}

jstring kj_string_new(JNIEnv *env, const char *c)
{
    if (c == NULL) return NULL;
    return (*env)->NewStringUTF(env, c);
}

jbyteArray kj_bytes_new(JNIEnv *env, const void *data, int32_t len)
{
    jbyteArray out;
    if (data == NULL || len < 0) {
        kj_throw_handle(env, "byte copy across the JNI boundary refused: NULL data or negative length");
        return NULL;
    }
    out = (*env)->NewByteArray(env, (jsize)len);
    if (out == NULL) return NULL; /* OOM already thrown */
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)data);
    return out;
}
