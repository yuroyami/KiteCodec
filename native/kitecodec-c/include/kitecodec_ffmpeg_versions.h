/* The only place src/kitecodec_abi.c reaches into FFmpeg.
 *
 * PRIVATE to the identity gate. It is deliberately NOT part of the def file's `headers` line, so
 * cinterop never binds it and no FFmpeg type ever arrives through kitecodec_abi.h. That header stays
 * opaque; this one is where the FFmpeg contact is concentrated.
 *
 * Two jobs, and the second is why this file exists at all rather than the includes sitting directly
 * in kitecodec_abi.c:
 *
 *  1. It brings in the six LIB*_VERSION_INT macros, which are the frozen expectations. Because
 *     kitecodec_abi.c is compiled by the same task, against the same include tree, as every helper
 *     unit, whatever the compiler baked as a struct offset it also read here. Nothing can recover a
 *     header version after the fact, so this is the only construction that is correct by definition.
 *
 *  2. It is the ONE file the hermetic identity test replaces. tests/fake_headers/<case>/ carries a
 *     shim of this same name that does `#include_next` on this file and only then redefines the
 *     LIB*_VERSION_* macros. Doing the redefinition here, after every real FFmpeg header has already
 *     been parsed, is what keeps the doctoring hermetic: FFmpeg's own headers use those macros for
 *     deprecation guards, and a shim that redefined them BEFORE the real headers were read would
 *     change which of FFmpeg's declarations exist, which is a different experiment from the one the
 *     test is running.
 *
 * The six public headers below are the same ones the helper layer includes, so the include tree the
 * gate reads is the include tree the offsets came from and not a subset of it.
 */

#ifndef KITECODEC_FFMPEG_VERSIONS_H
#define KITECODEC_FFMPEG_VERSIONS_H

#include <libavutil/avutil.h>
#include <libavutil/version.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/version.h>
#include <libavformat/avformat.h>
#include <libavformat/version.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/version.h>
#include <libswscale/swscale.h>
#include <libswscale/version.h>
#include <libswresample/swresample.h>
#include <libswresample/version.h>

#endif /* KITECODEC_FFMPEG_VERSIONS_H */
