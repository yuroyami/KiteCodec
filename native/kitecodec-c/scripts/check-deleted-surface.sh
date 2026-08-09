#!/usr/bin/env bash
#
# The repository-wide cross-check for the 15 helpers B1.4 deleted, register item B1-08.
#
# Why it exists. Those 15 were exported symbols of a versioned library that no Kotlin file
# imported: a compatibility promise nobody meant to make. Deleting them is only safe if nothing
# anywhere refers to them, and "nothing" has to mean both repositories and every file type, not
# just the ones a Kotlin developer thinks to grep.
#
# Why it must run with kitecodec-core/src/nativeInterop/cinterop/archived/ already deleted. That
# directory held six def files that no build file referenced and that redefined the same helper
# names. A grep run while it still existed reported a definition for almost every deleted name and
# would have masked a real reference behind duplicate noise. Plan section 15.2 B1.4 step 3 puts the
# deletion first for exactly that reason, and this script fails outright if the directory is back.
#
# What counts as a reference, stated exactly so the check is not fuzzy. Three questions:
#
#   1. Is any deleted name USED anywhere? A use is the name followed by an open parenthesis: a
#      call, a definition or a declaration. Prose cannot match that shape, so this question has a
#      mechanical answer and zero is the only acceptable one.
#   2. Does any Kotlin source or any def file mention one at all, in any form? Those are the two
#      file kinds where a mention is never bookkeeping: a Kotlin `import ffmpeg.<name>` or a def
#      body line is a real dependency. Zero again.
#   3. Which files still mention a deleted name as prose? Those are the record of the deletion
#      itself, and they are confined to an allowlist below. A mention outside it fails, so a new
#      reference cannot arrive disguised as a comment.
#
# The exclusions are --exclude-dir and never a `| grep -v build/` pipe. The pipe filters the OUTPUT
# LINE, so it silently drops a real hit whose own text happens to contain the word, which is the
# mistake plan section 9 records against the em dash scan: three real em dashes hid behind lines
# that mentioned "vendor/ffmpeg" and "build/install". `.claude/worktrees` holds gitignored scratch
# checkouts of this same repository at older commits, where every deleted helper is still present
# and correct, so it is excluded as a directory too.
#
# Usage:  ./scripts/check-deleted-surface.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
OTHER="$(cd "$REPO/../KitePlayer" 2>/dev/null && pwd || true)"

# The 15 names. Kept in step with tools/extract_from_def.py by construction: the extractor refuses
# to run when a supplied --exclude list is not exactly its DELETED table, and this list is fed to
# it below.
DELETED="ffkmp_averror_einval ffkmp_nopts_value ffkmp_frame_ref ffkmp_frame_make_writable \
ffkmp_packet_ref ffkmp_packet_flags ffkmp_codecpar_video_delay ffkmp_codecctx_sample_fmt \
ffkmp_codec_name ffkmp_fmt_bit_rate ffkmp_fmt_alloc_output ffkmp_stream_duration \
ffkmp_stream_nb_frames ffkmp_avseek_flag_byte ffkmp_avseek_flag_frame"

# Files allowed to mention a deleted name in prose, each because it is part of the record of the
# deletion rather than a use of it: the generator that removes them, the gate that names them, this
# script, the one generated comment that outlived its subject (src/helpers_format.c still says
# ffkmp_fmt_alloc_output2 is "Like ffkmp_fmt_alloc_output", because the units are the def body
# verbatim and rewriting prose would make verify-lift.sh fuzzy), the two test files that record why
# their cases went, the tree's own README, and KPKMP.md, which is the project's Execution log and so
# is the primary record of the deletion: its B1.4 entry names all 15 so a later reader can check the
# list without re-deriving it. Paths are relative to the KiteCodec repository root; a path starting
# with ../ lives in KitePlayer.
#
# KPKMP.md was added by the B1.4 to B1.6 gate run, which this check FAILED on the gate's own log
# entry. That is the check working rather than the check being wrong: it refused a new prose mention
# until someone gave a reason, and the reason is the line above.
ALLOWED_PROSE="
native/kitecodec-c/tools/extract_from_def.py
native/kitecodec-c/scripts/verify-lift.sh
native/kitecodec-c/scripts/check-deleted-surface.sh
native/kitecodec-c/src/helpers_format.c
native/kitecodec-c/tests/test_ownership.c
native/kitecodec-c/tests/test_rescale.c
native/kitecodec-c/README.md
../KitePlayer/KPKMP.md
"

# Every excluded directory is gitignored in one repository or the other: build output, the Gradle
# and Kotlin caches, the vendored study clones, the generated test clips, and the scratch worktrees.
# `.kotlin` earns its place for a reason worth naming: the commonizer keeps binary `.knm` metadata
# there, and those files carry the old helper names as text until Gradle regenerates them, so
# without this exclusion the check reports seven stale cache files and buries the answer.
EXCLUDES="--exclude-dir=build --exclude-dir=.claude --exclude-dir=.git --exclude-dir=vendor \
--exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=testmedia"

ARCHIVED="$REPO/kitecodec-core/src/nativeInterop/cinterop/archived"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

