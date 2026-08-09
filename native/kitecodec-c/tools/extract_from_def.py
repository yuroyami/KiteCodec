#!/usr/bin/env python3
"""Extract the FFmpeg helper layer out of ffmpeg.def into real C sources.

The cinterop def file carried 949 lines of C after its `---` separator until B1.3 deleted it:
20 `#include` lines and 176 `static inline ffkmp_*` helpers. That text had no translation unit,
so it had no object file, no test, no sanitizer run and no coverage (register item B1-01). This
script is the committed generator that turns it into ordinary files:

  include/kitecodec_helpers.h   the 20 includes, the KC_API macro, then one declaration per
                                exported helper that survives
  src/helpers_<subsystem>.c     nine translation units, one per subsystem, each the def body's
                                own banner sections verbatim with the `static inline ` token
                                rewritten and the header included first

All ten outputs are generated. Never edit them by hand: `scripts/verify-lift.sh` re-runs this
script against a git revision of the def and compares the result with what is committed, so a
hand edit shows up as a failed gate.

Transformation rules, all measured against ffmpeg.def at KiteCodec cdb8ad2:

  * The body is def lines 13 to 961. It starts after the `---` separator on line 11 and the
    blank line that follows it, and ends at the last non-blank line of the file.
  * The 20 `#include` lines move to the header. Each unit gets `#include
    "kitecodec_helpers.h"` in their place, so every unit is compiled against the very
    declarations the def will later consume.
  * 176 declarations are found by balancing parentheses from the `(` that opens the parameter
    list. Nine signatures span more than one line (def lines 251, 262, 470, 489, 531, 616,
    644, 684 and 816), and those keep their original line breaks in the header.
  * Four helpers have a trailing underscore in their name and are internal to the body:
    ffkmp_codec_pix_fmts_ (289), ffkmp_graph_finish_ (470), ffkmp_graph_finish_multi_ (616)
    and ffkmp_ch_layout_mask_ (908). They keep `static`, lose `inline`, and are NOT declared
    in the header. Each is called only from inside its own banner section, so each stays in
    the same unit as every one of its callers; this script re-checks that property against the
    unit map on every run and refuses to emit if it stops holding. That check is what decides
    the B1.4 question of whether any of the four has to become KC_API instead of `static`.
  * Every other surviving helper loses the whole `static inline ` token and gains `KC_API`,
    which is what gives it external linkage in a `-fvisibility=hidden` build and makes it a
    real exported symbol.
  * The 15 helpers in DELETED are emitted nowhere. They were exported surface that no Kotlin
    file imports, which in a versioned library is a compatibility promise nobody meant to make
    (register item B1-08). The set is not a hand list: it is exactly the difference between the
    172 exported declarations and the 157 `ffkmp_` names the `kitecodec-core` Kotlin sources
    import, measured that way before it was written down here.
  * Named helpers gain a documented contract comment above their declaration, from the
    CONTRACTS table below. The def body carries no such prose, and the header is generated, so
    a hand written comment would be erased by the next run. The table is the only place a
    contract can live and survive, and every name in it must still exist as an exported
    declaration, and must not be in DELETED, or the extractor refuses to emit.

Because every generated unit includes the generated header, compiling the units is the proof
that every emitted declaration matches its definition. Compile with -Werror, which
`scripts/build-host.sh` does, and one mismatch is a hard failure rather than a warning. The
same compile is also the mechanical proof of internal locality: a `static` helper called from
another unit would be an implicit declaration there, and a `static` helper defined in a unit
that never calls it would be an unused function, and both are errors under
`-Wall -Wextra -Werror`.

Usage:
  extract_from_def.py --report
  extract_from_def.py --header include/kitecodec_helpers.h --units src
  git show REV:kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def \\
      | extract_from_def.py --stdin --payload /tmp/payload.c
"""

import argparse
import os
import sys

SEPARATOR = "---"
DECL_TOKEN = "static inline "
HEADER_GUARD = "KITECODEC_HELPERS_H"
HEADER_NAME = "kitecodec_helpers.h"
GENERATOR = "native/kitecodec-c/tools/extract_from_def.py"
DEF_IN_REPO = "kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def"

# Measured shape of the def body. These are assertions, not configuration: if the def moves,
# the extractor must stop rather than emit something nobody measured. B1.3 deletes the body,
# so from that commit onward verify-lift.sh is pointed at the parent revision.
EXPECT_BODY_FIRST = 13
EXPECT_BODY_LAST = 961
EXPECT_INCLUDES = 20
EXPECT_DECLARATIONS = 176
EXPECT_MULTILINE = [251, 262, 470, 489, 531, 616, 644, 684, 816]
EXPECT_INTERNAL = {
    "ffkmp_codec_pix_fmts_": 289,
    "ffkmp_graph_finish_": 470,
    "ffkmp_graph_finish_multi_": 616,
    "ffkmp_ch_layout_mask_": 908,
}

BANNER_MARK = "═"  # the box drawing character the def's section banners are drawn with

# The KC_API macro, emitted into the header and prefixed onto every exported definition.
#
# `-fvisibility=hidden` is on in both the shipped per-target compile (CompileKiteCodecCTask) and,
# from B1.4 onward, the host compile. Under it an unmarked helper becomes a Mach-O private extern
# symbol: still resolvable by the static linker inside the one link that embeds the archive, and
# absent from a consumer binary's dynamic symbol table. KC_API puts the 157 helpers Kotlin imports
# back into the exported set explicitly, which is what `scripts/symbol-audit.sh` asserts, and it is
# also the only form that works on Windows, where visibility attributes mean nothing and
# `__declspec(dllexport)` is the mechanism.
KC_API_TOKEN = "KC_API"
KC_API_MACRO = [
    "/* KC_API marks the helpers the Kotlin side imports as deliberately exported.",
    " *",
    " * The archive is compiled with -fvisibility=hidden, which governs the DYNAMIC symbol table",
    " * and not static linking: an unmarked helper still resolves inside the link that embeds the",
    " * archive. So this macro is not what makes the cinterop work; it is what makes the exported",
    " * set a decision rather than an accident, and scripts/symbol-audit.sh checks the decision.",
    " * The four trailing-underscore helpers are `static` and never carry it.",
    " */",
    "#if defined(_WIN32)",
    "#define %s __declspec(dllexport)" % KC_API_TOKEN,
    "#else",
    "#define %s __attribute__((visibility(\"default\")))" % KC_API_TOKEN,
    "#endif",
]

