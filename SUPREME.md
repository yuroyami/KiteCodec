# SUPREME: Fable 5 verification pass over SOLSUPREME.md

- Pass date: 2026-08-18, two rounds (24-claim spot check, then a full sweep)
- Checked against: KiteCodec `f135ae2` (exact audit snapshot), KitePlayer `a743f56` (one commit past the audit snapshot `77ba7e6`, only a test rename)
- Method: single-threaded, direct file reads at every cited line. No agents, no guessing, no re-running of tests.

## 1. Verdict in one paragraph

SOLSUPREME.md is trustworthy end to end. I verified roughly 100 of its concrete code claims by reading the exact cited files and lines: all 20 P0s, the large majority of both P1 tables, the C/JNI section, the DSL audit, the doc-contradiction table, the KitePlayer audio/video/network/API sections, and the build rows. Result: one claim overstated (a fix already landed), a handful need severity framing softened because the code openly documents the limitation, and everything else held, usually word for word. Adopt SOLSUPREME as the working audit. This doc records the verification, the corrections, my own additional findings, and a compressed execution order.

## 2. Coverage and score

| Area of SOLSUPREME | Claims eyeballed | Held | Notes |
|---|---|---|---|
| P0-01 .. P0-20 | all 20 | 20 | Every one confirmed in code, most at the exact cited line |
| Section 7 (KiteCodec P1 tables) | ~28 of 35 | all checked held | P1-23 is half-fixed by the final Web RGBA commit (details below) |
| Section 8 (C/JNI bridge + filter/frame C) | ~16 of 21 | all checked held | Including the 31-vs-32 generation-bit mismatch and the token-kind bits never being validated |
| Section 10 (DSL audit) | 6 of 10 | 6 | Untyped steps, no `@DslMarker`, unvalidated ints, raw map overriding typed keys, CBR-as-VBV all confirmed |
| Section 11 (performance) | 3 of 10 | 3 | Per-byte Web crossing confirmed at the source; JVM copy-count claims not traced call by call |
| Section 12 (build/plugin/fetcher) | ~10 of 19 | all checked held | Native-only plugin, always-erroring BuildFromSource, auto-redirects undermining per-hop checks, config-cache disabled on the paths that matter |
| Section 13 (doc contradictions) | 5 of 10 | 5 | README 0.0.1 vs `VERSION=0.0.9`, minSdk 26, VideoToolbox enabled, JS-vs-Wasm split, JVM real backend |
| Section 15.1-15.2 (player session/semantics) | ~14 of 24 | all checked held | Including superseded-step-counts-as-success and the hardcoded "filters: none attached" diagnostic |
| Section 15.3 (audio) | 8 of 10 | 8 | One C claim (5.1 tag for every count above 2) confirmed including the ignored set verdict |
| Section 15.4 (video/Web) | 8 of 12 | 7 | One overstated: see section 3, item 1 |
| Section 15.5 (libass) | 4 of 7 | 4 | Unconditional loadLibrary, ctor leak, stale "JNI missing" comment all confirmed |
| Section 15.6 (network/DASH) | 5 of 7 | 5 | Unvalidated 206, lazy unvalidated seek, unbounded MPD expansion confirmed |
| Section 15.7 (public API) | 8 of 14 | 8 | Pts wrap, throwing stubs, dead videoRenderer property, stale mobile KDoc, raw options in support bundle |
| Section 15.9 (build rows KP-B1..B13) | 7 of 13 | 7 | Debuggable release sample, no wrapper checksum, lexicographic NDK pick, no publish plugin, no CI dir |
| Section 16 (Gemini seam) | 3 of 9 | 3 | Hardcoded 0.0.9, `api` leak of KiteCodec Frame, non-transactional reader/source close |
| Section 22 (executed commands) | 0 | n/a | Cannot be verified by reading. Not re-run. |

Overall: ~100 checks, 1 overstated, 0 fabricated, everything else confirmed.

## 3. Corrections to SOLSUPREME

1. **MediaCodec presentation-delay wrap (15.4 item 12): overstated or stale.** Current `MediaCodecBufferFrame.kt:105-112` computes `delay = targetNanos - engineNowNanos` and then guards the addition: `if (delay > 0 && codecNow > Long.MAX_VALUE - delay) Long.MAX_VALUE else codecNow + delay`. The positive-overflow clamp SOL asks for is already there. Only the initial subtraction is unchecked, which needs adversarial clock values to matter. Downgrade to a nit.
2. **P1-23 is now half-fixed.** The final Web RGBA commit made the pixel conversion full-range-correct (`helpers_frame.c:103`). What remains: `av_frame_copy_props` at line 120 re-stamps the source's MPEG range and BT470BG matrix onto the RGBA output, and `test_convert.c:240-245` asserts those wrong tags as correct. Fix is small: set output-accurate range/matrix after `copy_props`, flip the oracle.
3. **P0-12's "falls back instead of refusing" reads harsher than reality.** Unknown OS/CPU maps to `linux-x64`, but the loader then throws a clear `UnsatisfiedLinkError` naming the directory. Misleading label, not silent breakage.
4. **A candor ledger is missing.** Many confirmed findings are not hidden bugs but openly documented interim states, often with a roadmap tag in the same KDoc: LinearResampler ("no document may present it as the production rate conversion"), ChannelMixer ("no normalisation and no limiter... can clip"), the equal-count reorder skip ("Horizon B concern"), the Web whole-file staging (long design comment plus explicit typed refusals for unknown size and >512 MiB), glFinish on API 29-32 (no public fence API), the debuggable Android sample ("smoke oracle reads private files through run-as"), and the CoreAudio 5.1 tag ("best effort" comment). The defects are real and SOL's corrections are right, but a reader of SOLSUPREME alone would think the team does not know. They know, and wrote it down. Prioritize by user harm, not by how alarming the finding reads.
5. Trivia: `methods.def` has 192 grep hits for `KJ_METHOD` (190 rows plus macro lines). KitePlayer moved one commit past the audit snapshot (a test rename only). Some earlier-audit fixes are already tagged in code (`audit P1-5`, `audit P1-6`, `audit F-EOS1`, `F-DASH1`, `SOL-A4`, `SOL-A6`); do not re-fix those.

## 4. Findings SOLSUPREME missed or understated (the cherries)

