#!/usr/bin/env bash
#
# Build the host test binaries for the extracted FFmpeg helper layer.
#
# There is no make, no cmake and no ninja here, and that is deliberate. Register item B1-15:
# cmake is not installed on the proving machine, and GNU make starts a comment at an
# unescaped '#' while both repositories live under a path containing '#Kite'. Driving clang
# directly is the only form that is both available and safe under this path, and it is proven
# to work here.
#
# Usage:  ./scripts/build-host.sh <variant>
#         variant is one of: plain asan tsan
#
# Variants, per plan section 15.3. ASan and TSan are mutually exclusive, which is why there
# are three of them rather than one:
#
#   plain  -O2, no runtime instrumentation. The compile-fidelity and correctness variant.
#          This is also the only variant in which the allocation interposer works, so it is
#          the variant that carries the leak evidence (LeakSanitizer is not supported on
#          macOS arm64, register item B1-14).
#   asan   -fsanitize=address,undefined -fno-omit-frame-pointer -O1. Catches the
#          out-of-bounds class that the identity gate of B1.6 prevents.
#   tsan   -fsanitize=thread -O1. Keeps the threaded cases honest.
#
# Environment:
#   KC_CC             compiler to use, default /usr/bin/clang
#   KC_AR             archiver to use, default /usr/bin/ar
#   KC_FFMPEG_PREFIX  when set, FFmpeg include and library flags are derived from it instead
#                     of from pkg-config. Expects <prefix>/include and <prefix>/lib.
#
# Outputs, all under build/<variant>/ which is gitignored:
#   lib/libkitecodec_helpers_host.a   the extracted helper layer
#   lib/libkc_interpose_alloc.dylib   the allocation interposer
#   bin/test_*                        one binary per test suite
#
set -euo pipefail

VARIANT="${1:-}"
case "$VARIANT" in
    plain|asan|tsan) ;;
    "") echo "build-host.sh: usage: $0 <plain|asan|tsan>" >&2; exit 2 ;;
    *)  echo "build-host.sh: unknown variant '$VARIANT', expected plain, asan or tsan" >&2
        exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

CC="${KC_CC:-/usr/bin/clang}"
AR="${KC_AR:-/usr/bin/ar}"
[ -x "$CC" ] || { echo "build-host.sh: no compiler at $CC" >&2; exit 1; }
[ -x "$AR" ] || { echo "build-host.sh: no archiver at $AR" >&2; exit 1; }

# The six FFmpeg libraries the helper layer needs, in link order.
FFMPEG_LIBS="libavformat libavcodec libavfilter libavutil libswscale libswresample"

if [ -n "${KC_FFMPEG_PREFIX:-}" ]; then
    [ -d "$KC_FFMPEG_PREFIX/include" ] || {
        echo "build-host.sh: KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX has no include directory" >&2
        exit 1
    }
    FF_CFLAGS="-I$KC_FFMPEG_PREFIX/include"
    FF_LDFLAGS="-L$KC_FFMPEG_PREFIX/lib"
    FF_LIBS="-lavformat -lavcodec -lavfilter -lavutil -lswscale -lswresample"
    FF_ORIGIN="KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX"
else
    command -v pkg-config >/dev/null 2>&1 || {
        echo "build-host.sh: pkg-config not found and KC_FFMPEG_PREFIX is not set" >&2
        exit 1
    }
    pkg-config --exists $FFMPEG_LIBS || {
        echo "build-host.sh: pkg-config cannot find all of: $FFMPEG_LIBS" >&2
        exit 1
    }
    # pkg-config output is used exactly as it comes. It was measured warning-clean under
    # -Wall -Wextra -Werror against FFmpeg 8.0 here, so there is no reason to rewrite its -I
    # into -isystem: doing that would silence a real signal from a future FFmpeg for free.
    FF_CFLAGS="$(pkg-config --cflags $FFMPEG_LIBS)"
    FF_LDFLAGS=""
    FF_LIBS="$(pkg-config --libs $FFMPEG_LIBS)"
    FF_ORIGIN="pkg-config ($(pkg-config --modversion libavcodec) libavcodec)"
fi

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
case "$VARIANT" in
    plain) VARIANT_FLAGS="-O2" ;;
    asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
    tsan)  VARIANT_FLAGS="-fsanitize=thread -O1" ;;
esac

OUT="$ROOT/build/$VARIANT"
OBJ="$OUT/obj"
LIB="$OUT/lib"
BIN="$OUT/bin"
rm -rf "$OUT"
mkdir -p "$OBJ" "$LIB" "$BIN"

HELPER_LIB="$LIB/libkitecodec_helpers_host.a"
INTERPOSE_LIB="$LIB/libkc_interpose_alloc.dylib"

# The five suites of plan section 15.3. Keep this list and run-c-tests.sh in agreement.
TESTS="test_ownership test_buffers test_rescale test_strerror_thread test_convert"

echo "build-host.sh: variant $VARIANT"
echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  archiver   $AR"
echo "  ffmpeg     $FF_ORIGIN"
echo "  flags      $BASE_FLAGS $VARIANT_FLAGS"
echo "  output     $OUT"

compile() {
    # compile <source> <object> [extra flags...]
    local source="$1" object="$2"
    shift 2
    echo "  cc  $(basename "$source")"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/tests" \
        "$@" -c "$source" -o "$object"
}

# 1. The extracted helper layer. It includes its own generated header, so this compile is the
#    proof that every emitted declaration matches its definition. -Werror makes a mismatch a
#    hard failure rather than a warning nobody reads.
compile "$ROOT/src/kitecodec_helpers.c" "$OBJ/kitecodec_helpers.o"
rm -f "$HELPER_LIB"
"$AR" rcs "$HELPER_LIB" "$OBJ/kitecodec_helpers.o"
echo "  ar  $(basename "$HELPER_LIB")"

# 2. The allocation interposer. It has to be a dynamic library: a Mach-O __DATA,__interpose
#    section is only honoured by dyld when it arrives in a loaded image, and linking the same
#    object straight into the executable was measured to count exactly zero.
echo "  cc  interpose_alloc.c (dylib)"
# shellcheck disable=SC2086
"$CC" $BASE_FLAGS $VARIANT_FLAGS -dynamiclib \
    -install_name "@rpath/$(basename "$INTERPOSE_LIB")" \
    "$ROOT/tests/interpose_alloc.c" -o "$INTERPOSE_LIB"

# 3. The harness.
compile "$ROOT/tests/harness.c" "$OBJ/harness.o"

# 4. One binary per suite. Every binary links the interposer, so the harness can report
#    whether allocation accounting is live without any test having to care which variant it
#    is running under.
for test in $TESTS; do
    source="$ROOT/tests/$test.c"
    [ -f "$source" ] || {
        echo "build-host.sh: missing test source $source" >&2
        exit 1
    }
    compile "$source" "$OBJ/$test.o"
    echo "  ld  $test"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -o "$BIN/$test" \
        "$OBJ/$test.o" "$OBJ/harness.o" "$HELPER_LIB" "$INTERPOSE_LIB" \
        -Wl,-rpath,"$LIB" $FF_LDFLAGS $FF_LIBS -lpthread
done

echo "build-host.sh: variant $VARIANT built $(echo $TESTS | wc -w | tr -d ' ') binaries in $BIN"
