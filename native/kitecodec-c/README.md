# kitecodec-c

The FFmpeg helper layer as real C, with its own build, its own tests and its own sanitizer runs.

Until sub-phase B1.2 this code existed only as 949 lines of text inside
`kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`. Text in a def file has no translation
unit, so it had no object file, no test, no sanitizer run and no coverage. Its only compile check
was cinterop's, and its only test was whatever Kotlin happened to call, which left 19 of the
176 helpers never called at all. That is register item B1-01 in `KitePlayer/KPKMP.md`.

This directory is the fix, and since B1.3 it is what cinterop compiles and embeds. B1.4 finished
the shape: nine translation units, one per subsystem; `KC_API` on the 157 helpers Kotlin imports;
and the 15 that nothing imported deleted outright, because in a versioned library a dead exported
symbol is a compatibility promise nobody meant to make (register item B1-08).

## Layout

| Path | What it is |
|---|---|
| `tools/extract_from_def.py` | The committed generator. Turns the def body into the two files below. |
| `include/kitecodec_helpers.h` | Generated. The 20 includes, the `KC_API` macro, then one declaration per exported helper. |
| `src/helpers_*.c` | Generated, nine of them. The def body verbatim per subsystem, with the linkage token rewritten. |
| `include/kitecodec_abi.h` | HAND WRITTEN. The FFmpeg identity gate's contract. Includes no FFmpeg header and names no FFmpeg type. |
| `include/kitecodec_ffmpeg_versions.h` | HAND WRITTEN, private. The only place the gate reaches into FFmpeg, and the one file the identity test replaces. |
| `src/kitecodec_abi.c` | HAND WRITTEN. The gate: the frozen header macros, the runtime comparison, the report, the diagnostic bypass. |
| `scripts/build-host.sh` | Builds the host test binaries for one variant. |
| `scripts/verify-lift.sh` | Proves the ten generated files are exactly what the def produces. |
| `scripts/symbol-audit.sh` | Proves what the compiled archive needs, exports and keeps private. |
| `scripts/check-deleted-surface.sh` | Proves nothing in either repository refers to the 15 deleted helpers. |
| `scripts/run-c-tests.sh` | Runs the six suites for one variant. |
| `scripts/klib-metadata-diff.sh` | The compatibility instrument for the `ffmpeg` cinterop klib, added by B1.3. |
| `scripts/replay-corpus.sh` | Replays every committed fuzz seed through the replay driver under ASan and UBSan. Added by B1.5. |
| `scripts/run-fuzz.sh` | Runs the six libFuzzer targets. Refuses with one sentence on a host whose clang has no fuzzer runtime, which is every clang here. |
| `klib-metadata-baseline.txt` | Its baseline: the filtered metadata dump of that klib. |
| `fuzz/fuzz_*.c` | The six fuzz targets of plan section 15.2 B1.5, one per C entry point that parses a caller's string. |
| `fuzz/kc_fuzz.h`, `fuzz/kc_fuzz.c` | The input contract and the three helpers every target shares, so the corpus and the split cannot drift apart. |
| `fuzz/replay_main.c` | The `main()` that makes each target an ordinary sanitized regression test here. libFuzzer supplies its own in CI. |
| `fuzz/corpus/` | 103 committed seeds, 38077 bytes, all textual. |
| `fuzz/README.md` | What is fuzzed, what is deliberately not, and what B8 inherits. |
| `tests/harness.h`, `tests/harness.c` | The assertion and reporting API every suite uses. |
| `tests/interpose_alloc.c` | The allocation interposer, the local leak instrument. |
| `tests/test_*.c` | The six suites of plan section 15.3. |
| `tests/fake_headers/` | Five doctored shim include trees, one per identity verdict, plus the symbol renamer they share. |
| `coupling-baseline.txt` | The Kotlin to FFmpeg coupling ratchet's baseline, added by B1.1. |
| `build/` | Output. Gitignored. |

## The generated files are generated

