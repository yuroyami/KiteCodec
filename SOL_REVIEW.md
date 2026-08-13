# KiteCodec + KitePlayer implementation audit

Date: 2026-08-13

## Scope

This is a coupled, implementation-only review of KiteCodec and KitePlayer.

- The code is the sole source of truth.
- KPKMP.md and roadmap claims were deliberately ignored.
- The review covers missing APIs and functionality, defective API design, correctness and lifetime faults, syntax and Kotlin modernization opportunities, overengineering, underengineering, performance costs, platform gaps, build and publication problems, and C code that can be removed without adding runtime overhead.
- No source changes were made during the audit.

Severity:

- P0: memory safety, heap corruption, use-after-free, catastrophic clock error, or violation of a hard-real-time guarantee.
- P1: user-visible corruption, leaks, hangs, false success, destructive lifecycle races, or materially defective public behavior.
- P2: performance, maintainability, portability, incomplete behavior, or API quality gaps.

## Executive assessment

| Area | Current state | Principal blocker |
|---|---|---|
| Demux/decode | Broad local FFmpeg coverage | Default artifacts lack HTTPS/TLS, UDP/RTP, cancellation, and bitstream filters |
| Native ownership | Unsafe in several paths | Use-after-free paths, leaked callback frames, and unpinned JNI handles |
| Player lifecycle | Non-transactional | Timeouts, preemption, and supersession are often reported as success |
| Android video | Software and copy-heavy | RGBA conversion, copy/swizzle, Bitmap upload, then software canvas |
| Apple video | Better Metal foundation | Plane-validation hazards, leaks, and CPU readback in Compose |
| Audio timing | Multiple correctness faults | Wrong Android epoch, wrap handling, partial-write accounting, and drain races |
| Compose | Functional baseline | Not GPU-native end-to-end; subtitle and layout correctness gaps |
| API predictability | Broad but misleading | Public options and statistics are inert, fabricated, or only partly implemented |
| C footprint | Far too large in KiteCodec | Hundreds of thin wrappers can become direct Kotlin/Native cinterop |
| Hard-real-time C | Justified | CoreAudio callback and ring should remain a small, measured C island |

The shortest accurate verdict is:

- KiteCodec is overwrapped in C but underengineered around ownership, cancellation, and source-bound identities.
- KitePlayer is overengineered around threads and state machinery but underengineered around transactions, lifecycle fences, and output capability negotiation.
- “Plays anything” is blocked by packaged protocols and I/O behavior before it is blocked by FFmpeg decoder coverage.
- “libmpv/libvlc performance” is blocked principally by the output pipeline, not by Kotlin.

## P0: stop-ship defects

### KiteCodec native and JNI memory safety

#### 1. Native Frame can dereference freed memory after close

[Frame.native.kt:90](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt:90>) caches frame information lazily. After the lazy value has been initialized, later reads no longer execute the open-state check. Native plane and hardware-surface helpers then trust the cached value and dereference the closed native frame at [Playback.native.kt:503](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt:503>) and [Playback.native.kt:533](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt:533>).

Reproduction shape:

1. Read frame.info.
2. Close the frame.
3. Call withPlanes or hardwareSurface.

The JVM implementation rechecks the owner correctly.

Fix:

- Route every native pointer access through one checked internal accessor.
- Cache an immutable metadata snapshot if useful, but never use cached metadata as proof that the native owner remains open.
- Add a regression that warms the cache before close and then exercises every pointer-backed API.

#### 2. Native filter callback frames remain logically open after their lifetime ends

[FilterGraph.native.kt:171](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/FilterGraph.native.kt:171>) creates a callback wrapper and later only unrefs its landing AVFrame. A caller that retains the callback object still sees it as open. It can observe empty or reused storage and becomes a use-after-free once the graph closes.

The JVM path explicitly invalidates the wrapper.

Fix:

- Hold the callback wrapper in a variable.
- Close the wrapper in finally.
- Test that retaining the original callback frame never permits access after callback return.

#### 3. JNI handle lookup does not pin object lifetime across an operation

[kj_handles.c:146](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-jni/kj_handles.c:146>) resolves a handle under a mutex, unlocks, and returns a raw pointer. JNI operations such as [kj_frame.c:25](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-jni/kj_frame.c:25>) use that pointer after the lock has been released. Concurrent close can invalidate and free the object between lookup and use.

The current table guarantees a non-torn lookup, not lifetime for the complete operation.

Fix:

- Add acquire/release of an in-flight operation reference.
- Defer destruction until the reference count reaches zero.
- Alternatively enforce thread confinement for every native owner in code, not only in documentation. This is less flexible and would still need validation at public boundaries.

### KitePlayer native and output safety

#### 4. The 32-bit real-time ring allocation can wrap into native heap corruption

[kite_rt_ring.c:77](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_ring.c:77>) validates the sample allocation without reserving the complete aligned ring header and the final alignment padding subsequently added. On 32-bit systems, a large capacity can pass the guard, wrap the final allocation size, and then be cleared or written out of bounds.

A concrete near-boundary example is 16,777,215 frames × 64 channels × 4 bytes. The existing test checks the easier exact 2^32 case and misses this boundary.

Fix:

- Compute the aligned header size first.
- Checked-multiply frames, channels, and element size.
- Checked-add header, sample bytes, and final padding.
- Add 32-bit ASan and UBSan boundary tests.

#### 5. Public Metal software planes allow native reads beyond a Kotlin ByteArray

[MetalVideoSupport.kt:57](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:57>) publicly accepts arbitrary plane dimensions, row counts, strides, and storage. Upload code at [MetalVideoSupport.kt:325](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:325>) uploads a complete texture and ignores the declared row count. A short or malformed public array can therefore be read past its pin.

Fix:

- Keep the raw constructor internal.
- Expose a validating factory.
- Validate plane count, ceil chroma dimensions, stride, row count, bytes per pixel, and checked total byte length.

#### 6. CoreAudio destroy may free callback state without proving callback quiescence

[kite_rt_coreaudio.c:466](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:466>) ignores stop, uninitialize, and dispose failures and then frees the sink and ring. If the callback has not actually quiesced, this is a use-after-free.

Fix:

- Detach the callback first.
- Verify stop and disposal.
- Return a teardown result.
- If quiescence cannot be proven, fail closed and leak rather than free live callback state.

#### 7. Seek and renderer replacement continue after quiescence fails

[PlaybackCore.kt:766](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:766>) and [PlaybackCore.kt:1549](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1549>) ignore or merely warn about failed worker quiescence. They then flush, clear, seek, or replace resources that workers may still be using.

Fix:

- Treat quiescence as a required transaction precondition.
- Abort the mutation if it cannot be established.
- Return an explicit failure rather than continuing with unsafe best effort.

