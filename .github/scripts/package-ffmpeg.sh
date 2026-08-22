#!/usr/bin/env bash
# Packages one vendored FFmpeg build into a Release asset.
#
#   package-ffmpeg.sh <ffmpeg-version> <license> <target-triple> [ffmpeg-source-dir]
#
# Zips the {include,lib} tree at native-libs/<license>/<triple> (NOT the parent dir, so the archive
# root is {include,lib}, exactly what the kitecodec Gradle plugin's unzip expects) into
# dist/ffmpeg-<version>-<license>[-<flavour>]-<triple>.zip plus a matching .sha256. Every profile
# is portable (2026-08-22): nothing is bundled from the runner; the optional dav1d archive is
# already inside the tree, put there by BuildFFmpegTask.
#
# LGPL compliance: every zip additionally carries, at the archive root,
#   - COPYING.LGPLv2.1 (always) and COPYING.GPLv2 + COPYING.GPLv3 (gpl profile),
#   - LICENSE.md from the FFmpeg source tree,
#   - a generated BUILD-INFO.txt (tag, commit, configure line, build date, target,
#     license profile, source-code URL).
# The FFmpeg source tree defaults to vendor/ffmpeg, where the release workflow clones it.
set -euo pipefail

version="${1:?ffmpeg version, e.g. n8.0}"
license="${2:?license: lgpl|gpl}"
triple="${3:?target triple, e.g. macos-arm64}"
ffmpeg_src="${4:-vendor/ffmpeg}"
# Optional 5th argument: a flavour suffix such as `dav1d`, which becomes part of the asset name
# (ffmpeg-<version>-<license>-dav1d-<triple>.zip). The plugin picks the flavour that matches the
# consumer's `ffmpeg.dav1d` toggle, so both must be published for that toggle to be satisfiable.
flavour="${5:-}"

src="native-libs/${license}/${triple}"

# --- validate the build output --------------------------------------------------------------
# All six libav* archives must be present: a partial `make install` must never ship.
for lib in libavcodec libavformat libavutil libavfilter libswscale libswresample; do
  if [ ! -f "${src}/lib/${lib}.a" ]; then
    echo "::error::expected static lib ${src}/lib/${lib}.a but found none" >&2
    exit 1
  fi
done

# Sanity-check the installed headers really are the requested FFmpeg version. Tag n8.0 reports
# FFMPEG_VERSION as either "n8.0" (git describe) or "8.0[.x]" (release tarball), so accept both.
ffversion_h="${src}/include/libavutil/ffversion.h"
if [ ! -f "${ffversion_h}" ]; then
  echo "::error::missing ${ffversion_h}, incomplete FFmpeg install?" >&2
  exit 1
fi
if ! grep -q "FFMPEG_VERSION \"${version}" "${ffversion_h}" \
  && ! grep -q "FFMPEG_VERSION \"${version#n}" "${ffversion_h}"; then
  echo "::error::${ffversion_h} does not match expected FFmpeg version ${version}:" >&2
  grep "FFMPEG_VERSION" "${ffversion_h}" >&2 || true
  exit 1
fi

# BuildFFmpegTask installs the normalized configure invocation with the tree it describes. This
# installed record is the only provenance source: a vendor checkout may contain an unrelated or
# stale ffbuild/config.log, especially when packaging a cross-compiled tree.
configure_record="${src}/lib/kitecodec/ffmpeg-configure.txt"
if [ ! -f "${configure_record}" ]; then
  echo "::error::missing installed configure evidence ${configure_record}" >&2
  exit 1
fi
configure_line="$(cat "${configure_record}")"
configure_line_count="$(wc -l < "${configure_record}" | tr -d '[:space:]')"
if [ ! -s "${configure_record}" ] \
  || [ "${configure_line_count}" != "1" ] \
  || ! grep -q '[^[:space:]]' "${configure_record}" \
  || [[ "${configure_line}" == *$'\n'* ]] \
  || [[ "${configure_line}" == *$'\r'* ]]; then
  echo "::error::installed configure evidence ${configure_record} must contain exactly one nonblank LF-terminated line" >&2
  exit 1