1. **Wasm option reporting is broken by a comment describing C behavior that does not exist.** Sharpens P1-06. `MediaSource.wasmJs.kt:342-349` claims `ffkmp_fmt_open_input_io` NULLs the key-array entry for every consumed option. The C function (`helpers_format.c:216-292`) never writes to the key array; it returns the unused set through an `AVDictionary** unused` out-param instead, and the Wasm JS glue at `MediaSource.wasmJs.kt:325` passes literal `0` for that parameter. So `survivingKeys` reads back its own untouched pointers and reports EVERY option as unused, every time. Two-part fix: pass a real out-slot and read the dictionary, delete the fictional comment.
2. **Wasm drops a refused packet and sends its drain signal once.** In `MediaSource.wasmJs.kt:102-105` the drain branch calls `decoder.send(null)` exactly once and never retries it, while `receive()` collapses EAGAIN and EOF. **Correction, 2026-08-18:** I first wrote that this loses a tail "on every run for every buffered codec". A probe against real H.264 media disproved that. The old loop drains fully after every send, so the decoder's output queue is never full when the next send arrives and EAGAIN never occurs: old and new loops both decoded 300 of 300 frames, with zero refusals. The defect is real as a latent fragility, and it does bite `extractFrame`, which called `receive` only once per packet and so could fill the queue. But it is not the everyday data loss I claimed. Strengthens P0-01/P0-02 as a correctness fix, not as an active bug.
3. **The Wasm decoder-leak comment is factually wrong about FFmpeg.** `Playback.wasmJs.kt:193` says the container close "already tore the decoder's context down". `avformat_close_input` never frees a codec context the caller allocated with `avcodec_alloc_context3`. One-line fix: always free the decoder's own context. Strengthens P0-05.
4. **`WebCanvasVideoRenderer.supports()` contradicts its own KDoc in the same file.** The comment says a hardware frame must be refused at attach "instead of at present, per frame". The body is `supports(format) = state != null`: the `format` argument is ignored, everything including `Opaque` passes, and the refusal happens per frame in `present`. The code documents the right rule and implements the wrong one. Sharpens 15.4 item 5.
5. **The displaced-reply-success bug is visible in a second place.** `addExternalSubtitle` (`PlaybackCore.kt:312`) does `pendingTrackChange?.reply?.complete(Unit)`: a displaced track-change caller is told success for work that never ran. Same disease as KP-P1-01, one more site to fix in the same transaction rework.
6. **The early sink EOS widens P0-20's window.** `PlaybackCore.kt:2638-2642` tells the sink `endOfStream()` when the decoder is drained and the packet queue is empty, while up to four decoded buffers plus the WSOLA tail can still be in flight. Any P0-20 fix must move or gate this signal too.
7. **The codec version is pinned twice in KitePlayer.** `gradle/libs.versions.toml:8` has `kitecodec = "0.0.9"`, while `kiteplayer-ffmpeg/build.gradle.kts:105` hardcodes the coordinate string and bypasses the catalog. Route it through the catalog before any BOM work.
8. **The `$key` literal bug has a two-line fix.** `Playback.jvm.kt:236` and `Playback.native.kt:462` print the literal text `$key` in error messages. The sink paths interpolate correctly, which is itself the drift SOL predicts from hand-copied glue.

## 5. What remains unverified

- Section 22's command results (test runs, build outputs, the conflicting RT ASan observation). Reading cannot verify runs; nothing was re-executed.
- A tail of line items inside otherwise-sampled tables: the JVM copy-count chain in section 11, a few 15.1/15.2 rows (KP-P1-05/06/07/14/15), some section 12 topology rows, and parts of P0-16's provenance detail. Every claim of the same kind that I did check held, so I treat these as reliable. Re-read the cited lines at fix time; the tree moves fast and line numbers rot.

## 6. Distilled execution order

SOLSUPREME's phases are right. Compressed to what you actually do next, cheap wins first.

### Now: DONE 2026-08-18

All seven landed and gated. Details in section 7.

- [x] Fix the `$key` literal at both sites (section 4.8).
- [x] Always free the Wasm decoder context; delete the wrong comment (section 4.3).
- [x] Fix Wasm unused-option reporting: real `unused` out-slot, delete the fictional comment (section 4.1).
- [x] Make the C invalid-format test fail on any signal, then validate the destination format in `ffkmp_frame_convert_pixfmt` (P0-09).
- [x] Finish P1-23: output-accurate range/matrix after `copy_props`, flip the test oracle (section 3.2).
- [x] `WebCanvasVideoRenderer.supports()`: actually check the format (section 4.4), with a falsified test.
- [x] Route the KitePlayer codec pin through the version catalog (section 4.7). Two sites, not one: the web sample hardcoded it too.

### Next (the two silent-loss fixes, one per repo)

- [ ] KitePlayer EOF: add `decodedAudio` occupancy plus a DSP `finish()` to the terminal gate, and gate the early sink EOS (P0-20 plus section 4.6). Test with short clips at every speed.
- [ ] KiteCodec Wasm: implement the common send-drain-retry loop (the JVM/Native shape) once in common Kotlin and make Wasm use it (P0-01/02/04 plus section 4.2). Also the first concrete step of the common-orchestration convergence, so it pays twice.

### Then (correctness hardening, before any new features)

- [ ] Native operation leases for Frame/FilterGraph/Sink; stop bypassing `checkedNative` (P0-07/08).
- [ ] Atomic sink close state machine, JVM and Native (P0-10); move `restampPts` inside the frame-ownership scope (P1-12).
- [ ] Truthful player commands: one selection transaction with `Applied/Superseded/Rejected` results, covering track change, external subtitles (both sites, section 4.5), stop-vs-open, and capture/step cancellation scope (KP-P1-01..05).
- [ ] Wasm decoder options: apply or refuse, never ignore (P0-03).
- [ ] Typed not-found errors instead of `Internal` everywhere a decoder/encoder/filter name misses (P1-04).

### Later (distribution, only when going public)

- [ ] The whole P0-11 through P0-19 cluster is one program: release pipeline, per-platform runtime artifacts, Wasm runtime package, Android AAR, matched variant matrices, no mavenLocal. Do not start it piecemeal before the correctness work lands, and do not advertise any target it has not shipped.

### Laws to keep (SOLSUPREME section 18, compressed)

No operation outlives its resource. No command reports success before its state exists. No EOF drops a tail. No option is silently ignored. No target exists only in Gradle metadata. No consumer needs the source checkouts. And one addition from this pass: no comment describes behavior the code does not have.

## 7. Execution log: the Now tier, 2026-08-18

Tier 1 plus Tier 2, the latter selected mechanically by changed path: C sources under `native/`,
Kotlin under `nativeMain` and `jvmAndAndroidMain`, two `build.gradle.kts` and `libs.versions.toml`.

### What changed

