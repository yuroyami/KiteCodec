# Changelog

All notable changes to KiteCodec are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KiteCodec is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

## [Unreleased]

The library is source-only for now: **not yet published to Maven Central**, consumed by building from source (`io.github.yuroyami:kitecodec-core:0.0.1` locally). macOS arm64 is the only end-to-end-verified target; Linux x64 and Windows (mingw x64) build, test, and e2e-transcode in CI; Android native targets cross-compile in CI as a klib; iOS and macOS x64 code is written but unverified.

### Added
- Full single-pass `demux → decode → filter → encode → mux` pipeline for video and audio (`Transcoder.transcode`): frame-exact trim, `videoCopy`/`audioCopy`/`subtitleCopy` stream copy, container metadata, typed progress (`TranscodeProgress`).
- `Frame.ofVideo` / `Frame.ofAudio` — build frames from raw bytes for generative pipelines (images-to-video, synthesized audio).
- `MediaSink.open(path, format, options)` — explicit container selection and muxer private options (`movflags=+faststart`).
- Semantic error hierarchy: `FFmpegError` now classifies `AVERROR_*` codes into `FileNotFound`, `PermissionDenied`, `InvalidData`, `EncoderNotFound`, `DecoderNotFound`, `MuxerNotFound`, `FilterNotFound`, and more (raw code retained; unmapped codes fall back to `AvError`).
- `Rational`: `Comparable`, `plus`/`minus`/`div`/`unaryMinus`, overflow-safe construction and scalar multiply.
- `StreamInfo.metadata` (per-stream tags — `language`, `title`, …), 10-bit pixel format constants (`yuv420p10le`, `p010le`, …), `s64`/`s64p` sample formats.
- Explicit API mode + `@Throws` annotations across the public surface; kotlinx binary-compatibility-validator wired (klib mode).
- Maven publishing (vanniktech plugin, Central Portal, signing, Dokka javadoc jar) for `kitecodec-core`; Gradle Plugin Portal metadata + a TestKit functional test for `kitecodec-gradle-plugin`.

### Changed
- **Breaking:** `FFmpegError` no longer extends `RuntimeException` — it is a plain sealed hierarchy carried by `FFmpegException` (the only thrown type).
- **Breaking (contract):** frames emitted by `decodedFrames`/`decodeStreams`/`FilterGraph.process` are now OWNED by the collector — safe to buffer (`toList()`, `buffer()`), and each must be closed. Callback-style outputs (`feedInput`) keep the callback-scope rule.
- `Rational.Zero.inverse` and division by zero now throw instead of constructing an invalid rational.
- Concurrent misuse of one `MediaSource` (second decode flow, seek/close mid-decode) is rejected with `IllegalStateException` instead of racing native code.

### Fixed
- Muxer crash on header-write failure: a failed `avformat_write_header` no longer leads `close()` into `av_write_trailer` on a headerless context.
- A/V desync after trims: all streams of one sink now rebase timestamps against a single shared origin instead of each stream's own first timestamp.
- `Remuxer.remux` and copy-only transcodes are now cancellable (cancellation checked every packet).
- Filter graphs: `EAGAIN` from `av_buffersrc_add_frame` retries the same frame instead of silently dropping it; feeding a closed graph throws instead of use-after-free.
- Trim end detection gates on dts (monotonic) instead of pts — B-frame reordering no longer stops the demux a GOP early; graph-buffered frames past the trim end are filtered at the encoder.
- `extractFrame` accounts for nonzero container start times (MPEG-TS).
- `Rational.inverse` normalizes its result (no more negative denominators).
- Released FFmpeg zips now bundle LGPL/GPL license texts, a BUILD-INFO provenance record, and the source URL (LGPL compliance); release assets are attested and checksummed.
- `MediaSource`: probing (`streams`, `metadata`, `durationMicros`), `decodedFrames`/`decodeStreams` frame flows (EAGAIN-correct, single demux pass for multiple streams), `seekMicros`, `extractFrame` + `Frame.encodeImage` thumbnails.
- `MediaSink`: `addVideoEncoder`/`addAudioEncoder` (shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options`), `addCopyStream`, `setMetadata`.
- `FilterGraph`: single- and multi-input video/audio graphs (overlay, amix), encoder-ready audio output, `setOutputFrameSize` for AAC's 1024-sample framing.
- `Remuxer.remux`: lossless container rewrite with keyframe-snapped trim.
- Capability probing (`FFmpeg.versions`, `hasEncoder`/`hasDecoder`/`hasFilter`, `buildConfiguration`).
- Hardware encode via `h264_videotoolbox` (verified on macOS arm64; `allow_sw` for VMs) and MediaCodec codec ids for Android.
- FFmpeg build tasks (`buildFFmpegFor<Target>[Gpl]`): vendored static FFmpeg cross-compile, LGPL by default with a GPL opt-in flavour, Android NDK MediaCodec profile.
- `kitecodec-gradle-plugin`: provisions prebuilt/system FFmpeg for consumer builds with SHA-256 verification (in-repo; not yet published).
- Documentation site (MkDocs Material) and CI (macOS / Ubuntu / Windows unit + e2e).

### Known gaps
- Publishing is wired but not yet executed — no artifacts on Maven Central until the first release run (needs Central Portal credentials + signing key in CI). The BCV `apiDump` baseline must be generated on a machine with FFmpeg present for all targets.
- No Android AAR for JVM apps (JNI substrate planned); `kitecodec-gpl` artifact is a skeleton.
- No custom AVIO (in-memory/pipe sources and sinks), no typed channel layouts (channel count only), no chapter read/write, no subtitle decode (copy only).
- No hardware decode / hwframes pipelines; no bitstream filters on the copy path (MPEG-TS Annex B unsupported).
- iOS targets lack CI verification.

## [0.0.1] - 2026

Initial development baseline: project structure, consolidated FFmpeg cinterop binding (`ffmpeg.def` + `ffkmp_*` helpers), and the first working decode/encode paths on macOS arm64. Everything listed under [Unreleased] grew from here; treat 0.0.1 as the "it exists and transcodes" milestone rather than a supported release.

[Unreleased]: https://github.com/yuroyami/KiteCodec/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/yuroyami/KiteCodec/releases/tag/v0.0.1
