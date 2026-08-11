# kitecodec-gradle-plugin

Provisions the FFmpeg binaries KiteCodec links against, so consumers do not build FFmpeg from source.

KiteCodec's published library contains no FFmpeg bytes. This plugin supplies them at your build time,
which keeps the FFmpeg licence (LGPL / GPL) cleanly separate from KiteCodec's own Apache-2.0 artifact.

## Use

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.yuroyami.kitecodec") version "<version>"
}

kitecodec {
    ffmpeg {
        version = "n8.0"                 // pinned FFmpeg release
        source  = FFmpegSource.Prebuilt  // Prebuilt (default) | System | Local | BuildFromSource
        license = FFmpegLicense.LGPL     // REQUIRED, no default. See below
        // localRoot = layout.projectDirectory.dir("ffmpeg") // required for Local
    }
}
```

For every Kotlin/Native target you enable, the plugin maps it to an FFmpeg build, ensures the binaries
are present, and adds the `-L<libdir>` linker flag so the final native link resolves.

The `license` choice is **mandatory** (except for purely-Android projects, which always get the LGPL
MediaCodec build): the flavour decides your app's legal obligations, so the plugin refuses to pick one
for you and fails configuration with instructions when it is missing.

## Sources

- `Prebuilt` (default): downloads a pinned static build from KiteCodec's GitHub Releases
  (`ffmpeg-<version>-<license>-<triple>.zip`), verifies its SHA-256, and caches it under the Gradle
  user home. Android targets always use the LGPL MediaCodec build.
- `System`: links a system FFmpeg already installed (Homebrew / apt). Dynamic linking, dev convenience.
- `Local`: links a complete no-network tree at
  `<localRoot>/<license>/<target>/{include,lib}`. All six `libav*.a` archives and
  `include/libavformat/avformat.h` are validated for every wired target. Local mobile Apple permits
  LGPL only, rejects GPL before tree validation and adds only SDK zlib. Local macOS searches the
  local tree before the host fallback.
- `BuildFromSource`: only inside the KiteCodec checkout, which ships the `:buildFFmpegFor<Target>` tasks.

## Licence flavours

`LGPL` is App-Store and closed-source safe: no `--enable-gpl`, no x264 / x265. `GPL` adds
libx264 / libx265 for quality-focused software encode and makes the whole linked application GPL-3.0,
so it is for open-source or server use only. Selecting it logs a warning spelling out those
obligations.
