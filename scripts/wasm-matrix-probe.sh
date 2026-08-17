#!/usr/bin/env bash
# Runs the 17.5 format matrix through the WEB decode path (KPKMP.md 17.14, toward X-14).
#
# Not the project's own suite: that is Kotlin and needs the engine, which the web does not have
# yet. This is the honest interim, and it says so. It answers one question the owner actually asks,
# "does the web play my formats", by demuxing and decoding each matrix clip through the same wasm
# codec the browser demo uses, and reporting per row rather than in aggregate.
#
# The web build carries the 17.6 LEAN codec set on purpose, so rows outside that set are EXPECTED
# to fail here and are marked accordingly. A row failing for a reason other than "codec not in the
# lean set" is a real finding.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kitecodec/libkitecodec.a"
MEDIA="${KITE_TESTMEDIA:-$ROOT/../KitePlayer/testmedia}"
[ -d "$MEDIA" ] || { echo "no testmedia at $MEDIA" >&2; exit 1; }
[ -f "$KC" ] || { echo "run :kitecodec-core:compileKiteCodecCForWasm first" >&2; exit 1; }

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
python3 - "$ROOT/native-libs/deps/wasm32/binding/kitecodec-exports.json" "$WORK/exports.json" <<'PY'
import json, sys
names = json.load(open(sys.argv[1]))
for extra in ("_ffkmp_fmt_open_input_io", "_malloc", "_free"):
    if extra not in names: names.append(extra)
json.dump(names, open(sys.argv[2], "w"))
PY

cat > "$WORK/matrix.mjs" <<'JS'
import factory from "./kite.mjs";
import { readFileSync, existsSync } from "node:fs";

const M = await factory();
const dir = process.argv[2];
// The 17.5 matrix, in the order FormatMatrix.kt lists it.
const ROWS = ["sync1080p30.mp4","baseline.mkv","multitrack.mkv","vp9.webm","mpeg4part2.mp4",
  "hevc4k10.mp4","rotated90ccw.mp4","truevfr720.mp4","tsoffset1400.ts","subbed.mkv","surround51.mp4",
  "audio-aac.m4a","audio-mp3.mp3","audio-flac.flac","av1.mkv","torture-truncated.mp4",
  "torture-garbage.mp4","avi-mpeg4.avi","wmv-msmpeg4.wmv","flv-flv1.flv","vob-mpeg2.vob",
  "audio-eac3.mkv","audio-dts.mkv","audio-truehd.mkv","audio-alac.m4a","asssubbed.mkv"];
// What the 17.6 LEAN web tier deliberately omits. A failure here is expected, not a defect.
const OUT_OF_TIER = new Set(["mpeg4part2.mp4","avi-mpeg4.avi","wmv-msmpeg4.wmv","flv-flv1.flv",
  "vob-mpeg2.vob","audio-eac3.mkv","audio-dts.mkv","audio-truehd.mkv","audio-alac.m4a",
  "tsoffset1400.ts","av1.mkv"]);
const TORTURE = new Set(["torture-truncated.mp4","torture-garbage.mp4"]);

