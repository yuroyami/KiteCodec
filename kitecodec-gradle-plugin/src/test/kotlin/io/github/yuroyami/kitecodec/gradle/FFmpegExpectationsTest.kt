package io.github.yuroyami.kitecodec.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wording and the arithmetic of the plugin's two configuration-time FFmpeg checks, without a build.
 *
 * The functional test in `KiteCodecPluginFunctionalTest` proves the version check reaches a real
 * consumer's build and fails it. This file proves the parts that a TestKit run is a slow and blunt way to
 * ask about: which values are accepted, which are refused, and that the refusal says the things register
 * item B1-03 requires it to say. Both checks return their message instead of throwing precisely so this
 * is possible.
 */
class FFmpegExpectationsTest {

    @Test
    fun theDefaultVersionIsAccepted() {
        // A consumer who sets nothing gets the convention, so the convention has to pass.
        assertNull(FFmpegExpectations.versionMismatchMessage(DEFAULT_FFMPEG_VERSION, FFmpegSource.Prebuilt))
        assertTrue(DEFAULT_FFMPEG_VERSION in FFmpegExpectations.SUPPORTED_REFS)
    }

    @Test
    fun theTagPrefixDoesNotDecideAcceptance() {
        // n8.0 and 8.0 name one release. Refusing the second would be a false rejection.
        assertNull(FFmpegExpectations.versionMismatchMessage("8.0", FFmpegSource.Prebuilt))
        assertNull(FFmpegExpectations.versionMismatchMessage("  n8.0 ", FFmpegSource.Prebuilt))
        assertEquals("8.0", FFmpegExpectations.normaliseRef("n8.0"))
    }

    @Test
    fun anotherReleaseIsRefusedAndTheMessageNamesBothRefsAndBothWaysOut() {
        val message = FFmpegExpectations.versionMismatchMessage("n7.1", FFmpegSource.Prebuilt)
        assertTrue(message != null, "n7.1 must be refused")
        // Both refs. A message that named only one would leave the reader guessing what to change it to.
        assertContains(message!!, "n7.1")
        assertContains(message, DEFAULT_FFMPEG_VERSION)
        // The two ways out, and the reason the mistake is dangerous rather than merely wrong.
        assertContains(message, "Two ways out")
        assertContains(message, "Build KiteCodec yourself")
        assertContains(message, "no SONAME")
        // And the source, because Prebuilt and System fail for the same reason by different routes.
        assertContains(message, "Prebuilt")
    }

    @Test
    fun theRefusalIsIndependentOfTheSource() {
        for (source in FFmpegSource.entries) {
            val message = FFmpegExpectations.versionMismatchMessage("n6.1", source)
            assertTrue(message != null, "n6.1 must be refused with source $source")
            assertContains(message!!, source.toString())
        }
    }

    @Test
    fun aMatchingSystemFFmpegPasses() {
        val exact = FFmpegExpectations.EXPECTED_MAJORS.mapValues { (_, major) -> "$major.8.100" }
        assertNull(FFmpegExpectations.systemMajorMismatchMessage(exact))
    }

    @Test
    fun aNewerMinorOnTheSameMajorPasses() {
        // Only the major is compared. FFmpeg promises compatibility within one, and demanding an exact
        // triple would refuse a system install that is newer and fine.
        val newer = FFmpegExpectations.EXPECTED_MAJORS.mapValues { (_, major) -> "$major.99.100" }
        assertNull(FFmpegExpectations.systemMajorMismatchMessage(newer))
    }

    @Test
    fun aDifferentMajorIsRefusedAndNamedPerLibrary() {
        val off = FFmpegExpectations.EXPECTED_MAJORS.mapValues { (_, major) -> "${major - 1}.0.100" }
        val message = FFmpegExpectations.systemMajorMismatchMessage(off)
        assertTrue(message != null, "every library one major behind must be refused")
        for ((library, major) in FFmpegExpectations.EXPECTED_MAJORS) {
            assertContains(message!!, "$library: system reports ${major - 1}.0.100")
            assertContains(message, "KiteCodec expects major $major")
        }
        assertContains(message!!, "38 field offsets")
    }

