# Licensing

KiteCodec's own Kotlin code is **Apache-2.0**. The FFmpeg it links against is not. FFmpeg is **LGPL-2.1 or later**, or **GPL** when built with `--enable-gpl`. When you ship an app that embeds KiteCodec, the FFmpeg license travels with your binary, and it carries obligations. This page is the practical guide to meeting them.

!!! warning "Not legal advice"
    This page summarizes the obligations as they are commonly understood. It also explains how KiteCodec's build outputs help you meet them. It is not legal advice. For a commercial product, have a lawyer review your distribution plan.

## The two flavors

You choose the license when FFmpeg is built, not in Kotlin code:

| Flavor | Configure | Effective license | libx264 / libx265 |
|---|---|---|---|
| **LGPL** (default) | no `--enable-gpl` | LGPL-2.1+ | no |
| **GPL** (opt-in) | `--enable-gpl --enable-version3` | **GPL-3.0** | yes |

`buildFFmpegFor<Target>` produces the LGPL flavor. The GPL flavor comes from the `buildFFmpegFor<Target>Gpl` task variants plus `-Pkitecodec.ffmpeg.license=gpl`. The GPL flavor also passes `--enable-version3`, so the effective license of the combined work is GPL version 3, not "GPL-2.0+" generically. [Platform support](platforms.md#licensing) explains how to select a flavor.

## LGPL obligations when you distribute

The LGPL flavor does **not** make your app open source. It does obligate you, whenever you distribute the app to others, to do four things.

1. **Ship the license text.** Include FFmpeg's `COPYING.LGPLv2.1` and its `LICENSE.md`, which lists the per-component terms. An about screen, a bundled `licenses/` directory, or an oss-attribution page all work.
2. **Tell users FFmpeg is in there** and that it is LGPL-licensed, with a pointer to its source.
3. **Offer the FFmpeg source code.** You must offer the *complete corresponding source* of the exact FFmpeg you built, including your own modifications. KiteCodec's FFmpeg releases attach the exact source tarball (`ffmpeg-<version>-source.tar.gz`) next to the binary zips. Pointing at that release asset satisfies the offer, and it does not depend on a third-party URL staying alive. A URL to the upstream tag or commit also works in practice. The conservative reading of the license says the offer must remain valid, so host or link a copy that will.
4. **Let users relink**, under LGPL-2.1 §6. Users must be able to substitute a modified FFmpeg and run your app with it. How hard that is depends on how you link.

### Static and dynamic linking under §6

- **Dynamic linking** satisfies §6 naturally, whether you link system dylibs and `.so` files or FFmpeg dylibs you bundle yourself. The user replaces the library file. **Prefer dynamic linking where it is practical.** This is what KiteCodec's system-FFmpeg mode does.
- **Static linking** (KiteCodec's vendored `.a` builds) still complies, but only if you provide the material needed to relink. That means your app's object files, or an equivalent mechanism, so a user can produce a working binary against their own FFmpeg. Most vendors meet this by offering the linkable object code on request.

!!! warning "The App Store problem"
    Static linking plus the iOS App Store is the hardest case. Even if you offer object files, a user cannot practically install a relinked binary on a stock iPhone. Whether that satisfies §6 is disputed, and this is the argument that historically kept VLC out of the App Store. Positions differ, and many apps do ship LGPL code statically linked with an object-file offer. If you want to be conservative on Apple platforms, keep FFmpeg as a dynamically loaded framework inside your app bundle, so it can at least be replaced in the bundle. Take real legal advice before you ship.

### What the KiteCodec release zips include

The prebuilt FFmpeg archives on KiteCodec's GitHub Releases are named `ffmpeg-<version>-<license>-<triple>.zip` and are consumed by the [Gradle plugin](gradle-plugin.md). Each one is packaged for compliance. Next to `include/` and `lib/`, every zip carries:

- `COPYING.LGPLv2.1`, always. The `gpl` zips add `COPYING.GPLv2` and `COPYING.GPLv3`.
- `LICENSE.md` from the FFmpeg source tree, which is the per-component license map.
- `BUILD-INFO.txt`, which records the FFmpeg tag and commit, the full `./configure` line, the target, the license profile, and the source-code URL for that exact build.

The release itself also carries `ffmpeg-<version>-source.tar.gz`. That is the exact FFmpeg source those binaries were built from, so the source offer is self-contained on the same page as the binaries.

Ship the license texts onward with your app. Use `BUILD-INFO.txt` plus the attached source tarball to satisfy the source offer. Together they identify and provide the complete corresponding source.

## GPL flavor restrictions

The GPL flavor is different in kind: linking it makes the **whole combined work GPL-3.0**. If you distribute that work, then:

- your application's full source code must be available under a GPL-compatible license,
- you cannot use App Store, closed-source or proprietary distribution,
- open-source apps, server-side tools and internal tools are fine. The GPL's obligations trigger on *distribution*, so purely internal or server use does not require releasing source to the world.

If any of that is a problem, stay on the LGPL flavor. Use hardware encoders or `libsvtav1` instead of x264 and x265.

## Third-party components

The FFmpeg build is not only FFmpeg. The desktop profiles ([`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt)) enable these external libraries. Each has its own license that you must also pass through.

| Component | License | Flavor |
|---|---|---|
| FFmpeg (libav\*) | LGPL-2.1+ (GPL parts only in the GPL flavor) | both |
| SVT-AV1 (`libsvtav1`) | BSD-3-Clause-Clear + Alliance for Open Media patent license | both |
| libvpx | BSD-3-Clause | both |
| libaom | BSD-2-Clause + Alliance for Open Media patent license | both |
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

The Android profile enables none of these external libraries. It is FFmpeg (LGPL) plus MediaCodec plus zlib only.

Permissive components (BSD, MIT, ISC) only require you to reproduce their license text and copyright notice. The LGPL ones, libmp3lame and FriBidi, carry the same obligations as FFmpeg above.

## Patents: a separate question

Everything above is about **copyright**. Patents are a different layer, and complying with the LGPL or the GPL does nothing for them. A copyright license is not a patent grant.

- **Software H.264 and HEVC decoding** is in every KiteCodec FFmpeg profile. Active patent pools cover it in some countries: Via LA for H.264, and Access Advance, Via LA and others for HEVC. Whether a given app needs a pool license depends on what it does, where it ships, and how many users it has. **That check is the app distributor's responsibility, not KiteCodec's.** Typical pool terms only start charging above significant unit volumes, so small and non-commercial apps are rarely affected. A large commercial product should ask a lawyer.
- **Hardware codecs** (VideoToolbox on Apple platforms, MediaCodec on Android) are usually covered by the patent license the device vendor already pays for. Use them where they are available. They are also faster.
- **AV1, VP8, VP9, Opus and FLAC** are intended to be royalty-free and are the safest software-codec choice. Note that Sisvel operates a patent pool that claims to read on AV1 and VP9. The Alliance for Open Media disputes this and runs a legal defense program. "Royalty-free" here is a strongly defended position, not a court-settled guarantee.
- **MP3 is patent-free.** The last patents expired in 2017.

A practical summary for a commercial product: use hardware decode where you can, choose AV1 or Opus for software encode, and get real legal advice before you ship software H.264 or HEVC to a large paying audience.

## Practical checklist

Before you ship an app that embeds KiteCodec:

- [ ] Know your flavor: LGPL (default) or GPL (`-Pkitecodec.ffmpeg.license=gpl`). If GPL, confirm your whole app is GPL-3.0-compatible. If it is not, switch flavors.
- [ ] Bundle the license texts: `COPYING.LGPLv2.1`, FFmpeg's `LICENSE.md`, and notices for the third-party components above. Add `COPYING.GPLv3` for the GPL flavor.
- [ ] State in your app's about or licenses screen that it uses FFmpeg and the listed components.
- [ ] Provide the source offer. Link or host the exact FFmpeg source your build used. `BUILD-INFO.txt` in the release zips records the tag, the commit, and the configure line.
- [ ] Decide your §6 story: dynamic linking is easiest, or static linking plus an offer of relinkable object files. On Apple platforms, consider the App Store problem above.
- [ ] If you ship software H.264 or HEVC decode in a large commercial app, read the [patent question](#patents-a-separate-question). Otherwise stay with hardware decode and AV1 or Opus.
- [ ] Keep KiteCodec's own Apache-2.0 `LICENSE` and `NOTICE` in your attribution set.

## Related

- [Platform support](platforms.md#licensing): choosing the flavor, and per-platform encoder guidance.
- [Gradle plugin](gradle-plugin.md): how the compliance-packaged prebuilt FFmpeg zips are consumed.
- [About KiteCodec](about.md#license): the short version.