### Timing and hard-real-time correctness

#### 8. Android audio and video clocks use different epochs

[AndroidMonotonicClock.kt:7](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidMonotonicClock.kt:7>) uses elapsedRealtimeNanos, which is boottime. [AudioTrackDriver.kt:129](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt:129>) consumes AudioTrack timestamps paired with System.nanoTime and the monotonic clock.

After suspend, the difference between the two epochs equals cumulative device sleep and may be hours. A/V scheduling can therefore become catastrophically wrong after the device sleeps.

Fix:

- Use System.nanoTime consistently, or
- Explicitly translate between the two epochs and document the conversion.

#### 9. The hard-real-time callback’s bounded-loop guarantee is false in shipped arm64 code

[kite_rt_render.c:171](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_render.c:171>) and related counters use atomic fetch-add operations. Inspection of the generated iOS arm64 object showed ldxr, stxr, and conditional-branch retry loops in both render functions.

Each affected counter has one writer, so an atomic read-modify-write is unnecessary.

Fix:

- Publish single-writer counters through atomic load plus store or a snapshot.
- Re-audit the generated assembly after the change.
- Keep a machine-code regression check for the hard-real-time object.

## P1: KitePlayer lifecycle and playback correctness

### 1. Opening from Ended leaks the previous session

[PlaybackCore.kt:647](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:647>) permits open from Ended. [PlaybackCore.kt:790](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:790>) then overwrites the current session without tearing it down. The old source, workers, decoders, sink, and queues can remain live but unreachable.

Fix: either disallow open from Ended or execute and await complete teardown before installing the new session.

### 2. Session construction rollback is incomplete

[PlaybackCore.kt:862](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:862>) may create engine-owned decoders and sinks, but failure cleanup usually closes only BackendSession. [AudioPath.kt:63](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPath.kt:63>) also leaks a sink if open succeeds and a later capacity or ring construction step fails.

Fix:

- Use a reverse-order construction ledger.
- Transfer ownership into OpenSession only after every construction step commits.
- Make partial construction tests fail at every allocation boundary.

### 3. Audio submission retry is non-transactional and can duplicate samples

[PlaybackCore.kt:2421](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2421>) wraps incremental submission in a timeout. [AudioPlayback.kt:166](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPlayback.kt:166>) can partially commit to the ring and mutate mixer, resampler, and gain state before cancellation. Retrying the entire source buffer replays already accepted samples and repeats state mutation.

Fix:

- Convert and process the input once.
- Retain the unaccepted converted remainder.
- Track an explicit accepted offset.
- Never use cancellation of an incremental commit as a retry boundary.

### 4. Open and seek rendezvous timeouts are reported as success

[PlaybackCore.kt:1075](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1075>) treats a timeout or finished worker as a normal return, after which open still publishes Opened. [PlaybackCore.kt:1665](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1665>) may return no landing frame, yet [PlaybackCore.kt:1595](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1595>) emits SeekCompleted.

Fix: use explicit Ready, TimedOut, WorkerFailed, and Preempted outcomes.

### 5. Track requests report success when superseded or never applied

[PlaybackCore.kt:724](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:724>), [PlaybackCore.kt:1113](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1113>), and [PlaybackCore.kt:1498](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1498>) complete unapplied selections with Unit. Track rebuilding can also replace a pending user seek while retaining that user’s reply, so the caller receives Applied for a different internal reposition.

Fix:

- Return Applied, Superseded, or Rejected.
- Serialize seek and track arbitration.
- Keep each reply bound to the exact mutation it represents.

### 6. Track choices are not validated against stream kind and source identity

[PlaybackCore.kt:840](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:840>) accepts index-based choices without ensuring the selected stream belongs to the requested kind. Missing or wrong-kind IDs can silently deselect or rebuild the wrong path.

Fix: canonicalize against the active session’s track set and reject kind or identity mismatches before mutation.

### 7. Decoder EOF can hang forever

[PlaybackCore.kt:2216](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2216>) ignores false from send(null) but marks drain as sent. The decoder contract at [Decoders.kt:34](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt:34>) permits rejection, so the decoder may never enter drain and never report drained.

Fix: receive available output and retry the null packet until accepted.

### 8. Subtitle packets can be dropped after temporary input rejection

[PlaybackCore.kt:1273](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1273>) closes an unaccepted packet if no immediate output appears.

Fix: retain and retry subtitle packets under the same ownership rules as audio and video packets.

### 9. Startup and buffering inspect compressed input instead of decoded output

[PlaybackCore.kt:1192](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1192>) can declare startup ready while the PCM ring and video frame queue are empty. Playback starts into an underrun or blank frame. Decode starvation with compressed packets present is not reflected as Buffering.

Fix:

- Gate readiness on decoded output and worker health.
- Track compressed-input pressure separately from presentation readiness.

### 10. Precise seeking is not precise

[PlaybackCore.kt:2361](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2361>) keeps a whole PCM buffer that straddles the target and therefore plays pre-target samples. Video accepts target minus five milliseconds despite the public promise at [KitePlayer.kt:109](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt:109>).

Fix:

- Slice PCM at the exact sample offset.
- Enforce timestamps at or after the target, or expose a documented tolerance in the result.

### 11. Playback speed is a false API

[KitePlayer.kt:150](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt:150>) exposes speed control, but video scheduling never consumes the rate, so video-only playback remains 1×. [PlaybackCore.kt:737](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:737>) stores the rate before audio may reject it, while the facade discards the rejection.

Fix:

- Make rate changes suspendable, awaited, and transactional.
- Until both scheduling and tempo behavior exist, reject every non-1× value.

### 12. VideoMaster still publishes an audio-master position

Scheduling follows video, but [PlaybackCore.kt:2004](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2004>) and [PlaybackCore.kt:2488](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2488>) prefer audio for position, relative seeks, and subtitles.

Fix: centralize a single mode-aware master-clock selector and use it everywhere.

### 13. Subtitle resources are omitted from teardown and memory accounting

[PlaybackCore.kt:1840](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1840>) closes neither the subtitle decoder nor its queued packets. [PlaybackCore.kt:2563](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2563>) excludes subtitles from selected queues, so global budgets and EOF decisions can ignore an unbounded subtitle backlog.

Fix:

- Close the decoder and queue in normal and partial teardown.
- Separate all resource queues from the smaller set used for A/V readiness.

### 14. Subtitle overlay identity is based only on timing

[PlaybackCore.kt:1291](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1291>) can fail to republish content or style changes when timestamps stay the same. A content hash is also collision-prone.

Fix: use a structural cue key or monotonically assigned content generation.