| Item | Files | Proof it works |
|---|---|---|
| `$key` literal | `Playback.jvm.kt:236`, `Playback.native.kt:462` | Both suites green; the message now interpolates |
| Wasm decoder context leak | `Playback.wasmJs.kt` | Freed unconditionally; the comment claiming the container owned it is gone |
| Wasm unused options | `MediaSource.wasmJs.kt` | Runtime probe, below |
| C invalid pixel format | `helpers_frame.c`, `test_convert.c`, `kitecodec_helpers.h` | The child exits 0 where it used to die on signal 6, under plain AND ASan |
| P1-23 colour tags | `helpers_frame.c`, `test_convert.c` | The suite now reads `range=2 space=0`, which is JPEG range and an RGB matrix |
| Web `supports()` | `WebCanvasVideoRenderer.kt` + a new test | Falsified: reverting the one-line fix makes the new test fail |
| Codec version pin | `libs.versions.toml`, two `build.gradle.kts` | Both modules still resolve and compile |

### The runtime proof for the option fix

A throwaway emcc probe linked the real codec archive and called `ffkmp_fmt_open_input_io` with two
options, one FFmpeg consumes (`analyzeduration`) and one nothing recognises (`kite_not_an_option`):

- the caller's key array came back **byte for byte unchanged**, so the old binding's premise was
  false and it reported every option as unused on every open, exactly as section 4.1 predicted;
- the dictionary reported exactly `[kite_not_an_option]`;
- `ffkmp_dict_free` took the slot and NULLed it, which is the ownership the new code relies on.

That upgrades section 4.1 from a reading of the code to a measured fact.

### Gates run

Tier 1, both repos, all green: cinterop coupling, deleted-surface, KiteCodec plain C suites (7/7),
kitert coupling, Kotlin ABI dumps, core and subtitles JVM tests, RT plain C suites (8/8), render
audit (46 checks), source discipline (18 checks), em dash scan (silent in both repos).

Tier 2, all green: KiteCodec C suites under ASan, TSan and allocation interposition (7/7 each),
corpus replay (105 files, 6 targets), symbol audit, klib metadata diff, cinterop, `apiCheck`,
buildSrc and plugin tests, `macosArm64Test`, `jvmTest`, `wasmJsNodeTest`, `jsNodeTest`. KitePlayer:
buildSrc, RT C under ASan and TSan (8/8 each), `macosArm64Test` for core/output/ffmpeg, JVM suites
for output/mobile/ffmpeg/core, `wasmJsNodeTest`. The pair was then republished to Maven Local with
all three flags and the adapter suites re-run with `--rerun-tasks` against the new codec artifact.

Real media, the strongest single check: the macOS sample played `sync1080p30.mp4` end to end with
300 of 300 frames submitted, 0 dropped, 0 repeated, 0 audio underruns, 0 rebuffers, 0 warnings,
14 ms final A/V drift.

### What these gates do NOT cover

- No Kotlin test asserts converted-frame colour tags on any backend, so the P1-23 change is proven
  by the C suite alone. A common contract test belongs with the Phase 2 convergence work.
- The Wasm option fix is proven at the C ABI by the probe and by compilation on the Kotlin side.
  No packaged browser test exercises the Kotlin binding, because P0-13 means no such package
  exists yet.
- Not run, and not claimed: Linux and Windows suites, the iOS simulator suite, Android device or
  emulator, and the container-based JVM matrix. None of them touch the changed paths on this host.
- One near miss worth recording: the first draft of the Wasm change freed the option arrays in both
  a `catch` and a new `finally`, a double free of module memory. It compiled clean. The re-read
  before gating caught it, not a test, which is an argument for the multi-pass habit rather than
  against it.

## 8. The full backlog

Every open item, in the order section 6 recommends. `Repo` is who owns the fix: **KC** is KiteCodec,
**KP** is KitePlayer, **Pair** needs both. Size is relative work, not a schedule: **S** is a sitting,
**M** is a day or so, **L** is a focused week, **XL** is a design plus a week or more.

Counts: 19 of 20 P0s remain open (P0-09 is closed). Roughly 130 further findings sit behind the
grouped rows below, because one fix usually closes several.

### Done already

| ID | Item | Closed by |
|---|---|---|
| P0-09 | Invalid pixel format aborted the process; its test blessed the crash | Now tier, 2026-08-18 |
| P1-06 / S-W1 | Wasm reported every open option as unused | Now tier, 2026-08-18 |
| P1-23 | Converted RGB frames carried the source's limited-range tags | Now tier, 2026-08-18 |
| S-W3 | Wasm decoder context leaked when the source closed first | Now tier, 2026-08-18 |
| S-W5 | Web renderer accepted every pixel format including Opaque | Now tier, 2026-08-18 |
| S-W7 | Codec version pinned twice outside the catalog | Now tier, 2026-08-18 |
| S-W8 | `$key` printed literally in option errors | Now tier, 2026-08-18 |
| 15.4-12 | MediaCodec delay wrap | Already guarded before the audit; SOLSUPREME overstated it |

### Tier NEXT: DONE 2026-08-18

Both landed and gated. Details in section 9.

| ID | Item | Repo | Outcome |
|---|---|---|---|
| P0-20 + S-W6 | Ended fired while decoded audio and the tempo stage still held samples | KP | Fixed. 1,416 frames of real audio recovered at 1.5x, measured |
| P0-01, P0-02, P0-04, S-W2 | Wasm dropped refused packets, sent its drain signal once, treated a closed packet as that signal, and seeked without the container start time | KC | Fixed. The seek was landing 3 seconds wrong on a nonzero-start file, measured |

### Tier THEN: correctness hardening, before any new feature

