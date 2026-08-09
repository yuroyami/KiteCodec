# kitecodec-c

The FFmpeg helper layer as real C, with its own build, its own tests and its own sanitizer runs.

Until sub-phase B1.2 this code existed only as 949 lines of text inside
`kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`. Text in a def file has no translation
unit, so it had no object file, no test, no sanitizer run and no coverage. Its only compile check
was cinterop's, and its only test was whatever Kotlin happened to call, which leaves 19 of the
176 helpers never called at all. That is register item B1-01 in `KitePlayer/KPKMP.md`.

This directory is the fix. It is referenced by nothing in the Gradle build during B1.2: the def
file is untouched, the klib is bit identical to B1.1's, and a regression is impossible by
construction. The build wiring arrives in B1.3, after the sources here are proved faithful and
after their tests already pass.

## Layout

| Path | What it is |
|---|---|
| `tools/extract_from_def.py` | The committed generator. Turns the def body into the two files below. |
| `include/kitecodec_helpers.h` | Generated. The 20 includes, then one declaration per exported helper. |
| `src/kitecodec_helpers.c` | Generated. The def body verbatim, with the linkage token rewritten. |
| `scripts/build-host.sh` | Builds the host test binaries for one variant. |
| `scripts/verify-lift.sh` | Proves the two generated files are exactly what the def produces. |
| `scripts/run-c-tests.sh` | Runs the five suites for one variant. |
| `tests/harness.h`, `tests/harness.c` | The assertion and reporting API every suite uses. |
| `tests/interpose_alloc.c` | The allocation interposer, the local leak instrument. |
| `tests/test_*.c` | The five suites of plan section 15.3. |
| `coupling-baseline.txt` | The Kotlin to FFmpeg coupling ratchet's baseline, added by B1.1. |
| `build/` | Output. Gitignored. |

## The generated files are generated

Never hand edit `include/kitecodec_helpers.h` or `src/kitecodec_helpers.c`. `verify-lift.sh`
re-runs the generator against a git revision of the def and byte-compares the result, so a hand
edit fails the gate rather than surviving quietly. Change the def, or change the generator.

The extraction rules, all measured against the def at KiteCodec `cdb8ad2`:

* The body is def lines 13 to 961, which is 949 lines of C after the `---` separator on line 11.
* The 20 `#include` lines move to the header. The source gets `#include "kitecodec_helpers.h"`
  in their place, which is what makes compiling the source a proof that every declaration matches
  its definition.
* 176 declarations are found by balancing parentheses from the `(` that opens the parameter list.
  Nine signatures span more than one line, at def lines 251, 262, 470, 489, 531, 616, 644, 684
  and 816, and those keep their original line breaks in the header.
* 172 helpers lose the whole `static inline ` token and become real exported symbols.
* Named helpers gain a documented contract comment above their declaration, taken from the
  `CONTRACTS` table in the generator. See "Documented contracts" below.
* Four helpers keep `static` and lose `inline`, and are not declared in the header at all:
  `ffkmp_codec_pix_fmts_` (def 289), `ffkmp_graph_finish_` (470), `ffkmp_graph_finish_multi_`
  (616) and `ffkmp_ch_layout_mask_` (908). Each is called only from inside its own banner
  section, which is what lets B1.4 split the translation unit per subsystem without any of the
  four crossing a file boundary. The generator re-checks that property on every run and refuses
  to emit if it stops holding.

Run `python3 tools/extract_from_def.py --report` to print the measured shape, including the 11
banner sections and how many helpers each holds.

## Documented contracts

Some helpers carry a contract that their signature cannot express. Those contracts live in the
`CONTRACTS` table inside `tools/extract_from_def.py`, and the generator emits each one as a
comment above the declaration it belongs to.

The table exists because neither of the two obvious places works. A comment written into the
header by hand is erased by the next generator run and `verify-lift.sh` would fail. A comment in
the def body ends up in `src/kitecodec_helpers.c`, which documents the implementation rather than
the interface a consumer reads. So the table is the only place a contract survives, and the
generator refuses to emit if a name in it stops being an exported declaration, which keeps a
contract from going missing during a rename.

Plan section 15.5 Deferral 2 is why this is not optional. It rejects
`__attribute__((ownership_returns))`, because clang honours it only in the static analyzer, which
makes the attribute level 8 evidence, and it substitutes "documented ownership contracts in the
header plus exact pairing tests" in its place. The words are half of that substitution.

What is in the table today: 44 contracts, one per declaration.