Never hand edit `include/kitecodec_helpers.h` or any `src/helpers_*.c`. `verify-lift.sh` re-runs
the generator against a git revision of the def and compares the result, so a hand edit fails the
gate rather than surviving quietly. Change the def, or change the generator.

The extraction rules, all measured against the def at KiteCodec `cdb8ad2`:

* The body is def lines 13 to 961, which is 949 lines of C after the `---` separator on line 11.
* The 20 `#include` lines move to the header. Every unit gets `#include "kitecodec_helpers.h"`
  in their place, which is what makes compiling the units a proof that every declaration matches
  its definition.
* 176 declarations are found by balancing parentheses from the `(` that opens the parameter list.
  Nine signatures span more than one line, at def lines 251, 262, 470, 489, 531, 616, 644, 684
  and 816, and those keep their original line breaks in the header.
* 15 of the 172 exported helpers are emitted nowhere. They are register item B1-08, and the set is
  derived rather than listed: the header used to declare 172 helpers, the `kitecodec-core` Kotlin
  sources import 157 distinct `ffkmp_` names, and the difference is exactly those 15.
* The remaining 157 lose the whole `static inline ` token and gain `KC_API`, which is what makes
  them real exported symbols. See "KC_API and the nine units" below.
* Named helpers gain a documented contract comment above their declaration, taken from the
  `CONTRACTS` table in the generator. See "Documented contracts" below.
* Four helpers keep `static` and lose `inline`, and are not declared in the header at all:
  `ffkmp_codec_pix_fmts_` (def 289), `ffkmp_graph_finish_` (470), `ffkmp_graph_finish_multi_`
  (616) and `ffkmp_ch_layout_mask_` (908). Each is used only from inside its own banner section,
  so each lands in the same unit as every one of its callers. The generator re-checks that against
  the unit map on every run and refuses to emit if it stops holding, which is what decides whether
  any of the four has to become `KC_API` instead of staying `static`. At B1.4 none did.

Run `python3 tools/extract_from_def.py --report` to print the measured shape, including the 11
banner sections, the nine units and how many helpers each holds.

## KC_API and the nine units

The nine units are groupings of the def's own banner sections and never a re-cut of them, so the
map in `UNIT_MAP` inside the generator is short and checkable. Two of the eleven banners name no
subsystem of their own, "Pixel/sample format names" and "AVDictionary iteration", and
`helpers_frame.c` carries both because they sit between AVFrame and AVPacket and a unit has to be a
contiguous run. The generator proves three things about the map on every run: every banner is
claimed exactly once, each unit's banners are an unbroken run, and the nine line ranges tile the
whole body with no gap and no overlap.

| Unit | Def lines | Helpers | Banner sections |
|---|---|---|---|
| `helpers_error.c` | 13 to 53 | 6 | Errors & macros |
| `helpers_frame.c` | 54 to 193 | 38 | AVFrame, Pixel/sample format names, AVDictionary iteration |
| `helpers_packet.c` | 194 to 217 | 16 | AVPacket |
| `helpers_codecpar.c` | 218 to 244 | 12 | AVCodecParameters |
| `helpers_codec.c` | 245 to 345 | 24 | AVCodec / AVCodecContext |
| `helpers_format.c` | 346 to 439 | 24 | AVFormatContext (input + output) |
| `helpers_stream.c` | 440 to 465 | 10 | AVStream |
| `helpers_filter.c` | 466 to 782 | 11 | Filter graphs |
| `helpers_playback.c` | 783 to 961 | 35 | Playback additions |

`KC_API` is `__declspec(dllexport)` on Windows and `__attribute__((visibility("default")))`
everywhere else. Both the shipped compile and the host compile pass `-fvisibility=hidden`, which
governs the dynamic symbol table and not static linking: an unmarked helper still resolves inside
the link that embeds the archive. So `KC_API` is not what makes the cinterop work. It is what makes
the exported set a decision instead of an accident, and `symbol-audit.sh` is what checks the
decision, by comparing the archive's external symbols with the header's `KC_API` declarations and
finding them equal at 163: the 157 `ffkmp_` helpers plus the six `kc_` functions of the identity
gate below.