### 15. mov_text is parsed as raw UTF-8 or SRT

[KiteCodecSubtitleDecoder.kt:21](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt:21>) ignores the two-byte tx3g text length and optional style boxes. Output can contain binary prefixes and garbage. Boxed MP4 WebVTT also needs codec-specific extraction.

### 16. Audio with more than eight channels is index-corrupted

[KiteCodecSource.kt:517](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:517>) truncates the modeled output count, but [KiteCodecSource.kt:692](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:692>) indexes decoded samples using the truncated stride rather than the source stride.

Fix: preserve the source stride and map explicitly, or decode directly into the final modeled layout.

### 17. Synthesized audio timestamps break across sample-rate changes

[KiteCodecSource.kt:577](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:577>) applies the current rate to every accumulated sample since the timestamp anchor.

Fix: accumulate elapsed duration per buffer and rate, or re-anchor at every format transition.

### 18. Stop and close expose stale state

[PlaybackCore.kt:1689](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1689>) does not reset published position and progress. Terminal close retains media and track state. Statistics at [PlaybackCore.kt:1952](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1952>) can subtract a previous session baseline from a new zero-based counter, producing negative FPS despite a monotonic-total contract.

### 19. Invalid configuration can wedge the engine before the first read

[PlayerConfig.kt:93](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt:93>) accepts negative budgets, nonpositive thresholds and intervals, and undersized frame queues.

Effects include:

- Permanent over-budget state.
- Empty queues considered ready.
- Hot publication loops.
- Failure only after native resources have already been acquired.

Fix: validate the complete configuration before creating dispatchers or opening a session.

### 20. Cancellation and useful decoder errors are swallowed

[PlaybackCore.kt:994](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:994>) uses runCatching followed by getOrNull, losing diagnostics and potentially swallowing cancellation. [PlaybackCore.kt:1884](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1884>) collapses most failures into SourceUnavailable.

Fix:

- Rethrow cancellation.
- Retain all decoder candidate failures.
- Type failures according to their actual phase and source.

### 21. Backend warnings are disconnected from PlayerEvent

[KiteCodecMediaBackend.kt:24](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt:24>) defaults warning delivery to a no-op. Hardware fallback and codec degradation can therefore be silent.

Fix: add a backend/session warning stream or inject the core event reporter.

### 22. Fire-and-forget commands can disappear

[PlaybackCore.kt:477](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:477>) checks lifecycle state and then ignores the result of trySend. Closure between those actions silently drops the command.

Fix:

- Inspect and propagate send failure.
- Add awaitable lifecycle-fenced attach and detach APIs.

### 23. Finalization escapes structured concurrency

[PlaybackCore.kt:1751](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1751>) uses GlobalScope.async.

Fix: use an explicitly owned finalizer scope with a defined shutdown and failure policy.

### 24. Renderer-submission statistics contradict their contract

[PlaybackWorkers.kt:181](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackWorkers.kt:181>) increments submitted count before checking whether present accepted the frame.

### 25. A public FFmpeg frame leaks through an implementation dependency

[kiteplayer-ffmpeg/build.gradle.kts:118](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/build.gradle.kts:118>) declares KiteCodec as implementation, but [KiteCodecSource.kt:624](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:624>) publicly exposes KiteFrame.

Fix: hide the frame or publish the dependency as api.

### 26. Several size validations can overflow before validation

Examples:

- displayWidth in [PlayerState.kt:182](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:182>)
- width × height × 4 in [SubtitleCue.kt:131](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/SubtitleCue.kt:131>)
- capacityFrames × channels in [KotlinAudioRing.kt:86](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:86>)

Use checked Long arithmetic before narrowing to Int.

## P1: KiteCodec correctness and API integrity

### 1. Buffered Flow cancellation leaks queued native frames

[Frame.kt:7](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/Frame.kt:7>) claims buffer is safe. Standard Flow.buffer has no frame-aware undelivered-element cleanup. A buffered flow followed by take(1) can strand every independently owned native clone still in the channel.

Fix:

- Retract the blanket safety claim.
- Introduce a scoped FrameLease or useFrames API.
- Provide ownership-aware buffering with undelivered cleanup.
- Use a Cleaner only as a diagnostic fallback, not as the primary lifetime mechanism.

### 2. Transcoder filters are configured from codec parameters instead of decoder output

[Transcoder.native.kt:87](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Transcoder.native.kt:87>) builds filters from values obtained from AVCodecParameters. These values may be unknown, may differ from the decoder’s negotiated output, and do not account for midstream format changes.

Fix:

- Build lazily from the first decoded FrameInfo.
- Rebuild when format, dimensions, rate, or channel layout changes.

### 3. Known channel layouts are discarded and guessed from count

[StreamInfo.kt:41](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/StreamInfo.kt:41>) correctly states that channel count is insufficient. [FilterGraph.kt:16](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/FilterGraph.kt:16>) and [MediaSink.kt:92](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/MediaSink.kt:92>) nevertheless expose only a count. Native helpers then substitute a default layout. For example, 5.1(side) can silently become 5.1(back).

Fix: introduce a first-class ChannelLayout supporting masks, custom order, ambisonics, and an explicitly unspecified state.

### 4. Automatic video conversion is allocation-heavy and color-incorrect

[helpers_frame.c:61](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_frame.c:61>) creates and frees SwsContext and a destination frame per conversion. It copies only PTS, drops SAR, range, colorspace, and duration, and does not configure source or destination matrices. The repository’s own baseline records between 9 and 61 allocations per conversion.

Fix:

- Keep a persistent converter keyed by dimensions and formats.
- Use sws_getCachedContext.
- Call sws_setColorspaceDetails.
- Copy frame properties.
- Reject or explicitly download unsupported hardware frames.

### 5. MediaSink.close can report success without creating output

[MediaSink.native.kt:280](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSink.native.kt:280>) and [MediaSink.jvm.kt:189](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSink.jvm.kt:189>) write a trailer only when an earlier packet forced the header. Declaring streams and closing can perform no I/O and throw no error.

Fix:

- Finalization must ensure a header exists for declared streams.
- Write the trailer.
- Fail explicitly for zero-stream output.

### 6. Encoder flush errors are swallowed

[MediaSink.native.kt:291](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSink.native.kt:291>) and the JVM path ignore finish and close errors. Tail frames may be lost while the operation still reports success.

Fix: retain the first flush error, perform best-effort trailer and cleanup, and then throw with cleanup failures suppressed.

### 7. primaryVideo can choose album art

[MediaSource.native.kt:172](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt:172>) chooses the first video stream without excluding attached pictures, despite the contract at [ColorInfo.kt:147](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/ColorInfo.kt:147>).