# The nine translation units of B1.4, in banner order, each naming the def's own banner sections
# it carries. The def's banners are where the subsystems already are, so this map is a grouping of
# them and never a re-cut: build_units() proves that every banner is claimed exactly once, that
# each unit's banners are an unbroken run, and that the nine line ranges tile the whole body with
# no gap and no overlap. That tiling is what lets scripts/verify-lift.sh concatenate the units and
# compare the result with the def body.
#
# Two of the eleven banners name no subsystem of their own: "Pixel/sample format names" and
# "AVDictionary iteration", four and three helpers. They are carried by helpers_frame.c because
# they sit between AVFrame and AVPacket in the def and a unit has to be a contiguous run. The
# alternative, a tenth unit for seven one-line accessors, buys nothing.
UNIT_MAP = [
    ("helpers_error.c", ["Errors & macros"]),
    ("helpers_frame.c", ["AVFrame", "Pixel/sample format names", "AVDictionary iteration"]),
    ("helpers_packet.c", ["AVPacket"]),
    ("helpers_codecpar.c", ["AVCodecParameters"]),
    ("helpers_codec.c", ["AVCodec / AVCodecContext"]),
    ("helpers_format.c", ["AVFormatContext (input + output)"]),
    ("helpers_stream.c", ["AVStream"]),
    ("helpers_filter.c", ["Filter graphs (single-input video / audio)"]),
    ("helpers_playback.c", ["Playback additions"]),
]

# The 15 helpers B1.4 deletes, register item B1-08, each with the def line range of its whole
# definition. The ranges are measured, and check_shape() re-derives them by brace balancing and
# fails when they move, so this table is the record of the measurement rather than a hint.
#
# How the set was derived, and it was derived rather than copied: the header declares 172 exported
# helpers, the `kitecodec-core` Kotlin sources import 157 distinct `ffkmp_` names, and the
# difference is exactly these 15. Nothing else in either repository references them; five of them
# were referenced by the C test suites only, as a convenient source of an error code or a
# timestamp sentinel, and those uses moved to the libav macros they wrap.
DELETED = {
    "ffkmp_averror_einval": (43, 43),
    "ffkmp_nopts_value": (44, 44),
    "ffkmp_frame_ref": (59, 59),
    "ffkmp_frame_make_writable": (76, 76),
    "ffkmp_packet_ref": (199, 199),
    "ffkmp_packet_flags": (203, 203),
    "ffkmp_codecpar_video_delay": (228, 228),
    "ffkmp_codecctx_sample_fmt": (315, 315),
    "ffkmp_codec_name": (335, 335),
    "ffkmp_fmt_bit_rate": (375, 375),
    "ffkmp_fmt_alloc_output": (384, 389),
    "ffkmp_stream_duration": (444, 444),
    "ffkmp_stream_nb_frames": (452, 452),
    "ffkmp_avseek_flag_byte": (810, 810),
    "ffkmp_avseek_flag_frame": (811, 811),
}

