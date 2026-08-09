#!/usr/bin/env bash
#
# Audit the symbol table of the compiled FFmpeg helper archive.
#
# This is a test, not documentation: plan section 15.3 lists it beside the sanitizer runs, and it
# is the only instrument that checks what the archive PROMISES rather than what it does. Three
# questions, all of them answerable only from the object code:
#
#   1. What does the archive need from outside itself? Anything beyond libav*, libsw* and a short
#      allowlist is a dependency nobody decided to take. A `printf` would mean a helper writes to
#      stdout inside a library; an `av_log` would mean it writes to FFmpeg's log inside a library;
#      an `objc_msgSend` or a `dispatch_` would mean C that looked portable has quietly become
#      Apple-only.
#   2. What does it export? Exactly the 157 helpers the Kotlin side imports, plus the six kc_
#      functions of the identity gate, and nothing else. That set is a compatibility promise, which
#      is the whole reason B1.4 deleted the 15 helpers no Kotlin file imported (register item B1-08).
#   3. What does it keep to itself? The four trailing-underscore helpers, which are `static` and
#      must never appear as external symbols.
#   4. Does anything print? Nothing but the identity gate's diagnostic bypass warning, which plan
#      section 15.6 question 3 requires to be loud. Check 5 pins that to one source file.
#
# The default archive is the SHIPPED one, built per konan target by
# :kitecodec-core:compileKiteCodecCFor<Target> and embedded in the cinterop klib. That is the
# archive whose exported set a consumer sees. The host archive from scripts/build-host.sh is
# compiled with the same -fvisibility=hidden and answers the same way; `--host` points there.
#
# Usage:
#   ./scripts/symbol-audit.sh                     the shipped macos_arm64 archive
#   ./scripts/symbol-audit.sh --target macos_x64  another konan target's archive
#   ./scripts/symbol-audit.sh --host              the host test archive of build-host.sh
#   ./scripts/symbol-audit.sh --archive PATH      any archive
#
# Environment:
#   KC_NM   the nm to use, default /usr/bin/nm. Mach-O archives are read by the system nm; an ELF
#           archive needs a cross nm, and the Android toolchain package ships one that runs on
#           macOS (aarch64-linux-android-nm). Only macos_arm64 has an FFmpeg tree on the proving
#           machine, so only that archive exists here.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"

TARGET="macos_arm64"
ARCHIVE=""
HOST=0
NM="${KC_NM:-/usr/bin/nm}"

while [ $# -gt 0 ]; do
    case "$1" in
        --target)  [ $# -ge 2 ] || { echo "symbol-audit.sh: --target needs a name" >&2; exit 2; }
                   TARGET="$2"; shift 2 ;;
        --archive) [ $# -ge 2 ] || { echo "symbol-audit.sh: --archive needs a path" >&2; exit 2; }
                   ARCHIVE="$2"; shift 2 ;;
        --host)    HOST=1; shift ;;
        -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)         echo "symbol-audit.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

if [ -z "$ARCHIVE" ]; then
    if [ "$HOST" = 1 ]; then
        ARCHIVE="$ROOT/build/plain/lib/libkitecodec_helpers_host.a"
        HINT="build it first:  ./scripts/build-host.sh plain"
    else
        ARCHIVE="$REPO/kitecodec-core/build/kitecodec-c/$TARGET/libkitecodec.a"
        HINT="build it first:  ./gradlew :kitecodec-core:compileKiteCodecCFor<Target>"
    fi
fi
[ -f "$ARCHIVE" ] || {
    echo "symbol-audit.sh: no archive at $ARCHIVE" >&2
    echo "  ${HINT:-}" >&2
    exit 1
}
[ -x "$NM" ] || command -v "$NM" >/dev/null 2>&1 || {
    echo "symbol-audit.sh: no nm at $NM" >&2
    exit 1
}

