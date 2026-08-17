package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Generates the web binding from `signature-baseline.txt` (KPKMP.md 17.14 X-05).
 *
 * GENERATED, not hand-written, and the reason is the input: that file is already gated, so the
 * build fails when the C surface drifts from it. A generator makes the binding review rather than
 * authorship, and keeps it honest against future ABI changes the way the baselines already keep
 * the C side honest. Hand-writing 198 wrappers is how a binding silently falls behind its ABI.
 *
 * What is NOT generated, and must stay hand-written: anything taking a function pointer (the AVIO
 * bridge above all), because a callback crossing into JS is a lifetime problem and not a signature
 * problem. Those are listed in [HAND_WRITTEN] and deliberately excluded from the export list, so a
 * reader can see the boundary rather than infer it.
 */
abstract class GenerateWasmBindingTask @Inject constructor() : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signatureBaseline: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = signatureBaseline.get().asFile.readText()
        val declarations = parse(text)
        check(declarations.isNotEmpty()) { "no KC_API declarations parsed from the signature baseline" }

        val out = outputDir.get().asFile
        out.mkdirs()
        // emcc wants a JSON array of underscore-prefixed names for -sEXPORTED_FUNCTIONS.
        val exported = declarations.filterNot { it.name in HAND_WRITTEN }
        out.resolve(EXPORTS_FILE).writeText(
            exported.joinToString(",", "[", "]") { "\"_${it.name}\"" } + "\n",
        )
        out.resolve(MANIFEST_FILE).writeText(
            buildString {
                appendLine("# Generated from signature-baseline.txt. Do not edit.")
                appendLine("# ${exported.size} exported, ${declarations.size - exported.size} hand-written.")
                exported.forEach { appendLine("${it.returns}\t${it.name}\t${it.parameters}") }
            },
        )
        logger.lifecycle(
            "[KiteCodec wasm] ${exported.size} exported, " +
                "${declarations.size - exported.size} left hand-written",
        )
    }

    /** One `KC_API` prototype, reduced to what a binding needs. */
    data class Declaration(val returns: String, val name: String, val parameters: String)

    companion object {
        const val EXPORTS_FILE = "kitecodec-exports.json"
        const val MANIFEST_FILE = "kitecodec-exports.txt"

        /**
         * Entry points a generator must not touch, with the reason on each.
         *
         * `ffkmp_fmt_open_input_io` takes two function pointers and is X-06's whole subject.
         * `kc_jvm_attach` takes a `JavaVM *`, which does not exist in a browser at all.
         */
        val HAND_WRITTEN = setOf("ffkmp_fmt_open_input_io", "kc_jvm_attach")

        /**
         * Parses the normalized baseline. Pure, so the tests can hand it text and no build runs.
         *
         * The baseline is already normalized (one record per line, whitespace collapsed, comments
         * absent), which is why this is a regex and not a C parser. If that ever stops being true
         * the tests below fail rather than this silently emitting a short list.
         */
        fun parse(text: String): List<Declaration> {
            val pattern = Regex("""^KC_API\s+(.+?)\s*\b([a-z_][a-z0-9_]*)\s*\((.*)\)\s*;\s*$""")
            return text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("KC_API") }
                .mapNotNull { line ->
                    pattern.find(line)?.let { m ->
                        Declaration(
                            returns = m.groupValues[1].trim().removeSuffix(" ").trim(),
                            name = m.groupValues[2],
                            parameters = m.groupValues[3].trim(),
                        )
                    }
                }
                .toList()
        }
    }
}