# Documented contracts, emitted into the header above the named declaration.
#
# Why this table exists rather than a comment in the header: the header is generated and
# verify-lift.sh byte-compares it, so prose written into the header by hand is erased by the
# next run. Why not a comment in the def instead: the def body becomes the src/helpers_*.c units,
# so a comment there documents the implementation rather than the interface the consumer reads.
#
# Plan section 15.5 Deferral 2 is the reason this mechanism is not optional. It refuses
# `__attribute__((ownership_returns))` because clang honours it only in the static analyzer,
# which makes the attribute level 8 evidence, and it substitutes "documented ownership
# contracts in the header plus exact pairing tests" for it. The words are half of that
# substitution and the tests are the other half.
#
# What is here, and how the set was chosen. Register item B1-09 is the ffkmp_strerror entry,
# whose premise was re-measured rather than repeated: one match for `static` inside a function
# body across all 949 body lines, at def line 37. The rest are Deferral 2's ownership contracts.
#
# The ownership set is measured, not listed by hand. A helper is an ownership helper when its
# body reaches a libav call that allocates, frees, or moves a reference. Applied mechanically to
# the 176 bodies that gives 44 exported helpers plus the two internal graph finishers. B1.4
# deletes four of the 44 as dead exported surface (ffkmp_frame_ref, ffkmp_frame_make_writable,
# ffkmp_packet_ref and ffkmp_fmt_alloc_output, all in DELETED), leaving 40. 39 of the 40 have a
# contract below and a case in tests/test_ownership.c, so the words and the tests cover the same
# set by construction. The 40th, ffkmp_codecctx_flush, is the one classification boundary:
# avcodec_flush_buffers releases the references the codec holds internally, which is why the
# mechanical rule selects it, but nothing crosses the interface, so it has neither a contract here
# nor a case there. Nothing is documented that is not also asserted, and a contract for a deleted
# helper is refused outright by check_shape().
CONTRACTS = {
    "ffkmp_strerror": [
        "Thread affinity, register item B1-09. The returned pointer is into",
        "`static __thread char buf[256]` at def line 37, which is the only static storage in",
        "the whole helper layer. Two consequences, and both are contract rather than accident:",
        "the storage is per thread, so a pointer must never be shared between threads; and the",
        "next ffkmp_strerror call on the same thread overwrites it, so the string must be",
        "copied or consumed before calling again. It must never be stored.",
        "Proved by tests/test_strerror_thread.c.",
    ],

    # Frames.
    "ffkmp_frame_alloc": [
        "Ownership. Returns a new AVFrame the caller owns, or NULL when allocation fails.",
        "Release it with ffkmp_frame_free and never with free.",
    ],
    "ffkmp_frame_free": [
        "Ownership. Frees the frame and drops every reference it holds. The pointer arrives by",
        "value, so the caller's own variable is not cleared and must be cleared by the caller.",
        "A NULL frame is accepted and does nothing.",
    ],
    "ffkmp_frame_unref": [
        "Ownership. Drops the frame's data references and resets its fields. The AVFrame itself",
        "stays allocated and stays the caller's. A NULL frame is accepted and does nothing.",
    ],
    "ffkmp_frame_get_buffer": [
        "Ownership. Allocates data buffers from the frame's width, height, format and, for audio,",
        "nb_samples and ch_layout, all of which must be set first. The frame owns the buffers and",
        "ffkmp_frame_unref or ffkmp_frame_free releases them.",
    ],
    "ffkmp_frame_set_ch_layout_default": [
        "Ownership. Uninitialises the frame's existing channel layout before writing the default",
        "for `ch`, so calling it repeatedly does not leak a layout allocation. The frame keeps",
        "ownership of the result.",
    ],
    "ffkmp_frame_clone": [
        "Ownership. Returns a new caller-owned AVFrame, or NULL. The data is shared with the",
        "source through a new reference and is not copied. Release it with ffkmp_frame_free.",
    ],
    "ffkmp_frame_convert_pixfmt": [
        "Ownership. Returns a new caller-owned AVFrame with its own buffers, or NULL. Release it",
        "with ffkmp_frame_free. The SwsContext is allocated and freed inside this call on every",
        "path, including every failure path, so nothing about it reaches the caller. That per call",
        "cost is register item B1-23: it is the current behaviour on purpose, and",
        "tests/test_convert.c records the measured numbers as the baseline B2's caching must beat.",
    ],

    # Packets.
    "ffkmp_packet_alloc": [
        "Ownership. Returns a new AVPacket the caller owns, or NULL when allocation fails.",
        "Release it with ffkmp_packet_free and never with free.",
    ],
    "ffkmp_packet_free": [
        "Ownership. Frees the packet and drops every reference it holds. The pointer arrives by",
        "value, so the caller's own variable is not cleared and must be cleared by the caller.",
        "A NULL packet is accepted and does nothing.",
    ],
    "ffkmp_packet_unref": [
        "Ownership. Drops the packet's data reference and resets its fields. The AVPacket itself",
        "stays allocated and stays the caller's. A NULL packet is accepted and does nothing.",
    ],
    "ffkmp_packet_move_ref": [
        "Ownership. Moves every reference from src to dst and leaves src blank, so exactly one of",
        "the two owns the data afterwards. dst must be blank on entry. Neither packet is freed,",
        "and a NULL on either side makes the call do nothing.",
    ],

    # Codecs.
    "ffkmp_codecctx_alloc": [
        "Ownership. Returns a new AVCodecContext the caller owns, or NULL. Release it with",
        "ffkmp_codecctx_free whether or not it was ever opened.",
    ],
    "ffkmp_codecctx_free": [
        "Ownership. Frees the context together with everything it holds, including buffered",
        "frames and, when it was opened, the codec's private state. The pointer arrives by value,",
        "so the caller's own variable is not cleared.",
    ],
    "ffkmp_codecctx_open": [
        "Ownership. Allocates the codec's internal state onto the context. Failure leaves nothing",
        "extra to undo, because ffkmp_codecctx_free releases the context either way. Never call",
        "it twice on one context.",
    ],
    "ffkmp_codecctx_from_par": [
        "Ownership. Copies the parameters into the context, taking a private copy of extradata.",
        "The parameters stay owned by whoever holds them, normally an AVStream.",
    ],
    "ffkmp_codecctx_set_audio": [
        "Ownership. Uninitialises the context's existing channel layout before writing the default",
        "for `channels`, so calling it repeatedly does not leak a layout allocation.",
    ],
    "ffkmp_codecctx_set_opt": [
        "Ownership. The option system copies key and value, so neither string is retained and both",
        "may be freed immediately. A NULL context or key is refused with AVERROR(EINVAL).",
    ],
    "ffkmp_codecpar_from_context": [
        "Ownership. Fills the parameters from the context, freeing and replacing any extradata the",
        "parameters already held. The parameters stay owned by whoever holds them.",
    ],
    "ffkmp_codecpar_copy_for_mux": [
        "Ownership. Replaces the destination's contents with a copy of the source, freeing what the",
        "destination held, then clears codec_tag so the muxer picks its own. The destination stays",
        "owned by its stream.",
    ],

    # Demuxing.
    "ffkmp_fmt_open_input": [
        "Ownership. On success *out is a new AVFormatContext the caller owns and must release with",
        "ffkmp_fmt_close_input, never with ffkmp_fmt_free_output. On failure *out is set to NULL",
        "and nothing is left allocated.",
    ],
    "ffkmp_fmt_close_input": [
        "Ownership. Closes the demuxer, frees the context with every stream in it, and writes NULL",
        "through ctx. Safe on a pointer that is already NULL. It must not be used on a context",
        "from ffkmp_fmt_alloc_output2.",
    ],
    "ffkmp_fmt_find_stream_info": [
        "Ownership. Allocates per stream parsing state, and may probe and buffer packets. All of",
        "it belongs to the context and is released when the context is closed. Nothing becomes",
        "the caller's.",
    ],
    "ffkmp_fmt_read_frame": [
        "Ownership. On success the packet holds a new reference the caller owns. The packet must be",
        "blank on entry, and must be unreferenced before it is filled again, or the reference",
        "leaks. On failure the packet is left blank.",
    ],

    # Muxing.
    "ffkmp_fmt_alloc_output2": [
        "Ownership. On success *out is a new muxer context the caller owns and must release with",
        "ffkmp_fmt_free_output, never with ffkmp_fmt_close_input. On failure *out is set to NULL.",
    ],
    "ffkmp_fmt_set_opt": [
        "Ownership. The option system copies key and value, so neither string is retained and both",
        "may be freed immediately. A NULL context is refused with AVERROR(EINVAL).",
    ],
    "ffkmp_fmt_set_metadata": [
        "Ownership. Copies key and value into the context's metadata dictionary, which the context",
        "owns and its free releases. Neither string is retained.",
    ],
    "ffkmp_fmt_new_stream": [
        "Ownership. The returned AVStream belongs to the format context and not to the caller.",
        "There is no per stream free, so the pairing rule is different from every other allocating",
        "helper here: ffkmp_fmt_free_output releases every stream the context holds. Never free the",
        "result, and never use it after the context is gone.",
    ],
    "ffkmp_fmt_io_open": [
        "Ownership. Opens ctx->pb, which the context then holds. It is a no op, returning 0, for a",
        "format carrying AVFMT_NOFILE. There is no separate close: ffkmp_fmt_free_output closes pb",
        "exactly when this call opened it, so the pairing is with that free.",
    ],
    "ffkmp_fmt_write_header": [
        "Ownership. Allocates muxer private state onto the context, released when the context is",
        "freed. Once it has succeeded, write the trailer before freeing the context.",
    ],
    "ffkmp_fmt_write_frame": [
        "Ownership. Takes over the packet's reference. On success and on failure alike the packet",
        "is blank afterwards and must not be unreferenced again. A NULL packet flushes the",
        "interleaving queue.",
    ],
    "ffkmp_fmt_write_trailer": [
        "Ownership. Flushes and releases the packets the muxer had buffered. It does not free the",
        "context, so ffkmp_fmt_free_output is still required.",
    ],
    "ffkmp_fmt_free_output": [
        "Ownership. Closes ctx->pb when the format uses a file and pb is open, then frees the",
        "context with every stream in it, then writes NULL through ctx. Safe on a pointer that is",
        "already NULL. It must not be used on a context from ffkmp_fmt_open_input.",
    ],

    # Filter graphs.
    "ffkmp_graph_build_video": [
        "Ownership. On success the caller owns the graph through *out_graph and releases it with",
        "ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed",
        "separately. On every failure path the graph is freed inside the call and all three out",
        "parameters are left NULL.",
    ],
    "ffkmp_graph_build_audio": [
        "Ownership. On success the caller owns the graph through *out_graph and releases it with",
        "ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed",
        "separately. On every failure path the graph is freed inside the call and all three out",
        "parameters are left NULL.",
    ],
    "ffkmp_graph_build_video_multi": [
        "Ownership. On success the caller owns the graph through *out_graph and releases it with",
        "ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be",
        "freed separately. On failure the graph is freed inside the call and *out_graph and",
        "*out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the",
        "freed graph. Read out_srcs only when the call returned 0.",
    ],
    "ffkmp_graph_build_audio_multi": [
        "Ownership. On success the caller owns the graph through *out_graph and releases it with",
        "ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be",
        "freed separately. On failure the graph is freed inside the call and *out_graph and",
        "*out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the",
        "freed graph. Read out_srcs only when the call returned 0.",
    ],
    "ffkmp_graph_free": [
        "Ownership. Frees the graph together with every filter context in it, and writes NULL",
        "through g. Every AVFilterContext a builder handed out dangles afterwards. Safe on a",
        "pointer that is already NULL.",
    ],
    "ffkmp_graph_send": [
        "Ownership. Sends the frame with AV_BUFFERSRC_FLAG_KEEP_REF, so the graph takes its own",
        "reference and the caller keeps and must still release the frame it passed in. Without",
        "that flag the frame would be consumed, which is why the flag is part of the contract.",
    ],
    "ffkmp_graph_receive": [
        "Ownership. On success the frame holds a new reference the caller owns. The frame must be",
        "blank on entry and must be unreferenced before it is filled again. AVERROR(EAGAIN) and",
        "AVERROR_EOF leave it blank and are not failures.",
    ],
}