fi
if [[ "${configure_line}" == "(unavailable"* ]]; then
  echo "::error::installed configure evidence ${configure_record} contains the obsolete unavailable fallback" >&2
  exit 1
fi

# --- stage the license texts + build info ---------------------------------------------------
if [ ! -d "${ffmpeg_src}" ]; then
  echo "::error::FFmpeg source tree not found at ${ffmpeg_src} (needed for license texts + build info)" >&2
  exit 1
fi

legal_files=("COPYING.LGPLv2.1" "LICENSE.md")
if [ "${license}" = "gpl" ]; then
  legal_files+=("COPYING.GPLv2" "COPYING.GPLv3")
fi

stage="$(mktemp -d)"
trap 'rm -rf "${stage}"' EXIT
for f in "${legal_files[@]}"; do
  if [ ! -f "${ffmpeg_src}/${f}" ]; then
    echo "::error::license file ${ffmpeg_src}/${f} missing, cannot ship a compliant zip" >&2
    exit 1
  fi
  cp "${ffmpeg_src}/${f}" "${stage}/"
done

commit="$(git -C "${ffmpeg_src}" rev-parse HEAD 2>/dev/null || echo "unknown")"

cat > "${stage}/BUILD-INFO.txt" <<EOF
FFmpeg build info: KiteCodec vendored binaries
===============================================
FFmpeg version:   ${version}
Git commit:       ${commit}
Source code:      https://github.com/FFmpeg/FFmpeg/tree/${version}
Target:           ${triple}
License profile:  ${license}
Build date (UTC): $(date -u +"%Y-%m-%dT%H:%M:%SZ")
Configure:        ${configure_line}

FFmpeg is licensed under the LGPL v2.1 or later; builds with --enable-gpl are covered by the
GPL instead. See the bundled COPYING.* files and LICENSE.md. The complete corresponding source
code for this build is available at the source-code URL above, at the exact tag and commit
recorded in this file.
EOF

# --- self-containment check -----------------------------------------------------------------
# Every profile is PORTABLE (2026-08-22): no third-party stack is linked on any target, so there
# is nothing to bundle from the runner. The one optional third-party archive, the cross-built
# dav1d, is copied into the tree's lib/ by BuildFFmpegTask itself. Verify the flavour and the
# tree agree, both ways: a dav1d-flavoured zip without libdav1d.a would 404 the plugin's dav1d
# contract at the consumer's link, and a plain zip WITH it would violate it in reverse.
if [ "${flavour}" = "dav1d" ] && [ ! -f "${src}/lib/libdav1d.a" ]; then
  echo "::error::flavour is dav1d but ${src}/lib/libdav1d.a is missing (was the tree baked with -Pkitecodec.ffmpeg.dav1d=true?)" >&2
  exit 1
fi
if [ "${flavour}" != "dav1d" ] && [ -f "${src}/lib/libdav1d.a" ]; then
  echo "::error::flavour is plain but ${src}/lib/libdav1d.a is present; package it as the dav1d flavour or rebake without the switch" >&2
  exit 1
fi

# --- zip + checksum -------------------------------------------------------------------------
mkdir -p dist
asset="ffmpeg-${version}-${license}${flavour:+-${flavour}}-${triple}.zip"
dist_abs="$(cd dist && pwd)"
rm -f "${dist_abs}/${asset}"

( cd "${src}" && zip -r -q "${dist_abs}/${asset}" include lib )
( cd "${stage}" && zip -q "${dist_abs}/${asset}" "${legal_files[@]}" BUILD-INFO.txt )
( cd dist && { sha256sum "${asset}" 2>/dev/null || shasum -a 256 "${asset}"; } > "${asset}.sha256" )

echo "packaged dist/${asset} (+ ${legal_files[*]} BUILD-INFO.txt)"
cat "dist/${asset}.sha256"
