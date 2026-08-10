package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ratchet's own test. It runs against the real `kitecodec-core/src`, not a fixture, because
 * the only interesting question is whether the counts this task computes are the counts the
 * repository actually has: a fixture would prove the regexes match the fixture and nothing more.
 * The three interlude cases (I-13) copy the real tree and apply one surgical change each, so
 * they measure the exact scenarios the old ratchet got wrong, against the real sources.
 *
 * The repository root arrives as the `kitecodec.repo.root` system property, set by the `test`
 * task in `buildSrc/build.gradle.kts`.
 */
class CheckCinteropCouplingTaskTest {

    private val repoRoot: File = File(
        System.getProperty("kitecodec.repo.root") ?: "..",
    ).canonicalFile

    private val sourceDir: File get() = repoRoot.resolve("kitecodec-core/src")

    private val committedBaseline: File get() = repoRoot.resolve("native/kitecodec-c/coupling-baseline.txt")

    /**
     * The C of the helper layer. From B1.3 onward this is where the FFmpeg struct type names
     * live, because the lift deleted the 949 line body out of `ffmpeg.def`.
     */
    private val cDeclarationFiles: List<File>
        get() = listOf("include", "src")
            .map { repoRoot.resolve("native/kitecodec-c/$it") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("h", "c") } }
            .sortedBy { it.path }

    @Test
    fun theCommittedBaselineMatchesTheMeasuredCoupling() {
        val recorded = CheckCinteropCouplingTask.parseBaseline(committedBaseline)
        val measured = CheckCinteropCouplingTask.measure(sourceDir, cDeclarationFiles)
        for (name in CheckCinteropCouplingTask.RATCHETED_NAMES) {
            assertTrue(
                measured.counts.getValue(name) == recorded.counts.getValue(name),
                "$name: baseline ${recorded.counts.getValue(name)}, actual ${measured.counts.getValue(name)}. " +
                    "A drop means the baseline should be lowered in this commit; a rise means the " +
                    "coupling grew.",
            )
        }
        assertEquals(
            recorded.allowedStructTypes,
            measured.namedStructTypes,
            "the allowed_struct_type lines should be exactly the types Kotlin names today; " +
                "a type on neither side of that equality is either new coupling or a stale line",
        )
        // The task itself must pass, not just the numbers it is built on.
        newTask(committedBaseline, sourceDir).check()
    }

    @Test
    fun aBaselineLoweredByOneFailsAndNamesEveryCount() {
        val measured = CheckCinteropCouplingTask.measure(sourceDir, cDeclarationFiles)
        val lowered = File.createTempFile("coupling-baseline-lowered", ".txt")
        lowered.deleteOnExit()
        lowered.writeText(
            buildString {
                for (name in CheckCinteropCouplingTask.RATCHETED_NAMES) {
                    appendLine("$name ${measured.counts.getValue(name) - 1}")
                }
                for (type in measured.namedStructTypes) {
                    appendLine("${CheckCinteropCouplingTask.ALLOWED_STRUCT_TYPE} $type")
                }
            },
        )

        val failure = assertFailsWith<GradleException> { newTask(lowered, sourceDir).check() }
        val message = failure.message ?: ""
        for (name in CheckCinteropCouplingTask.RATCHETED_NAMES) {
            assertContains(
                message,
                "$name: baseline ${measured.counts.getValue(name) - 1}, actual ${measured.counts.getValue(name)}",
            )
        }
    }

    /**
     * The opaque `kc_` surface is not FFmpeg coupling, and count 1 must not read it as such.
     *
     * This case uses a fixture rather than the real sources, and it earns that: the question is
     * what the regex does to two specific import shapes, and the real tree cannot be made to hold
     * a counterexample on demand. It is also the behaviour B1.6 changed, so it needs a test that
     * fails if someone widens the regex back.
     */
    @Test
    fun countOneSeesFFmpegImportsAndNotTheOpaqueSurface() {
        val fixture = createTempDirectory("coupling-fixture").toFile()
        fixture.deleteOnExit()
        fixture.resolve("nativeInterop/cinterop").mkdirs()
        fixture.resolve(CheckCinteropCouplingTask.DEF_PATH).writeText("package = ffmpeg\n")
        fixture.resolve("Sample.kt").writeText(
            """
            |import ffmpeg.AVFrame
            |import ffmpeg.ffkmp_frame_alloc
            |import ffmpeg.avcodec_send_packet
            |import ffmpeg.kc_init
            |import ffmpeg.kc_ffmpeg_report_get
            |import ffmpeg.KC_LIB_AVUTIL
            |import kotlinx.cinterop.toKString
            """.trimMargin(),
        )

        val counts = CheckCinteropCouplingTask.measure(fixture).counts
        assertEquals(
            3,
            counts.getValue(CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES),
            "the three FFmpeg imports count; the three kc_/KC_ ones and the kotlinx one do not",
        )
    }

    /**
     * Interlude case 1 of 3 (I-13). Register item B1-22 asks B2 to move the hot decode and
     * encode calls behind helpers. Under the old ratchet that exact change was measured to FAIL
     * ("ffkmp_call_sites: baseline 273, actual 274") because it lowered one count and raised the
     * other, and only rises were looked at. Under the crossings number a category move is
     * neutral, so this is the improvement passing, measured on a copy of the real tree.
     */
    @Test
    fun movingARawCallBehindAHelperPasses() {
        val copy = copyOfRealTree()
        val playback = copy.resolve("nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt")
        val text = playback.readText()
        assertTrue("avcodec_send_packet(" in text, "the raw call this test moves has itself moved; update the test")
        playback.writeText(text.replaceFirst("avcodec_send_packet(", "ffkmp_codecctx_send_packet("))

        newTask(committedBaseline, copy).check()
    }

    /**
     * Interlude case 2 of 3 (I-13). B2's headline deliverable is the full AVChannelLayout model,
     * and under the old ratchet a KDoc sentence naming that type was measured to FAIL the build
     * ("ffmpeg_struct_types_named_in_kotlin: baseline 11, actual 12"), so B2 could not have
     * documented its own work. Comments leave the text before counting now.
     */
    @Test
    fun documentingAStructTypeInACommentPasses() {
        val copy = copyOfRealTree()
        val ffmpegKt = copy.resolve("commonMain/kotlin/io/github/yuroyami/kitecodec/FFmpeg.kt")
        ffmpegKt.appendText("\n// B2 note: the full AVChannelLayout model lands here.\n")

        newTask(committedBaseline, copy).check()
    }

    /**
     * Interlude case 3 of 3 (I-13). The rework must not have loosened the ratchet: a genuinely
     * new FFmpeg-typed call is a real rise in the crossings number and still refuses.
     */
    @Test
    fun aGenuinelyNewTypedCallStillFails() {
        val copy = copyOfRealTree()
        val ffmpegKt = copy.resolve("commonMain/kotlin/io/github/yuroyami/kitecodec/FFmpeg.kt")
        ffmpegKt.appendText("\nprivate fun probeCoupling() { av_probe_input_format(null, 0) }\n")

        val failure = assertFailsWith<GradleException> { newTask(committedBaseline, copy).check() }
        assertContains(failure.message ?: "", CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS)
    }

    /**
     * The stripper is what decides what the ratchet can see, so its two sharp edges get pinned:
     * Kotlin block comments NEST, and comment markers inside string literals are content.
     */
    @Test
    fun theCommentStripperHandlesNestingAndStrings() {
        val stripped = CheckCinteropCouplingTask.stripComments(
            """
            |val a = av_real_call(1) // av_line_comment(2)
            |/* av_block(3) /* nested av_block(4) */ still comment av_block(5) */
            |val b = "av_in_string(6) // not a comment"
            |val c = ${'"'}${'"'}${'"'}av_in_raw(7) /* not a comment */${'"'}${'"'}${'"'}
            |val d = av_after_all(8)
            """.trimMargin(),
        )
        assertContains(stripped, "av_real_call(1)")
        assertContains(stripped, "av_in_string(6)")
        assertContains(stripped, "av_in_raw(7)")
        assertContains(stripped, "av_after_all(8)")
        assertTrue("av_line_comment" !in stripped, "line comment survived")
        assertTrue("av_block" !in stripped, "nested block comment survived")
    }

    /** A disposable copy of the real `kitecodec-core/src`, excluding build output. */
    private fun copyOfRealTree(): File {
        val copy = createTempDirectory("coupling-real-copy").toFile()
        copy.deleteOnExit()
        sourceDir.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".claude" }
            .filter { it.isFile }
            .forEach { file ->
                val target = copy.resolve(file.relativeTo(sourceDir).path)
                target.parentFile.mkdirs()
                file.copyTo(target)
            }
        return copy
    }

    private fun newTask(baseline: File, source: File): CheckCinteropCouplingTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("checkCinteropCoupling", CheckCinteropCouplingTask::class.java)
            .get()
        task.sourceDir.set(source)
        task.baselineFile.set(baseline)
        task.cDeclarationFiles.from(cDeclarationFiles)
        return task
    }
}