Fix: exclude attached pictures and apply a documented selection policy based on default disposition or av_find_best_stream.

### 8. Foreign or forged StreamInfo values are accepted against another source

[MediaSource.native.kt:195](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt:195>) validates only type and index uniqueness. Because StreamInfo is a public data class, stream zero from source A can be passed to source B and interpreted using foreign timing and type metadata.

Fix:

- Use an opaque source-bound StreamHandle, or
- Canonicalize by index and verify every supplied field plus source identity.

### 9. Rational silently returns mathematically wrong results

[Rational.kt:80](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/Rational.kt:80>) repeatedly halves numerator and denominator to fit. When the denominator reaches zero it is coerced to one, destroying the represented value.

Example:

    Rational(50_000, 1) * Rational(50_000, 1)

returns 1,250,000,000/1 rather than 2,500,000,000/1. Long.MIN_VALUE negation and absolute-value cases also escape current checks.

Fix: use exact checked arithmetic and throw when the chosen representation cannot hold the result.

### 10. Negative valid container start times become zero

[helpers_format.c:108](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_format.c:108>) treats every start_time less than or equal to zero as absent. Only AV_NOPTS_VALUE means absent. Negative edit-list and priming starts are valid.

### 11. Color range cannot represent unspecified

[ColorInfo.kt:19](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/ColorInfo.kt:19>) models range as a Boolean. Every value other than explicit full is interpreted as limited.

Fix: model Unspecified, Limited, and Full, and separate declared metadata from a guessed rendering value.

### 12. Unknown video pixel formats silently become yuv420p

[helpers_filter.c:64](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_filter.c:64>) substitutes yuv420p for an unknown public pixel-format name.

Fix: validate in Kotlin and return EINVAL instead of silently declaring a different format.

### 13. SeekDirection.Any cannot implement its documented behavior

[Playback.native.kt:268](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt:268>) constrains the maximum seek position to the target, so it can never choose a closer later frame. AVSEEK_FLAG_ANY means non-keyframe allowed, not nearest on either side.

Fix: use honest naming and bounds that match the documented selection behavior.

### 14. Transcode progress measures frame start rather than processed end

[MediaSink.native.kt:372](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSink.native.kt:372>) omits video duration and audio sample count. Successful work may finish below 100 percent.

### 15. Native raw-frame construction has platform-divergent empty-input failure

[Frame.native.kt:322](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt:322>) calls addressOf(0) before producing the promised short-buffer error.

Fix: validate the required size in Kotlin before pinning.

## Rendering, Compose, audio, and subtitle defects

### Rendering and Compose

#### Android multi-image subtitles repeat image zero

[AndroidSurfaceVideoRenderer.kt:578](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:578>) does not advance the cache cursor after creating the first Bitmap. Every later image reuses image zero.

#### Overlay changes do not redraw a paused or still frame

Affected paths include:

- Android: [AndroidSurfaceVideoRenderer.kt:449](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:449>)
- Metal: [MetalVideoRenderer.kt:182](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:182>)
- UIKit: [UIKitVideoRenderer.kt:394](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRenderer.kt:394>)
- AppKit: [AppKitVideoRenderer.kt:494](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/macosArm64Main/kotlin/io/github/yuroyami/kiteplayer/output/AppKitVideoRenderer.kt:494>)

Cue changes and clears wait for another video frame and remain stale while paused.

Fix: retain a presentation-ready last frame for overlay-only redraw or use an independent subtitle layer.

#### Compose cannot show subtitles before the first frame or for audio-only media

[KiteVideo.kt:36](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteVideo.kt:36>) returns before drawing the overlay when no video image exists.

Fix: draw video conditionally, then draw overlays independently.

#### Compose and Metal place subtitles against the viewport rather than the fitted video

[KiteVideo.kt:59](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteVideo.kt:59>) and [MetalVideoSupport.kt:368](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:368>) disagree with Android’s correct picture-rectangle calculation.

Fix: centralize frame and overlay geometry, including fit/crop mode, rotation, and pixel aspect ratio.

#### Compose overlay publication races with close

[KiteVideoRenderer.kt:231](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:231>) can republish an older in-flight overlay after [KiteVideoRenderer.kt:275](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:275>) has closed and cleared the renderer.

Fix: serialize overlay work on the renderer worker or check a generation and closed state immediately before publication.

#### Transient overlay conversion failure becomes permanently cached

[KiteVideoRenderer.kt:240](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:240>) advances the overlay hash even if one or more images fail. The same overlay is never retried.

Fix: advance identity only after a fully successful build and retain the prior good overlay on failure.

#### Non-planar BGRA CVPixelBuffers create zero-sized textures

[MetalFrameComposer.kt:191](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:191>) calls plane width and height functions even when plane count is zero.

Fix: use CVPixelBufferGetWidth and CVPixelBufferGetHeight for non-planar buffers.

#### Odd-sized YUV frames truncate chroma dimensions

[MetalFrameComposer.kt:174](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:174>) uses floor shifts.

Fix: use ceil division for chroma planes.

#### Metal rotation is not normalized

[MetalVideoSupport.kt:342](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:342>) handles only literal 90, 180, and 270 degrees. Values such as -90 and 450 render unrotated.

Fix: normalize modulo 360 through a shared quarter-turn helper.

#### Metal hardware-texture wrappers leak on pre-commit encoding failure

[MetalFrameComposer.kt:103](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:103>) installs completion cleanup after multiple operations that may throw.

Fix: use failure cleanup around construction and transfer ownership only after the completion handler is installed.

#### The Metal texture cache and native holder are never released

[MetalFrameComposer.kt:244](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:244>) needs an explicit close path that fences the worker and GPU, releases the cache, and frees the native holder.

#### The CV texture cache is flushed after every hardware frame

[MetalFrameComposer.kt:237](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:237>) defeats the cache’s purpose.

Fix: flush only on teardown, memory pressure, or actual invalidation.

#### Switching Metal to CoreGraphics can leave stale Metal content covering new frames

[KitePlayerUIView.kt:46](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-phone/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/phone/KitePlayerUIView.kt:46>) keeps both layers visible.

Fix: hide and clear the inactive layer whenever renderer generation changes.

#### hasPicture derives current state from cumulative history

[KitePlayerUIView.kt:126](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-phone/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/phone/KitePlayerUIView.kt:126>) can report a picture before the current renderer generation has presented.

Fix: maintain presentation state per generation.

#### Phone attach and detach are not exception-safe

[PlayerViewBinding.kt:70](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-phone/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/phone/PlayerViewBinding.kt:70>) stores the renderer before attach succeeds. Detach can skip player detachment if close throws.

Fix:

- Commit the reference only after successful attachment.
- Ensure detach runs in finally.
- Make detach idempotent rather than broadly swallowing IllegalStateException.

#### Renderer close can block UI lifecycle callbacks

Multiple close implementations use runBlocking, including [AndroidSurfaceVideoRenderer.kt:467](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:467>) and [UIKitVideoRenderer.kt:403](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRenderer.kt:403>).

Conversion, drawable acquisition, or canvas locking can therefore freeze lifecycle and UI threads.

Fix: detach native surfaces immediately, then perform bounded fenced teardown in an owned lifecycle scope.

#### AppKit Metal drawable size is frozen at construction

[AppKitWindow.kt:89](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/macosArm64Main/kotlin/io/github/yuroyami/kiteplayer/output/AppKitWindow.kt:89>) does not update on resize or backing-scale changes.

#### Apple fallback renderers reject valid RgbaBitmap storage

The model accepts pixel storage larger than the minimum required size. [UIKitVideoRenderer.kt:365](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRenderer.kt:365>) and AppKit require exact equality.

Fix: either require exact size everywhere or add an explicit stride and consistently honor it.

### Audio

#### Android counts unwritten frames as submitted

[AudioTrackSink.kt:268](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:268>) increments by a whole block even when pause or stop interrupts a partial write.

Fix: advance submittedFrames only by complete written frames derived from the actual float offset.

#### Duplicate resume can create two writer threads

[AudioTrackSink.kt:186](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:186>) lacks an idempotent lifecycle transition and a writerRun guard equivalent to start.

Fix: use an explicit locked lifecycle state machine.

#### Writer failure permanently bricks the Android sink

[AudioTrackSink.kt:250](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:250>) exits without resetting writerRun. Later starts return early.

Fix:

- Catch callback, timestamp, device, and write failure.
- Reset lifecycle state in finally.
- Emit device-lost or write-failure events.
- Recreate the AudioTrack when recoverable.

#### Android polls and allocates AudioTimestamp almost every 512 frames

[AudioTrackSink.kt:295](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:295>) can poll roughly 94 times per second at 48 kHz. The driver and Android internals allocate for each sample.

Fix: sample sparsely, cache the clock mapping, and extrapolate between samples.

#### AudioTrack timestamp frame position is not extended across 32-bit wrap

[AudioTrackSink.kt:312](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:312>) treats wrap as regression. At 48 kHz it occurs after about 24.85 hours.

Fix: use the same unsigned wrap extension already used for playback-head position.

#### CoreAudio drain can stop before the final callback buffer is audible

[kite_rt_render.c:228](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_render.c:228>) publishes ring consumption before the callback deadline is stored. [CoreAudioSink.kt:370](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt:370>) can observe an empty ring paired with the previous deadline.

Fix: publish deadline before the consumed release or expose a coupled atomic drain generation.

#### CoreAudio statistics contain a C data race

The running field is ordinary storage in [kite_rt_sink_internal.h:74](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_sink_internal.h:74>) but is read concurrently by [kite_rt_coreaudio.c:497](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:497>).

Fix: make the field atomic or narrow and enforce the API’s synchronization contract.

#### CoreAudio reports a fictional fixed device period

[kite_rt_coreaudio.c:58](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:58>) hardcodes 512 frames.

Fix: query the current and maximum device period and update it after route changes.

#### Audio output is limited to mono or stereo F32

[AudioTrackSink.kt:101](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:101>) and [kite_rt_coreaudio.c:67](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:67>) exclude multichannel layouts, passthrough, offload, device selection, and complete route recovery.

### Subtitles

#### Straight-alpha contract is violated

[SubtitleCue.kt:119](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/SubtitleCue.kt:119>) promises straight RGBA. [AndroidSubtitleRasterizer.kt:121](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt:121>) and [AppleSubtitleRasterizer.kt:163](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt:163>) produce premultiplied pixels. Android Surface and Metal multiply alpha again, darkening antialiased edges.

Fix:

- Preserve straight RGBA in the public model and premultiply exactly once at upload, or
- Add an explicit alpha-type field and honor it everywhere.

#### Apple subtitle rasterization leaks CoreFoundation and CoreText objects

[AppleSubtitleRasterizer.kt:128](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt:128>) creates fonts, colors, a color space, framesetter, path, frame, and outline color without balancing every create or copy.

Fix: put all Create-rule objects under explicit try/finally ownership and test sustained cue churn with leak instrumentation.

#### Bitmap region dimensions are ignored

[AndroidSubtitleRasterizer.kt:55](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt:55>) and [AppleSubtitleRasterizer.kt:91](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt:91>) scale position from authoring space but retain source bitmap dimensions.

Fix: carry destination dimensions into OverlayImage or resample to the declared region.

#### Malformed or backwards SRT cues are documented as open-ended but never display

[SubRipParser.kt:85](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt:85>) emits end equal to start. [CueSelector.kt:18](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt:18>) requires time to be less than end.

Fix: resolve the cue against the next cue after sorting, apply a documented default duration, represent open end, or reject it with diagnostics.

#### WebVTT drops valid identifiers beginning with reserved words

[WebVttParser.kt:33](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:33>) uses broad startsWith checks for NOTE, STYLE, and REGION.

Fix: recognize keywords only when followed by grammar-appropriate whitespace or end-of-line.

#### SRT and WebVTT entities render literally

[SubRipParser.kt:118](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt:118>) and [WebVttParser.kt:103](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:103>) remove tags but never decode amp, lt, gt, or nbsp entities.

#### Public styling exceeds actual implementation

Rasterizers let the first span choose global properties and incompletely apply font family, size, shadow, wrapping, offset, decoration, and stroke behavior.

Fix: implement per-span attributed layout or narrow public styling claims.

#### Explicitly positioned bottom cues consume implicit stacking space

[AndroidSubtitleRasterizer.kt:48](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt:48>) and the Apple equivalent shift later implicit cues unnecessarily.

## Public APIs that exist but do not behave as advertised

These are more damaging to predictability than absent APIs because callers reasonably assume they work.

### Media and player configuration

- [MediaItem.kt:6](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt:6>): headers, externalSubtitles, startPosition, custom io, and formatHint are unused or rejected. Only URI and open options materially work.
- [PlayerConfig.kt:20](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt:20>): logger is unused.
- [PlayerConfig.kt:93](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt:93>): liveBackBuffer and liveMaxLag are unused.
- [PlayerConfig.kt:119](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt:119>): preservePitch, assumed latency when unreliable, and startDisabled are unused.
- [MediaItem.kt:113](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt:113>): KeyframeThenRefine behaves like precise seek.

### Published state and policies

