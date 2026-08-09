#!/usr/bin/env bash
#
# Prove that the committed C sources are exactly what the extractor produces from the def.
#
# This is the test for register item B1-01. The FFmpeg helper layer exists twice: as 949 lines of
# C inside ffmpeg.def at the revision before the B1.3 lift, and as include/kitecodec_helpers.h plus
# the nine src/helpers_*.c units here. Two representations of one thing drift unless something
# compares them, so this script extracts from the def at a git revision and compares the result
# with what is committed, printing a sha256 digest of each side either way.
#
# B1.4 changed its shape, because there is no longer a single .c file to byte-compare. Three
# comparisons now run, weakest first:
#
#   A. the header, byte for byte. Still one generated file, so still an exact comparison.
#   B. each of the nine units, byte for byte, against the same extraction.
#   C. the nine committed units, concatenated in banner order with their per-unit `#include` line
#      and their KC_API tokens stripped, against the whole def body's payload. This is the one that
#      matters, and it is not implied by B: a unit map that dropped or duplicated a stretch of the
#      def would pass every per-file comparison in B and fail here. The two digests printed for it
#      are the digests plan section 15.2 B1.4 step 5 asks for.
#
# The 15 helpers B1.4 deleted are supplied to the extractor as an explicit --exclude list, spelled
# out below rather than implied, so the comparison stays exact instead of becoming a fuzzy one that
# tolerates any difference. The extractor refuses to run when that list is not exactly its own
# DELETED set, so the gate and the generator cannot drift apart.
#
# Usage:  ./scripts/verify-lift.sh [REVISION]
#         REVISION defaults to PRE_LIFT_REVISION below, the last commit whose ffmpeg.def still
#         carries the body. Any revision git understands works.
#
# Section 15.4 relies on exactly this: the two representations can be proved equal in either
# direction at any time, which is what makes the lift cheap to roll back.
#
set -euo pipefail

# The parent of the B1.3 lift commit, "Extract the FFmpeg helper layer into real C sources with
# their own tests". This is a fixed historical anchor, not a moving target: every commit from the
# lift onward has an ffmpeg.def with no body, so pointing this script at HEAD cannot work and the
# default must name the revision that still has one.
PRE_LIFT_REVISION="5364329"

# The 15 dead exported helpers of register item B1-08, deleted by B1.4. Spelled out here on
# purpose: this list is what the gate asserts, and the extractor cross-checks it against its own
# DELETED table and fails on any difference.
DELETED_HELPERS="ffkmp_averror_einval,ffkmp_nopts_value,ffkmp_frame_ref,ffkmp_frame_make_writable,\
ffkmp_packet_ref,ffkmp_packet_flags,ffkmp_codecpar_video_delay,ffkmp_codecctx_sample_fmt,\
ffkmp_codec_name,ffkmp_fmt_bit_rate,ffkmp_fmt_alloc_output,ffkmp_stream_duration,\
ffkmp_stream_nb_frames,ffkmp_avseek_flag_byte,ffkmp_avseek_flag_frame"

REVISION="${1:-$PRE_LIFT_REVISION}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(git -C "$ROOT" rev-parse --show-toplevel)"

DEF_IN_REPO="kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def"
EXTRACTOR="$ROOT/tools/extract_from_def.py"
COMMITTED_HEADER="$ROOT/include/kitecodec_helpers.h"
COMMITTED_SRC="$ROOT/src"

command -v shasum >/dev/null 2>&1 || {
    echo "verify-lift.sh: shasum not found" >&2
    exit 1
}
for file in "$EXTRACTOR" "$COMMITTED_HEADER"; do
    [ -f "$file" ] || { echo "verify-lift.sh: missing $file" >&2; exit 1; }
done
[ -d "$COMMITTED_SRC" ] || { echo "verify-lift.sh: missing $COMMITTED_SRC" >&2; exit 1; }

