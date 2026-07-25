# Changelog

All notable changes to KiteCodec are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KiteCodec is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

## [Unreleased]

The library is source-only for now. Nothing has been published — not `kitecodec-core`, not the Gradle plugin, and not the FFmpeg Release assets the plugin's `FFmpegSource.Prebuilt` default downloads. Release state and the per-target table live in the [README](https://github.com/yuroyami/KiteCodec#release-status) rather than being restated here.

### Added
- `MediaSource.startTimeMicros` — where a container's timeline begins. Raw stream/frame timestamps are absolute and include it; every parameter KiteCodec takes (`seekMicros`, `extractFrame`, trim bounds) is content-relative. Exposed so callers can convert between the two.
- Vendored FFmpeg profile now also builds `mpeg4` (encode + decode), `flac`, and the `pcm_s16le`/`s24le`/`f32le` encoders — the dependency-free baseline every profile shares. Previously the LGPL build had **no** video encoder except libsvtav1 and mjpeg, and the already-enabled `wav`/`flac` muxers had no encoder to feed them.
- Vendored FFmpeg profile gained the MPEG-TS muxer/demuxer, the `matroska_audio` muxer (`.mka`), and the `http`/`tcp` protocols. (`https` still needs a TLS backend and is not built; see the note in `BuildFFmpegTask`.)
- CI job `vendored-lgpl`: builds the shipped LGPL profile from source and runs the unit tests, native tests and full e2e against **that**, not against Homebrew's FFmpeg.
- Full single-pass `demux → decode → filter → encode → mux` pipeline for video and audio (`Transcoder.transcode`): frame-exact trim, `videoCopy`/`audioCopy`/`subtitleCopy` stream copy, container metadata, typed progress (`TranscodeProgress`).
- `Frame.ofVideo` / `Frame.ofAudio` — build frames from raw bytes for generative pipelines (images-to-video, synthesized audio).
- `MediaSink.open(path, format, options)` — explicit container selection and muxer private options (`movflags=+faststart`).
- Semantic error hierarchy: `FFmpegError` now classifies `AVERROR_*` codes into `FileNotFound`, `PermissionDenied`, `InvalidData`, `EncoderNotFound`, `DecoderNotFound`, `MuxerNotFound`, `FilterNotFound`, and more (raw code retained; unmapped codes fall back to `AvError`).
- `Rational`: `Comparable`, `plus`/`minus`/`div`/`unaryMinus`, overflow-safe construction and scalar multiply.
- `StreamInfo.metadata` (per-stream tags — `language`, `title`, …), 10-bit pixel format constants (`yuv420p10le`, `p010le`, …), `s64`/`s64p` sample formats.
- Explicit API mode + `@Throws` annotations across the public surface; kotlinx binary-compatibility-validator wired (klib mode).
- Maven publishing (vanniktech plugin, Central Portal, signing, Dokka javadoc jar) for `kitecodec-core`; Gradle Plugin Portal metadata + a TestKit functional test for `kitecodec-gradle-plugin`.

### Changed
- **Breaking (behaviour):** `MediaSource.seekMicros`, `extractFrame`, and `Transcoder`/`Remuxer` trim bounds are now consistently **content-relative**. They previously mixed the two conventions — `extractFrame` compensated for a container's start time, `Transcoder`/`Remuxer` did not — so trimming an MPEG-TS capture silently shifted the window by the container's start (~1.4s, and more with an offset).
- `MediaSink.addVideoEncoder` now converts frames whose pixel format differs from the encoder's instead of failing. An unfiltered transcode of a 10-bit source, or a filter chain without a trailing `format=`, used to die with a bare `EINVAL`. Frame *dimensions* still throw — a size mismatch is a config error, and silently rescaling would hide it.
- `MediaSink.close()` now drains every encoder before writing the trailer, as its documentation always claimed. A sink closed without an explicit `finish()` used to discard whatever the encoder still had buffered.
- `AudioEncoder.sampleRate` / `.channels` now report what the encoder actually opened with rather than what was requested.
- The sample and `scripts/e2e.sh` pick their video encoder by probing the linked FFmpeg instead of hard-coding `libx264` (GPL-only). `kitecodec-sample info` prints the choice for scripts to read.
- **Breaking:** `FFmpegError` no longer extends `RuntimeException` — it is a plain sealed hierarchy carried by `FFmpegException` (the only thrown type).
- **Breaking (contract):** frames emitted by `decodedFrames`/`decodeStreams`/`FilterGraph.process` are now OWNED by the collector — safe to buffer (`toList()`, `buffer()`), and each must be closed. Callback-style outputs (`feedInput`) keep the callback-scope rule.
- `Rational.Zero.inverse` and division by zero now throw instead of constructing an invalid rational.
- Concurrent misuse of one `MediaSource` (second decode flow, seek/close mid-decode) is rejected with `IllegalStateException` instead of racing native code.