| ID | Item | Repo | Size |
|---|---|---|---|
| P0-07 | DONE 2026-08-18. Operation leases on Frame, Packet, StreamDecoder and FilterGraph; see section 11 | KC | done |
| P0-08 | DONE 2026-08-18. Checked accessor plus media-type guard, now under the real lease | KC | done |
| P0-10 | DONE 2026-08-18. Close-state machines on both sink backends; see section 11 | KC | done |
| P0-03 | Wasm accepts and silently ignores decoder, options and hardware requests | KC | M |
| P0-05 | Wasm cursor has no lease and multi-decoder construction is not staged | KC | M |
| P0-06 | Web custom I/O stages whole files, crosses JS per byte, never closes the source | KC | L |
| KP-P1-01, 02, 04, 05 | DONE 2026-08-18. Selection transaction, chained subtitle add, scoped cancellation, real preemption; see section 12 | KP | done |
| KP-P1-03 | DONE 2026-08-18. `MediaItem.io` is an owned factory: one reader per session; see section 12 | KP | done |
| KP-P1-06, 07, 09, 21 | DONE 2026-08-18. Explicit first-frame outcome, bounded close, counted event loss, monotonic totals; see section 12 | KP | done |
| KP-P1-08, 19 | Done in the THEN tier, 2026-08-18: teardown failures collected, open-failure staging | KP | done |
| P1-01, P1-02 | Custom-source and Native assembly failures leak or skip `close()` | KC | M |
| P1-03, P1-05 | Wrong-stream packets accepted; INVALIDDATA swallowed with no strict mode | KC | M |
| P1-04 | Decoder/encoder/filter absence collapses to `Internal` instead of typed not-found | KC | M |
| P1-07 | Blocking FFmpeg calls cannot be cancelled; needs `interrupt_callback` | KC | L |
| P1-09..P1-16 | Encoder and muxer state: reusable drive after EOF, poisoned muxer, foreign stream copy, late cleanup, hidden I/O failure, partial remux mutation, refused subtitle-only transcode, zero copy-only progress | KC | L |
| P1-17..P1-22 | Filter graph: JVM/Native divergence, incomplete per-frame key, close races, user callback under a native lock, no multi-pad scheduling, yuv420p substitution | KC | XL |
| P1-29, P1-32, P1-33, P1-35 | `Rational` overflow, mutable `StreamInfo`, Wasm `Frame` contract breaks, non-single-flight Web attach | KC | M |
| KP-P1-10 | DONE 2026-08-18. A step presents the next decoded frame; see section 13 | KP | done |
| KP-P1-12..15, 17 | Track switch rebuilds the source, infinite durations, subtitle lane and EOS, viewport-wrong rasters, raw filter timing | KP | L |
| FrameQueue | `bufferedUs` measures `last - first`, so one frame reads zero and the last duration is always lost | KP | S |

### Tier LATER: distribution. One program, not piecemeal

| ID | Item | Repo | Size |
|---|---|---|---|
| P0-11 | The release workflow records that it cannot pass with its configured dependencies | KC | L |
| P0-12 | JVM resource staging is effectively arm64-Mac only while the loader advertises more | KC | L |
| P0-13 | Wasm publication attaches no `.mjs` or `.wasm`, so the API resolves without a runtime | KC | L |
| P0-14 | Portable Linux/Windows GPL tasks never pass `--enable-gpl`; packaging would mislabel them | KC | M |
| P0-15 | The Android AAR exists only inside a hidden local phone scope | KC | L |
| P0-16 | Patched FFmpeg and static dependencies ship without matching source and provenance | KC | L |
| P0-17 | Android Native advertises MediaCodec with no `kc_jvm_attach` caller in `nativeMain` | KC | M |
| P0-18 | No reproducible public install path; `mavenLocal()` resolves first and KitePlayer has no CI | Pair | XL |
| P0-19 | Published player variants have no matching publishable codec variant | Pair | L |
| KP-B1..B13 | Player build and release: metadata-only readiness check, unpublished optional modules, no workflows, machine-dependent NDK discovery, no wrapper checksum, no Apple or desktop packages | KP | XL |

### The long tails, grouped

These are real and verified, but each is a body of work rather than a single fix.

| Area | Findings | What it actually is | Repo | Size |
|---|---|---|---|---|
| C and JNI bridge | 14 | Handle leases, O(n) close scan, generation-bit mismatch, unchecked kind, exception loss, modified UTF-8, untyped registration | KC | L |
| C filter and frame | 7 | Substring pin parsing, progressive publish on failure, unchecked `av_strdup`, plane-height lies, eight-channel cap | KC | M |
| Audio quality | 10 | PARTLY DONE 2026-08-18 (section 13): resampler, downmix policy and CoreAudio layout tags fixed. Left: device recovery, unvalidated `AudioFormat`, the pipeline ownership contract, the ring's hostile-input overflow | KP | M |
| Video and Web | 10 | PARTLY DONE 2026-08-18 (section 13): a failed renderer is detached instead of leaving permanent black. Left: MediaCodec control parity, the inert cadence contract, and every Web and GPU row | KP | L |
| Subtitles and libass | 7 | Not integrated into playback, UAF-shaped close, per-render reparse, encoding divergence | KP | L |
| Network and DASH | 7 | Unvalidated 206 ranges, no retry or reconnect, unbounded MPD expansion, prototype-grade DASH, unpublished module | KP | L |
| Public API | 14 | Throwing stubs, unusable default factory, dead config knobs, mutable models, leaked secrets, stale KDoc, global logger | KP | L |
| KiteCodec API and DSL | 10 | Untyped filter steps, no `@DslMarker`, unvalidated values, raw map overriding typed keys | KC | L |
| Performance | 10 | Per-byte Web interop, whole-input staging, JVM copy chain, no zero-copy lease, handle-table scaling | KC | XL |
| Build and plugin | 19 | 1,286-line build script, duplicated target truth, cache key gaps, redirect validation, excluded plugin tests | KC | L |
| Documentation truth | 10 | README version, minSdk, VideoToolbox, JS vs Wasm, "bit-exact" remux claims | KC | M |
| Gemini seam | 9 | Version alignment, generated metadata mapping, one application plugin, non-transactional source cleanup | Pair | L |

### Rule that outranks the order

A fix is done when its evidence exists, not when the code changes. Every row above lands with the
gate its changed path selects, and any row whose truth no test can express says so in writing rather
than passing quietly.

## 9. Execution log: the NEXT tier, 2026-08-18

Tier 1 both repos, plus the Tier 2 suites the changed paths select. Two silent-loss defects, one
per repository, each pinned by a test that was proven to fail without its fix.

### P0-20, the player's audio tail

The terminal state was decided from the packet queues and the decoders alone. Decoded audio lives
past both: a handoff channel four buffers deep, the buffer the feeder is converting, and the tempo
stage's lookahead. Four changes:

- `TempoStage.finish()` emits the queued lookahead instead of letting the next `reset` drop it.
  The remainder is passed through rather than spliced, because a splice needs the period that comes
  after it and at the end of a stream there is none.
- `AudioPipeline.finish()` runs that tail through the gain, so a mute still applies to it.
- `AudioPlayback.finishDecoded()` submits it on the same timestamp axis `submitDecoded` uses. This
  is new public API: the audio lifecycle now reads open, submitDecoded, finishDecoded, drain, close,
  and the ABI dumps were updated deliberately rather than to make a check go quiet.
- `PlaybackCore` counts decoded audio in flight, carries an explicit end-of-stream token to the
  feeder, and waits for the feeder's answer before Ended. Both waits share one bounded deadline, so
  a device that stops pulling cannot park the player one poll short of the end for ever.

