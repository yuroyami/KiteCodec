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
        source  = FFmpegSource.Prebuilt  // Prebuilt (default) | System | BuildFromSource
        license = FFmpegLicense.LGPL     // LGPL default; GPL is a loud opt-in
    }
}
```

For every Kotlin/Native target you enable, the plugin maps it to an FFmpeg build, ensures the binaries
are present, and adds the `-L<libdir>` linker flag so the final native link resolves.

## Sources

- `Prebuilt` (default): downloads a pinned static build from KiteCodec's GitHub Releases
  (`ffmpeg-<version>-<license>-<triple>.zip`), verifies its SHA-256, and caches it under the Gradle
  user home. Android targets always use the LGPL MediaCodec build.
- `System`: links a system FFmpeg already installed (Homebrew / apt). Dynamic linking, dev convenience.
- `BuildFromSource`: only inside the KiteCodec checkout, which ships the `:buildFFmpegFor<Target>` tasks.

## Licence flavours

`LGPL` (default) is App-Store and closed-source safe: no `--enable-gpl`, no x264 / x265. `GPL` adds
libx264 / libx265 for quality-focused software encode and makes the linked binary GPL, so it is for
open-source or server use only.
