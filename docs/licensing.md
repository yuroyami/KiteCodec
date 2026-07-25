# Licensing

KiteCodec's own Kotlin code is **Apache-2.0**. The FFmpeg it links against is not: FFmpeg is **LGPL-2.1 or later**, or **GPL** when built with `--enable-gpl`. When you ship an app that embeds KiteCodec, the FFmpeg licence travels with your binary, and it comes with obligations. This page is the practical guide to meeting them.

!!! warning "Not legal advice"
    This page summarises the obligations as commonly understood and how KiteCodec's build outputs help you meet them. It is not legal advice. For a commercial product, have a lawyer review your distribution plan.

## The two flavours

The licence choice is made when FFmpeg is built, not in Kotlin code:

| Flavour | Configure | Effective licence | libx264 / libx265 |
|---|---|---|---|
| **LGPL** (default) | no `--enable-gpl` | LGPL-2.1+ | no |
| **GPL** (opt-in) | `--enable-gpl --enable-version3` | **GPL-3.0** | yes |

The LGPL flavour is what `buildFFmpegFor<Target>` produces; the GPL flavour comes from the `buildFFmpegFor<Target>Gpl` task variants plus `-Pkitecodec.ffmpeg.license=gpl`. Because the GPL flavour also passes `--enable-version3`, the effective licence of the combined work is GPL version 3 — not "GPL-2.0+" generically. See [Platform support → Licensing](platforms.md#licensing) for how to select a flavour.

## LGPL obligations when you distribute

Using the LGPL flavour does **not** make your app open source. It does obligate you, whenever you distribute the app to others, to:

1. **Ship the licence text.** Include FFmpeg's `COPYING.LGPLv2.1` (and its `LICENSE.md`, which lists the per-component terms) with your app — an about screen, a bundled `licenses/` directory, or an oss-attribution page all work.
2. **Tell users FFmpeg is in there** and that it is LGPL-licensed, with a pointer to its source.
3. **Offer the FFmpeg source code** — the *complete corresponding source* of the exact FFmpeg you built, including your modifications if any. KiteCodec's FFmpeg releases attach the exact source tarball (`ffmpeg-<version>-source.tar.gz`) next to the binary zips, so pointing at that release asset satisfies the offer without depending on a third-party URL staying alive. (A URL to the upstream tag/commit also works in practice, but the conservative reading of the licence says the offer must remain good — host or link a copy that will.)
4. **Let users relink** — LGPL-2.1 §6. Users must be able to swap in a modified FFmpeg and run your app with it. How hard that is depends on how you link:

### Static vs. dynamic linking and §6

- **Dynamic linking** (against system dylibs/so files, or FFmpeg dylibs you bundle) satisfies §6 naturally: the user replaces the library file. **Prefer dynamic linking where feasible.** This is what KiteCodec's system-FFmpeg mode does.
- **Static linking** (KiteCodec's vendored `.a` builds) still complies, but only if you provide the material to relink: your app's object files, or an equivalent mechanism, so a user can produce a working binary against their own FFmpeg. Most vendors meet this by offering the linkable object code on request.

!!! warning "The App Store tension"
    Static linking plus the iOS App Store is where LGPL gets uncomfortable. Even with object files offered, a user cannot practically re-install a relinked binary on a stock iPhone, and whether that satisfies §6 is contested — this is the argument that historically kept VLC out of the App Store. Positions differ (many apps do ship LGPL code statically linked with an object-file offer), but if you want to be conservative on Apple platforms: keep FFmpeg as a dynamically loaded framework inside your app bundle so it can at least be swapped in the bundle, and take real legal advice before shipping.

### What the KiteCodec release zips include

The prebuilt FFmpeg archives on KiteCodec's GitHub Releases (`ffmpeg-<version>-<license>-<triple>.zip`, consumed by the [Gradle plugin](gradle-plugin.md)) are packaged for compliance. Each zip carries, next to `include/` and `lib/`:

- `COPYING.LGPLv2.1` — always; plus `COPYING.GPLv2` and `COPYING.GPLv3` in the `gpl` zips,
- `LICENSE.md` from the FFmpeg source tree (the per-component licence map),
- `BUILD-INFO.txt` — the FFmpeg tag and commit, the full `./configure` line, the target, the licence profile, and the source-code URL for that exact build.

The release itself additionally carries `ffmpeg-<version>-source.tar.gz` — the exact FFmpeg source those binaries were built from — so the source offer is self-contained on the same page as the binaries.

Ship the licence texts onward with your app, and use `BUILD-INFO.txt` plus the attached source tarball to satisfy the source offer (together they identify and provide the complete corresponding source).

## GPL flavour restrictions

The GPL flavour is a different deal entirely: linking it makes the **whole combined work GPL-3.0**. That means, if you distribute:

- your application's full source code must be available under a GPL-compatible licence,
- no App Store / closed-source / proprietary distribution,
- fine for open-source apps, server-side and internal tools (the GPL's obligations trigger on *distribution* — purely internal or server use does not require releasing source to the world).

If any of that is a problem, stay on the LGPL flavour and use hardware encoders or `libsvtav1` instead of x264/x265.

## Third-party components

The FFmpeg build is not just FFmpeg. The desktop profiles ([`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt)) enable these external libraries, each with its own licence you must also pass through:

| Component | Licence | Flavour |
|---|---|---|
| FFmpeg (libav\*) | LGPL-2.1+ (GPL parts only in the GPL flavour) | both |
| SVT-AV1 (`libsvtav1`) | BSD-3-Clause-Clear + Alliance for Open Media patent licence | both |
| libvpx | BSD-3-Clause | both |
| libaom | BSD-2-Clause + Alliance for Open Media patent licence | both |
| libopus | BSD-3-Clause | both |
| libmp3lame | **LGPL-2.0+** (same shipping obligations as FFmpeg itself) | both |
| libwebp | BSD-3-Clause | both |
| FreeType | FTL (BSD-style, with attribution) / GPL-2.0 dual | both |
| HarfBuzz | MIT-style ("Old MIT") | both |
| FriBidi | **LGPL-2.1+** | both |
| libass | ISC | both |
| zlib / bzip2 | zlib / BSD-style | both |
| x264 | **GPL-2.0+** | GPL only |
| x265 | **GPL-2.0+** | GPL only |

The Android profile enables none of these external libraries — it is FFmpeg (LGPL) + MediaCodec + zlib only.

Permissive components (BSD/MIT/ISC) only require you to reproduce their licence text and copyright notice. The LGPL ones (libmp3lame, FriBidi) carry the same obligations as FFmpeg above.

## Patents — a separate question

Everything above is about **copyright**. Patents are a different layer, and complying with the LGPL/GPL does nothing for them: a copyright licence is not a patent grant.

The practical picture:

- **Software H.264 and HEVC decoding** — which every KiteCodec FFmpeg profile includes — is covered by active patent pools in some countries (Via LA for H.264; Access Advance, Via LA and others for HEVC). Whether a given app needs a pool licence depends on what it does, where it ships, and how many users it has. **That check is the app distributor's responsibility, not KiteCodec's** — typical pool terms only start charging above significant unit volumes, so small and non-commercial apps are rarely affected, but a large commercial product should ask a lawyer.
- **Hardware codecs** (VideoToolbox on Apple platforms, MediaCodec on Android) generally ride on the patent licence the device vendor already pays for. Prefer them where available — they are also faster.
- **AV1, VP8/VP9, Opus, FLAC** are the royalty-free-intended path and the safest software-codec choice. (Full disclosure: Sisvel operates a patent pool that claims to read on AV1 and VP9; the Alliance for Open Media disputes this and runs a legal defense program. "Royalty-free" is a strongly-defended position, not a court-settled guarantee.)
- **MP3 is patent-free** — the last patents expired in 2017.

Rule of thumb for a commercial product: hardware decode where you can, AV1/Opus for software encode, and real legal advice before shipping software H.264/HEVC to a large paying audience.

## Practical checklist

Before you ship an app embedding KiteCodec:

- [ ] Know your flavour: LGPL (default) or GPL (`-Pkitecodec.ffmpeg.license=gpl`). If GPL: is your whole app GPL-3.0-compatible? If not, stop and switch flavours.
- [ ] Bundle the licence texts: `COPYING.LGPLv2.1`, FFmpeg's `LICENSE.md`, and notices for the third-party components above (plus `COPYING.GPLv3` for the GPL flavour).
- [ ] State in your app's about/licences screen that it uses FFmpeg and the listed components.
- [ ] Provide the source offer: link (or host) the exact FFmpeg source your build used — `BUILD-INFO.txt` in the release zips records tag, commit, and configure line.
- [ ] Decide your §6 story: dynamic linking (easiest), or static linking plus an offer of relinkable object files. On Apple platforms, weigh the App Store tension above.
- [ ] Shipping software H.264/HEVC decode in a large commercial app? Check the [patent question](#patents-a-separate-question) — or stick to hardware decode and AV1/Opus.
- [ ] Keep KiteCodec's own Apache-2.0 `LICENSE` and `NOTICE` in your attribution set.

## Related

- [Platform support → Licensing](platforms.md#licensing): choosing the flavour, per-platform encoder guidance.
- [Gradle plugin](gradle-plugin.md): how the prebuilt (compliance-packaged) FFmpeg zips are consumed.
- [About → Licence](about.md#licence): the short version.
