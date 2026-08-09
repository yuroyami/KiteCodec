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
 * The ratchet's own test. It runs against the real `kitecodec-core/src`, not a fixture, because the
 * only interesting question is whether the counts this task computes are the counts the repository
 * actually has: a fixture would prove the regexes match the fixture and nothing more.
 *
 * The repository root arrives as the `kitecodec.repo.root` system property, set by the `test` task
 * in `buildSrc/build.gradle.kts`.
 */
class CheckCinteropCouplingTaskTest {

    private val repoRoot: File = File(
        System.getProperty("kitecodec.repo.root") ?: "..",
    ).canonicalFile

    private val sourceDir: File get() = repoRoot.resolve("kitecodec-core/src")

    private val committedBaseline: File get() = repoRoot.resolve("native/kitecodec-c/coupling-baseline.txt")

    /**
     * The extracted C of the helper layer. From B1.3 onward this is where the FFmpeg struct type
     * names live, because the lift deleted the 949 line body out of `ffmpeg.def`.
     */
    private val cDeclarationFiles: List<File>
        get() = listOf("include", "src")
            .map { repoRoot.resolve("native/kitecodec-c/$it") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("h", "c") } }
            .sortedBy { it.path }

    @Test
    fun theCommittedBaselineMatchesTheMeasuredCoupling() {
        val recorded = CheckCinteropCouplingTask.parseBaseline(committedBaseline)
        val actual = CheckCinteropCouplingTask.measure(sourceDir, cDeclarationFiles)
        for (name in CheckCinteropCouplingTask.COUNT_NAMES) {
            assertTrue(
                actual.getValue(name) == recorded.getValue(name),
                "$name: baseline ${recorded.getValue(name)}, actual ${actual.getValue(name)}. " +
                    "A drop means the baseline should be lowered in this commit; a rise means the " +
                    "coupling grew.",
            )
        }
        // The task itself must pass, not just the numbers it is built on.
        newTask(committedBaseline).check()
    }

    @Test
    fun aBaselineLoweredByOneFailsAndNamesEveryCount() {
        val actual = CheckCinteropCouplingTask.measure(sourceDir, cDeclarationFiles)
        val lowered = File.createTempFile("coupling-baseline-lowered", ".txt")
        lowered.deleteOnExit()
        lowered.writeText(
            CheckCinteropCouplingTask.COUNT_NAMES.joinToString("\n", postfix = "\n") { name ->
                "$name ${actual.getValue(name) - 1}"
            },
        )

        val failure = assertFailsWith<GradleException> { newTask(lowered).check() }
        val message = failure.message ?: ""
        assertContains(message, "${CheckCinteropCouplingTask.COUNT_NAMES.size} of 4 counts rose")
        for (name in CheckCinteropCouplingTask.COUNT_NAMES) {
            assertContains(
                message,
                "$name: baseline ${actual.getValue(name) - 1}, actual ${actual.getValue(name)}",
            )
        }
    }

    /**
     * The opaque `kc_` surface is not FFmpeg coupling, and count 1 must not read it as such.
     *
     * This is the one case in this file that uses a fixture rather than the real sources, and it earns
     * that: the question is what the regex does to two specific import shapes, and the real tree cannot
     * be made to hold a counterexample on demand. It is also the behaviour B1.6 changed, so it needs a
     * test that fails if someone widens the regex back.
     *
     * The point of the exclusion is in the numbers. B1.6 removed 7 FFmpeg imports and added 26 `kc_` and
     * `KC_` ones; counted together that reads as 253 rising to 272, and the ratchet would have refused a
     * change whose net effect was less coupling. Counted apart it reads as 253 falling to 246, which is
     * what happened.
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

        val counts = CheckCinteropCouplingTask.measure(fixture)
        assertEquals(
            3,
            counts.getValue(CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES),
            "the three FFmpeg imports count; the three kc_/KC_ ones and the kotlinx one do not",
        )
    }

    private fun newTask(baseline: File): CheckCinteropCouplingTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("checkCinteropCoupling", CheckCinteropCouplingTask::class.java)
            .get()
        task.sourceDir.set(sourceDir)
        task.baselineFile.set(baseline)
        task.cDeclarationFiles.from(cDeclarationFiles)
        return task
    }
}