- [PlayerState.kt:69](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:69>): buffered ranges are always empty.
- [PlayerState.kt:109](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:109>): dropped decode frames remain zero.
- [PlayerState.kt:153](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:153>): audio latency is always zero.
- [PlayerState.kt:163](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:163>): container bitrate remains null.
- [PlayerState.kt:207](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:207>): ExternalMaster is accepted but not implemented.
- [PlayerState.kt:282](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt:282>): LateAndDecode behaves like LateOnly.

### Output SPI

- [AudioSink.kt:62](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt:62>): latency, events, and planar writes are not integrated by the engine.
- [VideoRenderer.kt:53](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt:53>): renderer events are not collected.
- [OutputBackend.kt:32](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt:32>): renderer and factory portions are unused.
- [CoreAudioSink.kt:238](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt:238>): CoreAudioSink implements AudioSink, but ordinary open always throws because it requires the native-ring protocol.
- Phone and Compose surfaces accept a generic player or backend and then hard-cast frames to FFmpeg-specific types. Unsupported combinations can produce runtime failure or a black surface instead of a capability error.

Recommendation: implement these immediately or remove or deprecate them. A smaller truthful API is more Kotlin-like than a broad configuration surface filled with placeholders.

## Functionality required for the stated parity target

### Transport, I/O, and streaming

The packaged FFmpeg configuration at [BuildFFmpegTask.kt:328](</Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt:328>) disables all protocols and enables only file, fd, pipe, data, http, and tcp.

Consequences:

- No HTTPS or TLS in default artifacts.
- No UDP, RTP, or RTSP transport foundation.
- No dependable internet HLS or DASH story.
- No live or DVR back-buffer semantics.
- No authenticated-header integration despite the public MediaItem field.
- No reconnect, backoff, or network-transition policy.

There is also no AVIOInterruptCB. Blocking open, read, and seek calls such as [MediaSource.native.kt:237](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt:237>) observe coroutine cancellation only between FFmpeg calls. A wedged network operation can make stop and close non-cancellable.

Required design:

- Atomic cancellation and deadline token connected to AVFormatContext.interrupt_callback.
- Suspend-friendly open, read, and seek wrappers with explicit timeout outcomes.
- avio_alloc_context support for memory, assets, Android content URIs, Ktor streams, and custom sinks.
- Tiered protocol profiles: local/minimal, secure-streaming, and broad/full.
- Security-conscious allowlists rather than blindly enabling every protocol.

### Media breadth

Missing or materially incomplete:

- Container-aware bitstream filters for H.264 or HEVC MP4-to-TS and AAC ASC or ADTS conversion.
- Subtitle, data, attachment, and timed-metadata decoding in KiteCodec.
- ASS and SSA with shaping and font attachments.
- PGS, VobSub or DVD, DVB, CEA-608 and CEA-708, and TTML.
- Complete WebVTT regions, vertical text, ruby, classes, streaming parsing, encodings, and BOM handling.
- Explicit multi-stream maps with copy, transcode, and filter decisions per stream.
- Chapters, attachments, metadata, and disposition mapping.
- Playlist, queue, gapless transition, and pre-open of the next source.
- Pitch-preserving speed.
- Audio equalizer, balance, headroom or limiter, output-device enumeration, passthrough, offload, and hotplug recovery.
- Frame stepping and accurate frame navigation.
- HDR metadata: mastering display, content light level, HDR10+, Dolby Vision, and dynamic metadata.
- Transfer-function handling and tone mapping.
- Deinterlacing, chroma siting, scaler-quality policy, crop, and display matrix.
- Capability enumeration for codecs, formats, filters, protocols, devices, and hardware configurations.

### Hardware and target coverage

- KiteCodec models only VideoToolbox as a concrete hardware path.
- The current generic data[3] surface abstraction is not portable to MediaCodec, CUDA, DRM PRIME, Vulkan, VAAPI, D3D11, and similar systems.
- Android output has no zero-copy MediaCodec-to-Surface or AHardwareBuffer path.
- Hardware codec coverage is narrow and does not include broad VP9, AV1, MPEG-2, or desktop acceleration.
- Output implementations are absent for Linux, Windows, Intel macOS, and tvOS.
- Compose output is absent for desktop and Web.

Use a sealed, pixel-format-aware hardware surface model plus explicit renderer capability negotiation. Output modules should not discover support by runtime casts.

## Performance findings

### Largest blockers

#### 1. Android’s flagship path is entirely CPU-bound

[AndroidSurfaceVideoRenderer.kt:191](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:191>) converts to RGBA, performs another Kotlin channel or copy pass, writes a Bitmap, and renders through lockCanvas.

This cannot match libmpv or libvlc at 4K and high frame rates.

Required direction:

- MediaCodec directly to Surface where possible.
- AHardwareBuffer, GLES, or Vulkan interop for composited paths.
- Skia GPU image or shader integration for Compose.
- CPU RGBA only as a compatibility fallback.

#### 2. Apple Compose performs GPU to CPU to Skia raster to GPU

[ImageBitmaps.ios.kt:46](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/compose/ImageBitmaps.ios.kt:46>) and [MetalPictureReader.kt:59](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalPictureReader.kt:59>) synchronously read Metal output back into CPU storage, create a raster image, and upload again.

Required direction:

- Expose fence-backed external textures or Skia backend images.
- Or render YUV directly with a Skia runtime shader.

#### 3. KiteCodec rebuilds conversion state per frame

Persistent SwsContext and SwrContext owners are required. Conversion metadata and color behavior must be correct before optimizing around them.

The target frame path should be:

    FFmpeg hardware surface or software planes
        -> typed frame/surface with color metadata and fence
        -> renderer capability negotiation
        -> Metal, Android Surface, or Skia GPU shader
        -> CPU RGBA only as fallback

“Skia-backed” should mean that Skia consumes the existing GPU resource or YUV planes. It should not mean every frame becomes a CPU ImageBitmap.

### Additional hot-path costs

