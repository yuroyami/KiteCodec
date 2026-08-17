/* Proves the wasm codec archive LINKS and RUNS, not merely that it compiled (KPKMP.md 17.14 X-03).
 *
 * The five filter names are the point. `helpers_filter.c` looks each of them up by name through
 * `avfilter_get_by_name`, and the web spike's FFmpeg recipe disabled avfilter entirely because its
 * own harness never opened a graph. A build without them links cleanly and returns 0 from every one
 * of these, which is a failure that would otherwise surface only when a user applied a filter.
 */
#include <stdio.h>

#include "kitecodec_abi.h"
#include "kitecodec_helpers.h"

int main(void) {
    printf("configuration=%.32s\n", kc_ffmpeg_configuration());
    static const char *const names[] = { "abuffer", "abuffersink", "anull", "buffer", "buffersink" };
    int missing = 0;
    for (int i = 0; i < 5; i++) {
        const int present = ffkmp_filter_exists(names[i]);
        printf("filter %s = %d\n", names[i], present);
        if (!present) missing = 1;
    }
    if (missing) {
        fprintf(stderr, "FAIL: the web FFmpeg is missing a filter helpers_filter.c needs\n");
        return 1;
    }
    printf("OK: the codec library links, runs and finds every filter it names\n");
    return 0;
}
