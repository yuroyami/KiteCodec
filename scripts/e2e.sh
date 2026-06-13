#!/usr/bin/env bash
# End-to-end pipeline check: generate a known clip with the system ffmpeg CLI,
# push it through the KiteCodec sample binary, assert the output with ffprobe.
#
# Usage: scripts/e2e.sh <path-to-sample-binary> [ffmpeg-cmd] [ffprobe-cmd]
set -euo pipefail

KEXE=${1:?usage: e2e.sh <path-to-sample-binary> [ffmpeg] [ffprobe]}
FFMPEG=${2:-ffmpeg}
FFPROBE=${3:-ffprobe}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== generate 3s A/V test clip (keyframe every second)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=duration=3:size=320x240:rate=30" \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:v libx264 -pix_fmt yuv420p -g 30 -keyint_min 30 -c:a aac -shortest "$WORK/in.mp4"

echo "== kitecodec probe"
"$KEXE" probe "$WORK/in.mp4"

echo "== kitecodec transcode A/V with filter chain"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out.mp4" "scale=160:120,eq=brightness=0.05,format=yuv420p"

streams=$("$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/out.mp4")
echo "$streams"
echo "$streams" | grep -q "h264,video" || { echo "FAIL: no h264 video stream in output"; exit 1; }
echo "$streams" | grep -q "aac,audio"  || { echo "FAIL: no aac audio stream in output"; exit 1; }

width=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$WORK/out.mp4")
[ "$width" = "160" ] || { echo "FAIL: output width $width != 160 (scale filter not applied?)"; exit 1; }

dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/out.mp4")
awk -v d="$dur" 'BEGIN { exit !(d > 2.5 && d < 3.5) }' \
  || { echo "FAIL: output duration $dur not ~3s (timestamp bug?)"; exit 1; }

echo "== kitecodec transcode video-only (-an)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_an.mp4" "scale=160:120,format=yuv420p" -an
audio_streams=$("$FFPROBE" -v error -select_streams a -show_entries stream=index -of csv=p=0 "$WORK/out_an.mp4" | grep -c . || true)
[ "$audio_streams" = "0" ] || { echo "FAIL: audio stream present after -an"; exit 1; }

echo "== kitecodec transcode with audio stream-copy (-acopy)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_acopy.mp4" "scale=160:120,format=yuv420p" -acopy
acopy_streams=$("$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/out_acopy.mp4")
echo "$acopy_streams" | grep -q "aac,audio" || { echo "FAIL: copied aac stream missing"; exit 1; }
# Stream copy must preserve the source audio bit-exactly — compare extracted packets.
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/in.mp4" -map 0:a:0 -c copy "$WORK/a_src.aac"
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/out_acopy.mp4" -map 0:a:0 -c copy "$WORK/a_copy.aac"
cmp -s "$WORK/a_src.aac" "$WORK/a_copy.aac" || { echo "FAIL: -acopy audio differs from source (not bit-exact)"; exit 1; }

echo "== kitecodec remux mp4 → mkv (full stream copy)"
"$KEXE" remux "$WORK/in.mp4" "$WORK/remuxed.mkv"
remux_streams=$("$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/remuxed.mkv")
echo "$remux_streams"
echo "$remux_streams" | grep -q "h264,video" || { echo "FAIL: remux lost video stream"; exit 1; }
echo "$remux_streams" | grep -q "aac,audio"  || { echo "FAIL: remux lost audio stream"; exit 1; }
remux_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/remuxed.mkv")
awk -v d="$remux_dur" 'BEGIN { exit !(d > 2.5 && d < 3.5) }' \
  || { echo "FAIL: remux duration $remux_dur not ~3s"; exit 1; }

echo "== kitecodec transcode with trim (--ss 1 --to 2, frame-exact)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_trim.mp4" "scale=160:120,format=yuv420p" --ss 1 --to 2 --title "trimmed by kitecodec"
trim_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/out_trim.mp4")
awk -v d="$trim_dur" 'BEGIN { exit !(d > 0.8 && d < 1.3) }' \
  || { echo "FAIL: trimmed duration $trim_dur not ~1s"; exit 1; }
