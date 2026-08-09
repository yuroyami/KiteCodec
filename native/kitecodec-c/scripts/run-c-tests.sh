#!/usr/bin/env bash
#
# Run the host C test suites for one build variant.
#
# Usage:  ./scripts/run-c-tests.sh <variant> [suite ...]
#         variant is one of: plain asan tsan
#         suite names are the file stems, for example test_buffers. With none given, all five
#         run, which is what a gate does.
#
# Build first: ./scripts/build-host.sh <variant>. This script never builds, so a gate cannot
# accidentally pass on a stale binary that was never recompiled.
#
# Each suite returns non-zero on its first failing case and prints one line per case, which is
# the contract in plan section 15.3. This runner does not stop at the first failing suite: it
# runs all of them and then exits non-zero, because when three suites break at once the useful
# output is all three.
#
set -uo pipefail

VARIANT="${1:-}"
case "$VARIANT" in
    plain|asan|tsan) shift ;;
    "") echo "run-c-tests.sh: usage: $0 <plain|asan|tsan> [suite ...]" >&2; exit 2 ;;
    *)  echo "run-c-tests.sh: unknown variant '$VARIANT', expected plain, asan or tsan" >&2
        exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BIN="$ROOT/build/$VARIANT/bin"

# The five suites of plan section 15.3. Keep this list and build-host.sh in agreement.
ALL_SUITES="test_ownership test_buffers test_rescale test_strerror_thread test_convert"
SUITES="${*:-$ALL_SUITES}"

[ -d "$BIN" ] || {
    echo "run-c-tests.sh: no binaries for variant $VARIANT. Run ./scripts/build-host.sh $VARIANT" >&2
    exit 1
}

# Sanitizer options, per variant.
#
# detect_leaks=0 is set explicitly rather than left to the default, because it is the fact that
# register item B1-14 turns on: LeakSanitizer is not supported on macOS arm64, and asking for it
# gets "detect_leaks is not supported on this platform" instead of leak evidence. The local leak
# instrument is the allocation interposer in the plain variant; LSan runs in the Linux CI job.
export ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"
export TSAN_OPTIONS="halt_on_error=1:second_deadlock_stack=1"

echo "run-c-tests.sh: variant $VARIANT"
echo "  binaries   $BIN"
case "$VARIANT" in
    plain) echo "  allocation accounting is live in this variant" ;;
    asan)  echo "  allocation accounting reads zero here: the ASan runtime owns the allocator" ;;
    tsan)  echo "  allocation accounting reads zero here: the TSan runtime owns the allocator" ;;
esac
echo

passed=""
failed=""
missing=""

for suite in $SUITES; do
    binary="$BIN/$suite"
    if [ ! -x "$binary" ]; then
        echo "MISSING  $suite has no binary in $BIN"
        missing="$missing $suite"
        continue
    fi
    echo "=== $suite"
    if "$binary"; then
        passed="$passed $suite"
    else
        code="$?"
        echo "=== $suite exited $code"
        failed="$failed $suite"
    fi
    echo
done

count() { echo $# ; }
# shellcheck disable=SC2086
echo "run-c-tests.sh: variant $VARIANT, $(count $passed) suites passed, $(count $failed) failed, $(count $missing) missing"
[ -n "$failed" ] && echo "  failed: ${failed# }"
[ -n "$missing" ] && echo "  missing: ${missing# }"

if [ -n "$failed" ] || [ -n "$missing" ]; then
    exit 1
fi
exit 0
