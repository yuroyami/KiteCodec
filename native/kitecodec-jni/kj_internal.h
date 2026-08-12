/* The KiteCodec JNI adapter: private declarations shared by the kj_* units.
 *
 * WHAT THIS LIBRARY IS. A narrow, dynamically registered JNI adapter over KiteCodec's opaque C
 * boundary (S1.c.1, register item S1C-01). It includes ONLY <jni.h>, the C runtime and KiteCodec's
 * three opaque headers. It never includes a libav header, never spells an av_* call and never
 * reproduces an FFmpeg struct: scripts/source-discipline.sh enforces all three, and a control that
 * plants a violation must fail it. The category units (kj_abi.c, kj_format.c, kj_packet.c,
 * kj_codec.c, kj_frame.c, kj_filter.c) call kc_* and ffkmp_* helpers only.
 *
 * WHY DYNAMIC REGISTRATION. The shared library exports exactly JNI_OnLoad (exports.map plus
 * -fvisibility=hidden). There is no Java_* symbol to keep in sync with a package name, R8 cannot
 * strip a native method it cannot see a symbol for (the consumer keep rule pins the bridge class
 * instead), and the whole Kotlin-to-C wiring lives in ONE manifest, methods.def, that both
 * kj_registration.c and the Kotlin bridge are written against. No hand-copied second list.
 *
 * HANDLES, NEVER POINTERS. Every C object crossing to the JVM travels as a generation-tagged token
 * minted by kj_handles.c. A jlong encodes a table slot and a generation, so a stale token, a zero
 * token, a double close or a token of the wrong kind is caught at the table and thrown as a typed
 * JVM exception BEFORE any helper runs. A raw pointer in a jlong would turn every one of those
 * caller bugs into memory corruption. Close invalidates the slot exactly once and is idempotent.
 */

#ifndef KJ_INTERNAL_H
#define KJ_INTERNAL_H

#include <jni.h>
#include <stdint.h>

#include "kitecodec_abi.h"
#include "kitecodec_handles.h"
#include "kitecodec_helpers.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── The handle table ─────────────────────────────────────────────────────────────────────── */

/* One kind per opaque typedef the boundary carries (kitecodec_handles.h), in one fixed order.
 * kj_handle_get refuses a token whose kind differs from the caller's expectation. */
enum kj_kind {
    KJ_KIND_NONE = 0,
    KJ_KIND_CODEC,
    KJ_KIND_CODEC_CTX,
    KJ_KIND_CODEC_PAR,
    KJ_KIND_DICT,
    KJ_KIND_DICT_ENTRY,
    KJ_KIND_FILTER_CTX,
    KJ_KIND_FILTER_GRAPH,
    KJ_KIND_FMT_CTX,
    KJ_KIND_FRAME,
    KJ_KIND_PACKET,
    KJ_KIND_STREAM,
    KJ_KIND_COUNT
};

/* Mint a token for ptr. Returns 0 only when ptr is NULL or the table is exhausted; the caller
 * decides whether 0 is an error or a legitimate "no object" answer. Thread safe. */
jlong kj_handle_put(int kind, void *ptr);

/* Resolve a token. On success returns the pointer. On a zero, stale, closed or wrong-kind token,
 * throws the bridge's typed exception on env and returns NULL; the caller must return immediately
 * (the pending exception is the result). Thread safe. */
void *kj_handle_get(JNIEnv *env, jlong token, int kind);

/* Like kj_handle_get but never throws: returns NULL silently. For close paths that must be
 * idempotent rather than loud. */
void *kj_handle_peek(jlong token, int kind);

/* Invalidate a token and return the pointer it held so the caller can free it. Returns NULL when
 * the token is zero, stale or of the wrong kind, which is what makes double close a no-op instead
 * of a crash. Thread safe. */
void *kj_handle_close(jlong token, int kind);

/* Test seam: how many live (unclosed) handles the table holds. The packaging-model host tests and
 * the leak assertions read this through a test-only external. */
int64_t kj_handle_live_count(void);

/* ── JNI utilities (kj_util.c). String, array and exception conversion lives HERE only. ─────── */

/* Throw the bridge's typed handle exception (class named in methods.def's header block). Safe to
 * call with a message only; returns void because the caller returns immediately after. */
void kj_throw_handle(JNIEnv *env, const char *msg);

/* Throw the bridge's typed native exception carrying an FFmpeg error code and a context string.
 * The Kotlin side turns (code, context) into the same FFmpegError taxonomy native uses. */
void kj_throw_ffmpeg(JNIEnv *env, int averror, const char *context);

/* Copy a jstring into a caller-owned malloc'd C string (UTF-8). NULL jstring gives NULL. The
 * caller frees with free(). On OOM throws and returns NULL. */
char *kj_string_dup(JNIEnv *env, jstring s);

/* New jstring from a C string; NULL c gives NULL without throwing. */
jstring kj_string_new(JNIEnv *env, const char *c);

/* New jbyteArray carrying an exact copy of len bytes. NULL data or negative len throws. */
jbyteArray kj_bytes_new(JNIEnv *env, const void *data, int32_t len);

/* ── Registration (kj_registration.c) ─────────────────────────────────────────────────────── */

/* Registers every methods.def row on its declared bridge class. Returns 0 on success, negative
 * on a missing class or failed RegisterNatives; JNI_OnLoad converts that into JNI_ERR. */
int kj_register_all(JNIEnv *env);

#ifdef __cplusplus
}
#endif

#endif /* KJ_INTERNAL_H */
