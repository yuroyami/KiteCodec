plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
}

allprojects {
    group   = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION").get()
}