- [KiteCodecSource.kt:692](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:692>) copies native audio into ByteArray and then FloatArray; the player interleaves or copies again.
- [AudioPipeline.kt:104](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt:104>) copies even for identity transformations.
- [Frame.native.kt:151](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt:151>) allocates native scratch and then a second ByteArray. Pin the destination and copy into it directly.
- [Frame.jvm.kt:222](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Frame.jvm.kt:222>) copies before JNI, which malloc-copies again. Use direct buffers or critical arrays and batch metadata snapshots.
- [Playback.native.kt:511](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt:511>) allocates lists and boxes plane metadata for nominally zero-copy access. Prefer inline scoped iteration.
- [PlatformDispatchers.kt:37](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlatformDispatchers.kt:37>) results in roughly six physical threads per player. Serial confinement does not require one OS thread per lane. Use shared executors and serial or limited-parallelism lanes, preserving pinned threads only where platform APIs require them.
- Subtitle processing sorts retained history, scans from the beginning, does not prune aggressively, and rasterizes on the core actor at [PlaybackCore.kt:1266](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1266>). Use append or merge, a playback cursor, pruning, and a separate raster worker.
- Core passes repeatedly allocate queue and worker lists and publish full snapshots at [PlaybackCore.kt:1927](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1927>). Use fixed references, dirty flags, and cached samples.
- Android Compose reuses mutable bitmaps without a GPU-consumption fence at [ImageBitmaps.android.kt:11](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-compose/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/compose/ImageBitmaps.android.kt:11>), risking visible mutation under jank.
- Metal pipelines are compiled per renderer or reader rather than cached per device.
- UIKit and AppKit CPU fallbacks recreate transformed full frames and subtitle CGImages even for identity geometry.
- [LinearResampler.kt:1](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/LinearResampler.kt:1>) is a low-quality linear resampler and aliases under meaningful rate changes.
- [ChannelMixer.kt:253](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:253>) does not reliably remap equal-count but different layouts and lacks headroom or limiting for surround downmix.
- Track changes reopen the complete backend session rather than reconfiguring only the affected decoder. That reconnects network inputs and cannot work reliably for live or nonseekable media.

## C that can be removed

### KiteCodec: most helper C should be replaced

The Native cinterop at [ffmpeg.def:8](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def:8>) exposes opaque KiteCodec helper types instead of FFmpeg’s public headers. This design created hundreds of one-line C getters, setters, and call-throughs.

On Kotlin/Native, import the public FFmpeg headers directly and call libav from Kotlin. A direct cinterop call or field access costs no more than calling a C wrapper that immediately calls FFmpeg.

| Area | Direction |
|---|---|
| [helpers_packet.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_packet.c:18>) | Replace entirely with direct Native cinterop |
| [helpers_codecpar.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_codecpar.c:17>) | Replace getters and copies directly |
| [helpers_stream.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_stream.c:19>) | Replace directly |
| [helpers_error.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_error.c:18>) | Use Kotlin memScoped with av_strerror and direct av_rescale_q |
| Trivial parts of [helpers_frame.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_frame.c:24>) | Replace allocation, field, and format wrappers |
| Trivial parts of [helpers_codec.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_codec.c:19>) | Replace codec calls and configuration |
| Most of [helpers_format.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_format.c:19>) | Replace open, read, write, and metadata wrappers |
| Most of [helpers_playback.c](</Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_playback.c:35>) | Replace seek, discard, and plane access |
| Filter-description composition | Move to common Kotlin and remove the fixed 2048-byte C buffer |
| Stateful filter and conversion helpers | Use Kotlin owners holding FFmpeg native state; do not reimplement media algorithms |

Keep:

- The JVM and Android JNI adapter. Java and Android still need a native bridge. Fix lifetime pinning and batch calls rather than pretending JNI can disappear.
- A small ABI and header/runtime identity probe.
- The hardware get_format callback until callback and context lifetime is redesigned and measured.
- FFmpeg itself and its optimized C and assembly algorithms.

The correct goal is not zero C. It is no redundant C abstraction on Kotlin/Native.

### KitePlayer: retain a tiny hard-real-time C island

Keep in unmanaged native code:

- CoreAudio’s static callback.
- kprt_render_into and kprt_ring_render.
- Timestamp and anchor publication.
- The callback’s atomics, memcpy, and zero-fill operations.
- The C-owned ring and callback state.
- Producer operations that share the same C11 atomic state.

Moving this path into Kotlin/Native is not currently a proven zero-cost change. A static Kotlin callback still enters Kotlin-generated or runtime code, and mixing Kotlin atomics with a remaining C callback introduces two memory-model boundaries.

Move to Kotlin/Native without steady-state audio overhead:

- Non-real-time AudioUnit discovery, configuration, start, stop, and disposal currently in [kite_rt_coreaudio.c:258](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:258>).
- AVAudioSession policy.
- Route, interruption, and media-service handling.
- Capability and device-period queries.
- Error mapping.
- Unsupported-platform C stubs as expect and actual declarations.
- Text subtitle parsing, state machines, option mapping, buffering, and non-real-time DSP.

Also remove raw C types from the public core API. [NativeRingAudioSink.kt:60](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/spi/NativeRingAudioSink.kt:60>) exposes CPointer of kprt_ring and forces the cinterop klib into the core ABI. Hide it behind an opaque Kotlin writer or handoff owned by the RT or output module.

Android needs no new C for MediaCodec-to-Surface or AudioTrack. Use the Android platform APIs directly from Kotlin.

## Kotlin modernization

Kotlin 2.4 makes context parameters stable and enabled by default. The build already warns that the explicit context-parameters compiler flag is redundant at [kitecodec-core/build.gradle.kts:54](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/build.gradle.kts:54>). Remove the flag.

Good targeted use:

    context(session: OpenSession, worker: Worker)
    private suspend fun decodeAudio(...)

This fits the worker helper cluster at [PlaybackCore.kt:2200](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2200>), where session and worker ownership context is passed repeatedly. Keep epoch and generation explicit because they are correctness boundaries.

A Codec execution context is also reasonable:

    context(codec: CodecExecutionContext)
    suspend fun Transcoder.transcode(request: TranscodeRequest)

It may carry:

- Dispatcher.
- Interrupt and deadline token.
- Log sink.
- Hardware-fallback policy.

Avoid:

- Hiding Frame, MediaSource, MediaSink, or other native owners in context parameters.
- Using context parameters as broad service location or dependency injection.
- Performing syntax churn in the large player core before repairing ownership and transactions.

Higher-value Kotlin changes than syntax alone:

- Sealed transactional outcomes instead of Unit.
- Structured finalizer scopes instead of GlobalScope.
- Source-bound opaque or value types instead of forgeable public data classes.
- Ownership-aware FrameLease and useFrames APIs.
- Inline plane iteration instead of list allocation and boxing.
- A checked packed Rational value class if 32-bit components remain the desired ABI.
- Shared explicit lifecycle state machines for renderers.
- Central checked-size helpers for buffers, images, samples, and planes.
- Resource ledgers and use-style ownership for partial native construction.

## Twin-library build and publication defects

The repositories do not currently behave as one atomic source graph:

