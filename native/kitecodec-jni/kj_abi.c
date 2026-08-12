/* Category unit: identity, capability and error plumbing (methods.def section "abi").
 *
 * THE CANONICAL PATTERN used by every implemented category unit:
 *   - one function per manifest row, named exactly as the row names it;
 *   - resolve every incoming token with kj_handle_get and return immediately on NULL (the typed
 *     exception is already pending);
 *   - call exactly one kc_ or ffkmp_ helper (two only when a size query pairs with a copy);
 *   - mint outgoing objects with kj_handle_put; convert text with kj_util; NEVER include a libav
 *     header, NEVER spell a direct FFmpeg call (scripts/source-discipline.sh fails the build).
 */

#include "kj_internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jint JNICALL kj_abi_version(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)kc_abi_version();
}

JNIEXPORT jint JNICALL kj_abi_init(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)kc_init();
}

JNIEXPORT jint JNICALL kj_abi_attach_current_vm(JNIEnv *env, jclass cls)
{
    JavaVM *vm = NULL;
    (void)cls;
    if ((*env)->GetJavaVM(env, &vm) != 0 || vm == NULL) {
        return KC_JVM_BAD_ARGUMENT;
    }
    return (jint)kc_jvm_attach(vm);
}

/* The full identity report as one parseable string. Kotlin (Internals.jvm.kt) splits on '\x1f'
 * (unit separator, cannot appear in any report text) and rebuilds the same typed report native
 * consumers read. Field order, fixed: status, bypassed, abi_major, abi_minor, then per library
 * i in 0..5: header M.m.p, runtime M.m.p, verdict; then configuration_agrees,
 * configuration_disagreed_count, configuration_disagreed, build_ffmpeg_ref,
 * build_license_flavour, build_provisioning_dir, runtime_version_info, runtime_license,
 * provisioning. 4 + 6*3 + 9 = 31 fields. */
JNIEXPORT jstring JNICALL kj_abi_identity_report(JNIEnv *env, jclass cls)
{
    kc_ffmpeg_report r;
    char buf[4096];
    int off = 0, i;
    (void)cls;
    kc_ffmpeg_report_get(&r);
    off += snprintf(buf + off, sizeof buf - (size_t)off, "%d\x1f%d\x1f%d\x1f%d",
                    r.status, r.bypassed, r.abi_major, r.abi_minor);
    for (i = 0; i < KC_FFMPEG_LIBRARY_COUNT; i++) {
        off += snprintf(buf + off, sizeof buf - (size_t)off,
                        "\x1f%d.%d.%d\x1f%d.%d.%d\x1f%d",
                        r.header_major[i], r.header_minor[i], r.header_micro[i],
                        r.runtime_major[i], r.runtime_minor[i], r.runtime_micro[i],
                        r.verdict[i]);
    }
    snprintf(buf + off, sizeof buf - (size_t)off,
             "\x1f%d\x1f%d\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s",
             r.configuration_agrees, r.configuration_disagreed_count,
             r.configuration_disagreed, r.build_ffmpeg_ref, r.build_license_flavour,
             r.build_provisioning_dir, r.runtime_version_info, r.runtime_license,
             r.provisioning);
    return kj_string_new(env, buf);
}

JNIEXPORT jstring JNICALL kj_abi_configuration(JNIEnv *env, jclass cls)
{
    (void)cls;
    return kj_string_new(env, kc_ffmpeg_configuration());
}

JNIEXPORT jboolean JNICALL kj_abi_has_decoder(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_find_decoder_by_name(c) != NULL ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jboolean JNICALL kj_abi_has_encoder(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_find_encoder_by_name(c) != NULL ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jboolean JNICALL kj_abi_has_filter(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_filter_exists(c) != 0 ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jint JNICALL kj_abi_error_eagain(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)ffkmp_averror_eagain();
}

JNIEXPORT jint JNICALL kj_abi_error_eof(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)ffkmp_averror_eof();
}

JNIEXPORT jstring JNICALL kj_abi_strerror(JNIEnv *env, jclass cls, jint errnum)
{
    (void)cls;
    return kj_string_new(env, ffkmp_strerror((int)errnum));
}

JNIEXPORT jint JNICALL kj_abi_media_type_video(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_video(); }

JNIEXPORT jint JNICALL kj_abi_media_type_audio(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_audio(); }

JNIEXPORT jint JNICALL kj_abi_media_type_subtitle(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_subtitle(); }

JNIEXPORT jint JNICALL kj_abi_media_type_data(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_data(); }

JNIEXPORT jint JNICALL kj_abi_media_type_attachment(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_attachment(); }

JNIEXPORT jlong JNICALL kj_abi_live_handles(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jlong)kj_handle_live_count();
}
