/* The shared plumbing declared in kc_fuzz.h. Compiled into both drivers.
 *
 * Deliberately tiny. Everything here runs on every single input, so a bug in this file would be
 * charged to whichever helper the target happened to call next, which is the one failure mode a
 * fuzz harness must not have.
 */

#include "kc_fuzz.h"

#include <stdlib.h>
#include <string.h>

void kc_fuzz_quiet(void) {
    static int done = 0;
    /* Not thread safe and does not need to be: both drivers are single threaded, and libFuzzer
     * only forks workers as separate processes. A word-sized flag is the whole guard. */
    if (done) return;
    done = 1;
    if (getenv("KC_FFMPEG_LOG") == NULL) av_log_set_level(AV_LOG_QUIET);
}

char *kc_fuzz_dup(const uint8_t *data, size_t size) {
    char *copy = (char *)malloc(size + 1);
    if (copy == NULL) return NULL;
    if (size > 0) memcpy(copy, data, size);
    copy[size] = '\0';
    return copy;
}

int kc_fuzz_split(const uint8_t *data, size_t size, char **out_key, char **out_value) {
    *out_key = NULL;
    *out_value = NULL;

    const uint8_t *newline = (size > 0) ? (const uint8_t *)memchr(data, '\n', size) : NULL;
    size_t key_size = (newline != NULL) ? (size_t)(newline - data) : size;

    char *key = kc_fuzz_dup(data, key_size);
    if (key == NULL) return -1;

    char *value = NULL;
    if (newline != NULL) {
        /* Everything after the newline, which may be zero bytes: an input ending in a newline
         * means an empty value, which is a different case from no value at all. */
        size_t value_offset = key_size + 1;
        value = kc_fuzz_dup(data + value_offset, size - value_offset);
        if (value == NULL) {
            free(key);
            return -1;
        }
    }

    *out_key = key;
    *out_value = value;
    return 0;
}

void kc_fuzz_free(char *s) {
    free(s);
}
