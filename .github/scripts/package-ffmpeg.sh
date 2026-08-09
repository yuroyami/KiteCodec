#!/usr/bin/env bash
# Packages one vendored FFmpeg build into a Release asset.
#
#   package-ffmpeg.sh <ffmpeg-version> <license> <target-triple> [ffmpeg-source-dir]
#
# Zips the {include,lib} tree at native-libs/<license>/<triple> (NOT the parent dir, so the archive
# root is {include,lib}, exactly what the kitecodec Gradle plugin's unzip expects) into
# dist/ffmpeg-<version>-<license>-<triple>.zip plus a matching .sha256.
#
# LGPL compliance: every zip additionally carries, at the archive root,
#   - COPYING.LGPLv2.1 (always) and COPYING.GPLv2 + COPYING.GPLv3 (gpl profile),
#   - LICENSE.md from the FFmpeg source tree,
#   - a generated BUILD-INFO.txt (tag, commit, configure line, build date, target,
#     license profile, source-code URL).
# The FFmpeg source tree defaults to vendor/ffmpeg — where the release workflow clones it.
set -euo pipefail

version="${1:?ffmpeg version, e.g. n8.0}"
license="${2:?license: lgpl|gpl}"
triple="${3:?target triple, e.g. macos-arm64}"
ffmpeg_src="${4:-vendor/ffmpeg}"

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

# The full ./configure invocation is the first line of ffbuild/config.log. BuildFFmpegTask
# configures out-of-tree under <src>/build/<license>/<triple>, so look there first, then fall
# back to an in-tree configure. (<prefix>/share/ffmpeg does not exist for these static builds,
# so config.log is the reliable source.)
configure_line="(unavailable — no ffbuild/config.log found)"
for log in "${ffmpeg_src}/build/${license}/${triple}/ffbuild/config.log" "${ffmpeg_src}/ffbuild/config.log"; do
  if [ -f "${log}" ]; then
    configure_line="$(head -n 1 "${log}" | sed 's/^# *//')"
    break
  fi
done

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

