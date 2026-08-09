#!/usr/bin/env python3
"""Extract the FFmpeg helper layer out of ffmpeg.def into real C sources.

The cinterop def file carries 949 lines of C after its `---` separator: 20 `#include` lines
and 176 `static inline ffkmp_*` helpers. That text has no translation unit, so it has no
object file, no test, no sanitizer run and no coverage (register item B1-01). This script is
the committed generator that turns it into two ordinary files:

  include/kitecodec_helpers.h   the 20 includes, then one declaration per exported helper
  src/kitecodec_helpers.c       the def body verbatim, with the `static inline ` token
                                rewritten and the header included first

Both outputs are generated. Never edit them by hand: `scripts/verify-lift.sh` re-runs this
script against a git revision of the def and byte-compares the result with what is committed,
so a hand edit shows up as a failed gate.

Transformation rules, all measured against ffmpeg.def at KiteCodec cdb8ad2:

  * The body is def lines 13 to 961. It starts after the `---` separator on line 11 and the
    blank line that follows it, and ends at the last non-blank line of the file.
  * The 20 `#include` lines move to the header. The source gets `#include
    "kitecodec_helpers.h"` in their place, so the source is compiled against the very
    declarations the def will later consume.
  * 176 declarations are found by balancing parentheses from the `(` that opens the parameter
    list. Nine signatures span more than one line (def lines 251, 262, 470, 489, 531, 616,
    644, 684 and 816), and those keep their original line breaks in the header.
  * Four helpers have a trailing underscore in their name and are internal to the body:
    ffkmp_codec_pix_fmts_ (289), ffkmp_graph_finish_ (470), ffkmp_graph_finish_multi_ (616)
    and ffkmp_ch_layout_mask_ (908). They keep `static`, lose `inline`, and are NOT declared
    in the header. Each is called only from inside its own banner section, which is what lets
    B1.4 split the unit per subsystem without any of the four crossing a file boundary; this
    script re-checks that property on every run and refuses to emit if it stops holding.
  * Every other helper loses the whole `static inline ` token, which is what gives it external
    linkage and makes it a real exported symbol.
  * Named helpers gain a documented contract comment above their declaration, from the
    CONTRACTS table below. The def body carries no such prose, and the header is generated, so
    a hand written comment would be erased by the next run. The table is the only place a
    contract can live and survive, and every name in it must still exist as an exported
    declaration or the extractor refuses to emit.

Because the generated source includes the generated header, compiling the source is the proof
that every emitted declaration matches its definition. Compile it with -Werror, which
`scripts/build-host.sh` does, and one mismatch is a hard failure rather than a warning.

Usage:
  extract_from_def.py --report
  extract_from_def.py --header include/kitecodec_helpers.h --source src/kitecodec_helpers.c
  git show REV:kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def \\
      | extract_from_def.py --stdin --header /tmp/h --source /tmp/c
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

# Documented contracts, emitted into the header above the named declaration.
#
# Why this table exists rather than a comment in the header: the header is generated and
# verify-lift.sh byte-compares it, so prose written into the header by hand is erased by the
# next run. Why not a comment in the def instead: the def body becomes src/kitecodec_helpers.c,
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
# the 176 bodies that gives 44 exported helpers plus the two internal graph finishers. 43 of the
# 44 have a contract below and a case in tests/test_ownership.c, so the words and the tests cover
# the same set by construction. The 44th, ffkmp_codecctx_flush, is the one classification
# boundary: avcodec_flush_buffers releases the references the codec holds internally, which is
# why the mechanical rule selects it, but nothing crosses the interface, so it has neither a
# contract here nor a case there. Nothing is documented that is not also asserted.
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
    "ffkmp_frame_ref": [
        "Ownership. Adds a reference rather than copying: after success dst and src describe the",
        "same data and each must be released separately. dst must be blank on entry, freshly",
        "allocated or unreferenced, or whatever it held is leaked.",
    ],
    "ffkmp_frame_get_buffer": [
        "Ownership. Allocates data buffers from the frame's width, height, format and, for audio,",
        "nb_samples and ch_layout, all of which must be set first. The frame owns the buffers and",
        "ffkmp_frame_unref or ffkmp_frame_free releases them.",
    ],
    "ffkmp_frame_make_writable": [
        "Ownership. May replace the frame's buffers with a private copy, so any plane pointer read",
        "out of the frame before the call can dangle after it. Re-read the planes afterwards.",
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
    "ffkmp_packet_ref": [
        "Ownership. Adds a reference rather than copying: after success both packets describe the",
        "same data and each must be released separately. The destination must be blank on entry.",
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
        "from ffkmp_fmt_alloc_output.",
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
    "ffkmp_fmt_alloc_output": [
        "Ownership. On success *out is a new muxer context the caller owns and must release with",
        "ffkmp_fmt_free_output, never with ffkmp_fmt_close_input. On failure *out is set to NULL.",
    ],
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
    def __init__(self, name, first_line, last_line, signature, internal):
        self.name = name
        self.first_line = first_line
        self.last_line = last_line
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
        declarations.append(Declaration(
            name=name,
            first_line=number,
            last_line=closed_at,
            signature=signature,
            internal=name.endswith("_"),
        ))
        index = cursor + 1
    return declarations


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


def check_shape(body, first, last, includes, declarations, sections):
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
    exported = set(d.name for d in declarations if not d.internal)
    orphans = sorted(name for name in CONTRACTS if name not in exported)
    if orphans:
        problems.append("CONTRACTS documents %s, which is not an exported declaration; a "
                        "documented contract must not be able to go missing quietly" % orphans)
    problems.extend(check_internal_locality(body, declarations, sections))
    if problems:
        raise ExtractionError("the def does not have the measured shape:\n  "
                             + "\n  ".join(problems))


def check_internal_locality(body, declarations, sections):
    """Every trailing-underscore helper must be called only inside its own banner section.

    B1.4 splits the translation unit per banner section. A `static` helper called from another
    section would stop linking the moment that split happens, so the property is checked here
    rather than discovered later.
    """
    problems = []
    for declaration in declarations:
        if not declaration.internal:
            continue
        home = section_of(sections, declaration.first_line)
        if home is None:
            problems.append("%s at def line %d sits in no banner section"
                            % (declaration.name, declaration.first_line))
            continue
        for number, line in body:
            if declaration.name not in line:
                continue
            if not references(line, declaration.name):
                continue
            if not (home.first_line <= number <= home.last_line):
                problems.append(
                    "%s is used at def line %d, outside its section %r (def lines %d to %d)"
                    % (declaration.name, number, home.title, home.first_line, home.last_line))
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
        " * byte-compares the result with this file, so a hand edit fails the gate.",
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
    exported = [d for d in declarations if not d.internal]
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
        out.append("%s;" % declaration.signature)
    out.append("")
    out.append("#endif /* %s */" % HEADER_GUARD)
    out.append("")
    return "\n".join(out)


def render_source(body, includes, declarations):
    internal_starts = set(d.first_line for d in declarations if d.internal)
    include_lines = set(number for number, _ in includes)
    out = generated_banner(
        "The FFmpeg helper layer, one definition per declaration in " + HEADER_NAME + ".")
    out.append("")
    out.append("#include \"%s\"" % HEADER_NAME)
    for number, line in body:
        if number in include_lines:
            continue
        if line.startswith(DECL_TOKEN):
            # Exported helpers lose the whole token and gain external linkage. The four
            # internal ones keep `static`: they are implementation detail of one section.
            if number in internal_starts:
                line = "static " + line[len(DECL_TOKEN):]
            else:
                line = line[len(DECL_TOKEN):]
        out.append(line)
    out.append("")
    return "\n".join(out)


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
    parser.add_argument("--source", default=None, help="path to write the source to")
    parser.add_argument("--report", action="store_true",
                        help="print the measured shape of the def body")
    args = parser.parse_args(argv)

    path = args.def_path or default_def_path()
    try:
        text = read_def(path, args.stdin)
        body, first, last = split_body(text)
        includes = [(number, line) for number, line in body
                    if line.startswith("#include")]
        declarations = parse_declarations(body)
        sections = find_sections(body)
        check_shape(body, first, last, includes, declarations, sections)
    except ExtractionError as error:
        sys.stderr.write("extract_from_def.py: %s\n" % error)
        return 2

    header = render_header(includes, declarations, sections)
    source = render_source(body, includes, declarations)

    if args.header is None and args.source is None and not args.report:
        sys.stderr.write("extract_from_def.py: nothing to do, pass --header, --source "
                         "or --report\n")
        return 2

    if args.header is not None:
        write_if_needed(args.header, header)
    if args.source is not None:
        write_if_needed(args.source, source)

    if args.report:
        exported = [d for d in declarations if not d.internal]
        internal = [d for d in declarations if d.internal]
        print("def:                  %s" % ("<stdin>" if args.stdin else path))
        print("body:                 def lines %d to %d (%d lines)"
              % (first, last, last - first + 1))
        print("include lines moved:  %d" % len(includes))
        print("declarations found:   %d" % len(declarations))
        print("  exported:           %d" % len(exported))
        print("  internal (static):  %d (%s)"
              % (len(internal), ", ".join(d.name for d in internal)))
        print("multi-line signatures: %d at def lines %s"
              % (len([d for d in declarations if d.multiline]),
                 ", ".join(str(d.first_line) for d in declarations if d.multiline)))
        print("banner sections:      %d" % len(sections))
        for section in sections:
            count = len([d for d in declarations
                         if section.first_line <= d.first_line <= section.last_line])
            print("  def %4d to %4d  %3d helpers  %s"
                  % (section.first_line, section.last_line, count, section.title))
        print("header lines:         %d" % len(header.split("\n")))
        print("source lines:         %d" % len(source.split("\n")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
