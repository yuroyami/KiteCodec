#!/usr/bin/env bash
#
# Prove that the committed C sources are exactly what the extractor produces from the def.
#
# This is the test for register item B1-01. The FFmpeg helper layer exists twice for the length
# of the lift: as 949 lines of C inside ffmpeg.def, and as include/kitecodec_helpers.h plus
# src/kitecodec_helpers.c. Two representations of one thing drift unless something compares
# them, so this script extracts from the def at a git revision and byte-compares the result
# with what is committed, printing the sha256 digest of each side either way.
#
# Usage:  ./scripts/verify-lift.sh [REVISION]
#         REVISION defaults to HEAD. Any revision git understands works.
#
# After B1.3 the def at HEAD no longer carries the body, because B1.3 deletes it. From that
# commit onward this script is run against the revision that still has it, which is B1.3's
# parent, and section 15.4 relies on exactly that: the two representations can be proved equal
# in either direction at any time, which is what makes the lift cheap to roll back.
#
set -euo pipefail

REVISION="${1:-HEAD}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(git -C "$ROOT" rev-parse --show-toplevel)"

DEF_IN_REPO="kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def"
EXTRACTOR="$ROOT/tools/extract_from_def.py"
COMMITTED_HEADER="$ROOT/include/kitecodec_helpers.h"
COMMITTED_SOURCE="$ROOT/src/kitecodec_helpers.c"

command -v shasum >/dev/null 2>&1 || {
    echo "verify-lift.sh: shasum not found" >&2
    exit 1
}
for file in "$EXTRACTOR" "$COMMITTED_HEADER" "$COMMITTED_SOURCE"; do
    [ -f "$file" ] || { echo "verify-lift.sh: missing $file" >&2; exit 1; }
done

RESOLVED="$(git -C "$REPO" rev-parse --verify "$REVISION")" || {
    echo "verify-lift.sh: cannot resolve revision '$REVISION'" >&2
    exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "verify-lift.sh: repository $REPO"
echo "  revision   $REVISION ($RESOLVED)"
echo "  def        $DEF_IN_REPO"

git -C "$REPO" show "$RESOLVED:$DEF_IN_REPO" > "$WORK/ffmpeg.def" || {
    echo "verify-lift.sh: $DEF_IN_REPO does not exist at $REVISION" >&2
    exit 1
}

python3 "$EXTRACTOR" --def "$WORK/ffmpeg.def" \
    --header "$WORK/kitecodec_helpers.h" \
    --source "$WORK/kitecodec_helpers.c" \
    --report

digest() {
    shasum -a 256 "$1" | cut -d' ' -f1
}

status=0
report_pair() {
    # report_pair <label> <extracted> <committed>
    local label="$1" extracted="$2" committed="$3"
    local a b
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

report_pair "include/kitecodec_helpers.h" "$WORK/kitecodec_helpers.h" "$COMMITTED_HEADER"
report_pair "src/kitecodec_helpers.c" "$WORK/kitecodec_helpers.c" "$COMMITTED_SOURCE"

echo
if [ "$status" -eq 0 ]; then
    echo "verify-lift.sh: both files are byte identical to the extraction from $REVISION"
else
    echo "verify-lift.sh: FAILED. Re-run the extractor and commit its output, or fix the def." >&2
fi
exit "$status"
