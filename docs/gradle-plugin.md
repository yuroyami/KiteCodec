# Gradle plugin

`kitecodec-gradle-plugin` provisions the FFmpeg binaries that a KiteCodec Kotlin/Native consumer
links against, so the consumer does not build FFmpeg from source. The native `kitecodec-core` klib
contains **no FFmpeg bytes**. The plugin supplies them at build time and keeps the FFmpeg license
(LGPL or GPL) separate from KiteCodec's own Apache-2.0 code.

!!! warning "Not published, and `Prebuilt` has nothing to fetch"
    The plugin lives in the KiteCodec repository (`kitecodec-gradle-plugin/`) and is not on the Gradle Plugin Portal yet. Neither are the FFmpeg Release assets that `FFmpegSource.Prebuilt` downloads. The [README's release status](https://github.com/yuroyami/KiteCodec#release-status) is the single place that tracks what exists. Use `FFmpegSource.System` on a desktop host or a complete `FFmpegSource.Local` tree after local publication. The DSL below is the supported surface.

The local phone proof's regular Android actuals and unpublished JVM test harness are a separate
JNI/AAR path in this repository. Their source, host tests and local Android packaging model exist,
but there is no functional public JVM jar or Android AAR. Public JVM is a typed unavailable
placeholder in every scope. This plugin does not turn the local JNI proof into a JVM distribution.

## Apply and configure

For a Kotlin/Native consumer, apply the plugin alongside the Kotlin Multiplatform plugin, then
configure the `kitecodec { }` extension. The library dependency and plugin are both required for
that native link, as explained below.

```kotlin
import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource

plugins {
    kotlin("multiplatform")
    id("io.github.yuroyami.kitecodec") version "<version>"
}

kotlin {
    macosArm64()
    sourceSets.commonMain.dependencies {
        implementation("io.github.yuroyami:kitecodec-core:<version>")
    }
}

kitecodec {
    ffmpeg {
        version = "n8.0"                 // pinned FFmpeg release
        source  = FFmpegSource.Prebuilt  // Prebuilt (default) | System | Local | BuildFromSource
        license = FFmpegLicense.LGPL     // REQUIRED. There is no default; see below.
        // localRoot = layout.projectDirectory.dir("ffmpeg") // required for Local
    }
}
```

For every Kotlin/Native target you enable, the plugin maps the target to the matching FFmpeg build.
It makes sure the binaries are present before the native link runs, and it adds the `-L<libdir>`
linker flag so the link resolves.

!!! warning "The plugin is not optional for Kotlin/Native"
    The Maven coordinate alone does not produce a working native link. The `kitecodec-core` klib contains no FFmpeg bytes, and its `ffmpeg.def` declares `linkerOpts` as bare `-lavformat -lavcodec …` with no `-L`. Without the plugin, the final native link fails on unresolved libav\* symbols.

!!! warning "The `license` choice is mandatory"
    The FFmpeg flavor decides your app's legal obligations, so the plugin does not choose one for you. If any non-Android Kotlin/Native target is wired and `license` is unset, configuration fails and prints the DSL snippet to add. Android Kotlin/Native-only projects are exempt, because those targets always use the LGPL MediaCodec build. Selecting `GPL` logs a warning that describes the GPL-3.0 obligations it places on your whole app.

## The DSL

Everything lives under `kitecodec { ffmpeg { ... } }`:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `version` | `String` | `"n8.0"` | FFmpeg release to provision. The value is pinned: the plugin fetches exactly this tag's builds. |
| `source` | `FFmpegSource` | `Prebuilt` | Where FFmpeg comes from (below). |
| `localRoot` | `DirectoryProperty` | **none** | Required with `Local`. Root of `<localRoot>/<license.id>/<target-triple>/{include,lib}`. |
| `license` | `FFmpegLicense` | **none, required** | License flavor for desktop targets. You must set it explicitly, or the build fails. Android Kotlin/Native targets always use the LGPL MediaCodec build. |
| `repo` | `String` | `"yuroyami/KiteCodec"` | GitHub `owner/repo` whose Releases host the prebuilt archives. Override it to self-host. |
| `pinnedSha256` | `MapProperty<String, String>` | empty | SHA-256 per Release asset name, for example `pinnedSha256.put("ffmpeg-n8.0-lgpl-macos-arm64.zip", "<sha256>")`. A pinned value is authoritative: the published `.sha256` is not fetched, and a download that does not match fails the build. |

### `FFmpegSource`

- **`Prebuilt`** is the default. It downloads a pinned static build from the configured repo's GitHub Releases and caches it under the Gradle user home. It needs no FFmpeg on the machine. KiteCodec has published no assets yet, so against the default `repo` it currently fails: at configuration time for a target outside the intended five, and with an HTTP 404 for the rest. Point `repo` at your own Releases to use it today.
- **`System`** links a system FFmpeg that is already installed. That means Homebrew on macOS, where you can override the prefix with the `kitecodec.macos.homebrew.prefix` Gradle property, or the apt-installed libraries on Linux. It links dynamically and is a convenience for development. It fails with a clear error when it finds no system install. It is not available for Kotlin/Native targets that have no system install path: iOS, Windows and Android Native.
- **`Local`** performs no download. It requires `localRoot` and validates `include/libavformat/avformat.h` plus all six `libav*.a` archives under `<localRoot>/<license.id>/<target-triple>/` for every wired target during configuration. A missing target produces one diagnostic naming its exact files. Local macOS searches that tree first, the configured Homebrew `lib` second, and links the desktop static stack. Local iOS links only that tree plus SDK zlib. Local with GPL on any iOS target is rejected before tree validation with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`
- **`BuildFromSource`** is only meaningful inside the KiteCodec checkout itself, which ships the `:buildFFmpegFor<Target>` tasks. In a consumer project it fails with instructions to use `Prebuilt`, `System` or `Local`.

### `FFmpegLicense`

There is no default. You must choose, as the warning above explains.

- **`LGPL`** has no `--enable-gpl` and no x264 or x265. It is safe for App Store and closed-source distribution.
- **`GPL`** adds libx264 and libx265, and it makes the whole linked application GPL-3.0. See [Licensing](licensing.md). Use it for open-source or server use only. The plugin logs a warning when you select this flavor.

## What `FetchFFmpegTask` does

With `source = Prebuilt`, the plugin registers one `fetchFFmpeg<Triple>` task per Kotlin/Native target: `fetchFFmpegMacosArm64`, `fetchFFmpegLinuxX64`, `fetchFFmpegAndroidArm64`, and so on. The name has no license segment, because the plugin reads the flavor from the DSL. Every binary's link task depends on the matching fetch task. Each task does four things.

1. **Downloads** `ffmpeg-<version>-<license>[-dav1d]-<triple>.zip` from `https://github.com/<repo>/releases/download/<releaseTag>/`, where `releaseTag` defaults to the plugin's own version tag (`v0.1.0` for plugin 0.1.0), following redirects to GitHub's object store. It uses HTTPS only, and it refuses a non-HTTPS redirect.
2. **Verifies the SHA-256.** A value from `pinnedSha256` wins outright. Otherwise the task fetches the `.sha256` published next to the asset. A mismatch fails the build and prints both digests. A checksum that cannot be obtained at all fails the build too, because an unverified native binary must never be linked into a consumer app silently. The error names both remedies: pin the checksum, or set the Gradle property `kitecodec.ffmpeg.allowUnverified=true` to downgrade the failure to a prominent warning.
3. **Unpacks** the archive into the Gradle user-home cache at `~/.gradle/caches/kitecodec/ffmpeg/<version>/<license>/<triple>/`. It expects `include/` and `lib/` at the archive root, plus the license texts described in [Licensing](licensing.md#what-the-kitecodec-release-zips-include). It rejects zip entries that would escape the target directory (a zip-slip guard).
4. **Is idempotent.** When `lib/libavformat.a` is already present in the cache, the task does nothing, so it costs nothing on later builds.

The cache is shared across projects on the machine: one download per FFmpeg version, flavor and target.

## Android targets

Android Kotlin/Native targets (`androidNativeArm64` / `Arm32` / `X64`) always map to the LGPL
native-codec FFmpeg build. Setting `license = FFmpegLicense.GPL` affects desktop targets only. There
is no GPL Android build.

The regular Android KMP target is different: it uses the shared JVM/Android actuals and a JNI
library packaged by the local AAR model for `arm64-v8a` and `x86_64` at `minSdk 24`. That model is
checked for 16 KiB ELF/app packaging constraints in the repository; it is not published, and this
page does not claim Android playback for either ABI. The x86_64 arm is link/package evidence only.

## Related

- [`kitecodec-gradle-plugin/README.md`](https://github.com/yuroyami/KiteCodec/blob/main/kitecodec-gradle-plugin/README.md): the module's own summary.
- [Platform support](platforms.md): the per-target FFmpeg sourcing picture when you are *not* using the plugin.
- [Licensing](licensing.md): what the prebuilt zips contain and what you must ship onward.