**Measured, on the deterministic harness, before and after:** at 1.5x with pitch preserved, the
device received **15,480 frames, then 16,896**. The 1,416 frames between them are the tempo stage's
two pitch periods, about 29 ms, and they are the end of the media. At 1.0 speed the tempo stage is
bypassed entirely and there is no difference, which is why the pinning test runs at 1.5x.

### P0-01, P0-02, P0-04, the Wasm backend

- A refused packet is offered again after draining instead of being dropped, and the drain signal is
  retried the same way.
- `isDrained` is set only by the codec's own EOF, never by submitting the drain signal, and
  `receive` no longer collapses "not yet" into "finished".
- A closed packet is a typed failure instead of silently becoming the drain signal.
- `PacketReader.seek` converts the public timeline onto the container's, and bounds a backward seek
  by its target, matching the JVM and Native actuals.
- `extractFrame` seeks to a safe earlier point and decodes forward to the first frame that reaches
  the requested timestamp, instead of returning whatever came out of the seek first.

**Measured, against a generated file whose timeline starts at 10 s, asking for content 3.000 s:**
the old seek landed at content **0.000 s, three seconds wrong**. With the start time added it landed
at 2.000 s, the keyframe before the target, and with the forward walk it landed at **exactly
3.000 s**.

### A claim of mine that did not survive its own test

SUPREME section 4.2 said the Wasm drain defect meant "every buffered codec loses its tail on every
run". A probe decoding real H.264 both ways in one process returned **300 frames either way, with
zero refusals**. The old loop drains fully after each send, so the decoder's output queue is never
full when the next send arrives and EAGAIN never happens. The fix is still right, and the shape it
replaces is still wrong, but it was a latent fragility rather than everyday loss. Section 4.2 now
carries the correction. The lesson is the same one the falsification step keeps teaching: a finding
that has not been run is a hypothesis.

### Two tests that had to be thrown away

The first pair of engine tests for P0-20 passed with the fix disabled, which means they tested
nothing. Two reasons, both worth recording: the 1.0-speed case cannot lose a tail because the tempo
stage is bypassed there, and the original tolerance of one decoded buffer was wider than the loss it
was meant to catch. They were replaced by one test at 1.5x whose threshold sits between two measured
readings, and it fails without the fix.

### Gates

Tier 1 both repos green: coupling ratchets, deleted-surface, KiteCodec C suites (7/7), Kotlin ABI
dumps, core and subtitles JVM tests, RT C suites (8/8), render audit, source discipline, em dash
scan. Tier 2: KiteCodec `wasmJsNodeTest`, `jsNodeTest`, `apiCheck`, `macosArm64Test`, `jvmTest`;
KitePlayer `macosArm64Test` for core/output/ffmpeg, JVM suites for output/mobile/ffmpeg/core, and
`wasmJsNodeTest` for core and output. The macOS sample played real 1080p media end to end: 300 of
300 frames, 0 dropped, 0 underruns, 0 rebuffers, 0 warnings.

The ABI ratchet did its job here: it failed the build on `finishDecoded` and forced the new public
method to be a decision rather than an accident.

### What these gates do NOT cover

- The handoff half of P0-20 (decoded buffers queued when the end is detected) is a guard without a
  falsifying test. In this harness the feeder always wins that race, so the counter never reads
  above zero at the deciding moment. The tempo-tail half is what the measured numbers pin.
- The Wasm fixes are proven at the C ABI by probes and by compilation on the Kotlin side. No
  packaged browser test exercises the Kotlin binding, because P0-13 means no such package exists.
- The real-media sample runs at 1.0 speed only, where the tempo stage is bypassed, so it confirms no
  regression rather than the tail fix itself.

## 10. Execution log: the THEN tier, 2026-08-18 (in progress)

Worked smallest first. Every fix below is gated; every test below was proven to fail without its
fix, except where this log says otherwise.

### KitePlayer

| ID | Fix | Test |
|---|---|---|
| KP-P1-11 | Chapter lookup ignored `end`, so a position in a gap reported the chapter that had already finished. One shared `chapterHolding` now answers for both the facade and the engine | Gap table, unit and through the engine. Falsified |
| KP-P1-18 | The support bundle said "filters: none attached" unconditionally, denying the one fact it is collected to establish. It now reports the attached graph | None: reading a support bundle is not something the suite asserts on today |
| KP-P1-19 | Every untyped open failure became "source unavailable", including audio-device and assembly failures. An open now records which stage it reached and classifies by it | None yet |
| KP-P1-20 | `selectImmediately` was honoured only when the container carried no subtitle stream. The files are now read BEFORE the session is built, so a flagged one that really loads wins, and one that fails to load leaves the container's own subtitles selected | Both directions, JVM. Falsified |
| FrameQueue | `bufferedUs` measured start-to-start, so a one-frame queue reported zero buffered and every reading was one frame short. It now measures to the end of the last frame, saturating | Covered by existing buffering tests |

### KiteCodec

| ID | Fix | Test |
|---|---|---|
| P1-29 | `-Int.MIN_VALUE` returned its own input, and `Long.MIN_VALUE * -1` wrapped past an overflow check that compared equal to itself. Both refuse now, and the scalar reduction no longer takes a magnitude by negating | Both floors. Falsified |
| P1-32 | `StreamInfo` compared its extradata by array REFERENCE, so two probes of the same file disagreed about describing the same stream. Content equality and a matching hash | Equal bytes, different bytes, no bytes. Falsified |
| P1-25 | The undeclared-colour guess made every SD height BT.470BG and swept a zero or negative height into BT.709. It now separates 525-line from 625-line primaries and answers a nonsense height with Unspecified | Both refinements. Falsified |
| P1-12 | A restamp that threw escaped before the frame-ownership scope existed, leaking the frame the call had promised to close | Covered by existing round-trip ownership tests |
| P1-14 | A duplicated stream index was caught only after a stream had been created in the output for every entry, so the caller got a half built file and then the refusal. Validated before the sink is opened, JVM and Native | Native, and the output file must not exist. Falsified |
| P1-11 | `codecparOf` accepted a `StreamInfo` from ANOTHER source, copying this file's parameters under the other file's time base. It now canonicalizes like every other entry point | Two real files. Falsified |
| P1-13 | `ffkmp_fmt_free_output` discarded the close result, which is where a full disk announces itself, so a truncated file reported as written. It returns the error now, through the C ABI, the JNI descriptor, the Kotlin extern and both sink closes | None: forcing a close-time I/O failure needs a full filesystem and no portable way to make one. Plumbing only |
| P0-03 | The Wasm backend accepted and ignored the exact decoder, decoder options, thread count and hardware request. Decoder selection and options are now IMPLEMENTED, hardware and multi-threading are refused typed | None: see the note below |
| P1-33 | Wasm `copyPlanesToByteArray` threw for a frame with no data where the contract returns empty, and `downloadFromHardware` copied where the contract refuses | None: see the note below |
| P1-35 | `attach` validated a second module and then dropped it silently; two concurrent `load` calls could each try to adopt their own. Re-attaching the same module is a no-op, a different one is refused loudly, and a load that lost the race does not adopt | None: see the note below |

