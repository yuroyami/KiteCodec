/* Category unit: filter graphs (methods.def section "filter, sink, remux, transcode").
 *
 * Empty by design at the S1.c.1 scaffold: methods.def carries no filter row yet. S1.c.2 grows
 * this unit row by row, by kj_abi.c's canonical pattern, when the FilterGraph, MediaSink,
 * Remuxer and Transcoder expects gain their JVM actuals. The unit exists now so the link line,
 * the audits and the file fence never change shape again. */

#include "kj_internal.h"

/* Keeps this translation unit non-empty under -Werror without exporting anything. */
static const int kj_filter_unit_present = 1;
const int *kj_filter_unit_present_(void);
const int *kj_filter_unit_present_(void) { return &kj_filter_unit_present; }
