# Gradle plugin

`kitecodec-gradle-plugin` provisions the FFmpeg binaries KiteCodec links against, so consumer projects do not build FFmpeg from source. KiteCodec's published klib contains **no FFmpeg bytes**: this plugin supplies them at your build time, which also keeps the FFmpeg licence (LGPL / GPL) cleanly separate from KiteCodec's own Apache-2.0 artifact.

!!! note "Status"
    The plugin lives in the KiteCodec repository (`kitecodec-gradle-plugin/`) and, like the library, is not on a public plugin repository yet. The DSL below is the supported surface.

## Apply and configure

Apply it alongside the Kotlin Multiplatform plugin, then configure the `kitecodec { }` extension:

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.yuroyami.kitecodec") version "<version>"
}

kitecodec {
    ffmpeg {
        version = "n8.0"                 // pinned FFmpeg release
        source  = FFmpegSource.Prebuilt  // Prebuilt (default) | System | BuildFromSource
        license = FFmpegLicense.LGPL     // REQUIRED — no default; see below
    }
}
```

For every Kotlin/Native target you enable, the plugin maps it to the matching FFmpeg build, makes sure the binaries are present before the native link runs, and adds the `-L<libdir>` linker flag so the link resolves.

!!! warning "The `license` choice is mandatory"
    The FFmpeg flavour decides your app's legal obligations, so the plugin does not default it. If any non-Android Kotlin/Native target is wired and `license` is unset, configuration fails with the DSL snippet to add. Purely-Android projects are exempt — Android always uses the LGPL MediaCodec build. Selecting `GPL` logs a warning describing the GPL-3.0 obligations it places on your whole app.

## The DSL

Everything lives under `kitecodec { ffmpeg { ... } }`:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `version` | `String` | `"n8.0"` | FFmpeg release to provision. Pinned — the plugin fetches exactly this tag's builds. |
| `source` | `FFmpegSource` | `Prebuilt` | Where FFmpeg comes from (below). |
| `license` | `FFmpegLicense` | **none — required** | Licence flavour for desktop targets. Must be set explicitly (build fails otherwise). Android targets always use the LGPL MediaCodec build, regardless. |
| `repo` | `String` | `"yuroyami/KiteCodec"` | GitHub `owner/repo` whose Releases host the prebuilt archives. Override to self-host. |

### `FFmpegSource`

- **`Prebuilt`** (default) — downloads a pinned static build from the configured repo's GitHub Releases and caches it under the Gradle user home. This is the zero-setup path for consumers.
- **`System`** — links a system FFmpeg that is already installed (Homebrew on macOS — override the prefix with the `kitecodec.macos.homebrew.prefix` Gradle property — or the apt-installed libraries on Linux). Dynamic linking; a dev convenience. Fails with a clear error when no system install is found, and is not available for targets that have no system-install story (iOS, Windows, Android).
- **`BuildFromSource`** — only meaningful inside the KiteCodec checkout itself, which ships the `:buildFFmpegFor<Target>` tasks. In a consumer project this errors with instructions to use `Prebuilt` or `System`.

### `FFmpegLicense`

No default — you must choose (see the warning above):

- **`LGPL`** — no `--enable-gpl`, no x264/x265. App-Store- and closed-source-safe.
- **`GPL`** — adds libx264 / libx265 and makes the whole linked application GPL-3.0 (see [Licensing](licensing.md)). Open-source or server use only. The plugin logs a warning when this flavour is selected.

## What `FetchFFmpegTask` does

With `source = Prebuilt`, the plugin registers one `fetchFFmpeg<Target><License>` task per Kotlin/Native target and wires every binary's link task to depend on it. The task:

1. **Downloads** `ffmpeg-<version>-<license>-<triple>.zip` from `https://github.com/<repo>/releases/download/ffmpeg-<version>/`, following redirects to GitHub's object store.
2. **Verifies the SHA-256**: it fetches the matching `.zip.sha256` asset and checks the archive against it. A mismatch fails the build with both digests printed. If no `.sha256` asset exists alongside the zip, the task warns and skips the check rather than failing.
3. **Unpacks** the archive (expecting `include/` and `lib/` at the archive root, plus the licence texts — see [Licensing](licensing.md#what-the-kitecodec-release-zips-include)) into the Gradle user-home cache: `~/.gradle/caches/kitecodec/ffmpeg/<version>/<license>/<triple>/`. Zip entries that would escape the target directory are rejected (zip-slip guard).
4. **Is idempotent**: when `lib/libavformat.a` is already present in the cache, the task does nothing, so it costs nothing on subsequent builds.

The cache is shared across projects on the machine — one download per FFmpeg version/flavour/target.

## Android targets

Android Kotlin/Native targets (`androidNativeArm64` / `Arm32` / `X64`) always map to the LGPL MediaCodec build. Setting `license = FFmpegLicense.GPL` affects desktop targets only; there is no GPL Android build.

## Related

- [`kitecodec-gradle-plugin/README.md`](https://github.com/yuroyami/KiteCodec/blob/main/kitecodec-gradle-plugin/README.md): the module's own summary.
- [Platform support](platforms.md): the per-target FFmpeg sourcing picture when you are *not* using the plugin.
- [Licensing](licensing.md): what the prebuilt zips contain and what you must ship onward.
