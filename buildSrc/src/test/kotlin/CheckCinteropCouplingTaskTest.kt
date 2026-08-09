package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
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