    @Test
    fun oneWrongLibraryOutOfSixIsEnoughToRefuse() {
        val mostlyRight = FFmpegExpectations.EXPECTED_MAJORS.mapValues { (_, major) -> "$major.0.100" }
            .toMutableMap()
            .apply { put("libavcodec", "61.0.100") }
        val message = FFmpegExpectations.systemMajorMismatchMessage(mostlyRight)
        assertTrue(message != null)
        assertContains(message!!, "libavcodec")
        // And it names only the one that is wrong.
        assertTrue("libavutil:" !in message, "libavutil should not be listed. Message:\n$message")
    }

    @Test
    fun whatCannotBeMeasuredIsNotReportedAsWrong() {
        // pkg-config absent, or unable to answer for a library: no opinion, no failure. A check that
        // turned "I could not measure" into "you are wrong" is a check people switch off.
        assertNull(FFmpegExpectations.systemMajorMismatchMessage(emptyMap()))
        assertNull(FFmpegExpectations.systemMajorMismatchMessage(mapOf("libavutil" to "not a version")))
        // A partial answer is still checked for what it did answer.
        val partial = mapOf("libavutil" to "1.0.0")
        assertTrue(FFmpegExpectations.systemMajorMismatchMessage(partial) != null)
    }

    /**
     * The header reader, against this machine's real FFmpeg headers.
     *
     * A fixture would prove the regex matches the fixture. What has to be true is that it matches what
     * FFmpeg actually ships, including the split that put the MAJOR of five of the six libraries in
     * `version_major.h`, which is the mistake this test exists to make impossible to repeat. Skipped with
     * no opinion when there is no system FFmpeg to read, because that is a property of the machine.
     */
    @Test
    fun theHeaderReaderReadsTheSystemFFmpegAndAgreesWithTheTable() {
        val includeDir = sequenceOf("/opt/homebrew/include", "/usr/local/include", "/usr/include")
            .map(::File)
            .firstOrNull { it.resolve("libavutil/version.h").isFile }
            ?: return
        var read = 0
        for ((library, expectedMajor) in FFmpegExpectations.EXPECTED_MAJORS) {
            val text = listOf("version.h", "version_major.h")
                .mapNotNull { name -> includeDir.resolve("$library/$name").takeIf { it.isFile }?.readText() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
                ?: continue
            val version = FFmpegExpectations.readVersionFromHeaders(text, library)
            assertTrue(version != null, "no version read from $includeDir/$library")
            assertTrue(
                Regex("""\d+\.\d+\.\d+""").matches(version!!),
                "$library read as \"$version\"",
            )
            // The system FFmpeg on the proving machine is the release these artifacts target, so the
            // majors agree. On a machine where they do not, this assertion is the check itself firing,
            // and the message says which library and what it read.
            assertEquals(
                expectedMajor,
                version.substringBefore('.').toInt(),
                "$library: system header says $version, EXPECTED_MAJORS says $expectedMajor",
            )
            read++
        }
        assertEquals(
            FFmpegExpectations.EXPECTED_MAJORS.size,
            read,
            "expected to read all six libraries from $includeDir",
        )
    }

    @Test
    fun theHeaderReaderRefusesToGuessAMissingMajor() {
        // version.h alone, which is what five of the six libraries ship: MINOR and MICRO but no MAJOR.
        // Returning "0.11.100" here would make the caller's check pass against nonsense.
        val onlyMinorAndMicro = """
            |#define LIBAVCODEC_VERSION_MINOR  11
            |#define LIBAVCODEC_VERSION_MICRO 100
        """.trimMargin()
        assertNull(FFmpegExpectations.readVersionFromHeaders(onlyMinorAndMicro, "libavcodec"))
        // With version_major.h concatenated on, it reads.
        val both = onlyMinorAndMicro + "\n#define LIBAVCODEC_VERSION_MAJOR  62\n"
        assertEquals("62.11.100", FFmpegExpectations.readVersionFromHeaders(both, "libavcodec"))
    }

    @Test
    fun allSixLibrariesAreCovered() {
        assertEquals(
            setOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample"),
            FFmpegExpectations.EXPECTED_MAJORS.keys,
        )
    }
}