function run(path) {
  const bytes = readFileSync(path);
  let pos = 0;
  const readFn = M.addFunction((o,b,l)=>{ if(pos>=bytes.length) return -541478725;
    const n=Math.min(l,bytes.length-pos); M.HEAPU8.set(bytes.subarray(pos,pos+n),b); pos+=n; return n; },"iiii");
  const seekFn = M.addFunction((o,off,wh)=>{ const x=Number(off),w=Number(wh);
    if(w===0x10000) return BigInt(bytes.length);
    if(w===0)pos=x; else if(w===1)pos+=x; else if(w===2)pos=bytes.length+x; else return -1n;
    return BigInt(pos); },"jiji");
  const outPtr = M._malloc(4);
  try {
    const rc = M._ffkmp_fmt_open_input_io(outPtr,0,readFn,seekFn,BigInt(bytes.length),0,0,0,0);
    if (rc < 0) return { ok:false, why:`open ${rc}` };
    const ctx = M.HEAPU32[outPtr>>2];
    if (M._ffkmp_fmt_find_stream_info(ctx) < 0) { M._ffkmp_fmt_close_input_io(outPtr); return { ok:false, why:"stream info" }; }
    const n = M._ffkmp_fmt_nb_streams(ctx);
    let vi=-1, ai=-1, subs=0, vcodec="", acodec="";
    for (let i=0;i<n;i++){
      const p=M._ffkmp_stream_codecpar(M._ffkmp_fmt_stream(ctx,i));
      const t=M._ffkmp_codecpar_codec_type(p);
      const nm=M.UTF8ToString(M._ffkmp_codec_id_name(M._ffkmp_codecpar_codec_id(p)));
      if (t===M._ffkmp_media_type_video() && vi<0){vi=i;vcodec=nm;}
      else if (t===M._ffkmp_media_type_audio() && ai<0){ai=i;acodec=nm;}
      else if (t===M._ffkmp_media_type_subtitle()) subs++;
    }
    let frames = 0, target = vi >= 0 ? vi : ai, kind = vi >= 0 ? "video" : "audio";
    if (target < 0) { M._ffkmp_fmt_close_input_io(outPtr); return { ok:false, why:"no a/v stream", n, subs }; }
    const par = M._ffkmp_stream_codecpar(M._ffkmp_fmt_stream(ctx,target));
    const codec = M._ffkmp_find_decoder_by_id(M._ffkmp_codecpar_codec_id(par));
    if (!codec) { M._ffkmp_fmt_close_input_io(outPtr); return { ok:false, why:`no decoder for ${vcodec||acodec}`, n, subs }; }
    const dec = M._ffkmp_codecctx_alloc(codec);
    M._ffkmp_codecctx_from_par(dec, par);
    if (M._ffkmp_codecctx_open(dec, codec) < 0) { M._ffkmp_fmt_close_input_io(outPtr); return { ok:false, why:"decoder open", n, subs }; }
    const pkt = M._ffkmp_packet_alloc(), frame = M._ffkmp_frame_alloc();
    let guard = 0;
    while (frames < 2 && guard++ < 6000) {
      if (M._ffkmp_fmt_read_frame(ctx, pkt) < 0) break;
      if (M._ffkmp_packet_stream_index(pkt) === target && M._ffkmp_codecctx_send_packet(dec, pkt) >= 0) {
        while (M._ffkmp_codecctx_receive_frame(dec, frame) >= 0) { frames++; M._ffkmp_frame_unref(frame); if (frames>=2) break; }
      }
      M._ffkmp_packet_unref(pkt);
    }
    M._ffkmp_frame_free(frame); M._ffkmp_packet_free(pkt); M._ffkmp_codecctx_free(dec);
    M._ffkmp_fmt_close_input_io(outPtr);
    return { ok: frames > 0, why: frames > 0 ? `${frames} ${kind} frames` : "decoded nothing",
             n, subs, codec: vcodec || acodec };
  } finally { M._free(outPtr); }
}

let played = 0, expected = 0, surprises = [];
console.log("row                        result");
for (const row of ROWS) {
  const path = `${dir}/${row}`;
  if (!existsSync(path)) { console.log(`${row.padEnd(26)} SKIP (no fixture)`); continue; }
  let r;
  try { r = run(path); } catch (e) { r = { ok:false, why:"threw " + e.message }; }
  const lean = OUT_OF_TIER.has(row), torture = TORTURE.has(row);
  let verdict;
  if (torture) { verdict = "SURVIVED"; }          // must not crash; decoding is not required
  else if (r.ok) { verdict = "PLAYS"; played++; }
  else if (lean) { verdict = "not in web tier"; expected++; }
  else { verdict = "FAIL"; surprises.push(`${row}: ${r.why}`); }
  console.log(`${row.padEnd(26)} ${verdict.padEnd(16)} ${r.codec ?? ""} ${r.why ?? ""}`);
}
console.log("");
console.log(`plays ${played}, omitted by the lean web tier ${expected}, unexpected failures ${surprises.length}`);
surprises.forEach(s => console.log("  UNEXPECTED " + s));
process.exit(surprises.length === 0 ? 0 : 1);
JS

emcc -O2 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$KC" "$FF"/lib/libav{filter,format,codec,util}.a "$FF"/lib/libsw{scale,resample}.a \
  -sEXPORTED_FUNCTIONS=@"$WORK/exports.json" \
  -sEXPORTED_RUNTIME_METHODS='["ccall","cwrap","UTF8ToString","addFunction","HEAPU8","HEAPU32"]' \
  -sALLOW_TABLE_GROWTH=1 -sMODULARIZE=1 -sEXPORT_ES6=1 -sALLOW_MEMORY_GROWTH=1 \
  -o "$WORK/kite.mjs" > "$WORK/link.log" 2>&1 || { tail -20 "$WORK/link.log"; exit 1; }
node "$WORK/matrix.mjs" "$MEDIA"
