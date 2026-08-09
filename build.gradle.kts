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
}
