/* Doctored shim, case 2 of tests/test_identity.c: libavcodec's HEADER minor one above the runtime's.
 *
 * Same major, so every symbol still resolves and the struct layout is compatible by FFmpeg's own
 * promise; what is missing is whatever the headers declared and this older runtime does not have.
 * FFmpeg guarantees backward compatibility only, so a runtime BELOW the header minor is a reject and a
 * runtime above it is fine. Policy: reject.
 *
 * Written as "real plus one" rather than as a literal so the case keeps meaning "the runtime is one
 * minor behind the headers" when FFmpeg moves. On the proving machine today that is header 62.12.100
 * against runtime 62.11.100.
 */

#ifndef KC_FAKE_RUNTIME_OLDER_H
#define KC_FAKE_RUNTIME_OLDER_H

#define KC_CASE kc_runtime_older
#include "../kc_rename.h"

#include_next "kitecodec_ffmpeg_versions.h"

enum { KC_FAKE_REAL_AVCODEC_MINOR = LIBAVCODEC_VERSION_MINOR };

#undef LIBAVCODEC_VERSION_MINOR
#define LIBAVCODEC_VERSION_MINOR (KC_FAKE_REAL_AVCODEC_MINOR + 1)

#endif /* KC_FAKE_RUNTIME_OLDER_H */