class ExtractionError(Exception):
    """Raised when the def does not have the shape this extractor was measured against."""


class Declaration(object):
    def __init__(self, name, first_line, last_line, definition_last, signature, internal):
        self.name = name
        self.first_line = first_line
        self.last_line = last_line
        # The last def line of the whole definition, closing brace included. `last_line` is only
        # where the parameter list closes, which is the same line for a one-liner and an earlier
        # one for the nine multi-line signatures. Deleting a helper needs the definition range.
        self.definition_last = definition_last
        self.signature = signature
        self.internal = internal

    @property
    def multiline(self):
        return self.last_line > self.first_line


class Section(object):
    def __init__(self, title, first_line):
        self.title = title
        self.first_line = first_line
        self.last_line = None


class Unit(object):
    """One generated translation unit: a contiguous run of the def's own banner sections."""

    def __init__(self, filename, titles, first_line, last_line):
        self.filename = filename
        self.titles = titles
        self.first_line = first_line
        self.last_line = last_line

    def holds(self, line):
        return self.first_line <= line <= self.last_line


def read_def(path, use_stdin):
    if use_stdin:
        return sys.stdin.read()
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def split_body(text):
    """Return [(def_line_number, line_text)] for the C body, plus its first and last line."""
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    separators = [i for i, line in enumerate(lines) if line.strip() == SEPARATOR]
    if len(separators) != 1:
        raise ExtractionError(
            "expected exactly one %r separator line, found %d" % (SEPARATOR, len(separators)))
    start = separators[0] + 1
    while start < len(lines) and lines[start].strip() == "":
        start += 1
    end = len(lines) - 1
    while end >= start and lines[end].strip() == "":
        end -= 1
    if end < start:
        raise ExtractionError("the def has no C body after its separator")
    body = [(i + 1, lines[i]) for i in range(start, end + 1)]
    return body, start + 1, end + 1