## The FFmpeg identity gate

`include/kitecodec_abi.h` plus `src/kitecodec_abi.c`, added by B1.6. Register item B1-02. Unlike the
nine units beside them these two are HAND WRITTEN: there was never an identity gate in the def to
extract, so `verify-lift.sh` does not compare them against anything and editing them is normal work.

**What it prevents was demonstrated, not argued.** Older FFmpeg headers against a newer runtime link
cleanly. Every symbol resolves, the static archive has no SONAME to object, and 38 measured struct
field offsets are wrong. 48 of the helpers in `kitecodec_helpers.h` read or write through one of
them, so the process reads wrong values and then dies inside `av_frame_free`, with AddressSanitizer
naming a four byte read 36 bytes past a 416 byte region. In the nondeterministic case it is silent.

**How the expectations are frozen.** `src/kitecodec_abi.c` initialises a file scope `static const`
array from the six `LIB*_VERSION_INT` macros. It is compiled by the same task, against the same
include tree, as every helper unit, so if the compiler baked a struct offset it also read these
macros. Nothing can recover a header version afterwards, which is why this construction is the only
one that is correct by definition rather than by care.

**The policy, and it is not up for renegotiation here.** Major must be exactly equal: hard reject, no
override, because a major bump lets FFmpeg reorder struct contents and its own `doc/developer.texi`
says so. Runtime minor at or above header minor: below is a reject, because FFmpeg guarantees
backward compatibility only. Micro is compared, reported and never rejects, and it is only comparable
within one minor because FFmpeg restarts it at each. The six `*_configuration()` strings must agree
with each other: disagreement is a mixed install, which agreeing version numbers cannot see at all.

**Why it needs a real library.** `kc_init` is guarded by `pthread_once`. A function-local static
inside a `static inline` gate in a header would give one flag per translation unit, so the gate would
run once per consumer of the header instead of once per process.

**Opaque from birth.** `kitecodec_abi.h` includes no FFmpeg header and names no FFmpeg type. The
report is flat plain data with fixed char arrays and no pointers, and there are no two-dimensional
arrays in it: cinterop flattens `char names[6][16]` into one byte array, so `names[i]` would be byte
`i` and not row `i`, which is a wrong reading that compiles. The per-library names come from
`kc_ffmpeg_library_name(index)` instead.

**The diagnostic bypass**, `KITECODEC_FFMPEG_ABI_BYPASS=1`. Opt-in only, exact value only, and never
quiet: it downgrades a rejection to a warning written once per process naming both identities, and it
records in the report that it was used, so no investigation starts from a gate that was bypassed
silently. It exists because an unbypassable gate turns one false rejection into an outage inside a
consumer's product that the consumer cannot patch. It is not a supported configuration.

This is also the only unit in the library that writes to a stream, and `symbol-audit.sh` check 5
pins that permission to this one file so the rule stays true everywhere else.

**How the test reaches the rejecting path**, since the archive it links was built against the headers
of the FFmpeg it loads. `tests/fake_headers/<case>/kitecodec_ffmpeg_versions.h` shims the private
versions header: it `#include_next`s the real one and only then redefines the `LIB*_VERSION_*` macros,
so FFmpeg's own deprecation guards still saw their true values and the doctoring touches nothing but
the frozen expectation array. Each shim also renames that copy's six exported symbols through
`tests/fake_headers/kc_rename.h`, so five doctored copies of the same source link into one binary.
The shim directory has to come BEFORE `-I include` on the command line; put it after and the real
header wins, no copy is renamed, and the link fails on an undefined `kc_<case>_init`, which is how
that mistake announces itself rather than passing vacuously.

## Documented contracts

Some helpers carry a contract that their signature cannot express. Those contracts live in the
`CONTRACTS` table inside `tools/extract_from_def.py`, and the generator emits each one as a
comment above the declaration it belongs to.

