import io.github.yuroyami.kitecodec.buildtools.BuildFFmpegTask
import io.github.yuroyami.kitecodec.buildtools.CheckCinteropCouplingTask

plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    // Pin the Kotlin Gradle plugin version once at the root so :kitecodec-gradle-plugin can apply
    // kotlin.jvm without re-declaring a version (both ids resolve to the same KGP on the classpath).
    alias(libs.plugins.kotlin.jvm).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
    // Guards the public API surface of :kitecodec-core (apiDump / apiCheck, klib-aware).
    alias(libs.plugins.binary.compatibility.validator)
}

allprojects {
    group   = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION").get()
}

/* Aggregate the published library modules into a single Dokka API reference. */
dependencies {
    dokka(project(":kitecodec-core"))
}

dokka {
    moduleName.set("KiteCodec")
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root:
// under aggregation the root only renders the "all modules" landing page, and
// each module renders its own pages from its own configuration. Configuring
// only the root leaves every actual API page on the stock theme.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                customStyleSheets.from(
                    rootProject.layout.projectDirectory.file("docs/api-theme/kite.css"),
                )
                templatesDir.set(
                    rootProject.layout.projectDirectory.dir("dokka-templates"),
                )
                footerMessage.set("Apache-2.0 · KiteCodec is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }

        }
    }
}


