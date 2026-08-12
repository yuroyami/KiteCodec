/* Give one doctored copy of the identity gate its own private set of exported names.
 *
 * tests/test_identity.c needs several copies of src/kitecodec_abi.c in ONE binary, each compiled
 * against a different shim include tree, so that the five verdicts of plan section 15.2 B1.6 can be
 * asserted side by side in one table driven suite. Every copy would otherwise define kc_init and its
 * five siblings, and the link would fail on duplicate symbols.
 *
 * Renaming through macros is what keeps the experiment honest: the SOURCE compiled is byte for byte
 * the shipped src/kitecodec_abi.c, with no test-only branch inside it and no #ifdef anywhere in the
 * production file. What differs between the copies is only what the preprocessor handed them, which is
 * exactly the difference the gate exists to detect.
 *
 * The struct type kc_ffmpeg_report is deliberately NOT renamed: every copy must agree on the report
 * layout, because the test reads all of them through one type. Macro replacement is whole token, so
 * renaming kc_ffmpeg_report_get leaves kc_ffmpeg_report alone.
 *
 * Include this BEFORE kitecodec_abi.h is reached, which the shims do by sitting at the top of their
 * kitecodec_ffmpeg_versions.h; src/kitecodec_abi.c includes that file first for this reason.
 */

#ifndef KC_RENAME_H
#define KC_RENAME_H

#ifndef KC_CASE
#error "define KC_CASE to the symbol prefix for this doctored copy before including kc_rename.h"
#endif

#define KC_PASTE_(a, b) a##b
#define KC_PASTE(a, b) KC_PASTE_(a, b)

#define kc_init KC_PASTE(KC_CASE, _init)
#define kc_ffmpeg_report_get KC_PASTE(KC_CASE, _report_get)
#define kc_abi_version KC_PASTE(KC_CASE, _abi_version)
#define kc_ffmpeg_library_name KC_PASTE(KC_CASE, _library_name)
#define kc_verdict_name KC_PASTE(KC_CASE, _verdict_name)
#define kc_ffmpeg_configuration KC_PASTE(KC_CASE, _configuration)
#define kc_jvm_attach KC_PASTE(KC_CASE, _jvm_attach)

#endif /* KC_RENAME_H */