def find_sections(body):
    """The def's own banner comments, used to group the header and to prove locality."""
    sections = []
    for number, line in body:
        stripped = line.strip()
        if not stripped.startswith("/*") or BANNER_MARK not in stripped:
            continue
        title = stripped
        for junk in ("/*", "*/"):
            title = title.replace(junk, "")
        title = title.replace(BANNER_MARK, " ").strip()
        if not title:
            continue
        if sections:
            sections[-1].last_line = number - 1
        sections.append(Section(title, number))
    if sections:
        sections[-1].last_line = body[-1][0]
    return sections


def section_of(sections, line):
    for section in sections:
        if section.first_line <= line <= section.last_line:
            return section
    return None


def parse_declarations(body):
    """Find every helper by balancing parentheses from the start of its parameter list."""
    declarations = []
    index = 0
    while index < len(body):
        number, line = body[index]
        if not line.startswith(DECL_TOKEN):
            index += 1
            continue
        open_at = line.find("(")
        if open_at < 0:
            raise ExtractionError(
                "def line %d starts a declaration with no parameter list" % number)
        name = identifier_before(line, open_at, number)
        depth = 0
        cursor = index
        pieces = []
        closed_at = None
        while cursor < len(body):
            _, text = body[cursor]
            scan_from = open_at if cursor == index else 0
            stop = None
            for position in range(scan_from, len(text)):
                char = text[position]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        stop = position
                        break
            if stop is None:
                pieces.append(text)
                cursor += 1
                continue
            pieces.append(text[:stop + 1])
            closed_at = body[cursor][0]
            break
        if closed_at is None:
            raise ExtractionError(
                "def line %d has an unbalanced parameter list" % number)
        signature = "\n".join(pieces)[len(DECL_TOKEN):]
        end_index = find_definition_end(body, cursor, stop + 1)
        declarations.append(Declaration(
            name=name,
            first_line=number,
            last_line=closed_at,
            definition_last=body[end_index][0],
            signature=signature,
            internal=name.endswith("_"),
        ))
        index = end_index + 1
    return declarations


def code_only(text, in_comment):
    """`text` with block comments, string literals and char literals blanked out.

    Returns (blanked_text, still_in_comment). Brace balancing runs over the result so a `{` inside
    a comment or a literal cannot move a definition's end. Measured against the pre-B1.3 def body:
    it contains no brace inside any comment or literal and no `//` comment at all, so this
    function changes nothing there. It exists so that a future edit which does put one there fails
    to confuse the generator rather than silently truncating a definition.
    """
    out = []
    index = 0
    while index < len(text):
        if in_comment:
            if text.startswith("*/", index):
                in_comment = False
                out.append("  ")
                index += 2
            else:
                out.append(" ")
                index += 1
            continue
        if text.startswith("/*", index):
            in_comment = True
            out.append("  ")
            index += 2
            continue
        if text.startswith("//", index):
            out.append(" " * (len(text) - index))
            index = len(text)
            continue
        if text[index] in "\"'":
            quote = text[index]
            out.append(" ")
            index += 1
            while index < len(text):
                if text[index] == "\\":
                    out.append("  ")
                    index += 2
                    continue
                if text[index] == quote:
                    out.append(" ")
                    index += 1
                    break
                out.append(" ")
                index += 1
            continue
        out.append(text[index])
        index += 1
    return "".join(out), in_comment


def find_definition_end(body, index, from_position):
    """Index into `body` of the line closing the definition that starts at `body[index]`."""
    depth = 0
    seen_brace = False
    in_comment = False
    cursor = index
    while cursor < len(body):
        number, text = body[cursor]
        blanked, in_comment = code_only(text, in_comment)
        start = from_position if cursor == index else 0
        for position in range(start, len(blanked)):
            char = blanked[position]
            if char == "{":
                depth += 1
                seen_brace = True
            elif char == "}":
                depth -= 1
                if depth < 0:
                    raise ExtractionError(
                        "def line %d closes more braces than it opens" % number)
                if depth == 0 and seen_brace:
                    return cursor
            elif char == ";" and not seen_brace:
                raise ExtractionError(
                    "def line %d is a prototype rather than a definition; the def body has none"
                    % number)
        cursor += 1
    raise ExtractionError("def line %d has an unterminated body" % body[index][0])


def identifier_before(line, open_at, number):
    end = open_at
    while end > 0 and line[end - 1].isspace():
        end -= 1
    start = end
    while start > 0 and (line[start - 1].isalnum() or line[start - 1] == "_"):
        start -= 1
    name = line[start:end]
    if not name:
        raise ExtractionError("def line %d declares something with no name" % number)
    return name