### Second batch, same run

| ID | Fix | Test |
|---|---|---|
| P1-01 | A custom byte source was not closed when the open FAILED: Native released only its internal reference, and the JVM's open sat outside the scope that owns the source. Both close exactly once now, on every failure path | Two sources, JVM and Native, counting closes rather than flagging them. Falsified |
| P1-02 | A probe that had already SUCCEEDED stranded the format context and the byte source when reading streams, metadata or chapters then threw. The whole assembly unwinds as one scope | Covered by the P1-01 tests' close counting |
| P1-03 | A packet routed to the wrong decoder reached FFmpeg, came back as INVALIDDATA, and was swallowed as consumed, so the input vanished silently. One shared guard refuses it on all three backends | Real two-stream file, audio packet into the video decoder. Falsified |
| P0-08 | The Native encoder read a frame's raw pointer instead of the checked accessor its own contract demands, and never checked the frame's media type | Wrong media type falsified. See the note below on the closed-frame half |

### Third batch, same run

| ID | Fix | Test |
|---|---|---|
| P1-22 | The multi-input C filter builder substituted yuv420p for a pixel format FFmpeg does not know, building a graph for a layout the caller's frames are not in. It refuses with EINVAL now, exactly as the single-input builder always did | Valid dimensions, one unknown format. Falsified |
| P1-04 | A missing decoder, encoder or filter collapsed into `Internal`, so the documented catch-by-kind could not work. The typed variants already existed and are now used across JVM, Native and Wasm | Covered by the existing suites compiling against the taxonomy |
| KP-P1-08 | Teardown wrapped every close in `runCatching` and then dropped the failures, so a decoder or device that refused to close left no trace. The failures are collected and surfaced as one new typed warning, `ResourcesNotReleased` | The warning audit test enumerates every type with no else branch, so the new one did not compile until its emission site was documented |

### What the P0-08 test does and does not pin

A closed frame is refused, and the test proves it. But reverting every `checkedNative` back to the
raw pointer leaves that test GREEN, because the new media-type guard reads `frame.info` first and
that read is what throws. The accessor change closes the window BETWEEN that read and the FFI call,
which only a concurrent close can open, and no single-threaded test can express it. It is defence in
depth against the same race P0-07 is about, and P0-07's lease is what will actually close it.

### Why three Wasm fixes have no tests

There is no `wasmJsTest` source set in `kitecodec-core`: the Wasm target runs `commonTest`, and every
behaviour above is Wasm-specific, so a common test cannot express it. Reaching `openDecoder` at all
also needs a loaded Emscripten module, which is the packaged runtime P0-13 says does not exist yet.
These three are implemented and compile-verified, and they get their tests when the Web runtime
package and its browser suite land. Writing a test that cannot fail would be worse than saying this.

### The ABI change, and the ratchets that caught it

P1-13 changed an exported C signature from `void` to `int`. That touched the header, the signature
baseline, the generated Wasm binding, its committed mirror in the source tree, the JNI function, the
JNI method descriptor, the Kotlin extern and both sink closes. Two ratchets caught the parts a
person would forget: `symbol-audit.sh` validated the baseline against the header, and
`klib-metadata-diff.sh` reported the declaration as removed-and-added and refused to pass until it
was re-baselined. The generated binding and its committed mirror are still two files kept in step by
hand, which is the drift SOLSUPREME names in its generation plan.

### Gates

Tier 1 both repos green after every batch. For the C ABI change: C suites under plain, ASan, TSan and
allocation interposition (7/7 each), corpus replay (105 files), symbol audit, klib metadata re-baseline,
`apiCheck`, and the JVM, Native, Wasm and JS suites.


## 11. Execution log: Group 1, the safety pair, 2026-08-18

The design call, made here rather than deferred: the JVM backend has always held a per-object lock
across every native call, and the audit names it the semantic reference. So the native backends got
exactly that, not a new abstraction.

### What changed

- **Frame, Packet, StreamDecoder** (native): every operation now runs inside a per-object reentrant
  lock, and close takes the same lock. A close arriving mid-operation waits for the FFI call to
  come back before freeing what it is using. `Frame.withNative {}` is the lease; the encoder holds
  it across its whole restamp-convert-encode span, and the filter graph holds it for each send but
  never across its user callbacks, which is the JVM's own shape.
- **FilterGraph** (native): an operation ledger on top of the lock. `process()` suspends while it
  emits, and a lock cannot be held across a suspension, so the ledger counts operations in flight
  and a close that arrives during one, from another thread or reentrantly from a callback, marks
  the graph closed and lets the outermost operation free it on the way out.
- **MediaSink** (both): a real close-state machine. On the JVM, `closing` flips under the mux lock
  before the flush, so a second close returns and an add refuses instead of appending an encoder
  the close's snapshot already passed. On native, `closeBegun` and `closed` are two flags because
  the close WRITES on its way out: new work refuses from the first, the write path refuses only
  from the second, which flips after the muxer is freed.
- **Wasm** is single-threaded by construction; nothing to do there.

### Proven, and how

- Three native race suites: 300 rounds of close-vs-read on a frame, 200 of close-vs-receive on a
  decoder, 200 of close-vs-feed on a graph, each with the closer and the worker on different
  threads. All complete with typed refusals and a living process.
- 50 rounds of two simultaneous JVM sink closes: both return, exactly one valid container on disk.
- Real 1080p playback over the leased frame path: 300 of 300 frames, worst schedule 1 ms, so the
  per-frame lock is invisible at playback rates.
- The existing suite caught a real mistake mid-implementation: the first native close set its flag
  before its own flush, which refused the flush it owed. That is what the flag split exists for.

### Honesty section

- The race tests prove the fix present; they cannot prove its absence. A use-after-free is
  probabilistic, so running them against the old code likely crashes but is not guaranteed to, and
  the two-flag JVM window is microseconds wide through the public API. The falsification run for
  the double-close stayed green for exactly that reason, and the state machine is kept because the
  SEMANTIC is right: a losing close returns instead of failing the winner.
- Kotlin/Native has no thread sanitizer, so "no crash under stress" is the strongest available
  oracle on this side of the C boundary.
