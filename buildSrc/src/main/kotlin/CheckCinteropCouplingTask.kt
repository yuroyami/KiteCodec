package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The ratchet on how tightly kitecodec-core's Kotlin is bolted to FFmpeg's C types.
 *
 * B1 turned the `ffkmp_` helpers into a compiled, versioned library but deliberately left the
 * Kotlin side alone: the cinterop imports, the helper call sites, the raw libav calls and the
 * FFmpeg headers in `ffmpeg.def` all stayed. That deferral is only safe if the thing being
 * deferred can do nothing but shrink, which is what this task enforces.
 *
 * Reworked at the interlude (I-13), because the first version fought the very work it protects,
 * measured three ways: moving a raw libav call behind a helper (register item B1-22's own ask)
 * lowered one count and raised another, and the ratchet only looked at rises, so the improvement
 * failed the build; a KDoc sentence naming a struct type counted as coupling, so B2 could not
 * document its own headline deliverable; and the baseline's prose mis-split the fourteen raw
 * sites. What this task measures now:
 *
 *  RATCHETED, may never rise above the baseline:
 *  1. `cinterop_import_lines`: lines importing an FFmpeg symbol out of the `ffmpeg` cinterop
 *     package. Imports of the opaque `kc_` surface are excluded; see [CINTEROP_IMPORT].
 *  2. `ffmpeg_typed_crossings`: helper mentions plus raw libav calls, one number. A category move
 *     (raw call becomes helper call) is neutral by construction; a genuine reduction shows as a
 *     fall; only genuinely new FFmpeg-typed traffic shows as a rise.
 *
 *  REPORTED, printed every run and recorded nowhere: `ffkmp_call_sites` and
 *  `direct_libav_call_sites`, the two components of the crossings number, so nothing is hidden
 *  by the sum.
 *
 *  ALLOWLISTED BY NAME: every FFmpeg struct type name that reaches Kotlin must be one of the
 *  `allowed_struct_type` lines in the baseline. The guarantee is no longer "the number eleven
 *  does not rise" but "no new FFmpeg struct type reaches Kotlin without being named in a commit",
 *  which is stronger, and a type that stops being named simply stops appearing (the stale line is
 *  reported so it can be cleaned up in a normal commit).
 *
 * All counting happens over comment-stripped Kotlin: line comments, nested block comments and
 * KDoc leave the text before any pattern runs, with string literals (escaped and raw) preserved,
 * because a comment is not coupling and the count must not punish documentation. The candidate
 * struct type set is still derived from the C (the def plus [cDeclarationFiles]), so it cannot go
 * stale.
 *
 * Lowering a baseline number is a normal commit. Raising one, or adding an `allowed_struct_type`
 * line, needs an Execution log entry; the move procedure is in KPKMP.md section 9's ratchet move
 * table.
 *
 * Two properties of this implementation are load bearing rather than incidental:
 *
 *  - It skips every directory named `build` or `.claude`. Both hold gitignored scratch checkouts
 *    of the same source files, and walking them would roughly double every count.
 *  - It is configuration cache safe. Nothing but a directory and a file is captured, no script
 *    object is referenced from the task action, and no process is started at configuration time.
 */
abstract class CheckCinteropCouplingTask : DefaultTask() {

    /** The module source root to measure, `kitecodec-core/src`. Also holds `ffmpeg.def`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /**
     * `native/kitecodec-c/coupling-baseline.txt`: `name value` lines for the two ratcheted counts
     * plus one `allowed_struct_type Name` line per FFmpeg struct type Kotlin may name. `#` starts
     * a comment.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    /**
     * The C of the FFmpeg helper layer: the headers under `native/kitecodec-c/include` and the
     * sources under `native/kitecodec-c/src`. It supplies the FFmpeg struct type names that the
     * def body used to supply before B1.3 moved the C.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cDeclarationFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val root = sourceDir.get().asFile
        val baseline = baselineFile.get().asFile

        val recorded = parseBaseline(baseline)
        val measured = measure(root, cDeclarationFiles.files)

        val failures = mutableListOf<String>()
        for (name in RATCHETED_NAMES) {
            val actual = measured.counts.getValue(name)
            val ceiling = recorded.counts.getValue(name)
            if (actual > ceiling) failures += "$name: baseline $ceiling, actual $actual"
        }
        val newTypes = measured.namedStructTypes - recorded.allowedStructTypes
        if (newTypes.isNotEmpty()) {
            failures += "FFmpeg struct type(s) newly named in Kotlin: ${newTypes.sorted().joinToString()}"
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The FFmpeg coupling ratchet failed against ${baseline.path}.")
                    for (line in failures) appendLine("  $line")
                    appendLine()
                    appendLine(
                        "The deferred coupling may only shrink, and since the interlude (I-13) a " +
                            "category move, a raw call becoming a helper call, is neutral here and " +
                            "cannot be what fired. Either take the new coupling back out, or, if " +
                            "it is deliberate, raise the number or add the allowed_struct_type " +
                            "line in the same commit and say why in the KPKMP.md Execution log.",
                    )
                },
            )
        }

        for (name in RATCHETED_NAMES) {
            logger.lifecycle("$name: ${measured.counts.getValue(name)} (ceiling ${recorded.counts.getValue(name)})")
        }
        for (name in REPORTED_NAMES) {
            logger.lifecycle("$name: ${measured.counts.getValue(name)} (component, reported only)")
        }
        logger.lifecycle(
            "struct types named in Kotlin: ${measured.namedStructTypes.size} of " +
                "${recorded.allowedStructTypes.size} allowed",
        )
        val stale = recorded.allowedStructTypes - measured.namedStructTypes
        if (stale.isNotEmpty()) {
            logger.lifecycle(
                "  allowed but no longer named (stale lines, removable in a normal commit): " +
                    stale.sorted().joinToString(),
            )
        }
    }

    /** What [measure] returns: the four counts plus the struct type names Kotlin actually names. */
    data class Measurement(val counts: Map<String, Int>, val namedStructTypes: Set<String>)

    /** What [parseBaseline] returns: the two ceilings plus the allowed struct type names. */
    data class Baseline(val counts: Map<String, Int>, val allowedStructTypes: Set<String>)

    companion object {

        const val CINTEROP_IMPORT_LINES: String = "cinterop_import_lines"
        const val FFMPEG_TYPED_CROSSINGS: String = "ffmpeg_typed_crossings"
        const val FFKMP_CALL_SITES: String = "ffkmp_call_sites"
        const val DIRECT_LIBAV_CALL_SITES: String = "direct_libav_call_sites"
        const val ALLOWED_STRUCT_TYPE: String = "allowed_struct_type"

        /** The ratcheted counts, in the order the baseline file lists them. */
        val RATCHETED_NAMES: List<String> = listOf(CINTEROP_IMPORT_LINES, FFMPEG_TYPED_CROSSINGS)

        /** The reported-only components of [FFMPEG_TYPED_CROSSINGS]. */
        val REPORTED_NAMES: List<String> = listOf(FFKMP_CALL_SITES, DIRECT_LIBAV_CALL_SITES)

        /** `ffmpeg.def`, relative to the measured source root. */
        const val DEF_PATH: String = "nativeInterop/cinterop/ffmpeg.def"

        /**
         * Directory names never walked. Both hold gitignored scratch checkouts of the same files:
         * `build` the Gradle outputs, `.claude` the agent worktrees.
         */
        private val SKIPPED_DIRECTORY_NAMES = setOf("build", ".claude")

        /**
         * An import out of the `ffmpeg` cinterop package that is coupling to FFmpeg.
         *
         * The negative lookahead is the load-bearing part, and B1.6 is what forced it. The `ffmpeg`
         * cinterop module holds two quite different surfaces. One is FFmpeg itself: `AVFrame`,
         * `avcodec_send_packet`, the `ffkmp_` helpers, every one of them naming or handling an
         * FFmpeg type. The other is the OPAQUE surface, spelled `kc_` and `KC_`, whose header
         * (`native/kitecodec-c/include/kitecodec_abi.h`) includes no FFmpeg header and names no
         * FFmpeg type at all.
         *
         * Counting the second as coupling would make this ratchet punish the very migration it
         * exists to protect. Measured at B1.6: adding the identity gate took the FFmpeg imports
         * from 253 to 246 and added 26 `kc_`/`KC_` imports, and a ratchet that read that as 272
         * would have failed a change whose net effect was to reduce the coupling.
         *
         * The opaque surface is deliberately not ratcheted here, in either direction. It is meant
         * to grow, and `native/kitecodec-c/exported-symbols-baseline.txt` (installed by interlude
         * item I-09) plus `symbol-audit.sh` are what hold it to a decided set.
         */
        private val CINTEROP_IMPORT = Regex("""^import ffmpeg\.(?!kc_|KC_)""")

        private val HELPER_MENTION = Regex("""ffkmp_[A-Za-z0-9_]*""")

        /**
         * A call straight into libav. Longest prefix first so the alternation reads in the order
         * it resolves, although the engine would backtrack into the right branch either way.
         */
        private val LIBAV_CALL = Regex(
            """\b(?:avcodec|avdevice|avfilter|avformat|avutil|swresample|swscale|postproc|sws|swr|av)""" +
                """_[A-Za-z0-9_]*\(""",
        )

        /**
         * An FFmpeg CamelCase type token in C, with the `enum` keyword in front of it when it is
         * there. Group 1 empty means this occurrence proves the token is a struct type.
         */
        private val DEF_TYPE_TOKEN = Regex("""(enum\s+)?\b((?:AV|Sws|Swr)[A-Z][a-z][A-Za-z0-9]*)\b""")

        /**
         * Removes line comments, KDoc and (nested) block comments from Kotlin source, preserving
         * string literals, character literals and line structure. Comments leave as one space so
         * token boundaries survive. Kotlin block comments nest, and a `//`, `/*` or `*/` inside a
         * quoted or raw string is content rather than a comment marker; both facts are handled
         * here and covered by the task's tests, because this stripper decides what the ratchet
         * can see.
         */
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            val n = text.length
            var blockDepth = 0
            var inLine = false
            var inString = false
            var inRawString = false
            var inChar = false
            while (i < n) {
                val c = text[i]
                when {
                    inLine -> {
                        if (c == '\n') { inLine = false; out.append(c) }
                        i++
                    }
                    blockDepth > 0 -> {
                        if (c == '/' && i + 1 < n && text[i + 1] == '*') { blockDepth++; i += 2 }
                        else if (c == '*' && i + 1 < n && text[i + 1] == '/') { blockDepth--; i += 2 }
                        else { if (c == '\n') out.append(c); i++ }
                    }
                    inRawString -> {
                        out.append(c)
                        if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                            out.append("\"\""); i += 3; inRawString = false
                        } else i++
                    }
                    inString -> {
                        out.append(c)
                        if (c == '\\' && i + 1 < n) { out.append(text[i + 1]); i += 2 }
                        else { if (c == '"') inString = false; i++ }
                    }
                    inChar -> {
                        out.append(c)
                        if (c == '\\' && i + 1 < n) { out.append(text[i + 1]); i += 2 }
                        else { if (c == '\'') inChar = false; i++ }
                    }
                    else -> when {
                        c == '/' && i + 1 < n && text[i + 1] == '/' -> { inLine = true; out.append(' '); i += 2 }
                        c == '/' && i + 1 < n && text[i + 1] == '*' -> { blockDepth = 1; out.append(' '); i += 2 }
                        c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            inRawString = true; out.append("\"\"\""); i += 3
                        }
                        c == '"' -> { inString = true; out.append(c); i++ }
                        c == '\'' -> { inChar = true; out.append(c); i++ }
                        else -> { out.append(c); i++ }
                    }
                }
            }
            return out.toString()
        }

        /**
         * Recomputes the counts over [sourceDir]. [cDeclarationFiles] are the C headers and
         * sources, which together with the def supply the candidate struct type names.
         */
        fun measure(sourceDir: File, cDeclarationFiles: Collection<File> = emptyList()): Measurement {
            if (!sourceDir.isDirectory) {
                throw GradleException("Cannot measure the FFmpeg coupling: ${sourceDir.path} is not a directory.")
            }
            val def = sourceDir.resolve(DEF_PATH)
            if (!def.isFile) {
                throw GradleException("Cannot measure the FFmpeg coupling: no cinterop def at ${def.path}.")
            }

            val kotlinTexts = sourceDir.walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRECTORY_NAMES }
                .filter { it.isFile && it.extension == "kt" }
                .map { stripComments(it.readText()) }
                .toList()

            var importLines = 0
            var helperCalls = 0
            var libavCalls = 0
            for (text in kotlinTexts) {
                for (line in text.lineSequence()) {
                    // An import is a declaration of coupling, not a use of it: counted by count 1
                    // and excluded from the crossings so one import does not read as a call.
                    if (line.startsWith("import ")) {
                        if (CINTEROP_IMPORT.containsMatchIn(line)) importLines++
                        continue
                    }
                    helperCalls += HELPER_MENTION.findAll(line).count()
                    libavCalls += LIBAV_CALL.findAll(line).count()
                }
            }

            val candidates = ffmpegStructTypeNames(listOf(def) + cDeclarationFiles.filter { it.isFile })
            val named = candidates.filterTo(linkedSetOf()) { name ->
                val wholeWord = Regex("""\b""" + Regex.escape(name) + """\b""")
                kotlinTexts.any { wholeWord.containsMatchIn(it) }
            }

            return Measurement(
                counts = mapOf(
                    CINTEROP_IMPORT_LINES to importLines,
                    FFMPEG_TYPED_CROSSINGS to helperCalls + libavCalls,
                    FFKMP_CALL_SITES to helperCalls,
                    DIRECT_LIBAV_CALL_SITES to libavCalls,
                ),
                namedStructTypes = named,
            )
        }

        /**
         * The FFmpeg struct type names named across [files]: every CamelCase `AV`, `Sws` or `Swr`
         * token that occurs at least once without `enum` in front of it.
         */
        fun ffmpegStructTypeNames(files: Collection<File>): Set<String> {
            val names = linkedSetOf<String>()
            for (file in files) {
                for (match in DEF_TYPE_TOKEN.findAll(file.readText())) {
                    if (match.groupValues[1].isEmpty()) names += match.groupValues[2]
                }
            }
            return names
        }

        /**
         * Reads `name value` lines for the ratcheted counts and `allowed_struct_type Name` lines
         * for the type allowlist, ignoring blank lines and everything from a `#` onwards.
         */
        fun parseBaseline(baselineFile: File): Baseline {
            val recorded = LinkedHashMap<String, Int>()
            val allowed = linkedSetOf<String>()
            baselineFile.readLines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("""\s+"""))
                if (parts.size != 2) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: expected `name value`, found `$raw`.",
                    )
                }
                if (parts[0] == ALLOWED_STRUCT_TYPE) {
                    if (!allowed.add(parts[1])) {
                        throw GradleException(
                            "${baselineFile.path}:${index + 1}: duplicate $ALLOWED_STRUCT_TYPE `${parts[1]}`.",
                        )
                    }
                    return@forEachIndexed
                }
                if (parts[0] !in RATCHETED_NAMES || parts[1].toIntOrNull() == null) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: unknown line `$raw`. The ratcheted " +
                            "counts are ${RATCHETED_NAMES.joinToString()}, and type names are " +
                            "`$ALLOWED_STRUCT_TYPE Name` lines.",
                    )
                }
                recorded[parts[0]] = parts[1].toInt()
            }
            val missing = RATCHETED_NAMES - recorded.keys
            if (missing.isNotEmpty()) {
                throw GradleException("${baselineFile.path}: no baseline for ${missing.joinToString()}.")
            }
            return Baseline(recorded, allowed)
        }
    }
}