- KiteCodec is version 0.0.6 at [gradle.properties:19](</Users/macbook/StudioProjects/#Kite/KiteCodec/gradle.properties:19>).
- KitePlayer hardcodes the KiteCodec plugin at 0.0.1 while depending on core 0.0.6 at [kiteplayer-ffmpeg/build.gradle.kts:17](</Users/macbook/StudioProjects/#Kite/KitePlayer/kiteplayer-ffmpeg/build.gradle.kts:17>).
- [KitePlayer settings.gradle.kts:3](</Users/macbook/StudioProjects/#Kite/KitePlayer/settings.gradle.kts:3>) puts mavenLocal first, allowing stale local artifacts to shadow the current sibling checkout.

Fix:

- Use a Gradle composite build with includeBuild of the sibling and dependency plus plugin substitution, or
- Put both projects under a shared root build.
- CI must always compile Player against current Codec source and run cross-repository ABI and API tests.

Additional confirmed build defects:

- [kitecodec-core/build.gradle.kts:22](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/build.gradle.kts:22>) applies the Android KMP plugin unconditionally, but Android and compileSdk configuration exist only in the phone-target branch at [kitecodec-core/build.gradle.kts:186](</Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/build.gradle.kts:186>). Host-only configuration fails before tests.
- Native static macOS linking omits -llzma in [StaticLinkFlags.kt:106](</Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/StaticLinkFlags.kt:106>) and [PrebuiltLinkFlags.kt:43](</Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/PrebuiltLinkFlags.kt:43>), even though the JNI list includes it. TIFF-enabled libavcodec fails to link.
- Vendored FFmpeg and third-party archives were built with a macOS 26 deployment version while Kotlin/Native links a macOS 12 binary. [BuildFFmpegTask.kt:463](</Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt:463>) does not pin one deployment floor; the shim separately uses macOS 11.
- BuildSrc FFmpeg tests have stale configuration goldens at [BuildFFmpegTaskTest.kt:39](</Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/test/kotlin/BuildFFmpegTaskTest.kt:39>).
- Normal JVM and Android publication exists only in local phone scope; stable remote publication does not provide the expected ordinary Android JVM or AAR artifact.
- JNI packaging omits armeabi-v7a.
- Both builds emit deprecated Gradle API warnings that will become Gradle 10 incompatibilities.

## Validation performed

Passed:

    KitePlayer:
      :buildSrc:test
      :kiteplayer-core:jvmTest
      :kiteplayer-subtitles:jvmTest
      :kiteplayer-ffmpeg:macosArm64Test
      :kiteplayer-output:macosArm64Test

    KiteCodec:
      :kitecodec-core:jvmTest -Pkitecodec.phoneTargetsOnly=true

Failed for confirmed repository reasons:

    :kitecodec-core:macosArm64Test -Pkitecodec.hostTargetsOnly=true
      Configuration failure: Android compileSdk is not configured.

    buildSrc:test
      Three stale BuildFFmpegTask golden expectations.

    :kitecodec-core:macosArm64Test -Pkitecodec.phoneTargetsOnly=true
      Native linker failure:
      missing _lzma_code, _lzma_end, and _lzma_stream_decoder.

The native macOS link also prints extensive deployment-version mismatch warnings.

The passing tests do not cover the highest-risk paths. Missing regressions include:

- Cached Frame.info followed by close and plane or surface access.
- Retaining a filter callback frame after callback return.
- Concurrent JNI operation and close.
- 32-bit near-boundary ring allocation under ASan.
- Failed quiescence during seek and renderer replacement.
- Cancellation after partial audio submission.
- Device-sleep clock-epoch verification.
- 24-hour-equivalent AudioTrack timestamp wrap simulation.
- Multiple simultaneous subtitle images.
- Straight-alpha and premultiplied-alpha golden tests.
- Non-planar BGRA and odd-sized Metal frames.
- Failed CoreAudio shutdown with a simulated live callback.
- Attached-picture-first media.
- Negative media start time.
- Foreign StreamInfo.
- Decoder output format differing from codec parameters.
- Empty-output MediaSink finalization.
- Midstream format changes.
- Secure-protocol and static-prebuilt link smoke tests.

## Recommended remediation order

### 1. Freeze feature expansion and repair safety

Fix:

- The three KiteCodec lifetime faults.
- The 32-bit ring overflow.
- Metal plane validation.
- CoreAudio shutdown proof.
- Android clock-epoch mismatch.
- Failed-quiescence behavior.

Add sanitizers and focused concurrency tests before resuming broad feature work.

### 2. Make the player transactional

Introduce explicit outcomes for:

- Open.
- Seek.
- Track selection.
- Renderer swap.
- Stop and close.
- Attach and detach.

Construction must roll back. Mutation must require quiescence. Supersession and preemption must never report success.

### 3. Make ownership explicit across both libraries

Add:

- Source-bound stream handles.
- Frame leases.
- A reverse-order resource ledger.
- Opaque native-ring handoff.
- Consistent close invalidation.
- Explicit lifetime and fence ownership on hardware surfaces.

### 4. Repair the twin build

- Composite-build the current sibling source.
- Unify versions.
- Unify deployment targets.
- Correct static link flags.
- Publish the actual Android artifacts.
- Make every supported target configure and link in CI.

### 5. Replace the presentation pipeline rather than micro-optimizing it

- Add hardware-surface capability negotiation.
- Add fence-backed Metal, Android Surface, and Skia paths.
- Keep CPU RGBA only as a compatibility fallback.

### 6. Make the public surface truthful

- Remove or implement inert fields, policies, statistics, and callbacks.
- Split push-audio from native-ring audio.
- Split generic video frames from FFmpeg-specific frames.
- Make every async mutation awaitable or explicitly fire-and-forget with a result.

### 7. Add streaming foundations

Before claiming “plays anything,” implement:

- Secure protocols.
- Interrupt callbacks and deadlines.
- Custom I/O.
- Reconnect policy.
- Bitstream filters.
- Adaptive and live semantics.

### 8. Reduce C deliberately

- Move Native FFmpeg wrappers and non-real-time Apple control code to Kotlin.
- Keep JNI.
- Keep the minimal, measured hard-real-time callback and ring in C.

### 9. Broaden parity only after the foundation is correct

Then implement:

- HDR and color management.
- Complete subtitles.
- Device control.
- Multichannel and passthrough audio.
- Filters and pitch-preserving rate.
- Playlist and gapless behavior.
- Additional desktop and platform outputs.

## Central architectural recommendation

The highest-leverage change is a typed, ownership-aware frame and surface contract shared by KiteCodec and KitePlayer.

It should carry:

- Software plane or hardware-surface type.
- Pixel or sample format.
- Complete color metadata.
- Channel layout.
- Dimensions, stride, and checked storage bounds.
- Lifetime and close ownership.
- GPU or decoder fence.
- Presentation and conversion capabilities.
- Explicit fallback behavior.

That one boundary would eliminate many current runtime casts, copies, redundant C wrappers, undefined ownership rules, and renderer-specific state drift.
