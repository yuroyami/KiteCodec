package io.github.yuroyami.kitecodec.gradle

/**
 * What FFmpeg the KiteCodec artifacts of this build expect, and the checks that hold a consumer to it.
 *
 * **Why this exists at all.** Register item B1-03: `kitecodec { ffmpeg { version = ... } }` was a free
 * `Property<String>` with a convention and no validation. A consumer writing `version = "n7.1"` against
 * the default `Prebuilt` source downloads FFmpeg 7.1 archives and links them against a klib whose C was
 * compiled against 8.0 headers. Every symbol the def needs exists in 7.1, the static link succeeds, there
 * is no SONAME to stop it, and the result is the memory corruption of register item B1-02: struct field
 * offsets that moved, read through by 48 helpers. That route is the most likely way a real consumer
 * reaches that corruption, and it is a configuration mistake, so it belongs at configuration time.
 *
 * **The runtime gate is not a substitute for this one, and neither is a substitute for the other.**
 * `kc_init` inside the C layer compares header macros with runtime versions and refuses to run; that
 * catches everything, including a runtime swapped in after the build. But it fails at the consumer's
 * first playback, on their user's machine, with an exception. This check fails at their `./gradlew`
 * prompt, with a sentence naming both refs and the two ways out. Both are cheap; the early one is kinder.
 */
public object FFmpegExpectations {

    /**
     * The FFmpeg releases the KiteCodec artifacts of this build were compiled against.
     *
     * A set, because a future release may support more than one, and because the validation message
     * reads correctly either way. One entry today, [DEFAULT_FFMPEG_VERSION], which is also the
     * convention of `kitecodec { ffmpeg { version } }`, so a consumer who sets nothing is always right.
     *
     * This is not a fourth copy of the pin. The root `build.gradle.kts` asserts at configuration time
     * that `DEFAULT_FFMPEG_VERSION` here, `BuildFFmpegTask.DEFAULT_SOURCE_REF` in buildSrc,
     * `FFMPEG_VERSION` in `.github/workflows/publish.yml` and, when the checkout is present,
     * `vendor/ffmpeg/RELEASE` all name one release. That assertion is register item B1-04 and it is why
     * the plugin may treat its own constant as authoritative.
     */
    public val SUPPORTED_REFS: Set<String> = setOf(DEFAULT_FFMPEG_VERSION)

    /**
     * The six libav major versions that FFmpeg [DEFAULT_FFMPEG_VERSION] ships, keyed by pkg-config name.
     *
     * Written down here because the plugin runs in the CONSUMER's build, where neither KiteCodec's
     * vendored checkout nor its C sources exist, so there is nothing else to read them from. The numbers
     * are not taken on trust: the root `build.gradle.kts` reads the six `version.h` files out of
     * `vendor/ffmpeg` when that checkout is present and fails configuration if this table disagrees with
     * them. Measured from that checkout on 2026-08-09 at FFmpeg 8.0.
     *
     * Majors and not full triples on purpose. A major is the number FFmpeg itself promises not to break
     * within, so it is the only part a consumer's system FFmpeg has to match; requiring an exact triple
     * would refuse a system install that is newer and fine, which is the false rejection plan section
     * 15.4 warns about.
     */
    public val EXPECTED_MAJORS: Map<String, Int> = mapOf(
        "libavutil" to 60,
        "libavcodec" to 62,
        "libavformat" to 62,
        "libavfilter" to 11,
        "libswscale" to 9,
        "libswresample" to 6,
    )

    /** Drops FFmpeg's `n` tag prefix so `n8.0` and `8.0` compare equal. */
    public fun normaliseRef(ref: String): String = ref.trim().removePrefix("n")

    /**
     * `MAJOR.MINOR.MICRO` for [library] out of the text of its FFmpeg version headers, or null.
     *
     * [headerText] is the concatenation of `<library>/version.h` and `<library>/version_major.h`, and it
     * has to be both: FFmpeg keeps the MAJOR of every library except libavutil in its own
     * `version_major.h`, so reading only `version.h` finds no MAJOR for five of the six and would make
     * the caller's check pass vacuously. That mistake was made once during B1.6 and caught by a negative
     * test, which is why the requirement is written down here rather than left to the caller to notice.
     *
     * Pure, so `FFmpegExpectationsTest` can point it at this machine's real Homebrew headers and at a
     * fixture missing the MAJOR, neither of which needs a build.
     */
    public fun readVersionFromHeaders(headerText: String, library: String): String? {
        val prefix = library.uppercase()
        fun component(part: String): Int? =
            Regex("""^\s*#define\s+${prefix}_VERSION_$part\s+(\d+)""", RegexOption.MULTILINE)
                .find(headerText)?.groupValues?.get(1)?.toIntOrNull()
        val major = component("MAJOR") ?: return null
        return "$major.${component("MINOR") ?: 0}.${component("MICRO") ?: 0}"
    }