The table exists because neither of the two obvious places works. A comment written into the
header by hand is erased by the next generator run and `verify-lift.sh` would fail. A comment in
the def body ends up in a `src/helpers_*.c` unit, which documents the implementation rather than
the interface a consumer reads. So the table is the only place a contract survives, and the
generator refuses to emit if a name in it stops being an exported declaration, or if it documents
a helper the deletion list removes, which keeps a contract from going missing during a rename and
from outliving its subject.

Plan section 15.5 Deferral 2 is why this is not optional. It rejects
`__attribute__((ownership_returns))`, because clang honours it only in the static analyzer, which
makes the attribute level 8 evidence, and it substitutes "documented ownership contracts in the
header plus exact pairing tests" in its place. The words are half of that substitution.

What is in the table today: 40 contracts, one per declaration. It was 44 until B1.4 deleted
`ffkmp_frame_ref`, `ffkmp_frame_make_writable`, `ffkmp_packet_ref` and `ffkmp_fmt_alloc_output`.

| Group | Count | What the contracts say | Register item |
|---|---|---|---|
| `ffkmp_strerror` | 1 | Thread affine, and invalidated by the next call on the same thread. | B1-09 |
| Frames | 7 | Who owns the returned frame, which calls add a reference rather than copy, and which can move a plane pointer under the caller. | Deferral 2 |
| Packets | 4 | Which calls leave a packet blank and which leave it owning data. | Deferral 2 |
| Codecs | 8 | Context lifetime, and which setters copy their arguments. | Deferral 2 |
| Demuxing | 4 | The open and close pairing, and that a read packet must be released before the next read. | Deferral 2 |
| Muxing | 9 | The two contexts that must never be crossed, the stream the parent owns, the `pb` with no separate close, and the write that consumes its packet. | Deferral 2 |
| Filter graphs | 7 | The graph owns its filter contexts, every failure path frees the graph, and `out_srcs` is not cleared on a failed multi build. | Deferral 2 |

The ownership set is measured rather than listed by hand: a helper is an ownership helper when its
body reaches a libav call that allocates, frees, or moves a reference. Applied mechanically to the
176 bodies that selects 44 exported helpers plus the two internal graph finishers; B1.4 deleted
four of the 44, leaving 40. 39 of the 40 have a contract here and a case in
`tests/test_ownership.c`, so the words and the tests cover the same set. The 40th is
`ffkmp_codecctx_flush`: `avcodec_flush_buffers` releases the references the codec holds internally,
which is why the mechanical rule selects it, but nothing crosses the interface, so it has neither a
contract nor a case. Nothing here is documented that is not also asserted.

## Building and running

There is no make, no cmake and no ninja here, and that is not a preference. Register item B1-15:
cmake is not installed on the proving machine, and GNU make starts a comment at an unescaped `#`
while both repositories live under a path containing `#Kite`. `buildSrc/BuildFFmpegTask.kt`
already had to guard against that hazard. Driving clang directly is the only form that is both
available and safe under this path.

```bash
./scripts/verify-lift.sh                 # defaults to the last revision whose def has the body
./scripts/check-deleted-surface.sh
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
./scripts/symbol-audit.sh --host         # or with no argument, for the shipped archive
```

`verify-lift.sh` needs a revision whose def still carries the body. B1.3 deleted it, so from the
lift onward the revision to use is the lift's parent, which is the script's default and never
`HEAD`. Since B1.4 it makes three comparisons rather than one: the header byte for byte, each of
the nine units byte for byte, and the nine committed units concatenated in banner order, with their
per-unit `#include` line and their `KC_API` tokens stripped, against the whole def body. The third
one is not implied by the second: a unit map that dropped or duplicated a stretch of the def would
pass every per-file comparison and fail the concatenation. The 15 deleted helpers are supplied to
the generator as an explicit exclusion list, and the generator refuses to run if that list is not
exactly its own, so the gate and the generator cannot drift apart.