def build_units(body, sections):
    """Turn UNIT_MAP into nine line ranges over the body, and prove the map sound.

    Three properties, all required by scripts/verify-lift.sh: every banner section is claimed by
    exactly one unit, each unit's sections are an unbroken run, and the nine ranges tile the body
    from its first line to its last with no gap and no overlap. The first unit is extended
    backwards to the body's first line so that the include prelude, which sits before the first
    banner, belongs to exactly one unit like everything else.
    """
    by_title = {}
    for section in sections:
        if section.title in by_title:
            raise ExtractionError("two banner sections are both titled %r" % section.title)
        by_title[section.title] = section

    claimed = [title for _, titles in UNIT_MAP for title in titles]
    if sorted(claimed) != sorted(by_title):
        missing = sorted(set(by_title) - set(claimed))
        unknown = sorted(set(claimed) - set(by_title))
        raise ExtractionError(
            "UNIT_MAP does not claim the def's banner sections exactly: unclaimed %s, unknown %s"
            % (missing, unknown))

    units = []
    for index, (filename, titles) in enumerate(UNIT_MAP):
        first = min(by_title[t].first_line for t in titles)
        last = max(by_title[t].last_line for t in titles)
        run = [s.title for s in sections if first <= s.first_line <= last]
        if run != titles:
            raise ExtractionError(
                "%s claims %s, which is not an unbroken run of banner sections; the def's own "
                "order there is %s" % (filename, titles, run))
        if index == 0:
            first = body[0][0]
        units.append(Unit(filename, titles, first, last))

    if units[0].first_line != body[0][0] or units[-1].last_line != body[-1][0]:
        raise ExtractionError(
            "the units span def lines %d to %d, but the body is %d to %d"
            % (units[0].first_line, units[-1].last_line, body[0][0], body[-1][0]))
    for left, right in zip(units, units[1:]):
        if left.last_line + 1 != right.first_line:
            raise ExtractionError(
                "%s ends at def line %d and %s starts at %d, so the units do not tile the body"
                % (left.filename, left.last_line, right.filename, right.first_line))
    return units


def unit_of(units, line):
    for unit in units:
        if unit.holds(line):
            return unit
    return None


def check_shape(body, first, last, includes, declarations, sections, units):
    problems = []
    if (first, last) != (EXPECT_BODY_FIRST, EXPECT_BODY_LAST):
        problems.append("body is def lines %d to %d, expected %d to %d"
                        % (first, last, EXPECT_BODY_FIRST, EXPECT_BODY_LAST))
    if len(includes) != EXPECT_INCLUDES:
        problems.append("found %d include lines, expected %d"
                        % (len(includes), EXPECT_INCLUDES))
    if len(declarations) != EXPECT_DECLARATIONS:
        problems.append("found %d declarations, expected %d"
                        % (len(declarations), EXPECT_DECLARATIONS))
    multiline = sorted(d.first_line for d in declarations if d.multiline)
    if multiline != EXPECT_MULTILINE:
        problems.append("multi-line signatures at %s, expected %s"
                        % (multiline, EXPECT_MULTILINE))
    internal = dict((d.name, d.first_line) for d in declarations if d.internal)
    if internal != EXPECT_INTERNAL:
        problems.append("internal helpers %s, expected %s"
                        % (sorted(internal.items()), sorted(EXPECT_INTERNAL.items())))
    names = [d.name for d in declarations]
    duplicates = sorted(set(n for n in names if names.count(n) > 1))
    if duplicates:
        problems.append("duplicate helper names: %s" % duplicates)

    # The definitions must not overlap and must appear in line order, which is the check that
    # catches a mis-balanced brace: a definition swallowing the next one shows up here rather than
    # as a silently truncated unit.
    for left, right in zip(declarations, declarations[1:]):
        if left.definition_last >= right.first_line:
            problems.append(
                "%s (def %d to %d) runs into %s (def line %d)"
                % (left.name, left.first_line, left.definition_last, right.name,
                   right.first_line))

    exported = set(d.name for d in declarations if not d.internal)
    orphans = sorted(name for name in CONTRACTS if name not in exported)
    if orphans:
        problems.append("CONTRACTS documents %s, which is not an exported declaration; a "
                        "documented contract must not be able to go missing quietly" % orphans)
    documented_and_deleted = sorted(set(CONTRACTS) & set(DELETED))
    if documented_and_deleted:
        problems.append("CONTRACTS documents %s, which DELETED also removes; a contract for a "
                        "helper that is not emitted is a promise about nothing"
                        % documented_and_deleted)
    problems.extend(check_deletions(declarations))
    problems.extend(check_internal_locality(body, declarations, sections, units))
    if problems:
        raise ExtractionError("the def does not have the measured shape:\n  "
                             + "\n  ".join(problems))


def check_deletions(declarations):
    """DELETED must name exported declarations whose measured line ranges still hold."""
    problems = []
    by_name = dict((d.name, d) for d in declarations)
    for name in sorted(DELETED):
        declaration = by_name.get(name)
        if declaration is None:
            problems.append("DELETED names %s, which the def does not declare" % name)
            continue
        if declaration.internal:
            problems.append("DELETED names %s, which is internal; an internal helper is not "
                            "exported surface and deleting it is not register item B1-08" % name)
            continue
        measured = (declaration.first_line, declaration.definition_last)
        if measured != DELETED[name]:
            problems.append("DELETED records %s at def lines %s, measured %s"
                            % (name, DELETED[name], measured))
    return problems


def check_internal_locality(body, declarations, sections, units):
    """Every trailing-underscore helper must be callable only from inside its own unit.

    This is plan section 15.2 B1.4 step 1, and it is the check that decides the question that step
    asks: whether any of the four internal helpers has to become KC_API rather than stay `static`.
    It is enforced twice, at two strengths. The unit check is the one that matters, because a
    `static` helper called from another unit does not link. The banner-section check is stricter
    than necessary and is kept because it is what makes the unit grouping free to change: as long
    as every use sits inside its own banner section, no regrouping of banners into units can break
    linkage.
    """
    problems = []
    for declaration in declarations:
        if not declaration.internal:
            continue
        home_section = section_of(sections, declaration.first_line)
        home_unit = unit_of(units, declaration.first_line)
        if home_section is None or home_unit is None:
            problems.append("%s at def line %d sits in no banner section or no unit"
                            % (declaration.name, declaration.first_line))
            continue
        for number, line in body:
            if declaration.name not in line:
                continue
            if not references(line, declaration.name):
                continue
            if not home_unit.holds(number):
                problems.append(
                    "%s is used at def line %d, outside its unit %s (def lines %d to %d); plan "
                    "section 15.2 B1.4 step 1 says it must become KC_API rather than be made "
                    "non-static silently, and the Execution log must record it"
                    % (declaration.name, number, home_unit.filename, home_unit.first_line,
                       home_unit.last_line))
            elif not (home_section.first_line <= number <= home_section.last_line):
                problems.append(
                    "%s is used at def line %d, outside its section %r (def lines %d to %d)"
                    % (declaration.name, number, home_section.title, home_section.first_line,
                       home_section.last_line))
    return problems


