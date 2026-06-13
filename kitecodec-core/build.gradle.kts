import io.github.yuroyami.kitecodec.buildtools.BuildFFmpegTask
import io.github.yuroyami.kitecodec.buildtools.FFmpegPaths
import io.github.yuroyami.kitecodec.buildtools.TargetTriple
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.atomicfu)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xcontext-parameters",
        )
    }

    // Every Kotlin/Native target gets the same single consolidated cinterop. Each resolves its
    // own FFmpeg install via FFmpegPaths.resolve(...) — vendored static if available, else system.
    val knTargetMap = mapOf<KotlinNativeTarget, TargetTriple>(
        macosArm64()           to TargetTriple.MacosArm64,
        macosX64()             to TargetTriple.MacosX64,
        iosArm64()             to TargetTriple.IosArm64,
        iosSimulatorArm64()    to TargetTriple.IosSimulatorArm64,
        iosX64()               to TargetTriple.IosX64,
        linuxX64()             to TargetTriple.LinuxX64,
        linuxArm64()           to TargetTriple.LinuxArm64,
        mingwX64()             to TargetTriple.MingwX64,
        // Android NDK targets (LGPL FFmpeg profile w/ MediaCodec — see BuildFFmpegTask).
        // Vendored-only: run :kitecodec-core:buildFFmpegForAndroid<Abi> first.
        androidNativeArm64()   to TargetTriple.AndroidArm64,
        androidNativeArm32()   to TargetTriple.AndroidArm32,
        androidNativeX64()     to TargetTriple.AndroidX64,
    )

    knTargetMap.forEach { (target, triple) ->
        val paths = runCatching { FFmpegPaths.resolve(project, triple) }.getOrNull() ?: return@forEach
        target.compilations.getByName("main").cinterops {
            // One cinterop module for all six libav* libraries — this keeps AVCodec, AVFrame,
            // AVPacket etc. as a SINGLE Kotlin type across every binding (each cinterop module
            // otherwise generates its own duplicate copy of identical C types).
            create("ffmpeg") {
                defFile(project.file("src/nativeInterop/cinterop/ffmpeg.def"))
                includeDirs.allHeaders(paths.includeDir)
                extraOpts("-libraryPath", paths.libDir)
                compilerOpts("-I${paths.includeDir}")
            }
        }
        target.binaries.all {
            linkerOpts("-L${paths.libDir}")
            if (!paths.isStaticVendored && triple in setOf(TargetTriple.MacosArm64, TargetTriple.MacosX64)) {
                // Embed Homebrew rpath for dev convenience — release builds use static vendored libs.
                linkerOpts("-rpath", paths.libDir)
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Register a :buildFFmpegFor<Target> task per target. Users run these to populate
// native-libs/<target> with static .a files; subsequent Gradle syncs pick them up automatically.
TargetTriple.entries.forEach { triple ->
    tasks.register<BuildFFmpegTask>("buildFFmpegFor${triple.gradleSuffix}") {
        target = triple
    }
}

tasks.register("buildFFmpegForAll") {
    group = "kitecodec"
    description = "Cross-compile FFmpeg for every supported Kotlin/Native target."
    dependsOn(TargetTriple.entries.map { "buildFFmpegFor${it.gradleSuffix}" })
}
