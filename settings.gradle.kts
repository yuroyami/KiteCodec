enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KiteCodec"
include(":kitecodec-core")
include(":kitecodec-sample")
include(":kitecodec-gradle-plugin")
// include(":kitecodec-gpl") — uncomment once kitecodec-gpl/build.gradle.kts is implemented (see kitecodec-gpl/README.md)