def references(line, name):
    """True when `line` contains `name` as a whole identifier rather than as a prefix."""
    start = 0
    while True:
        at = line.find(name, start)
        if at < 0:
            return False
        before = line[at - 1] if at > 0 else " "
        after = line[at + len(name)] if at + len(name) < len(line) else " "
        head_ok = not (before.isalnum() or before == "_")
        tail_ok = not (after.isalnum() or after == "_")
        if head_ok and tail_ok:
            return True
        start = at + 1


def generated_banner(kind):
    return [
        "/* GENERATED FILE. Do not edit.",
        " *",
        " * Extracted from %s by %s." % (DEF_IN_REPO, GENERATOR),
        " * scripts/verify-lift.sh re-runs the generator against a git revision of the def and",
        " * compares the result with this file, so a hand edit fails the gate.",
        " *",
        " * %s */" % kind,
    ]


def render_header(includes, declarations, sections):
    out = generated_banner(
        "Declarations for the exported FFmpeg helper layer.")
    out.append("")
    out.append("#ifndef %s" % HEADER_GUARD)
    out.append("#define %s" % HEADER_GUARD)
    out.append("")
    for _, line in includes:
        out.append(line)
    out.append("")
    out.extend(KC_API_MACRO)
    exported = [d for d in declarations if not d.internal and d.name not in DELETED]
    current = None
    for declaration in exported:
        section = section_of(sections, declaration.first_line)
        title = section.title if section is not None else "Ungrouped"
        if title != current:
            out.append("")
            out.append("/* %s */" % title)
            current = title
        contract = CONTRACTS.get(declaration.name)
        if contract:
            out.append("")
            out.append("/* %s" % contract[0])
            for line in contract[1:]:
                out.append(" * %s" % line)
            out.append(" */")
        out.append("%s %s;" % (KC_API_TOKEN, declaration.signature))
    out.append("")
    out.append("#endif /* %s */" % HEADER_GUARD)
    out.append("")
    return "\n".join(out)


def render_payload(body, includes, declarations, first_line=None, last_line=None):
    """The def body as C, for one line range or for all of it, with no unit prelude.

    This is the text every generated unit carries after its `#include "kitecodec_helpers.h"` line,
    and it is deliberately KC_API free: `scripts/verify-lift.sh` strips KC_API from the committed
    units and compares what is left with the payload for the whole body, so this function is the
    reference side of that comparison. Three rewrites happen and nothing else:

      * the 20 `#include` lines are dropped, because they live in the header now;
      * an exported helper loses the whole `static inline ` token, which is what gives it external
        linkage;
      * an internal helper keeps `static` and loses `inline`.

    Every line of every deleted helper's definition is dropped. Comments are never touched, so a
    comment that mentions a deleted helper survives; the one that does is the note above
    ffkmp_fmt_alloc_output2, which reads "Like ffkmp_fmt_alloc_output but with an explicit
    container short name". It stays because the units are the def body verbatim apart from the
    three rewrites above, and rewriting prose would make the comparison fuzzy.
    """
    if first_line is None:
        first_line = body[0][0]
    if last_line is None:
        last_line = body[-1][0]
    internal_starts = set(d.first_line for d in declarations if d.internal)
    include_lines = set(number for number, _ in includes)
    dropped = set()
    for declaration in declarations:
        if declaration.name in DELETED:
            dropped.update(range(declaration.first_line, declaration.definition_last + 1))
    out = []
    for number, line in body:
        if not (first_line <= number <= last_line):
            continue
        if number in include_lines or number in dropped:
            continue
        if line.startswith(DECL_TOKEN):
            if number in internal_starts:
                line = "static " + line[len(DECL_TOKEN):]
            else:
                line = "%s %s" % (KC_API_TOKEN, line[len(DECL_TOKEN):])
        out.append(line)
    return "\n".join(out)


def strip_kc_api(text):
    """Remove the KC_API token the units carry, which is the units-side half of the comparison."""
    return "\n".join(
        line[len(KC_API_TOKEN) + 1:] if line.startswith(KC_API_TOKEN + " ") else line
        for line in text.split("\n"))


def render_units(body, includes, declarations, units):
    """One text per translation unit, keyed by filename, in banner order."""
    rendered = []
    for unit in units:
        out = generated_banner(
            "The %s part of the FFmpeg helper layer: the def's %s section(s)."
            % (unit.filename.replace("helpers_", "").replace(".c", ""),
               ", ".join(repr(t) for t in unit.titles)))
        out.append("")
        out.append("#include \"%s\"" % HEADER_NAME)
        out.append(render_payload(body, includes, declarations,
                                  unit.first_line, unit.last_line))
        out.append("")
        rendered.append((unit.filename, "\n".join(out)))
    return rendered


def unit_payload(text):
    """The payload of a generated unit: everything after its `#include` line, KC_API removed."""
    marker = "#include \"%s\"" % HEADER_NAME
    lines = text.split("\n")
    for index, line in enumerate(lines):
        if line == marker:
            body = "\n".join(lines[index + 1:])
            if body.endswith("\n"):
                body = body[:-1]
            return strip_kc_api(body)
    raise ExtractionError("a generated unit has no %r line" % marker)


