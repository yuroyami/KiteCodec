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

apiValidation {
    // Only :kitecodec-core is a published library with a guarded API surface.
    ignoredProjects += listOf("kitecodec-sample", "kitecodec-gradle-plugin")

    // :kitecodec-core is Kotlin/Native-only, so its API surface lives in klibs.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