The cinterop surface has its own instrument, which is not part of the C build and needs a klib
rather than a host binary:

```bash
../../gradlew :kitecodec-core:cinteropFfmpegMacosArm64
./scripts/klib-metadata-diff.sh          # reports added and removed declarations by name
./scripts/klib-metadata-diff.sh --check  # the same, and exits non-zero on any difference
./scripts/klib-metadata-diff.sh --update # re-baseline after reading and accepting a change
```

Editing a `.c` body here does reach the klib, but only because `kitecodec-core/build.gradle.kts`
declares the archive an input of the cinterop task. The cinterop task runs its own up-to-date
check over the def and the headers and would otherwise report UP-TO-DATE and keep the previous
archive, which was measured at B1.3 and is written up in that build file and in the plan's
Execution log. If a local change to a helper body ever appears to have no effect, check that
declaration before suspecting the compiler.

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

The helper units also get `-fvisibility=hidden`, matching the shipped compile in
`buildSrc/CompileKiteCodecCTask.kt`, so the host archive carries the same exported set as the
shipped one and `symbol-audit.sh` means the same thing whichever archive it is pointed at.

`-Werror` is not decoration. Because every unit includes its own generated header, this compile is
the only mechanical proof that all the emitted declarations agree with their definitions, and a
warning that nobody reads would not be a proof. Separate compilation earns a second proof for free:
the four `static` helpers cannot be called from another unit, because that would be an implicit
declaration, and cannot sit in a unit that never calls them, because that would be an unused
function, and under `-Wall -Wextra -Werror` both are hard errors. A green build is the compiler's
answer to the locality question rather than an argument about it.

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

## The six suites, and what each one earns

250 cases per variant, 750 case runs across the three. Measured at the B1 closing gate. The history
is worth one sentence, because this line disagreed with its own table until that gate: 240 at B1.2
and B1.3, then 234 at B1.4, when six cases went with the helpers B1.4 deleted, four in
`test_ownership.c` and two in `test_rescale.c`; then 250 at B1.6, when `test_identity.c` arrived with
16. B1.6 added the table row and left this line at 234, so the table was right and the prose was
wrong for two sub-phases.

| Suite | Cases | What it establishes | Register item |
|---|---|---|---|
| `test_ownership.c` | 39 | Exact allocation pairing for all 39 ownership helpers under the interposer, including the parent-owned stream, the per call `SwsContext` and the conditional `pb` close. Every case ends with `live=0`. | B1-14 |
| `test_buffers.c` | 32 | All 12 buffer declaration sites and all 4 size-taking copy helpers, at the limit and one past it, under ASan and UBSan. | B1-10 |
| `test_rescale.c` | 114 | The 13 arithmetic helpers at the D9 overflow vectors, and `AV_CEIL_RSHIFT` plane heights over a 7 format by 6 height table. | D9 |
| `test_strerror_thread.c` | 24 | Both halves of the thread affinity contract, over 4 threads and 256 rendezvous-synchronised rounds, clean under TSan. | B1-09 |
| `test_convert.c` | 25 | Conversion correctness against an independently computed oracle, and the per call allocation cost as a number. | B1-23 |
| `test_identity.c` | 16 | One case per identity verdict against five doctored header trees, the true build, and all three conditions the diagnostic bypass has to satisfy. | B1-02, B1-21 |

Each suite proved load bearing by mutation against copies of the helper sources in a
scratch directory, never against the files in the repository. Dropping `sws_freeContext` from the
success path of `ffkmp_frame_convert_pixfmt` fails `test_ownership` case 9 with 5 blocks live.
Removing one running-length check in the audio builder gives UBSan `index 2054 out of bounds for
type 'char[2048]'`. Weakening any of the four copy bounds by one byte gives an ASan
`heap-buffer-overflow`. The details are in each suite's own file header.

## What each instrument proves, and what it cannot

