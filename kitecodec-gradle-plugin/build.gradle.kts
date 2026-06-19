plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    // Match the rest of the repo (JDK 21). KiteCodec already requires a 21 toolchain to build, so
    // any consumer building it has one.
    jvmToolchain(21)
}

dependencies {
    // The Kotlin Gradle plugin types (KotlinMultiplatformExtension, KotlinNativeTarget) are present
    // in the consumer's build at apply time, so compile against them but do not bundle them.
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("kitecodec") {
            id = "io.github.yuroyami.kitecodec"
            implementationClass = "io.github.yuroyami.kitecodec.gradle.KiteCodecPlugin"
            displayName = "KiteCodec FFmpeg provisioning"
            description = "Fetches and links the prebuilt FFmpeg binaries KiteCodec needs, one per Kotlin/Native target."
        }
    }
}

// `java-gradle-plugin` + `maven-publish` already publish the plugin and its marker artifact to
// mavenLocal / Maven Central. To publish to the Gradle Plugin Portal instead, add the
// `com.gradle.plugin-publish` plugin and point it at the repository metadata.
