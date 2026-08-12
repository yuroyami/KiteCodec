/* The generation-tagged handle table (S1.c.1 step 5).
 *
 * A jlong token encodes { generation:31 | kind:5 | slot:20 } with bit 63 unused so the value stays
 * positive in Kotlin. Kind travels INSIDE the token as well as in the table so a wrong-kind lookup
 * is distinguishable from a stale one in error text, but the table's own record is authoritative.
 *
 * The table grows in fixed-size chunks and never shrinks or moves entries, so a slot index stays
 * valid for the process lifetime. A live generation is always odd and never zero, so the zero
 * jlong can never collide with a legal token and safely means "no handle" at the Kotlin boundary.
 * One mutex guards mint/close; resolve reads under the same mutex because a resolve racing a close
 * must observe either the live pointer or the closed slot, never a torn pair.
 */

#include "kj_internal.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define KJ_SLOT_BITS 20
#define KJ_KIND_BITS 5
#define KJ_GEN_BITS  31
#define KJ_MAX_SLOTS (1 << KJ_SLOT_BITS)
#define KJ_CHUNK     1024

typedef struct kj_slot {
    void    *ptr;      /* NULL when free or closed */
    uint32_t gen;      /* odd while live, even while free; starts at 0 */
    uint8_t  kind;
} kj_slot;

static pthread_mutex_t kj_lock = PTHREAD_MUTEX_INITIALIZER;
static kj_slot *kj_slots = NULL;
static int32_t  kj_capacity = 0;
static int32_t  kj_next_free_scan = 0;
static int64_t  kj_live = 0;

static jlong kj_encode(uint32_t gen, int kind, int32_t slot)
{
    return ((jlong)(gen & ((1u << KJ_GEN_BITS) - 1)) << (KJ_SLOT_BITS + KJ_KIND_BITS))
         | ((jlong)(kind & ((1 << KJ_KIND_BITS) - 1)) << KJ_SLOT_BITS)
         | (jlong)(slot & (KJ_MAX_SLOTS - 1));
}

jlong kj_handle_put(int kind, void *ptr)
{
    int32_t i, slot = -1;
    jlong token = 0;
    if (ptr == NULL || kind <= KJ_KIND_NONE || kind >= KJ_KIND_COUNT) return 0;
    pthread_mutex_lock(&kj_lock);
    for (i = 0; i < kj_capacity; i++) {
        int32_t probe = (kj_next_free_scan + i) % (kj_capacity ? kj_capacity : 1);
        if (kj_slots[probe].ptr == NULL && (kj_slots[probe].gen & 1u) == 0u) { slot = probe; break; }
    }
    if (slot < 0) {
        if (kj_capacity >= KJ_MAX_SLOTS) { pthread_mutex_unlock(&kj_lock); return 0; }
        {
            int32_t grown = kj_capacity + KJ_CHUNK;
            kj_slot *bigger = (kj_slot *)realloc(kj_slots, (size_t)grown * sizeof(kj_slot));
            if (bigger == NULL) { pthread_mutex_unlock(&kj_lock); return 0; }
            memset(bigger + kj_capacity, 0, (size_t)KJ_CHUNK * sizeof(kj_slot));
            kj_slots = bigger;
            slot = kj_capacity;
            kj_capacity = grown;
        }
    }
    kj_slots[slot].gen += 1;             /* even -> odd: live */
    if ((kj_slots[slot].gen & 1u) == 0u) kj_slots[slot].gen += 1; /* wrap guard: stay odd */
    kj_slots[slot].ptr = ptr;
    kj_slots[slot].kind = (uint8_t)kind;
    kj_next_free_scan = slot + 1;
    kj_live += 1;
    token = kj_encode(kj_slots[slot].gen, kind, slot);
    pthread_mutex_unlock(&kj_lock);
    return token;
}

static void *kj_resolve(jlong token, int kind, int close_it)
{
    int32_t slot = (int32_t)(token & (KJ_MAX_SLOTS - 1));
    uint32_t gen = (uint32_t)((uint64_t)token >> (KJ_SLOT_BITS + KJ_KIND_BITS));
    void *ptr = NULL;
    if (token == 0) return NULL;
    pthread_mutex_lock(&kj_lock);
    if (slot < kj_capacity
        && kj_slots[slot].ptr != NULL
        && kj_slots[slot].gen == gen
        && kj_slots[slot].kind == (uint8_t)kind) {
        ptr = kj_slots[slot].ptr;
        if (close_it) {
            kj_slots[slot].ptr = NULL;
            kj_slots[slot].gen += 1;     /* odd -> even: free, and every old token is stale */
            kj_slots[slot].kind = KJ_KIND_NONE;
            kj_live -= 1;
        }
    }
    pthread_mutex_unlock(&kj_lock);
    return ptr;
}

void *kj_handle_peek(jlong token, int kind)
{
    return kj_resolve(token, kind, 0);
}

void *kj_handle_get(JNIEnv *env, jlong token, int kind)
{
    void *ptr = kj_resolve(token, kind, 0);
    if (ptr == NULL) {
        char msg[96];
        snprintf(msg, sizeof msg,
                 "invalid native handle (token=%lld, expected kind=%d): zero, closed, stale or wrong kind",
                 (long long)token, kind);
        kj_throw_handle(env, msg);
    }
    return ptr;
}

void *kj_handle_close(jlong token, int kind)
{
    return kj_resolve(token, kind, 1);
}

int64_t kj_handle_live_count(void)
{
    int64_t n;
    pthread_mutex_lock(&kj_lock);
    n = kj_live;
    pthread_mutex_unlock(&kj_lock);
    return n;
}