Three instruments carry the evidence in this directory, and they are not interchangeable. Plan
section 2 grades evidence and forbids presenting a weaker instrument as a stronger one, so the table
is here rather than left to a reader's assumption.

| Instrument | Level | Proves | Cannot prove |
|---|---|---|---|
| The allocation interposer | 2 | Exact allocation and free pairing per ownership helper, and that nothing is left live at the end of a case. Every count is a real `malloc`, `calloc`, `realloc`, `free`, `mmap` or `posix_memalign`. | Anything under `asan` or `tsan`, where the sanitizer owns the allocator and the counters read zero; those cases report a partial. And anything about a managed runtime's allocation, which does not go through these functions at all. |
| ASan with UBSan | 2 | An out of bounds read or write, and a signed overflow, named at the byte and the line. This is the variant that reproduced the stale-header class the identity gate now prevents: a four byte read 36 bytes past a 416 byte region. | A leak, because LeakSanitizer is unsupported here. A race, because it cannot be combined with TSan. |
| TSan | 2 | A real data race between two threads, which is what keeps `ffkmp_strerror`'s thread affinity honest. | Memory ordering strength. Downgrading a release store to relaxed is still atomic, and TSan says nothing about it; that class needs a source level check or a proof. |

Four limits of this machine shape all of the above, each measured rather than assumed:

* **No libFuzzer.** Apple clang 17 and konan's LLVM 21 both fail with `libclang_rt.fuzzer_osx.a not
  found`, and Homebrew LLVM is not installed. So `run-fuzz.sh` refuses with one sentence here, the
  Linux CI job is the only place the fuzzer itself executes, and what runs locally is the corpus
  replay, which is a regression test over the committed seeds and nothing more.
* **No LeakSanitizer.** `detect_leaks=1` answers "not supported on this platform", which is why the
  interposer above exists.
* **No cmake, and GNU make is unsafe here.** cmake is not installed, and make starts a comment at an
  unescaped `#`, which this checkout's own path contains. The C build drives clang directly.
* **One FFmpeg tree.** Ten of the eleven registered targets have no FFmpeg on this machine, so
  exactly one real archive is built here, for `macos_arm64`.

## What is not here yet

Not here, and not this directory's job: the lock-free C audio ring and the pure C device callback.
Those landed in KitePlayer's `kiteplayer-rt` in sub-phases B1.7 and B1.8, with their own eight C
suites, their own render audit and their own supervised device run. A lock-free audio ring has
nothing to do with FFmpeg, and putting it here would have made KitePlayer's real-time core a
transitive consequence of a codec dependency.

Nothing in this directory claims to work on a target whose archive was never built: plan section
15.3 grades that claim as level 8 and bans it. On this machine one archive is built, for
`macos_arm64`, and the other ten registered targets are skipped for want of an FFmpeg tree.

Done in B1.3: the Gradle compile task (`buildSrc/CompileKiteCodecCTask.kt`) and the def edit that
make this library the one cinterop consumes.

Done in B1.4: the split into nine units, `KC_API`, the deletion of the 15 dead helpers and of the
six unreferenced def files under `kitecodec-core/src/nativeInterop/cinterop/archived/`,
`symbol-audit.sh`, `check-deleted-surface.sh`, and `verify-lift.sh`'s new three-way shape.

Done in B1.5: the six fuzz targets under `fuzz/`, their 103 committed seeds, `replay-corpus.sh`
with its `--prove-power` mutation check, `run-fuzz.sh`, and the `fuzz-linux` CI job.

Done in B1.6: the FFmpeg header versus runtime identity gate, `include/kitecodec_abi.h`,
`src/kitecodec_abi.c`, `tests/test_identity.c` against `tests/fake_headers/`, and the
`ffmpeg-identity-gate` CI job.

Done in B1.9: the words. Nothing in this directory changed except this file and the two documents
above it, and the coupling baseline was re-read rather than re-recorded, because B1.7 to B1.9 touch
KitePlayer only and none of the four counts could move. B1 is closed.