def check_units_reassemble(body, includes, declarations, rendered):
    """The units, concatenated in banner order, must be the whole body's payload.

    Cheap, and it is the property scripts/verify-lift.sh depends on. A unit map that quietly
    dropped or duplicated a stretch of the def would pass every per-file byte comparison and fail
    here.
    """
    whole = strip_kc_api(render_payload(body, includes, declarations))
    joined = "\n".join(unit_payload(text) for _, text in rendered)
    if whole != joined:
        raise ExtractionError(
            "the generated units do not reassemble into the def body: %d payload lines against "
            "%d" % (len(whole.split("\n")), len(joined.split("\n"))))


def write_if_needed(path, text):
    if path is None:
        return False
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as handle:
            if handle.read() == text:
                return False
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return True


def default_def_path():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", "..", ".."))
    return os.path.join(repo, DEF_IN_REPO)


def main(argv):
    parser = argparse.ArgumentParser(
        description="Extract the FFmpeg helper layer from ffmpeg.def into C sources.")
    parser.add_argument("--def", dest="def_path", default=None,
                        help="path to ffmpeg.def (default: the one in this checkout)")
    parser.add_argument("--stdin", action="store_true",
                        help="read the def from standard input instead of a file")
    parser.add_argument("--header", default=None, help="path to write the header to")
    parser.add_argument("--units", default=None,
                        help="directory to write the nine translation units into")
    parser.add_argument("--payload", default=None,
                        help="path to write the whole body's KC_API-free payload to, which is "
                             "what verify-lift.sh compares the concatenated units against")
    parser.add_argument("--exclude", default=None,
                        help="comma separated list of the deleted helpers, spelled out by the "
                             "caller. It must equal this script's own DELETED set exactly, so a "
                             "gate that names the list cannot drift from the generator that "
                             "applies it.")
    parser.add_argument("--list-units", action="store_true",
                        help="print the unit filenames in banner order, one per line, which is "
                             "the order verify-lift.sh concatenates them in")
    parser.add_argument("--report", action="store_true",
                        help="print the measured shape of the def body")
    args = parser.parse_args(argv)

    path = args.def_path or default_def_path()
    try:
        if args.exclude is not None:
            supplied = set(name.strip() for name in args.exclude.split(",") if name.strip())
            if supplied != set(DELETED):
                raise ExtractionError(
                    "--exclude does not match this script's DELETED set:\n    only in --exclude: "
                    "%s\n    only in DELETED:   %s"
                    % (sorted(supplied - set(DELETED)), sorted(set(DELETED) - supplied)))
        text = read_def(path, args.stdin)
        body, first, last = split_body(text)
        includes = [(number, line) for number, line in body
                    if line.startswith("#include")]
        declarations = parse_declarations(body)
        sections = find_sections(body)
        units = build_units(body, sections)
        check_shape(body, first, last, includes, declarations, sections, units)
        header = render_header(includes, declarations, sections)
        rendered = render_units(body, includes, declarations, units)
        payload = strip_kc_api(render_payload(body, includes, declarations))
        check_units_reassemble(body, includes, declarations, rendered)
    except ExtractionError as error:
        sys.stderr.write("extract_from_def.py: %s\n" % error)
        return 2

    if (args.header is None and args.units is None and args.payload is None
            and not args.report and not args.list_units):
        sys.stderr.write("extract_from_def.py: nothing to do, pass --header, --units, --payload, "
                         "--list-units or --report\n")
        return 2

    if args.list_units:
        for filename, _ in rendered:
            print(filename)

    if args.header is not None:
        write_if_needed(args.header, header)
    if args.units is not None:
        for filename, unit_text in rendered:
            write_if_needed(os.path.join(args.units, filename), unit_text)
    if args.payload is not None:
        write_if_needed(args.payload, payload + "\n")

    if args.report:
        exported = [d for d in declarations if not d.internal]
        internal = [d for d in declarations if d.internal]
        emitted = [d for d in exported if d.name not in DELETED]
        print("def:                  %s" % ("<stdin>" if args.stdin else path))
        print("body:                 def lines %d to %d (%d lines)"
              % (first, last, last - first + 1))
        print("include lines moved:  %d" % len(includes))
        print("declarations found:   %d" % len(declarations))
        print("  exported:           %d" % len(exported))
        print("  internal (static):  %d (%s)"
              % (len(internal), ", ".join(d.name for d in internal)))
        print("deleted (B1-08):      %d" % len(DELETED))
        print("exported and emitted: %d with %s" % (len(emitted), KC_API_TOKEN))
        print("multi-line signatures: %d at def lines %s"
              % (len([d for d in declarations if d.multiline]),
                 ", ".join(str(d.first_line) for d in declarations if d.multiline)))
        print("banner sections:      %d" % len(sections))
        for section in sections:
            count = len([d for d in declarations
                         if section.first_line <= d.first_line <= section.last_line])
            print("  def %4d to %4d  %3d helpers  %s"
                  % (section.first_line, section.last_line, count, section.title))
        print("translation units:    %d" % len(units))
        by_filename = dict(rendered)
        for unit in units:
            declared = [d for d in declarations if unit.holds(d.first_line)]
            gone = [d for d in declared if d.name in DELETED]
            print("  def %4d to %4d  %3d helpers  %d deleted  %3d lines  %s"
                  % (unit.first_line, unit.last_line, len(declared), len(gone),
                     len(by_filename[unit.filename].split("\n")), unit.filename))
        print("header lines:         %d" % len(header.split("\n")))
        print("payload lines:        %d" % len(payload.split("\n")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