trim_start=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=start_time -of csv=p=0 "$WORK/out_trim.mp4")
awk -v s="$trim_start" 'BEGIN { exit !(s < 0.2 && s > -0.2) }' \
  || { echo "FAIL: trimmed output starts at $trim_start, expected ~0 (ts rebase broken)"; exit 1; }
trim_title=$("$FFPROBE" -v error -show_entries format_tags=title -of csv=p=0 "$WORK/out_trim.mp4")
[ "$trim_title" = "trimmed by kitecodec" ] || { echo "FAIL: metadata title '$trim_title'"; exit 1; }

echo "== kitecodec remux with trim (keyframe-snapped)"
"$KEXE" remux "$WORK/in.mp4" "$WORK/remux_trim.mp4" --ss 1 --to 2
rtrim_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/remux_trim.mp4")
awk -v d="$rtrim_dur" 'BEGIN { exit !(d > 0.8 && d < 1.6) }' \
  || { echo "FAIL: remux-trim duration $rtrim_dur not in keyframe-snap range"; exit 1; }

echo "== kitecodec thumbnail (jpg + png)"
"$KEXE" thumbnail "$WORK/in.mp4" "$WORK/thumb.jpg" 1.5
"$KEXE" thumbnail "$WORK/in.mp4" "$WORK/thumb.png" 1.5
for img in thumb.jpg thumb.png; do
  [ -s "$WORK/$img" ] || { echo "FAIL: $img empty"; exit 1; }
  codec=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$WORK/$img")
  case "$img:$codec" in
    thumb.jpg:mjpeg|thumb.png:png) ;;
    *) echo "FAIL: $img decoded as '$codec'"; exit 1;;
  esac
done

echo "== kitecodec audio-only transcode (no video input)"
"$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=330:duration=2" -c:a pcm_s16le "$WORK/tone.wav"
"$KEXE" transcode "$WORK/tone.wav" "$WORK/tone.m4a"
ao_streams=$("$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/tone.m4a")
echo "$ao_streams" | grep -q "aac,audio" || { echo "FAIL: audio-only output missing aac"; exit 1; }
echo "$ao_streams" | grep -q "video" && { echo "FAIL: audio-only output has video"; exit 1; }
ao_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/tone.m4a")
awk -v d="$ao_dur" 'BEGIN { exit !(d > 1.7 && d < 2.3) }' \
  || { echo "FAIL: audio-only duration $ao_dur not ~2s"; exit 1; }

echo "== kitecodec subtitle passthrough (mkv)"
printf '1\n00:00:00,500 --> 00:00:02,000\nkitecodec was here\n' > "$WORK/subs.srt"
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/in.mp4" -i "$WORK/subs.srt" \
  -map 0 -map 1 -c copy -c:s srt "$WORK/in_subs.mkv"
"$KEXE" transcode "$WORK/in_subs.mkv" "$WORK/out_subs.mkv" "scale=160:120,format=yuv420p" -scopy
echo "$("$FFPROBE" -v error -show_entries stream=codec_type -of csv=p=0 "$WORK/out_subs.mkv")" \
  | grep -q "subtitle" || { echo "FAIL: subtitle stream lost"; exit 1; }

if [ "$(uname)" = "Darwin" ]; then
  echo "== kitecodec VideoToolbox hardware encode (-vt)"
  "$KEXE" transcode "$WORK/in.mp4" "$WORK/out_vt.mp4" "scale=320:240,format=yuv420p" -vt -an
  vt_codec=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$WORK/out_vt.mp4")
  [ "$vt_codec" = "h264" ] || { echo "FAIL: VT output codec '$vt_codec'"; exit 1; }
fi

echo "e2e OK (A/V=${dur}s, remux=${remux_dur}s, trim=${trim_dur}s, audio-only=${ao_dur}s)"