### Fixed
- **The vendored static path had never linked.** `ffmpeg.def` names only the six libav* libraries, which is all a shared FFmpeg needs; a static `libavcodec.a` also needs every third-party archive it draws symbols from named at the final link. The library's own build never did that, so `native-libs/` was unusable — `_svt_av1_enc_init`, `_png_set_tRNS_to_alpha`, `_gr_*`, and the CoreGraphics/CoreText/VideoToolbox frameworks all went unresolved. `BuildFFmpegTask` now bundles those archives into the tree and `StaticLinkFlags` names them.
- **The vendored profile enabled no bitstream filters at all.** libavformat inserts these itself during stream copy, so their absence produced a corrupt output file rather than an error — copying h264 from MPEG-TS into mp4 yielded a file ffprobe rejects with "No start code is found". `extract_extradata`, `aac_adtstoasc`, `h264/hevc_mp4toannexb`, `vp9_superframe` are now built.
- **`eq` and `boxblur` are `deps="gpl"` in FFmpeg**, so the LGPL profile silently dropped them — including `eq`, the filter every example and the e2e suite reached for. They moved to the GPL flavour; the examples now use `hue=b=…`, which exists everywhere.
- `.m4a` and `.mka` map to FFmpeg's separate `ipod` and `matroska_audio` muxers. Neither was enabled, so writing either extension failed with "Unable to choose an output format".
- Decoding no longer aborts the whole file on a recoverable error. Every seek into a stream carrying its parameter sets in band (MPEG-TS) lands before the next SPS/PPS, so the first packets after it decode to nothing; that used to throw instead of being skipped, which made trimming a broadcast capture impossible.
- Seeking before a decode now aims deliberately early (`seekForDecode`). Indexless containers resolve a seek by searching byte positions and can land past the keyframe they aimed at, after which the decoder emits nothing until the next IDR — a whole GOP of requested content silently dropped.
- `BuildFFmpegTask` verified nothing after `make install`. A prefix GNU make cannot parse (any path containing `#`) truncates `libdir` to empty, so the install becomes a silent no-op that still exits 0 — and `FFmpegPaths` then falls back to the system FFmpeg, exactly the "publication silently drops a target" failure the publish guard exists to prevent. It now rejects such paths up front and verifies the six archives plus headers landed.
- `kitecodec-sample` ignored `-Pkitecodec.ffmpeg.license`, always resolving the LGPL tree even when the library it links was built against the GPL one.
- **The vendored macOS FFmpeg build never configured.** It died on `ERROR: libmp3lame >= 3.98.3 not found` even with the dependency installed: `BuildFFmpegTask` passed no `--extra-cflags`/`--extra-ldflags` for the Homebrew prefix, and lame ships no pkg-config file. It now passes both, plus `PKG_CONFIG_PATH`.
- **`--enable-videotoolbox` never produced a VideoToolbox encoder.** Under `--disable-everything` each encoder must be named explicitly; `h264_videotoolbox`/`hevc_videotoolbox` were not, so vendored Apple builds advertised hardware encode and had none. (The Android profile always listed `h264_mediacodec` correctly.)
- Trim windows on containers that do not start at zero (MPEG-TS) selected the wrong range — see the seek/trim change above.
- `StreamInfo.codec` reported the *decoder's* name, so an AV1 stream read as `libdav1d`, Opus as `libopus`, and any stream with no decoder compiled in as `codec_<number>`. It now reports the codec's canonical name.
- `Frame.encodeImage()` overwrote the source frame's timestamp with 0 when no pixel-format conversion was needed (it encodes the caller's own frame in that path).
- `Transcoder` produced **no output file at all**, and no error, when the trim window selected nothing: the muxer header is written lazily on the first packet. It is now written eagerly, as `Remuxer` already did.
- Non-monotonic audio timestamps were bumped by one tick. With a codec time-base of `1/sample_rate` that collapses a 1024-sample AAC frame into a single sample; the bump is now the frame's own duration.
- Encoder packets were rescaled from the time-base requested in the spec rather than the one `avcodec_open2` settled on, which the muxer stream was already (correctly) told about.
- Output timestamps could go negative: streams share one rebase origin, so a stream starting before the claiming one (AAC priming samples) fell below zero. The muxer's `avoid_negative_ts` policy is now pinned to `make_zero`, which shifts every stream by the same amount and keeps the A/V offset intact.
- `FFmpegPaths` (and the Gradle plugin's system-FFmpeg lookup) matched library paths by existence, never by architecture — so `macosX64` on an Apple-silicon Mac resolved to the arm64 Homebrew libraries and `linuxArm64` on an x64 host to the x86_64 ones. System resolution is now restricted to the host's own target, with an error that says why.
- `FetchFFmpegTask` wrote a shared Gradle-user-home cache with delete-then-move and no locking, so concurrent builds could pull the tree out from under a running link. It now holds an exclusive lock for the whole fetch and marks completion with a file written last.
- `FetchFFmpegTask` threw a `NullPointerException` on a relative redirect `Location` instead of resolving it against the request URL.
- Muxer crash on header-write failure: a failed `avformat_write_header` no longer leads `close()` into `av_write_trailer` on a headerless context.
- A/V desync after trims: all streams of one sink now rebase timestamps against a single shared origin instead of each stream's own first timestamp.
- `Remuxer.remux` and copy-only transcodes are now cancellable (cancellation checked every packet).
- Filter graphs: `EAGAIN` from `av_buffersrc_add_frame` retries the same frame instead of silently dropping it; feeding a closed graph throws instead of use-after-free.
- Trim end detection gates on dts (monotonic) instead of pts — B-frame reordering no longer stops the demux a GOP early; graph-buffered frames past the trim end are filtered at the encoder.
- `extractFrame` accounts for nonzero container start times (MPEG-TS).
- `Rational.inverse` normalizes its result (no more negative denominators).
- Released FFmpeg zips now bundle LGPL/GPL license texts, a BUILD-INFO provenance record, and the source URL (LGPL compliance); release assets are attested and checksummed.

### Baseline surface

Everything below grew from `0.0.1` and is listed for orientation rather than as a change.

- `MediaSource`: probing (`streams`, `metadata`, `durationMicros`), `decodedFrames`/`decodeStreams` frame flows (EAGAIN-correct, single demux pass for multiple streams), `seekMicros`, `extractFrame` + `Frame.encodeImage` thumbnails.
- `MediaSink`: `addVideoEncoder`/`addAudioEncoder` (shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options`), `addCopyStream`, `setMetadata`.
- `FilterGraph`: single- and multi-input video/audio graphs (overlay, amix), encoder-ready audio output, `setOutputFrameSize` for AAC's 1024-sample framing.
- `Remuxer.remux`: lossless container rewrite with keyframe-snapped trim.
- Capability probing (`FFmpeg.versions`, `hasEncoder`/`hasDecoder`/`hasFilter`, `buildConfiguration`).
- Hardware encode via `h264_videotoolbox` (verified on macOS arm64; `allow_sw` for VMs) and MediaCodec codec ids for Android.
- FFmpeg build tasks (`buildFFmpegFor<Target>[Gpl]`): vendored static FFmpeg cross-compile, LGPL by default with a GPL opt-in flavour, Android NDK MediaCodec profile.
- `kitecodec-gradle-plugin`: provisions prebuilt/system FFmpeg for consumer builds with SHA-256 verification (in-repo; not yet published).
- Documentation site (MkDocs Material) and CI (macOS / Ubuntu / Windows unit + e2e, plus a vendored-LGPL job that exercises the shipped profile).

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