HEADER="$ROOT/include/kitecodec_helpers.h"
ABI_HEADER="$ROOT/include/kitecodec_abi.h"
SRC="$ROOT/src"
for path in "$HEADER" "$ABI_HEADER" "$SRC"; do
    [ -e "$path" ] || { echo "symbol-audit.sh: missing $path" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The allowlist of undefined symbols that are not libav or libsw.
#
# Re-measured at B1.4 rather than taken from the plan, which lists memcpy, memset, snprintf, strlen
# and strerror. The measurement on this machine, against the macos_arm64 archive built by konan's
# clang 21.1.6, is different in both directions and the measurement wins:
#
#   _memcpy _snprintf _strstr   the three C library calls the helper bodies actually make, and each
#                               one is in the source rather than compiler-generated: memcpy at
#                               src/helpers_frame.c lines 94 and 128, the two audio plane copies;
#                               snprintf at the 18 filter description sites; strstr at
#                               src/helpers_filter.c line 278, where the multi input graph builder
#                               checks a caller's description for "[out]". memset, strlen and
#                               strerror do not appear at all: the layer calls av_strerror and never
#                               strerror, and it never zeroes or measures a string itself.
#   ___stack_chk_fail           clang's stack protector, emitted by the compiler and not called by
#   ___stack_chk_guard          any line of the source. Removing it would mean turning the stack
#                               protector off, which would be a worse trade than allowing it.
#   __tlv_bootstrap             the Mach-O thread-local bootstrap for `static __thread char
#                               buf[256]` in ffkmp_strerror. It is the object-code fingerprint of
#                               register item B1-09, so its presence here is expected and its
#                               ABSENCE would mean the thread-affine buffer stopped being thread
#                               local. Mach-O only; an ELF build resolves TLS differently.
#
# Seven more arrived with B1.6's identity gate, src/kitecodec_abi.c, and each was measured rather than
# assumed. All of them are in that one unit and nowhere else, which check 5 asserts at source level:
#
#   _pthread_once             the gate's once-only guard. Its ABSENCE would mean the gate had become a
#                             per-translation-unit flag, which is the exact construction plan section
#                             15.2 B1.6 step 3 rejects and the reason the gate needs external linkage.
#   _getenv                   reads KITECODEC_FFMPEG_ABI_BYPASS. The only environment read in the
#                             library, and the only way the diagnostic bypass can be turned on.
#   _fputs                    writes the bypass warning.
#   ___stderrp                the stream it writes to. These two are the ONE place the C layer writes
#                             anything, and they exist because plan section 15.6 question 3 makes the
#                             bypass warning mandatory: an escape hatch nobody can hear is the silently
#                             bypassed gate the owner ruled out. `printf` stays forbidden by check 2.
#   _strcmp                   compares the six *_configuration() strings and the bypass value.
#   _strlen                   bounds every copy into the report's fixed char arrays.
#   ___memcpy_chk             clang's bounds-checked memcpy, emitted for the report copies at -O2 from
#                             the same source line as _memcpy. Compiler output, not a source call.
ALLOWED_UNDEFINED="_memcpy _snprintf _strstr ___stack_chk_fail ___stack_chk_guard __tlv_bootstrap
_pthread_once _getenv _fputs ___stderrp _strcmp _strlen ___memcpy_chk"

# Calls that must never appear. A library does not print, does not log through its host's logger,
# and does not reach into an Apple runtime from portable C.
FORBIDDEN_PATTERNS="printf av_log objc_msgSend dispatch_ _exit abort"

status=0
fail() {
    echo "  FAIL: $*"
    status=1
}

echo "symbol-audit.sh: $ARCHIVE"
echo "  nm        $NM"
echo "  header    $HEADER"

# ---------------------------------------------------------------------------------------------
# The three sets, all derived and none written down twice.
#
#   expected exported   the KC_API declarations in the generated helper header, plus the KC_API
#                       declarations of the hand written identity gate header
#   expected internal   the `static` trailing-underscore definitions in the generated units
#   actual              what nm reports
# ---------------------------------------------------------------------------------------------

{
    sed -n 's/^KC_API [^(]*[^A-Za-z0-9_]\(ffkmp_[A-Za-z0-9_]*\)(.*/\1/p' "$HEADER"
    sed -n 's/^KC_API [^(]*[^A-Za-z0-9_]\(kc_[A-Za-z0-9_]*\)(.*/\1/p' "$ABI_HEADER"
} | sort -u > "$WORK/expected_exported.txt"
sed -n 's/^static [^(]*[^A-Za-z0-9_]\(ffkmp_[A-Za-z0-9_]*_\)(.*/\1/p' "$SRC"/*.c \
    | sort -u > "$WORK/expected_internal.txt"

"$NM" -m "$ARCHIVE" > "$WORK/nm_m.txt" 2>/dev/null

# nm prints one `archive.a(member.o):` banner per member and blank lines between them; neither is
# a symbol line.
grep -v -e '^$' -e ':$' "$WORK/nm_m.txt" > "$WORK/symbols.txt"

grep 'undefined' "$WORK/symbols.txt" | awk '{print $NF}' | sort -u > "$WORK/undefined.txt"
grep -v 'undefined' "$WORK/symbols.txt" | grep ' external ' | awk '{print $NF}' | sort -u \
    > "$WORK/external.txt"
grep -v 'undefined' "$WORK/symbols.txt" \
    | grep -E ' (non-external|was private external|private external) ' \
    | awk '{print $NF}' | sort -u > "$WORK/internal.txt"

echo "  members   $(grep -c ':$' "$WORK/nm_m.txt" | tr -d ' ')"
echo "  undefined $(wc -l < "$WORK/undefined.txt" | tr -d ' ')"
echo "  external  $(wc -l < "$WORK/external.txt" | tr -d ' ')"
echo

# ---------------------------------------------------------------------------------------------
# 1. Undefined symbols.
# ---------------------------------------------------------------------------------------------
echo "1. undefined symbols resolve only to libav*, libsw* and the allowlist"
: > "$WORK/unexpected_undefined.txt"
while read -r symbol; do
    [ -n "$symbol" ] || continue
    # The libav and libsw entry points. `_swscale_*` and `_swresample_*` are the library-level
    # queries (swscale_version, swscale_configuration and their siblings); the helper layer only ever
    # called `_sws_*` and `_swr_*`, so B1.6's identity gate is what made those two prefixes appear.
    case "$symbol" in
        _av_*|_avcodec_*|_avformat_*|_avfilter_*|_avutil_*|_avio_*) continue ;;
        _sws_*|_swr_*|_swscale_*|_swresample_*) continue ;;
    esac
    allowed=0
    for name in $ALLOWED_UNDEFINED; do
        [ "$symbol" = "$name" ] && allowed=1 && break
    done
    [ "$allowed" = 1 ] || echo "$symbol" >> "$WORK/unexpected_undefined.txt"
done < "$WORK/undefined.txt"
if [ -s "$WORK/unexpected_undefined.txt" ]; then
    fail "$(wc -l < "$WORK/unexpected_undefined.txt" | tr -d ' ') undefined symbol(s) are neither"
    echo "        a libav/libsw symbol nor on the allowlist:"
    sed 's/^/          /' "$WORK/unexpected_undefined.txt"
    echo "        Either the helper layer took a new dependency, or the allowlist needs a"
    echo "        measured entry with a reason, which is a normal commit and a log line."
else
    echo "  ok: every undefined symbol is libav/libsw or allowlisted"
    printf '      allowlisted and present:'
    for name in $ALLOWED_UNDEFINED; do
        grep -qx "$name" "$WORK/undefined.txt" && printf ' %s' "$name"
    done
    echo
    printf '      allowlisted and absent: '
    absent=""
    for name in $ALLOWED_UNDEFINED; do
        grep -qx "$name" "$WORK/undefined.txt" || absent="$absent $name"
    done
    echo "${absent:- none}"
fi
echo

# ---------------------------------------------------------------------------------------------
# 2. Forbidden calls. Checked by pattern over every symbol line, defined and undefined, so a
#    helper that DEFINED a printf wrapper would be caught too.
# ---------------------------------------------------------------------------------------------
echo "2. no printf, no av_log, no objc_msgSend, no dispatch_, no exit path"
for pattern in $FORBIDDEN_PATTERNS; do
    hits="$(grep -E "(^|[^A-Za-z0-9_])$pattern" "$WORK/symbols.txt" \
        | grep -v -E '(^|[^A-Za-z0-9_])_snprintf($|[^A-Za-z0-9_])' || true)"
    if [ -n "$hits" ]; then
        fail "the archive mentions '$pattern':"
        echo "$hits" | sed 's/^/          /'
    else
        echo "  ok: no '$pattern'"
    fi
done
echo

# ---------------------------------------------------------------------------------------------
# 3. The exported set is exactly the header's KC_API set.
# ---------------------------------------------------------------------------------------------
echo "3. exported symbols are exactly the KC_API declarations of the two headers"
sed 's/^/_/' "$WORK/expected_exported.txt" | sort > "$WORK/expected_external_symbols.txt"
comm -23 "$WORK/expected_external_symbols.txt" "$WORK/external.txt" > "$WORK/missing.txt"
comm -13 "$WORK/expected_external_symbols.txt" "$WORK/external.txt" > "$WORK/extra.txt"
echo "  header declares $(wc -l < "$WORK/expected_exported.txt" | tr -d ' ') KC_API helpers"
echo "  archive exports $(wc -l < "$WORK/external.txt" | tr -d ' ') symbols"
if [ -s "$WORK/missing.txt" ]; then
    fail "declared KC_API but not exported:"
    sed 's/^/          /' "$WORK/missing.txt"
fi
if [ -s "$WORK/extra.txt" ]; then
    fail "exported but not a KC_API declaration; the archive promises more than the header:"
    sed 's/^/          /' "$WORK/extra.txt"
fi
[ -s "$WORK/missing.txt" ] || [ -s "$WORK/extra.txt" ] || echo "  ok: the two sets are equal"
echo

# ---------------------------------------------------------------------------------------------
# 4. The internals are not external.
# ---------------------------------------------------------------------------------------------
# Three outcomes are possible for a `static` helper and only one of them is a failure. Measured on
# the macos_arm64 archive at B1.4: ffkmp_graph_finish_ and ffkmp_graph_finish_multi_ are emitted as
# non-external symbols, and ffkmp_codec_pix_fmts_ and ffkmp_ch_layout_mask_ are absent entirely,
# because at -O2 clang inlined both into their only callers and had no reason to keep a symbol. An
# absent static helper is therefore correct rather than suspicious. What must never happen is the
# third outcome, an EXTERNAL one, which would mean the `static` was lost and the archive had
# started promising an internal helper to consumers.
echo "4. the trailing-underscore helpers are private to their unit"
echo "  units define $(wc -l < "$WORK/expected_internal.txt" | tr -d ' ') static helper(s)"
while read -r name; do
    [ -n "$name" ] || continue
    if grep -qx "_$name" "$WORK/external.txt"; then
        fail "$name is an EXTERNAL symbol; it is declared static, so this cannot happen"
    elif grep -qx "_$name" "$WORK/internal.txt"; then
        echo "  ok: $name is present and not external"
    else
        echo "  ok: $name is not in the symbol table at all, inlined into its caller"
    fi
done < "$WORK/expected_internal.txt"
echo

# ---------------------------------------------------------------------------------------------
# 5. Only the identity gate may write to a stream.
#
# Check 2 forbids printf outright, and check 1 allowlists _fputs and ___stderrp because plan section
# 15.6 question 3 makes the diagnostic bypass warning mandatory. An allowlist alone would let any
# future unit start printing under cover of that entry, so the permission is pinned to one file here.
# Source level rather than symbol level on purpose: nm cannot say which unit an archive-wide undefined
# symbol came from without per-member bookkeeping, and the source is the thing a reviewer reads.
echo "5. only src/kitecodec_abi.c writes to a stream"
PRINTING_UNITS="$(grep -l -E '\b(stderr|stdout|fputs|fputc|fwrite|puts|vfprintf|fprintf)\b' "$SRC"/*.c \
    | xargs -n1 basename | sort || true)"
if [ "$PRINTING_UNITS" = "kitecodec_abi.c" ]; then
    echo "  ok: kitecodec_abi.c and nothing else, which is the bypass warning of register item B1-02"
elif [ -z "$PRINTING_UNITS" ]; then
    fail "no unit mentions a stream at all; the diagnostic bypass warning that plan section 15.6"
    echo "        question 3 requires has gone missing, so a bypassed gate would now be silent."
else
    fail "these units mention a stream, and only kitecodec_abi.c may:"
    echo "$PRINTING_UNITS" | sed 's/^/          /'
fi
echo

if [ "$status" -eq 0 ]; then
    echo "symbol-audit.sh: PASS"
else
    echo "symbol-audit.sh: FAILED, see the lines marked FAIL above" >&2
fi
exit "$status"
