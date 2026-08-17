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

    /**
     * The web tier must serve the 17.5 matrix rows it claims, and this pins the REASON rather than
     * the literal list.
     *
     * An earlier version asserted the demuxer string equalled `mov,matroska` exactly. It was right
     * to fail when that changed, but it pinned the wrong thing: what matters is that every codec a
     * MustPlay row needs is reachable, not that the string never moves. The matrix run found three
     * gaps this now covers, and each is named with the row that exposed it.
     */
    @Test
    fun everyCodecTheMatrixRequiresIsReachable() {
        val decoders = args("base").single { it.startsWith("--enable-decoder=") }
            .substringAfter("=").split(",")
        val demuxers = args("base").single { it.startsWith("--enable-demuxer=") }
            .substringAfter("=").split(",")

        // Video rows: sync1080p30.mp4 and friends, hevc4k10.mp4, vp9.webm.
        listOf("h264", "hevc", "vp9").forEach {
            assertTrue(it in decoders, "$it missing: a MustPlay video row needs it")
        }
        // Audio rows: audio-aac.m4a, audio-mp3.mp3, audio-flac.flac, the pcm cases.
        listOf("aac", "mp3", "flac", "pcm_s16le").forEach {
            assertTrue(it in decoders, "$it missing: a MustPlay audio row needs it")
        }
        // A decoder with no demuxer is a codec nobody can reach, which is exactly how
        // audio-mp3.mp3 and audio-flac.flac failed at open with -29 before this was fixed.
        listOf("mov", "matroska", "mp3", "flac").forEach {
            assertTrue(it in demuxers, "$it demuxer missing: its MustPlay row cannot be opened")
        }
    }

    @Test
    fun anUnknownVariantIsRefusedRatherThanTreatedAsBase() {
        val task = task()
        task.sourceRef.set("n8.0")
        task.variant.set("turbo")
        assertFailsWith<IllegalArgumentException> { task.run() }
    }
}