- MediaSource and PacketReader keep their documented single-owner contract, per the audit's own
  scoping; their close already holds the state lock across the native call.

## 12. Execution log: Group 2, the commands that lied, 2026-08-18

Nine defects, all in `KitePlayer`, all the same shape: a call that reported success for something
that did not happen, or a number that was not true. Nothing in KiteCodec changed.

### What changed

- **Track selection is a transaction with a result.** `selectTrack` returns a new public
  `TrackChange`: `Applied`, `Superseded` or `Discarded`. The engine keeps one desired selection PER
  KIND rather than one in total, so setting the audio track and then the subtitle track rides one
  reopen and BOTH apply; only a second request for the same kind displaces the first, and that one
  is told. Stop, close and a fresh open answer `Discarded` with a reason instead of success.
- **`addExternalSubtitle` waits for the reopen its selection needs.** It used to hand back a track
  id the moment the file parsed. A selection that never applies now takes the appended track back
  out of the track table, so no row survives for a subtitle nobody can show.
- **Cancelling a capture or a frame step no longer stops the player.** The global Stop on
  cancellation is now only for the calls that own the session: open, openQueue and the two queue
  jumps. A cancelled capture withdraws its own armed request, matched by identity so a newer
  capture is left alone.
- **A stop queued during an open really preempts it.** The open tears down and refuses with a typed
  `IllegalStateException` instead of publishing Paused, announcing `Opened`, completing
  successfully and then being undone by the very next command. Same for a track-change rebuild.
- **The first frame is reported as what it was.** `presentFirstFrame` answers `Submitted`,
  `Headless`, `Refused`, `None` or `NoVideo`. A renderer refusal is now a frame that LEFT the
  schedule, so it satisfies the one-frame gate instead of burning the whole ten second budget, and
  both `Refused` and `None` are warned typed.
- **A renderer refusal is its own counter.** `PlaybackStats.refusedFrames` is new;
  `droppedFramesLate` is now the schedule's own late drops alone. One number for both made a dead
  surface read as a slow decoder.
- **The totals are totals.** A session's counters are folded into player-level totals as it is
  detached, so a track switch, a decoder recovery, a loop or a queue advance can no longer make a
  documented monotonic figure fall. Gauges stay per-session and empty at stop, which is now
  published off the interval rather than left stale.
- **A dropped event is counted.** Every emission goes through one funnel that records what
  `tryEmit` answers; the loss is published as `PlaybackStats.droppedEvents` and printed in the
  dump. Backend warnings now go through `warn()`, so they reach the bounded history and therefore
  every support bundle, which they did not before.
- **Terminal close is bounded.** The deadline used to wrap a `NonCancellable` body and could never
  fire, so a wedged native close kept `closeAndAwait` suspended for ever. The session is detached
  first, released on its own lane, and the wait is bounded; past it the close reports
  `RuntimeCompromised` and deliberately does NOT close the dispatchers the release is still
  standing on.
- **Every open of an item gets its own reader.** `MediaItem.io` and `SubtitleSource.io` are now
  `suspend () -> MediaIo` factories. A track switch, a decoder recovery, a loop and a queue coming
  back round all reopen the item, and the previous session had already closed the single live
  reader they used to share. The DASH module was building exactly that shape and now builds a
  factory.

### Proven, and how

14 falsifications were run: each new test had its own fix reverted, one at a time, and the test was
required to go red. 13 went red. The fourteenth is the bounded close, and reverting it does not
produce a failure, it produces a HANG, which is the defect itself: the run was killed after ten
minutes with the test still waiting. That is recorded as the evidence rather than dressed up as a
clean red.

Two tests were thrown away during this pass for being green for the wrong reason. The first
version of the subtitle rollback test passed without any rollback at all, because a FAILED reopen
rebuilds the track table from the container and wipes the appended row anyway; it was replaced with
a DISPLACED add, which is the case the rollback actually owns, and that one falsifies. The first
version of the dropped-event test never dropped anything, because a collector that stalls on a
timed delay keeps up perfectly under virtual time, which auto-advances whenever the test is idle;
it now stalls on a deferred that never completes, and the run loses 135 of 200 events.

### Gates

Tier 1 both repositories, green: cinterop coupling, deleted surface, KiteCodec's seven plain C
suites, KitePlayer's eight, `checkKitertCoupling`, `checkKotlinAbi`, `:kiteplayer-core:jvmTest`,
`:kiteplayer-subtitles:jvmTest`, render audit (46 checks), source discipline (18 checks), and the
em dash scan over both trees printing nothing.

Tier 2's KitePlayer half, selected by the rule "the completion of any item, unconditionally":
`:buildSrc:test`, the macOS native suites for core, output and ffmpeg, the JVM suites for output,
mobile and ffmpeg, `:kiteplayer-output:wasmJsNodeTest`, the JS, Wasm, Android and iOS-simulator
compile spot checks, and the real-media sample runs. KiteCodec's Tier 2 half was not selected: not
one file in that repository changed.

Real media, through every changed path: `sync1080p30.mp4` 300 of 300 frames submitted, 0 dropped, 0
repeated, 0 underruns, worst schedule 3 ms; `truevfr720.mp4` 240 of 240, worst 2 ms;
`hevc4k10.mp4` 180 of 180, worst 3 ms. All three ended with zero warnings, which is the check that
matters most here: the new first-frame reporting warns typed, and a false positive would have shown
up on every clip.

The ABI ratchet moved, as it must: `MediaItem.io` and `SubtitleSource.io` change type, `MediaIo`
gains an explicit `close`, `PlaybackStats` gains two fields, `VideoPlayback` gains two counters,
`selectTrack` gains a return type, and `TrackChange` is new.

### Honesty section

- `TrackChange` has three cases, not the four the audit named. A selection that FAILS because the
  media or the device failed still throws `PlaybackException`, because that is what every other
  suspending member of the facade does, and one member with its own error convention is worse than
  matching a word list.
- The bounded close does not make a wedged native call finish. Nothing inside the process can. What
  it does is stop the caller waiting for ever and tell it, typed, that the threads were left alive
  on purpose. Ending the process is still the only way to get those threads back, and the error
  text says so.
- The dropped-event counter measures ONE of the two ways an event reaches nobody. The other, an
  event emitted while nobody is collecting, is undetectable from inside the flow and is deliberate:
  the feed replays nothing to a late collector by design. That is why every warning is also written
  to the bounded history a support bundle reads.
- The merge of same-pass selections goes beyond the defect, which was only the false success. It is
  here because a player that silently loses an audio change when a subtitle change follows it is a
  defect callers hit constantly, and reporting it honestly would not have made it acceptable.