# --- desktop only: bundle third-party static libs + LINK-FLAGS.txt ---------------------------
# The six libav* .a files reference symbols from the third-party encoder/text stack (svt-av1,
# vpx, aom, opus, mp3lame, webp, freetype/harfbuzz/fribidi/ass, see BuildFFmpegTask's
# desktopBaseArgs/desktopGplArgs), which on the build runner come from brew/apt SHARED installs.
# A consumer on a clean machine would hit undefined symbols at final link, so bundle the STATIC
# (.a) versions of those libs into the zip's lib/ dir and write lib/LINK-FLAGS.txt (one linker
# flag per line) describing the extra -l set that (triple, flavour) needs.
#
# KEEP IN SYNC with PrebuiltLinkFlags.kt in kitecodec-gradle-plugin. The plugin hardcodes the
# same per-(platform, flavour) flag list because linkerOpts are fixed at configuration time,
# before this zip (and its LINK-FLAGS.txt) has been fetched.
#
# HONESTY NOTE, this block is CI-verified, not locally verifiable:
#   - Some brew formulas / apt -dev packages may ship only shared libs. The hard-fail below names
#     every missing .a so a CI run surfaces the gap; the fix is building that dep statically from
#     source in release-binaries.yml. Do NOT silently fall back to the shared lib.
#   - Transitive static deps are a known follow-up: freetype may pull libpng/brotli, harfbuzz
#     graphite2, libass fontconfig/libunibreak (Linux). If a consumer link reports undefined
#     symbols from those, extend BOTH this bundle/flag list and PrebuiltLinkFlags.kt.
# Android is skipped: its profile links no third-party libs (MediaCodec is a platform service).
case "${triple}" in
  macos-*|linux-*)
    mkdir -p "${stage}/lib"

    # Static archives to bundle. webp is split across three archives since libwebp 1.3
    # (libwebp + libwebpmux for FFmpeg's anim encoder + libsharpyuv, which libwebp depends on).
    static_libs=(
      libSvtAv1Enc.a libvpx.a libaom.a libopus.a libmp3lame.a
      libwebpmux.a libwebp.a libsharpyuv.a
      libass.a libharfbuzz.a libfreetype.a libfribidi.a
      # Transitive deps of the text stack, and they must come after it: freetype's
      # embedded-PNG bitmaps call into libpng, harfbuzz's shaping backend into graphite2.
      libpng16.a libgraphite2.a
    )
    if [ "${license}" = "gpl" ]; then
      static_libs+=(libx264.a libx265.a)
      if [ "${triple%%-*}" = "linux" ]; then
        static_libs+=(libnuma.a)  # x265's Ubuntu build links libnuma
      fi
    fi

    # Search roots, per the runner's package manager layout.
    search_dirs=()
    case "${triple}" in
      macos-*)
        for formula in svt-av1 libvpx aom opus lame webp freetype harfbuzz fribidi libass x264 x265; do
          prefix="$(brew --prefix "${formula}" 2>/dev/null || true)"
          if [ -n "${prefix}" ] && [ -d "${prefix}/lib" ]; then
            search_dirs+=("${prefix}/lib")
          fi
        done
        ;;
      linux-*)
        for dir in /usr/lib/x86_64-linux-gnu /usr/lib/aarch64-linux-gnu /usr/lib /usr/local/lib; do
          if [ -d "${dir}" ]; then
            search_dirs+=("${dir}")
          fi
        done
        ;;
    esac

    missing=()
    for lib in "${static_libs[@]}"; do
      found=""
      for dir in ${search_dirs[@]+"${search_dirs[@]}"}; do
        if [ -f "${dir}/${lib}" ]; then
          found="${dir}/${lib}"
          break
        fi
      done
      if [ -n "${found}" ]; then
        cp "${found}" "${stage}/lib/"
      else
        missing+=("${lib}")
      fi
    done
    if [ "${#missing[@]}" -gt 0 ]; then
      echo "::error::static third-party libs missing on this runner for ${triple}/${license}: ${missing[*]}" >&2
      echo "::error::the zip would NOT be link-anywhere — build/install the static variants before packaging (see release-binaries.yml)" >&2
      exit 1
    fi

    # One linker flag per line, naming the extra -l set a consumer's final link needs for this
    # (triple, flavour). Dependents before dependencies (GNU ld resolves archives left→right).
    # -lz/-lbz2 (and -liconv on macOS) come from the OS/SDK; the C++ runtime backs x265.
    {
      printf '%s\n' -lSvtAv1Enc -lvpx -laom -lopus -lmp3lame \
        -lwebpmux -lwebp -lsharpyuv \
        -lass -lharfbuzz -lfreetype -lfribidi \
        -lpng16 -lgraphite2
      if [ "${license}" = "gpl" ]; then
        printf '%s\n' -lx264 -lx265
      fi
      printf '%s\n' -lz -lbz2
      case "${triple}" in
        macos-*)
          # harfbuzz's CoreText backend and libass' CoreGraphics rasterisation are compiled into
          # the static archives, and the videotoolbox encoders need the media frameworks. A shared
          # build resolved these through its own dylib dependencies; a static one must name them.
          printf '%s\n' -liconv -lc++ \
            -framework CoreGraphics -framework CoreText -framework CoreFoundation \
            -framework CoreMedia -framework CoreVideo -framework VideoToolbox \
            -framework AudioToolbox
          ;;
        linux-*)
          if [ "${license}" = "gpl" ]; then
            printf '%s\n' -lnuma -lstdc++
          fi
          ;;
      esac
    } > "${stage}/lib/LINK-FLAGS.txt"

    echo "bundled third-party static libs: ${static_libs[*]} (+ lib/LINK-FLAGS.txt)"
    ;;
esac

# --- zip + checksum -------------------------------------------------------------------------
mkdir -p dist
asset="ffmpeg-${version}-${license}-${triple}.zip"
dist_abs="$(cd dist && pwd)"
rm -f "${dist_abs}/${asset}"

( cd "${src}" && zip -r -q "${dist_abs}/${asset}" include lib )
( cd "${stage}" && zip -q "${dist_abs}/${asset}" "${legal_files[@]}" BUILD-INFO.txt )
# Desktop only: merge the staged third-party static libs + LINK-FLAGS.txt into the zip's lib/.
if [ -d "${stage}/lib" ]; then
  ( cd "${stage}" && zip -r -q "${dist_abs}/${asset}" lib )
fi
( cd dist && { sha256sum "${asset}" 2>/dev/null || shasum -a 256 "${asset}"; } > "${asset}.sha256" )

echo "packaged dist/${asset} (+ ${legal_files[*]} BUILD-INFO.txt)"
cat "dist/${asset}.sha256"
