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
 * B1 turns the 176 `ffkmp_` helpers into a compiled, versioned library but deliberately leaves the
 * Kotlin side alone: the cinterop imports, the helper call sites, the raw libav calls and the
 * FFmpeg headers in `ffmpeg.def` all stay. That deferral is only safe if the thing being deferred
 * can nothing but shrink, which is what this task enforces. It recomputes four counts over
 * [sourceDir] and fails when any of them is higher than the number recorded in [baselineFile]:
 *
 *  1. `cinterop_import_lines`: lines importing a symbol out of the `ffmpeg` cinterop package.
 *  2. `ffkmp_call_sites`: mentions of an `ffkmp_` helper outside an import line.
 *  3. `direct_libav_call_sites`: calls straight into libav, behind no helper.
 *  4. `ffmpeg_struct_types_named_in_kotlin`: how many FFmpeg struct type names reach Kotlin text.
 *
 * The fourth name set is derived from the C instead of hand written, so it cannot go stale: a
 * `(AV|Sws|Swr)[A-Z][a-z]...` token is a struct type when at least one of its occurrences is not
 * preceded by the `enum` keyword. That rule separates the 18 struct types from `AVCodecID`,
 * `AVPixelFormat` and `AVSampleFormat`, which only ever appear as `enum X`.
 *
 * The C it reads is `ffmpeg.def` plus [cDeclarationFiles]. Both, because B1.3 moved the C: until the
 * lift the 949 line body lived in the def, and afterwards it lives in `native/kitecodec-c`. The two
 * are the same text (`scripts/verify-lift.sh` proves it byte for byte), so reading both keeps the
 * candidate set identical across the move. Measured either side of the lift: 18 struct type names,
 * 11 of them named in Kotlin. Reading only the def after the lift would have silently reported 0 of
 * 0, which is a ratchet that has stopped measuring anything while still passing.
 *
 * Lowering a baseline number is a normal commit. Raising one needs an Execution log entry.
 *
 * Two properties of this implementation are load bearing rather than incidental:
 *
 *  - It skips every directory named `build` or `.claude`. Both hold gitignored scratch checkouts of
 *    the same source files, and walking them would roughly double every count.
 *  - It is configuration cache safe. Nothing but a directory and a file is captured, no script
 *    object is referenced from the task action, and no process is started at configuration time.
 */
abstract class CheckCinteropCouplingTask : DefaultTask() {

    /** The module source root to measure, `kitecodec-core/src`. Also holds `ffmpeg.def`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** `native/kitecodec-c/coupling-baseline.txt`: four `name value` lines, `#` starts a comment. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    /**
     * The extracted C of the FFmpeg helper layer: the headers under `native/kitecodec-c/include` and
     * the sources under `native/kitecodec-c/src`. It supplies the FFmpeg struct type names that the
     * def body used to supply; see the class note on where count four comes from.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cDeclarationFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val root = sourceDir.get().asFile
        val baseline = baselineFile.get().asFile

        val recorded = parseBaseline(baseline)
        val actual = measure(root, cDeclarationFiles.files)

        val risen = COUNT_NAMES.filter { name -> actual.getValue(name) > recorded.getValue(name) }
        if (risen.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "The FFmpeg coupling ratchet failed: ${risen.size} of ${COUNT_NAMES.size} " +
                            "counts rose above ${baseline.path}.",
                    )
                    for (name in risen) {
                        appendLine("  $name: baseline ${recorded.getValue(name)}, actual ${actual.getValue(name)}")
                    }
                    appendLine()
                    appendLine(
                        "B1 defers the opaque handle migration, so this coupling may only shrink. " +
                            "Either take the new coupling back out, or, if the rise is deliberate, " +
                            "raise the baseline in the same commit and say why in the KPKMP.md " +
                            "Execution log.",
                    )
                },
            )
        }

        for (name in COUNT_NAMES) {
            logger.lifecycle("$name: ${actual.getValue(name)} (baseline ${recorded.getValue(name)})")
        }
    }

    companion object {

        const val CINTEROP_IMPORT_LINES: String = "cinterop_import_lines"
        const val FFKMP_CALL_SITES: String = "ffkmp_call_sites"
        const val DIRECT_LIBAV_CALL_SITES: String = "direct_libav_call_sites"
        const val FFMPEG_STRUCT_TYPES: String = "ffmpeg_struct_types_named_in_kotlin"

        /** The four counts, in the order the baseline file lists them. */
        val COUNT_NAMES: List<String> = listOf(
            CINTEROP_IMPORT_LINES,
            FFKMP_CALL_SITES,
            DIRECT_LIBAV_CALL_SITES,
            FFMPEG_STRUCT_TYPES,
        )

