plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    // Pin the Kotlin Gradle plugin version once at the root so :kitecodec-gradle-plugin can apply
    // kotlin.jvm without re-declaring a version (both ids resolve to the same KGP on the classpath).
    alias(libs.plugins.kotlin.jvm).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
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
