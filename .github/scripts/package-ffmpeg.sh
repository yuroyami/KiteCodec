#!/usr/bin/env bash
# Packages one vendored FFmpeg build into a Release asset.
#
#   package-ffmpeg.sh <ffmpeg-version> <license> <target-triple>
#
# Zips the {include,lib} tree at native-libs/<license>/<triple> (NOT the parent dir, so the archive
# root is {include,lib} — exactly what the kitecodec Gradle plugin's unzip expects) into
# dist/ffmpeg-<version>-<license>-<triple>.zip plus a matching .sha256.
set -euo pipefail

version="${1:?ffmpeg version, e.g. n8.0}"
license="${2:?license: lgpl|gpl}"
triple="${3:?target triple, e.g. macos-arm64}"

src="native-libs/${license}/${triple}"
if [ ! -f "${src}/lib/libavformat.a" ]; then
  echo "::error::expected static libs at ${src}/lib/libavformat.a but found none" >&2
  exit 1
fi

mkdir -p dist
asset="ffmpeg-${version}-${license}-${triple}.zip"
dist_abs="$(cd dist && pwd)"

( cd "${src}" && zip -r -q "${dist_abs}/${asset}" include lib )
( cd dist && { sha256sum "${asset}" 2>/dev/null || shasum -a 256 "${asset}"; } > "${asset}.sha256" )

echo "packaged dist/${asset}"
cat "dist/${asset}.sha256"