RESOLVED="$(git -C "$REPO" rev-parse --verify "$REVISION")" || {
    echo "verify-lift.sh: cannot resolve revision '$REVISION'" >&2
    exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "verify-lift.sh: repository $REPO"
echo "  revision   $REVISION ($RESOLVED)"
echo "  def        $DEF_IN_REPO"
echo "  excluded   15 helpers, register item B1-08"

git -C "$REPO" show "$RESOLVED:$DEF_IN_REPO" > "$WORK/ffmpeg.def" || {
    echo "verify-lift.sh: $DEF_IN_REPO does not exist at $REVISION" >&2
    exit 1
}

mkdir -p "$WORK/units"
python3 "$EXTRACTOR" --def "$WORK/ffmpeg.def" \
    --exclude "$DELETED_HELPERS" \
    --header "$WORK/kitecodec_helpers.h" \
    --units "$WORK/units" \
    --payload "$WORK/payload.c" \
    --report

UNIT_ORDER="$(python3 "$EXTRACTOR" --def "$WORK/ffmpeg.def" \
    --exclude "$DELETED_HELPERS" --list-units)"

digest() {
    shasum -a 256 "$1" | cut -d' ' -f1
}

status=0
report_pair() {
    # report_pair <label> <extracted> <committed>
    local label="$1" extracted="$2" committed="$3"
    local a b
    if [ ! -f "$committed" ]; then
        echo
        echo "$label"
        echo "  MISSING: the tree has no $committed"
        status=1
        return
    fi
    a="$(digest "$extracted")"
    b="$(digest "$committed")"
    echo
    echo "$label"
    printf '  %-26s %s\n' "extracted from $REVISION" "$a"
    printf '  %-26s %s\n' "committed in the tree" "$b"
    if [ "$a" = "$b" ]; then
        echo "  MATCH"
    else
        echo "  MISMATCH: the committed file is not what the extractor produces"
        status=1
        echo "  first 40 lines of the difference, extracted against committed:"
        diff -u "$extracted" "$committed" | sed -n '1,40p' | sed 's/^/    /' || true
    fi
}

echo
echo "A and B. byte comparison of the header and of every unit"
report_pair "include/kitecodec_helpers.h" "$WORK/kitecodec_helpers.h" "$COMMITTED_HEADER"
for unit in $UNIT_ORDER; do
    report_pair "src/$unit" "$WORK/units/$unit" "$COMMITTED_SRC/$unit"
done

# ---------------------------------------------------------------------------------------------
# C. The concatenation. Each unit contributes everything after its `#include` line, with the
#    KC_API token removed, and the result must equal the whole body's payload.
# ---------------------------------------------------------------------------------------------
: > "$WORK/concatenated.c"
for unit in $UNIT_ORDER; do
    [ -f "$COMMITTED_SRC/$unit" ] || continue
    awk 'seen { print } /^#include "kitecodec_helpers\.h"$/ { seen = 1 }' \
        "$COMMITTED_SRC/$unit" \
        | sed 's/^KC_API //' >> "$WORK/concatenated.c"
done

echo
echo "C. the nine units concatenated in banner order against the def body"
echo "   order: $(echo $UNIT_ORDER | tr '\n' ' ')"
PAYLOAD_DIGEST="$(digest "$WORK/payload.c")"
CONCAT_DIGEST="$(digest "$WORK/concatenated.c")"
printf '  %-30s %s  (%s lines)\n' "def body payload at $REVISION" "$PAYLOAD_DIGEST" \
    "$(wc -l < "$WORK/payload.c" | tr -d ' ')"
printf '  %-30s %s  (%s lines)\n' "committed units concatenated" "$CONCAT_DIGEST" \
    "$(wc -l < "$WORK/concatenated.c" | tr -d ' ')"
if [ "$PAYLOAD_DIGEST" = "$CONCAT_DIGEST" ]; then
    echo "  MATCH: the nine units are the def body, minus the 15 deletions, and nothing else"
else
    echo "  MISMATCH: the units do not reassemble into the def body"
    status=1
    echo "  first 60 lines of the difference, def body against concatenation:"
    diff -u "$WORK/payload.c" "$WORK/concatenated.c" | sed -n '1,60p' | sed 's/^/    /' || true
fi

echo
if [ "$status" -eq 0 ]; then
    echo "verify-lift.sh: the header, the nine units and their concatenation all agree with the"
    echo "                extraction from $REVISION"
else
    echo "verify-lift.sh: FAILED. Re-run the extractor and commit its output, or fix the def." >&2
fi
exit "$status"