| Group | Count | What the contracts say | Register item |
|---|---|---|---|
| `ffkmp_strerror` | 1 | Thread affine, and invalidated by the next call on the same thread. | B1-09 |
| Frames | 9 | Who owns the returned frame, which calls add a reference rather than copy, and which can move a plane pointer under the caller. | Deferral 2 |
| Packets | 5 | Which calls leave a packet blank and which leave it owning data. | Deferral 2 |
| Codecs | 8 | Context lifetime, and which setters copy their arguments. | Deferral 2 |
| Demuxing | 4 | The open and close pairing, and that a read packet must be released before the next read. | Deferral 2 |
| Muxing | 10 | The two contexts that must never be crossed, the stream the parent owns, the `pb` with no separate close, and the write that consumes its packet. | Deferral 2 |
| Filter graphs | 7 | The graph owns its filter contexts, every failure path frees the graph, and `out_srcs` is not cleared on a failed multi build. | Deferral 2 |

The ownership set is measured rather than listed by hand: a helper is an ownership helper when its
body reaches a libav call that allocates, frees, or moves a reference. Applied mechanically to the
176 bodies that selects 44 exported helpers plus the two internal graph finishers. 43 of the 44
have a contract here and a case in `tests/test_ownership.c`, so the words and the tests cover the
same set. The 44th is `ffkmp_codecctx_flush`: `avcodec_flush_buffers` releases the references the
codec holds internally, which is why the mechanical rule selects it, but nothing crosses the
interface, so it has neither a contract nor a case. Nothing here is documented that is not also
asserted.

## Building and running

There is no make, no cmake and no ninja here, and that is not a preference. Register item B1-15:
cmake is not installed on the proving machine, and GNU make starts a comment at an unescaped `#`
while both repositories live under a path containing `#Kite`. `buildSrc/BuildFFmpegTask.kt`
already had to guard against that hazard. Driving clang directly is the only form that is both
available and safe under this path.

```bash
./scripts/verify-lift.sh HEAD
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
```

`run-c-tests.sh` never builds, so a gate cannot pass on a stale binary. It accepts suite names
after the variant, which is the fast loop while writing a suite:

```bash
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain test_buffers
```

FFmpeg flags come from `pkg-config` for the six libraries, or from `KC_FFMPEG_PREFIX` when that
is set. `KC_CC` and `KC_AR` override the compiler and the archiver, which default to
`/usr/bin/clang` and `/usr/bin/ar`.

## The three variants

ASan and TSan cannot be combined, which is why there are three of them rather than one.

| Variant | Flags on top of `-std=c11 -Wall -Wextra -Werror -Werror=vla -g` | What it is for |
|---|---|---|
| `plain` | `-O2` | Compile fidelity, correctness, and allocation pairing. |
| `asan` | `-fsanitize=address,undefined -fno-omit-frame-pointer -O1` | The out of bounds and undefined behaviour class. |
| `tsan` | `-fsanitize=thread -O1` | The threaded cases, starting with `ffkmp_strerror`. |

`-Werror` is not decoration. Because `src/kitecodec_helpers.c` includes its own generated
header, this compile is the only mechanical proof that all the emitted declarations agree with
their definitions, and a warning that nobody reads would not be a proof.

## The allocation interposer

LeakSanitizer is not supported on macOS arm64. An ASan and UBSan binary built by Apple clang 17
answers `ASAN_OPTIONS=detect_leaks=1` with "AddressSanitizer: detect_leaks is not supported on
this platform". That is register item B1-14, and it is why `tests/interpose_alloc.c` exists: it
counts allocations through the Mach-O `__DATA,__interpose` section and is the local leak
instrument. LSan in the Linux CI job is the corroboration, not the primary.

Three measured facts about it, each of which is a trap if it is not known:

* Inserting a library that simply defines its own `malloc` counts exactly zero. The two-level
  namespace binds every call to libSystem's definition, so the shadowing one is never reached.
  The interpose section is the mechanism that works.
* dyld does not apply an interpose section to the image that carries it. That is what lets the
  wrappers call the real `malloc` with no recursion, and it is also why the "is the interposer
  effective" probe lives in `harness.c`, in the executable, rather than in the interposer.
* FFmpeg's `av_malloc` goes through `posix_memalign` on this platform and `av_free` goes through
  `free`. An `av_frame_alloc` and `av_frame_free` pair measures as one `posix_memalign` against
  one `free`. An interposer that watched only `malloc` and `free` would report zero allocations
  against one free for every FFmpeg object in the suite, which is worse than no instrument at all
  because it looks like a finding.