    /**
     * The message for a `version` the artifacts were not built for, or null when it is fine.
     *
     * Returns the text instead of throwing so that [KiteCodecPluginFunctionalTest] and any future caller
     * can assert the wording without driving a whole build, and so the two ways out cannot drift from
     * the check that names them.
     */
    public fun versionMismatchMessage(requested: String, source: FFmpegSource): String? {
        val wanted = normaliseRef(requested)
        if (SUPPORTED_REFS.any { normaliseRef(it) == wanted }) return null
        val supported = SUPPORTED_REFS.joinToString()
        return """
            |kitecodec: ffmpeg { version = "$requested" } is not a release these KiteCodec artifacts were built for.
            |
            |KiteCodec's native layer is compiled against the headers of $supported, and those header
            |versions are baked into the published klib: struct field offsets, not just symbol names.
            |Linking a different FFmpeg still succeeds, because every symbol resolves and a static
            |archive has no SONAME, and then reads land at the wrong offsets. That is silent memory
            |corruption, not a link error, so it is refused here instead.
            |
            |  requested: $requested (normalised ${normaliseRef(requested)})
            |  built for: $supported
            |  source:    $source
            |
            |Two ways out, and only two:
            |  1. Use the release these artifacts were built for:
            |         kitecodec { ffmpeg { version = "${SUPPORTED_REFS.first()}" } }
            |     Removing the `version` line does the same, because that is its default.
            |  2. Build KiteCodec yourself against the FFmpeg you want, and depend on your own build.
            |     KiteCodec's own checkout builds FFmpeg from source with :buildFFmpegFor<Target>, and
            |     its C layer is then compiled against that tree's headers, so the two agree by
            |     construction.
            |
            |At runtime the same mismatch is caught again by the FFmpeg identity gate, which reports both
            |version columns and refuses to start. This check exists so that you see it now rather than
            |your users seeing it later.
        """.trimMargin()
    }

    /**
     * The message for a system FFmpeg whose majors do not match, or null when they do.
     *
     * [modversions] maps a pkg-config name to what `pkg-config --modversion` answered, with an absent
     * entry meaning pkg-config could not answer for that library. An absent entry is NOT a failure: a
     * host without pkg-config, or with the libraries installed somewhere pkg-config cannot see, is a
     * host this check has no opinion about, and turning "I could not measure" into "you are wrong" is how
     * a check becomes something people disable.
     */
    public fun systemMajorMismatchMessage(modversions: Map<String, String>): String? {
        val mismatched = EXPECTED_MAJORS.mapNotNull { (library, expectedMajor) ->
            val answered = modversions[library] ?: return@mapNotNull null
            val major = answered.trim().substringBefore('.').toIntOrNull() ?: return@mapNotNull null
            if (major == expectedMajor) null else Triple(library, expectedMajor, answered.trim())
        }
        if (mismatched.isEmpty()) return null
        return buildString {
            appendLine(
                "kitecodec: source = FFmpegSource.System, and the system FFmpeg's major version does " +
                    "not match what these KiteCodec artifacts were compiled against.",
            )
            appendLine()
            for ((library, expectedMajor, answered) in mismatched) {
                appendLine("  $library: system reports $answered, KiteCodec expects major $expectedMajor")
            }
            appendLine()
            append(
                "A major bump lets FFmpeg reorder struct contents, which its own doc/developer.texi " +
                    "permits, and 38 field offsets were measured to move across one. The link would " +
                    "succeed and the reads would not. Install FFmpeg ${SUPPORTED_REFS.joinToString()}, " +
                    "or switch to FFmpegSource.Prebuilt, which downloads the build these artifacts " +
                    "were made for.",
            )
        }
    }
}