apiValidation {
    // Only :kitecodec-core is a published library with a guarded API surface.
    ignoredProjects += listOf("kitecodec-sample", "kitecodec-gradle-plugin")

    // :kitecodec-core is Kotlin/Native-only, so its API surface lives in klibs.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

/*
 * The ratchet on kitecodec-core's coupling to FFmpeg's C types. It recomputes the four counts of
 * native/kitecodec-c/coupling-baseline.txt and fails when any one of them rose. See
 * CheckCinteropCouplingTask for what each count is and why the deferral needs a ratchet at all.
 */
tasks.register<CheckCinteropCouplingTask>("checkCinteropCoupling") {
    group = "verification"
    description = "Fails when kitecodec-core's coupling to FFmpeg's C types grew past its baseline."
    sourceDir.set(layout.projectDirectory.dir("kitecodec-core/src"))
    baselineFile.set(layout.projectDirectory.file("native/kitecodec-c/coupling-baseline.txt"))
    // Count four needs the C of the helper layer. Before B1.3 that was the def body; from B1.3 it is
    // this tree, and reading both is what keeps the count identical across the move. The file tree
    // rather than two fixed names, because B1.4 splits the single .c into nine.
    cDeclarationFiles.from(
        fileTree(layout.projectDirectory.dir("native/kitecodec-c")) {
            include("include/**/*.h", "src/**/*.c")
        },
    )
}

/*
 * Register item B1-04: the expected FFmpeg release is written down in three places bound only by a
 * comment asking the reader to keep them in sync, and nothing checked any of them against the vendored
 * checkout. This is that check, and it is a build-time ASSERTION rather than a task on purpose: a task
 * has to be asked for, and the failure this prevents is one nobody would think to ask about. It runs
 * during configuration of every build in this repository.
 *
 * Why the file reads go through `providers.fileContents`. Reading a file with File.readText() at
 * configuration time is invisible to the configuration cache, so a cached entry would keep passing after
 * one of these files drifted, which is the one outcome a drift check must not have. A `fileContents`
 * provider is a tracked configuration input: change publish.yml and Gradle discards the entry and
 * re-runs this. It also starts no process, which is the other thing the configuration cache forbids.
 */
run {
    val workflow = layout.projectDirectory.file(".github/workflows/publish.yml")
    val workflowText = providers.fileContents(workflow).asText.orNull
        ?: throw GradleException(
            "Cannot check the FFmpeg release pins (register item B1-04): no ${workflow.asFile.path}.",
        )
    val workflowRef = BuildFFmpegTask.readWorkflowFFmpegVersion(workflowText)
        ?: throw GradleException(
            "Cannot check the FFmpeg release pins (register item B1-04): " +
                ".github/workflows/publish.yml has no `FFMPEG_VERSION:` line in its env block. It is " +
                "one of the three places that must name the release; a workflow that stopped pinning " +
                "one is exactly the drift this check exists to catch.",
        )
    // Absent on a checkout that never vendored FFmpeg, which is normal and not a failure. Present at
    // the wrong release is a failure, because that tree is what the C would be compiled against.
    val vendorRelease = providers
        .fileContents(layout.projectDirectory.file("vendor/ffmpeg/RELEASE"))
        .asText
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val pluginSource = layout.projectDirectory
        .file("kitecodec-gradle-plugin/src/main/kotlin/io/github/yuroyami/kitecodec/gradle/KiteCodecPlugin.kt")
    val pluginRef = providers.fileContents(pluginSource).asText.orNull
        ?.let { BuildFFmpegTask.readPluginDefaultFFmpegVersion(it) }
        ?: throw GradleException(
            "Cannot check the FFmpeg release pins (register item B1-04): no " +
                "`DEFAULT_FFMPEG_VERSION = \"...\"` in ${pluginSource.asFile.path}. It is one of the " +
                "three places that must name the release.",
        )

    BuildFFmpegTask.assertFFmpegRefsAgree(
        listOf(
            BuildFFmpegTask.FFmpegRefSite(
                "buildSrc/src/main/kotlin/BuildFFmpegTask.kt DEFAULT_SOURCE_REF",
                BuildFFmpegTask.DEFAULT_SOURCE_REF,
            ),
            BuildFFmpegTask.FFmpegRefSite(
                "kitecodec-gradle-plugin/.../KiteCodecPlugin.kt DEFAULT_FFMPEG_VERSION",
                pluginRef,
            ),
            BuildFFmpegTask.FFmpegRefSite(".github/workflows/publish.yml FFMPEG_VERSION", workflowRef),
        ),
        vendorRelease,
    )

    /*
     * The plugin's EXPECTED_MAJORS table, against the vendored checkout's own version headers.
     *
     * FFmpegExpectations.EXPECTED_MAJORS is written down in the plugin because the plugin runs in the
     * consumer's build, where neither the vendored checkout nor KiteCodec's C sources exist. Written down
     * means it can be wrong, and a wrong table would refuse a system FFmpeg that is fine or accept one
     * that is not. So when the checkout IS present, which is here, the table is checked against it.
     *
     * Skipped without complaint when vendor/ffmpeg is absent: most builds never vendor FFmpeg, and a
     * missing optional checkout must not fail every build in the repository.
     */
    val expectedMajors = mapOf(
        "libavutil" to "LIBAVUTIL",
        "libavcodec" to "LIBAVCODEC",
        "libavformat" to "LIBAVFORMAT",
        "libavfilter" to "LIBAVFILTER",
        "libswscale" to "LIBSWSCALE",
        "libswresample" to "LIBSWRESAMPLE",
    )
    val pluginTableText = providers.fileContents(
        layout.projectDirectory
            .file("kitecodec-gradle-plugin/src/main/kotlin/io/github/yuroyami/kitecodec/gradle/FFmpegExpectations.kt"),
    ).asText.orNull
    if (vendorRelease != null && pluginTableText != null) {
        val disagreements = expectedMajors.mapNotNull { (pkgName, macroPrefix) ->
            // Both headers, concatenated, and both are needed. The pkg-config name IS the directory name
            // in an FFmpeg checkout, so nothing is stripped from it. And FFmpeg keeps the MAJOR of every
            // library except libavutil in its own `version_major.h`, so `version.h` alone carries only
            // MINOR and MICRO for five of the six.
            //
            // Two measured mistakes are recorded here because both passed silently. The first stripped
            // the `lib` prefix, so every read returned null. The second read only `version.h`, so the
            // MAJOR regex found nothing. Either way every entry was skipped and the check reported
            // success against a table that had been deliberately falsified. The negative test is what
            // caught both, which is why it is part of the gate and not an afterthought.
            val headerText = listOf("version.h", "version_major.h")
                .mapNotNull { name ->
                    providers.fileContents(
                        layout.projectDirectory.file("vendor/ffmpeg/$pkgName/$name"),
                    ).asText.orNull
                }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
                ?: return@mapNotNull null
            val vendored = Regex("""^\s*#define\s+${macroPrefix}_VERSION_MAJOR\s+(\d+)""", RegexOption.MULTILINE)
                .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val declared = Regex(""""$pkgName"\s+to\s+(\d+)""")
                .find(pluginTableText)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (vendored == declared) null else "  $pkgName: vendor/ffmpeg says $vendored, FFmpegExpectations says $declared"
        }
        if (disagreements.isNotEmpty()) {
            throw GradleException(
                "FFmpegExpectations.EXPECTED_MAJORS disagrees with vendor/ffmpeg (register item B1-04):\n" +
                    disagreements.joinToString("\n") + "\n\n" +
                    "That table is what the Gradle plugin compares a consumer's system FFmpeg against, " +
                    "and it is written down rather than read because the plugin runs where the checkout " +
                    "does not exist. Correct the table, or check the vendored tree out at the release " +
                    "the three pins name.",
            )
        }
    }
}