The counters are live in the `plain` variant and read zero under `asan` and `tsan`, because each
sanitizer runtime replaces the allocator before dyld reaches the interpose section. Suites do not
have to care: `KC_ALLOC_BALANCED` and `KC_ALLOC_LIVE` record the gap with `kc_partial()` when
`kc_alloc_active()` is 0, the case line says so, and the suite summary counts how many cases were
partial. So the pairing evidence comes from the plain run and the sanitizer runs contribute their
own findings, and nothing anywhere claims a property that the variant it ran in could not observe.

## Writing a suite

`tests/harness.h` is the API and carries the details. The contract from plan section 15.3 is
that every suite is table driven, prints one line per case, and returns non-zero on the first
failure. In short:

```c
#include "harness.h"
#include "kitecodec_helpers.h"

int main(void) {
    kc_suite_begin("test_something");
    for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        kc_case("%s at %d", rows[i].name, rows[i].size);
        int rc = ffkmp_something(rows[i].size);
        KC_EQ_INT(rc, rows[i].expected);
        kc_detail("rc=%d", rc);
    }
    return kc_suite_end();
}
```

* Reporting: `kc_suite_begin`, `kc_case`, `kc_detail`, `kc_partial`, `kc_note`, `kc_suite_end`.
  `kc_suite_end` returns the process exit code and returns non-zero when the suite ran no cases,
  so an empty suite cannot pass by accident.
* Assertions: `KC_CHECK`, `KC_CHECKF`, `KC_EQ_INT`, `KC_EQ_I64`, `KC_EQ_SIZE`, `KC_EQ_PTR`,
  `KC_NOT_NULL`, `KC_NULL`, `KC_EQ_STR`, `KC_EQ_STRLEN`, `KC_EQ_MEM`, `KC_ALL_ZERO`. Each prints
  the case line, the source location and the actual against the expected value, then exits
  non-zero. There is no continue-after-failure mode on purpose: the first failure is the one with
  intact state around it.
* Allocation: `kc_alloc_active`, `kc_alloc_snapshot`, `kc_alloc_live_delta`, `kc_alloc_new_delta`,
  `kc_alloc_free_delta`, and the `KC_ALLOC_BALANCED` and `KC_ALLOC_LIVE` macros.
* `kc_suite_begin` silences the FFmpeg log, because a suite that drives error paths on purpose
  would otherwise bury its own output. Set `KC_FFMPEG_LOG=1` to keep FFmpeg's diagnostics while
  debugging a case.
* Everything is compiled with `-Wall -Wextra -Werror`, so a suite that warns does not build.
  Unused parameters, sign comparisons and shortened formats have to be dealt with.

Add a suite by adding its source to `tests/` and its stem to the `TESTS` list in
`build-host.sh` and the `ALL_SUITES` list in `run-c-tests.sh`. The two lists must agree.

## The five suites, and what each one earns

240 cases per variant, 720 case runs across the three. Measured at the B1.2 gate.

| Suite | Cases | What it establishes | Register item |
|---|---|---|---|
| `test_ownership.c` | 43 | Exact allocation pairing for all 43 ownership helpers under the interposer, including the parent-owned stream, the per call `SwsContext` and the conditional `pb` close. Every case ends with `live=0`. | B1-14 |
| `test_buffers.c` | 32 | All 12 buffer declaration sites and all 4 size-taking copy helpers, at the limit and one past it, under ASan and UBSan. | B1-10 |
| `test_rescale.c` | 116 | The 15 arithmetic helpers at the D9 overflow vectors, and `AV_CEIL_RSHIFT` plane heights over a 7 format by 6 height table. | D9 |
| `test_strerror_thread.c` | 24 | Both halves of the thread affinity contract, over 4 threads and 256 rendezvous-synchronised rounds, clean under TSan. | B1-09 |
| `test_convert.c` | 25 | Conversion correctness against an independently computed oracle, and the per call allocation cost as a number. | B1-23 |

Each suite proved load bearing by mutation against copies of `src/kitecodec_helpers.c` in a
scratch directory, never against the file in the repository. Dropping `sws_freeContext` from the
success path of `ffkmp_frame_convert_pixfmt` fails `test_ownership` case 9 with 5 blocks live.
Removing one running-length check in the audio builder gives UBSan `index 2054 out of bounds for
type 'char[2048]'`. Weakening any of the four copy bounds by one byte gives an ASan
`heap-buffer-overflow`. The details are in each suite's own file header.

## What is not here yet

Not here, by sub-phase: the Gradle compile task and the def edit that make this library the
one cinterop consumes (B1.3), the deletion of the 15 dead helpers and the split per subsystem
(B1.4), the fuzz targets and their corpus (B1.5), and the FFmpeg header versus runtime identity
gate (B1.6). Nothing in this directory claims to work on a target whose archive was never built:
plan section 15.3 grades that claim as level 8 and bans it.
