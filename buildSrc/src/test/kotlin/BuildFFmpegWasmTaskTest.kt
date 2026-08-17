package io.github.yuroyami.kitecodec.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildFFmpegWasmTaskTest {

    private fun task() = ProjectBuilder.builder().build().tasks
        .create("ffmpegWasm", BuildFFmpegWasmTask::class.java)

    private fun args(variant: String) = task().configureArgs(variant, Path.of("/tmp/prefix"))

    /**
     * The regression this file exists for.
     *
     * The web spike's recipe disabled avfilter, because its bare harness never opened a filter
     * graph. `libkitecodec.a` does: `helpers_filter.c` looks up these five filters BY NAME and
     * calls `avfilter_graph_parse_ptr`. A build without them links and then fails at the first
     * filter call, which is the worst place to find out.
     */
    @Test
    fun everyFilterTheCLibraryNamesIsEnabled() {
        val enabled = args("base").single { it.startsWith("--enable-filter=") }
            .removePrefix("--enable-filter=").split(",").toSet()
        listOf("abuffer", "abuffersink", "anull", "buffer", "buffersink").forEach { filter ->
            assertTrue(filter in enabled, "helpers_filter.c names '$filter' but the build disables it")
        }
        assertTrue(args("base").contains("--enable-avfilter"))
        assertFalse(args("base").contains("--disable-avfilter"))
    }

    /** n8.0 has no `--disable-postproc`, and passing it makes configure exit non-zero. */
    @Test
    fun noArgumentThatFFmpegN8DoesNotHave() {
        BuildFFmpegWasmTask.VARIANTS.forEach { variant ->
            assertFalse(
                args(variant).any { it == "--disable-postproc" },
                "--disable-postproc does not exist on n8.0 and configure rejects it",
            )
        }
    }

    /**
     * `--disable-asm` turns SIMD off with it, so the SIMD variant must not pass it. Without this
     * the simd build silently produces the base build and its measurement is a lie.
     */
    @Test
    fun theSimdVariantDoesNotDisableAsmAndTheOthersDo() {
        assertFalse("--disable-asm" in args("simd"))
        assertTrue("-O3 -msimd128" in args("simd").single { it.startsWith("--extra-cflags=") })
        assertTrue("--disable-asm" in args("base"))
        assertTrue("--disable-asm" in args("mt"))
    }

    /** Threads are opt-in: the default artifact must not need cross-origin isolation to run. */
    @Test
    fun onlyTheThreadedVariantEnablesPthreads() {
        assertTrue("--disable-pthreads" in args("base"))
        assertTrue("--disable-pthreads" in args("simd"))
        assertTrue("--enable-pthreads" in args("mt"))
        assertFalse("--disable-pthreads" in args("mt"))
    }

    /** The lean web set is a decision, not an accident, so its contents are pinned. */
    @Test
    fun theLeanSetCarriesTheCodecsTheWebTierPromises() {
        val decoders = args("base").single { it.startsWith("--enable-decoder=") }
        listOf("h264", "hevc", "aac", "mp3", "flac", "pcm_s16le").forEach {
            assertTrue(it in decoders.split("=", limit = 2)[1].split(","), "$it missing from the web tier")
        }
        val demuxers = args("base").single { it.startsWith("--enable-demuxer=") }
        assertEquals("--enable-demuxer=mov,matroska", demuxers)
    }

    @Test
    fun anUnknownVariantIsRefusedRatherThanTreatedAsBase() {
        val task = task()
        task.sourceRef.set("n8.0")
        task.variant.set("turbo")
        assertFailsWith<IllegalArgumentException> { task.run() }
    }
}
