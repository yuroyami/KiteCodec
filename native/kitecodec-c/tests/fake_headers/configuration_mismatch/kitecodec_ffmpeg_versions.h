/* Doctored shim, case 5 of tests/test_identity.c: one library's *_configuration() string disagrees.
 *
 * This is the case version numbers cannot see. Six libraries taken from two different configure runs
 * can report six agreeing version triples and still be a mixed install, which is a real failure mode
 * on a machine that has both a package manager FFmpeg and a hand built one: the loader resolves each
 * -lav* independently, and nothing about the numbers says they came from one tree. The configure line
 * is the fingerprint that does say it. Policy: reject, naming the library that disagreed.
 *
 * No version macro is touched here. What is doctored is which function the gate calls for libavfilter:
 * kc_fake_avfilter_configuration, defined in tests/test_identity.c, which returns a string that is
 * deliberately not the real configure line. The macro is a function rename and not a wrapper, so the
 * production source still reads `avfilter_configuration` and has no test-only branch in it.
 */

#ifndef KC_FAKE_CONFIGURATION_MISMATCH_H
#define KC_FAKE_CONFIGURATION_MISMATCH_H

#define KC_CASE kc_configuration_mismatch
#include "../kc_rename.h"

#include_next "kitecodec_ffmpeg_versions.h"

/* Defined by tests/test_identity.c. */
const char *kc_fake_avfilter_configuration(void);

#undef avfilter_configuration
#define avfilter_configuration kc_fake_avfilter_configuration

#endif /* KC_FAKE_CONFIGURATION_MISMATCH_H */
