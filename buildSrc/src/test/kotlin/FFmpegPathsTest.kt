package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FFmpegPathsTest {

    @Test
    fun everyIosTargetRejectsGplBeforeLookingForAFileTree() {
        val project = ProjectBuilder.builder().build()

        listOf(
            TargetTriple.IosArm64,
            TargetTriple.IosSimulatorArm64,
            TargetTriple.IosX64,
        ).forEach { target ->
            val failure = assertFailsWith<GradleException> {
                FFmpegPaths.resolve(project, target, FFmpegLicense.GPL)
            }
            assertEquals(IOS_GPL_REFUSAL, failure.message)
        }
    }
}
