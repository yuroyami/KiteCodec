package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The two cases plan section 15.2 B1.3 names for [CompileKiteCodecCTask]: a correct object archives,
 * and an object of the wrong architecture fails with a message naming both architectures and the
 * target. A third case covers the output-directory guard, because that guard is the other half of
 * register item B1-11 and is as easy to break as it is to state.
 *
 * The compiler is the real konan clang and the objects are real objects. A fixture would prove that
 * a fake `file` output matches a hand written expectation and nothing about the toolchain. The
 * translation unit is deliberately trivial and includes nothing, so these cases need no FFmpeg tree
 * and run on any host whose Kotlin/Native distribution is installed.
 */
class CompileKiteCodecCTaskTest {

    @Test
    fun aCorrectObjectArchives() {
        val fixture = fixture()
        val task = newTask("macos_arm64", fixture)
        task.compile()

        val archive = fixture.outputRoot.resolve("macos_arm64/${CompileKiteCodecCTask.ARCHIVE_NAME}")
        assertTrue(archive.isFile, "no archive at ${archive.absolutePath}")
        assertTrue(archive.length() > 0, "the archive at ${archive.absolutePath} is empty")
        assertContains(describe(archive), "ar archive")

        val objectFile = fixture.outputRoot.resolve("macos_arm64/obj/probe.o")
        assertTrue(objectFile.isFile, "no object at ${objectFile.absolutePath}")
        assertContains(describe(objectFile), CompileKiteCodecCTask.expectedObjectDescription("macos_arm64"))
    }

    @Test
    fun anObjectOfTheWrongArchitectureIsRefusedNamingBothArchitecturesAndTheTarget() {
        // A real object of the wrong architecture: the same source, compiled for macos_x64, which is
        // what an output directory shared between two targets would leave behind.
        val fixture = fixture()
        newTask("macos_x64", fixture).compile()
        val x64Object = fixture.outputRoot.resolve("macos_x64/obj/probe.o")
        assertTrue(x64Object.isFile, "the x64 object was not produced at ${x64Object.absolutePath}")

        val actual = describe(x64Object)
        val failure = assertFails {
            CompileKiteCodecCTask.verifyObjectArchitecture("macos_arm64", x64Object, actual)
        }
        val message = failure.message ?: ""
        // Both architectures, so the reader does not have to guess which end is wrong.
        assertContains(message, actual)
        assertContains(message, CompileKiteCodecCTask.expectedObjectDescription("macos_arm64"))
        // And the target, so the reader knows which archive would have been poisoned.
        assertContains(message, "macos_arm64")
        assertContains(message, x64Object.absolutePath)
    }

    @Test
    fun anOutputDirectoryNotNamedAfterItsTargetIsRefused() {
        val fixture = fixture()
        val task = newTask("macos_arm64", fixture)
        // The one mistake that produces a wrong-architecture archive without any compiler being
        // wrong: two targets writing into one directory (register item B1-11).
        task.outputDir.set(fixture.outputRoot.resolve("shared"))

        val message = assertFails { task.compile() }.message ?: ""
        assertContains(message, "B1-11")
        assertContains(message, "macos_arm64")
        assertContains(message, "shared")
    }

    /**
     * One trivial translation unit plus the directories the task requires. The `.c` includes nothing
     * and is warning clean under `-Wall -Wextra -Werror`, so it compiles for every target the task
     * knows without an FFmpeg tree.
     */
    private class Fixture(val sourceDir: File, val includeDir: File, val outputRoot: File)

    private fun fixture(): Fixture {
        val root = createTempDirectory()
        val sourceDir = root.resolve("src").also { it.mkdirs() }
        val includeDir = root.resolve("include").also { it.mkdirs() }
        sourceDir.resolve("probe.c").writeText("int kc_probe(void) { return 0; }\n")
        return Fixture(sourceDir, includeDir, root.resolve("out"))
    }

    private fun newTask(konanTarget: String, fixture: Fixture): CompileKiteCodecCTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("compileKiteCodecCFor$konanTarget", CompileKiteCodecCTask::class.java)
            .get()
        task.konanTargetName.set(konanTarget)
        task.sourceDir.set(fixture.sourceDir)
        task.includeDir.set(fixture.includeDir)
        task.ffmpegIncludeDirs.set(emptyList<String>())
        task.konanDataDir.set(konanDataDir)
        task.outputDir.set(fixture.outputRoot.resolve(konanTarget))
        return task
    }

    /** `~/.konan`, or whatever `KONAN_DATA_DIR` points at, the same resolution the build uses. */
    private val konanDataDir: File
        get() {
            val fromEnv = System.getenv("KONAN_DATA_DIR")
            val dir = if (fromEnv.isNullOrBlank()) {
                File(System.getProperty("user.home"), ".konan")
            } else {
                File(fromEnv)
            }
            assertTrue(
                dir.resolve("dependencies").isDirectory,
                "No konan dependencies under ${dir.absolutePath}. They arrive with the " +
                    "Kotlin/Native distribution, so run any Kotlin/Native compilation first.",
            )
            return dir
        }

    private fun describe(file: File): String {
        val tool = if (File("/usr/bin/file").canExecute()) "/usr/bin/file" else "file"
        val process = ProcessBuilder(tool, "-b", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor() == 0, "$tool -b failed: $output")
        return output
    }

    private fun createTempDirectory(): File =
        File.createTempFile("compile-kitecodec-c", "").let { placeholder ->
            placeholder.delete()
            placeholder.mkdirs()
            placeholder.deleteOnExit()
            placeholder
        }

    /**
     * Gradle wraps a task action failure, so the exception the caller sees is not always the
     * [GradleException] the task threw. This walks the cause chain for it and fails when there is
     * none, which keeps every assertion below about the message rather than about the wrapper.
     */
    private fun assertFails(block: () -> Unit): Throwable {
        val thrown = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(thrown != null, "expected a failure, but the call succeeded")
        var candidate: Throwable? = thrown
        while (candidate != null) {
            if (candidate is GradleException) return candidate
            candidate = candidate.cause
        }
        return thrown
    }
}