        /** `ffmpeg.def`, relative to the measured source root. */
        const val DEF_PATH: String = "nativeInterop/cinterop/ffmpeg.def"

        /**
         * Directory names never walked. Both hold gitignored scratch checkouts of the same files:
         * `build` the Gradle outputs, `.claude` the agent worktrees.
         */
        private val SKIPPED_DIRECTORY_NAMES = setOf("build", ".claude")

        private val CINTEROP_IMPORT = Regex("""^import ffmpeg\.""")

        private val HELPER_MENTION = Regex("""ffkmp_[A-Za-z0-9_]*""")

        /**
         * A call straight into libav. Longest prefix first so the alternation reads in the order it
         * resolves, although the engine would backtrack into the right branch either way.
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
         * Recomputes the four counts over [sourceDir]. [cDeclarationFiles] are the extracted C
         * headers and sources, which together with the def supply the candidate struct type names.
         */
        fun measure(sourceDir: File, cDeclarationFiles: Collection<File> = emptyList()): Map<String, Int> {
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
                .map { it.readText() }
                .toList()

            var importLines = 0
            var helperCalls = 0
            var libavCalls = 0
            for (text in kotlinTexts) {
                for (line in text.lineSequence()) {
                    // An import is a declaration of coupling, not a use of it: counted by count 1
                    // and excluded from counts 2 and 3 so one import does not read as a call.
                    if (line.startsWith("import ")) {
                        if (CINTEROP_IMPORT.containsMatchIn(line)) importLines++
                        continue
                    }
                    helperCalls += HELPER_MENTION.findAll(line).count()
                    libavCalls += LIBAV_CALL.findAll(line).count()
                }
            }

            val structTypes = ffmpegStructTypeNames(listOf(def) + cDeclarationFiles.filter { it.isFile })
            val namedStructTypes = structTypes.count { name ->
                val wholeWord = Regex("""\b""" + Regex.escape(name) + """\b""")
                kotlinTexts.any { wholeWord.containsMatchIn(it) }
            }

            return mapOf(
                CINTEROP_IMPORT_LINES to importLines,
                FFKMP_CALL_SITES to helperCalls,
                DIRECT_LIBAV_CALL_SITES to libavCalls,
                FFMPEG_STRUCT_TYPES to namedStructTypes,
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

        /** Reads `name value` lines, ignoring blank lines and everything from a `#` onwards. */
        fun parseBaseline(baselineFile: File): Map<String, Int> {
            val recorded = LinkedHashMap<String, Int>()
            baselineFile.readLines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("""\s+"""))
                if (parts.size != 2 || parts[1].toIntOrNull() == null) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: expected `name value`, found `$raw`.",
                    )
                }
                if (parts[0] !in COUNT_NAMES) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: unknown count `${parts[0]}`. " +
                            "The counts are ${COUNT_NAMES.joinToString()}.",
                    )
                }
                recorded[parts[0]] = parts[1].toInt()
            }
            val missing = COUNT_NAMES - recorded.keys
            if (missing.isNotEmpty()) {
                throw GradleException("${baselineFile.path}: no baseline for ${missing.joinToString()}.")
            }
            return recorded
        }
    }
}
