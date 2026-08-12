#!/bin/sh
# Source discipline of the JNI adapter (S1.c.1). The adapter may include only <jni.h>, the C
# runtime and KiteCodec's three opaque headers, and may call only kc_*/ffkmp_* helpers, JNI and
# the C runtime. Three bans, each with a falsifiability control in the S1.c.1 gate:
#   1. no libav/libsw include anywhere in the tree;
#   2. no av_*(/sw_*) call spelled in any unit;
#   3. no Java_* symbol defined (dynamic registration only).
# Prints the violations and exits 1 on any; prints a PASS line and exits 0 otherwise.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

echo "source-discipline.sh (kitecodec-jni): three bans over $DIR"

HITS=$(grep -n '#include[^"]*<libav\|#include[^"]*<libsw\|#include[^"]*"libav\|#include[^"]*"libsw' "$DIR"/*.c "$DIR"/*.h "$DIR"/methods.def 2>/dev/null)
if [ -n "$HITS" ]; then echo "FAIL: libav/libsw include:"; echo "$HITS"; FAIL=1
else echo "  ok: no libav or libsw include"; fi

HITS=$(grep -nE '(^|[^A-Za-z0-9_"])(av|sws|swr)_[a-z0-9_]+\(' "$DIR"/*.c "$DIR"/*.h 2>/dev/null | grep -v 'ffkmp_\|kc_jvm')
if [ -n "$HITS" ]; then echo "FAIL: direct av_/sws_/swr_ call:"; echo "$HITS"; FAIL=1
else echo "  ok: no direct av_, sws_ or swr_ call"; fi

HITS=$(grep -nE 'JNIEXPORT[^(]*Java_' "$DIR"/*.c 2>/dev/null)
if [ -n "$HITS" ]; then echo "FAIL: Java_* export (dynamic registration only):"; echo "$HITS"; FAIL=1
else echo "  ok: no Java_* symbol"; fi

if [ "$FAIL" -ne 0 ]; then
    echo "source-discipline.sh (kitecodec-jni): FAILED"
    exit 1
fi
echo "source-discipline.sh (kitecodec-jni): PASS, 3 of 3 bans hold"