- A stale KDoc was corrected in passing: `Tracks.selectedSubtitle` still claimed it was always null
  and that selecting a subtitle was always refused, which stopped being true when the text cue path
  landed.

## 13. Execution log: Group 3, picture and sound quality, 2026-08-18

Five of the eight items I named to the owner. The three I did not finish are listed at the end with
what each actually needs, because a group reported as done with three quiet gaps in it is the same
kind of lie the last group was about.

### What changed

- **The resampler is a real one.** `LinearResampler` is gone; `SincResampler` replaces it with a
  32-tap windowed-sinc polyphase filter, 512 phases, Blackman window, cutoff at the LOWER of the two
  Nyquist frequencies. The old class's own documentation said no document may present it as
  production rate conversion, and it was the default. The exact integer read position is kept
  unchanged, so the conversion is still sample-locked and cannot drift.
- **The downmix has a policy, and the policy is FFmpeg's, measured.** The LFE is dropped and the
  matrix is not normalised, both because that is what `ffmpeg -ac 2` does; a `DownmixConfig` on
  `AudioConfig` makes each one a choice, so a caller shipping to an integer device can turn
  normalisation on and pay about 7 dB for a guarantee that nothing can clip.
- **CoreAudio no longer declares a layout it was not given.** It tagged EVERY channel count above
  two as MPEG 5.1 A, so a three channel stream was announced to the unit as six speakers, and the
  unit's refusal was thrown away with a cast to void. The tag is now chosen by the count, the
  verdict is kept, and the order actually in force is reported back through a new
  `kprt_sink_format.channel_layout_mask` and published as the negotiated `AudioFormat`'s mask, so
  the engine's mixer keys on an order that is really in force instead of on nothing.
- **The mixer matches equal channel counts by SPEAKER.** Equal counts used to mean a straight copy.
  They now resolve per speaker, with side and back surrounds standing in for each other so a device
  with back speakers plays a mix authored for side ones instead of going quiet.
- **A renderer that fails is let go of.** `RendererEvent.Failed` was a warning and nothing else, so
  the schedule kept handing frames to a renderer that had already said it could not draw: the sound
  played and the picture stayed black for the rest of the session. The engine now detaches it,
  playback continues headless, and the warning says so. The SPI KDoc promised a software fallback
  that never existed; it now says what really happens and why the engine cannot invent a surface.
- **A frame step steps a frame.** It was a precise seek to the current position plus one nominal
  frame period from the container's declared rate, which is wrong on variable frame rate, on
  B-frames, on repeated timestamps and on a mis-tagged container, and which also counted a
  superseded seek as success. It now releases exactly one already-decoded frame through the
  schedule. It needs no seek, so it works on a source that cannot seek, and it repeats no decoding.

### Measured, not asserted

- **The resampler.** A 15 kHz tone converted from 48 kHz to 16 kHz has nowhere to go: the new
  Nyquist is 8 kHz. With the old linear interpolation, **1.0 of the tone's amplitude** arrives at
  1 kHz, in the middle of speech, as a whistle that was never in the recording. With the sinc
  kernel it is below 0.01, which is the test's threshold. That is the whole difference between the
  two implementations in one number.
- **The LFE policy.** This was an assumption in every previous version of this code, and the
  surround fixtures were built with a SILENT LFE specifically so that the engine's disagreement with
  FFmpeg could never show up in a test; the generator says so in a comment. A new fixture carries a
  60 Hz tone in the LFE and silence in all five other speakers, and `ffmpeg -ac 2` turns it into
  EXACT silence. FFmpeg drops the LFE. The engine now does too, and `ReferencePcmTest` pins it.
- **Normalisation.** Turning it on by default put the engine a fixed 7 dB below its own reference
  recordings and broke both existing `ReferencePcmTest` comparisons. That is the oracle answering a
  design question, so the default follows it.

### Proven, and how

Eight falsifications, seven red. Each new test had its own fix reverted, one at a time.

The eighth is recorded rather than dressed up: the rule that normalisation divides the WHOLE matrix
rather than each row by its own sum is the correct rule and cannot be falsified today, because every
downmix matrix this build models is symmetric, so the two rules produce identical numbers. The test
I wrote for it was deleted rather than kept, because a test that cannot fail is not a test. The rule
stays, documented, and becomes falsifiable the day an asymmetric matrix exists.

### Gates

Tier 1 both repositories. Tier 2 selected by rule (C sources changed, and the completion of items):
KitePlayer's C suites in plain, ASan and TSan modes (8 of 8 each), render audit 46 checks, source
discipline 18 checks, the macOS native suites for core, output and ffmpeg, the JVM suites for core,
output, mobile, ffmpeg and subtitles, `wasmJsNodeTest`, the JS, Wasm and Android compile checks,
`checkKitertCoupling`, and the ABI ratchet moved for the new `DownmixConfig`.

Real media: `sync1080p30.mp4` 300 of 300 frames, `truevfr720.mp4` 240 of 240, `surround51.mp4`
through the whole rebuilt audio path, all with zero dropped frames, zero underruns and zero
warnings. The variable-frame-rate clip reported a 23 ms worst schedule on a loaded machine and 3 ms
on two quiet reruns, which section 9's own rule records as a load observation rather than a result.

### What is NOT done, and what each needs

- **Audio device recovery (15.3.5).** The sink SPI still promises device and format recreation that
  the engine does not perform: it warns on DeviceLost and DeviceChanged and ignores
  FormatChangeRequested and Underrun. The fix is a quiesced rebuild transaction (stop, close, create,
  open, new ring, re-anchor the clock), which is real work and, more importantly, cannot be proven on
  this host without a scripted device-loss harness that does not exist yet. Left rather than
  half-built.
- **Subtitles rasterised at the viewport (KP-P1-15).** Cues are still rasterised at the source
  video's size. The engine cannot fix this alone: `setViewport` is called by the application's view
  ON the application's renderer, so the engine never learns the viewport. It needs a
  `RendererEvent.ViewportChanged` and every renderer emitting it, which is a change across five
  platform modules.
- **Colour, HDR and pixel aspect through encoding (KiteCodec P1-26).** Untouched. It is a KiteCodec
  C ABI change plus typed output specs on both backends, and nothing in this group's KitePlayer work
  depends on it.

Also unchanged and worth naming: the equal-count reorder is INERT for the nine layouts this build
models, because all nine follow the same native bit order and no two of them differ in position. It
is correct machinery for the case the audit describes, a device reporting an order of its own, and
it changes nothing today. Saying it fixed something now would be overselling it.