status=0
fail() {
    echo "  FAIL: $*"
    status=1
}

echo "check-deleted-surface.sh: register item B1-08, 15 deleted helpers"
echo "  KiteCodec   $REPO"
echo "  KitePlayer  ${OTHER:-not found beside this repository}"
echo "  names       $(echo $DELETED | wc -w | tr -d ' ')"
echo

echo "0. the archived/ directory is gone, so duplicate definitions cannot mask a reference"
if [ -e "$ARCHIVED" ]; then
    fail "$ARCHIVED still exists. Delete it before this check means anything: it redefines"
    echo "        almost every deleted name and would answer question 1 for the wrong reason."
else
    echo "  ok: $ARCHIVED does not exist"
fi
echo

TREES="$REPO"
[ -n "$OTHER" ] && TREES="$TREES $OTHER"

echo "1. no deleted name is used, that is followed by an open parenthesis, anywhere"
: > "$WORK/uses.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    # The braces around name are not decoration. Written bare, `$name[[:space:]]` reads to a human
    # and to shellcheck (SC1087) as an array subscript, and shellcheck grades that an error rather
    # than a style note. bash expands it correctly either way, so this is a legibility fix and not
    # a behaviour fix: the script's output was verified identical before and after.
    grep -rnE "(^|[^A-Za-z0-9_])${name}[[:space:]]*\(" $EXCLUDES $TREES >> "$WORK/uses.txt" 2>/dev/null || true
done
if [ -s "$WORK/uses.txt" ]; then
    fail "$(wc -l < "$WORK/uses.txt" | tr -d ' ') use site(s) survive:"
    sed 's|^|          |' "$WORK/uses.txt"
else
    echo "  ok: zero use sites in either repository, in any file type"
fi
echo

echo "2. no Kotlin source and no def file mentions a deleted name at all"
: > "$WORK/kotlin.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    grep -rnw "$name" --include="*.kt" --include="*.kts" --include="*.def" \
        $EXCLUDES $TREES >> "$WORK/kotlin.txt" 2>/dev/null || true
done
if [ -s "$WORK/kotlin.txt" ]; then
    fail "$(wc -l < "$WORK/kotlin.txt" | tr -d ' ') mention(s) in Kotlin or def files:"
    sed 's|^|          |' "$WORK/kotlin.txt"
else
    echo "  ok: zero mentions in *.kt, *.kts and *.def"
fi
echo

echo "3. every surviving prose mention is in a file that records the deletion"
: > "$WORK/prose.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    grep -rlw "$name" $EXCLUDES $TREES >> "$WORK/prose.txt" 2>/dev/null || true
done
sort -u "$WORK/prose.txt" > "$WORK/prose_files.txt"
: > "$WORK/allowed.txt"
for path in $ALLOWED_PROSE; do
    case "$path" in
        ../*) echo "$(cd "$REPO/.." && pwd)/${path#../}" >> "$WORK/allowed.txt" ;;
        *)    echo "$REPO/$path" >> "$WORK/allowed.txt" ;;
    esac
done
sort -u "$WORK/allowed.txt" -o "$WORK/allowed.txt"
comm -23 "$WORK/prose_files.txt" "$WORK/allowed.txt" > "$WORK/unexpected.txt"
comm -13 "$WORK/prose_files.txt" "$WORK/allowed.txt" > "$WORK/silent.txt"
echo "  files mentioning a deleted name: $(wc -l < "$WORK/prose_files.txt" | tr -d ' ')"
while read -r file; do
    [ -n "$file" ] || continue
    echo "    ${file#"$REPO"/}"
done < "$WORK/prose_files.txt"
if [ -s "$WORK/unexpected.txt" ]; then
    fail "not on the allowlist:"
    sed 's|^|          |' "$WORK/unexpected.txt"
    echo "        A prose mention outside the record of the deletion is how a real reference"
    echo "        arrives disguised. Either remove it or add the file with a reason."
else
    echo "  ok: every one is on the allowlist"
fi
if [ -s "$WORK/silent.txt" ]; then
    echo "  note: allowlisted but mentioning nothing, so the entry is stale:"
    sed 's|^|          |' "$WORK/silent.txt"
fi
echo

echo "4. the generator agrees that these are exactly the 15 it deletes"
if python3 "$ROOT/tools/extract_from_def.py" \
        --def "$WORK/absent.def" \
        --exclude "$(echo $DELETED | tr ' ' ',')" \
        --report >/dev/null 2>"$WORK/generator.txt"; then
    fail "the generator accepted a def file that does not exist"
elif grep -q -- "--exclude does not match" "$WORK/generator.txt"; then
    fail "the generator's DELETED table is not this list:"
    sed 's|^|          |' "$WORK/generator.txt"
else
    echo "  ok: the list matches the generator's DELETED table"
    echo "      (the run then failed on the absent def file, which is the expected next error)"
fi
echo

if [ "$status" -eq 0 ]; then
    echo "check-deleted-surface.sh: PASS, the 15 helpers are gone and nothing refers to them"
else
    echo "check-deleted-surface.sh: FAILED, see the lines marked FAIL above" >&2
fi
exit "$status"
