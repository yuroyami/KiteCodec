package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

internal const val DEFAULT_FFMPEG_VERSION = "n8.0"
internal const val DEFAULT_RELEASE_REPO = "yuroyami/KiteCodec"

/**
 * Provisions the FFmpeg binaries KiteCodec links against, so consumers do not have to build FFmpeg
 * from source. Apply it alongside the Kotlin Multiplatform plugin: for every Kotlin/Native target it
 * fetches (or locates) the matching FFmpeg build and adds the `-L<libdir>` linker flag, wiring the
 * fetch task in ahead of the link step.
 *
 * KiteCodec's published klib contains no FFmpeg bytes; this plugin supplies them at the consumer's
 * build time, which also keeps the FFmpeg licence (LGPL / GPL) cleanly separate from KiteCodec's own
 * Apache-2.0 artifact.
 */
class KiteCodecPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("kitecodec", KiteCodecExtension::class.java)
        ext.ffmpeg.version.convention(DEFAULT_FFMPEG_VERSION)
        ext.ffmpeg.source.convention(FFmpegSource.Prebuilt)
        ext.ffmpeg.license.convention(FFmpegLicense.LGPL)
        ext.ffmpeg.repo.convention(DEFAULT_RELEASE_REPO)

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { knTarget ->
                val triple = KiteCodecTarget.forKonan(knTarget.konanTarget.name) ?: return@configureEach
                wireTarget(project, ext, knTarget, triple)
            }
        }
    }

    private fun wireTarget(
        project: Project,
        ext: KiteCodecExtension,
        knTarget: KotlinNativeTarget,
        triple: KiteCodecTarget,
    ) {
        val source = ext.ffmpeg.source.get()
        // Android has no GPL flavour, so its targets always link the LGPL MediaCodec build.
        val license = if (triple.android) FFmpegLicense.LGPL else ext.ffmpeg.license.get()
        val version = ext.ffmpeg.version.get()

        val libDir: File = when (source) {
            FFmpegSource.Prebuilt -> {
                val cache = project.gradle.gradleUserHomeDir
                    .resolve("caches/kitecodec/ffmpeg/$version/${license.id}/${triple.triple}")
                val asset = "ffmpeg-$version-${license.id}-${triple.triple}.zip"
                val downloadUrl =
                    "https://github.com/${ext.ffmpeg.repo.get()}/releases/download/ffmpeg-$version/$asset"

                val fetch = project.tasks.register(
                    "fetchFFmpeg${triple.name}${license.name}",
                    FetchFFmpegTask::class.java,
                ) { task ->
                    task.downloadUrl.set(downloadUrl)
                    task.sha256Url.set("$downloadUrl.sha256")
                    task.destDir.set(project.layout.dir(project.provider { cache }))
                }

                // The final native link for this target must wait for the binaries to be present.
                knTarget.binaries.all { binary -> binary.linkTaskProvider.configure { it.dependsOn(fetch) } }
                cache.resolve("lib")
            }

            FFmpegSource.System -> systemLibDir(project, triple)
                ?: error(
                    "kitecodec: source = System but no system FFmpeg was found for ${triple.triple}. " +
                        "Install it (brew install ffmpeg / apt install the libav* dev packages) or " +
                        "switch to FFmpegSource.Prebuilt.",
                )

            FFmpegSource.BuildFromSource -> error(
                "kitecodec: source = BuildFromSource is only available inside the KiteCodec checkout, " +
                    "which ships the :buildFFmpegFor<Target> tasks. In a consumer project use Prebuilt " +
                    "(the default) or System.",
            )
        }

        knTarget.binaries.all { binary -> binary.linkerOpts("-L${libDir.absolutePath}") }
    }

    /** Minimal system-FFmpeg resolution for dev convenience (macOS Homebrew / Linux). */
    private fun systemLibDir(project: Project, triple: KiteCodecTarget): File? = when (triple) {
        KiteCodecTarget.MacosArm64, KiteCodecTarget.MacosX64 -> {
            val configured = project.providers.gradleProperty("kitecodec.macos.homebrew.prefix").orNull
            sequenceOf(configured, "/opt/homebrew", "/usr/local")
                .filterNotNull()
                .map(::File)
                .firstOrNull { it.resolve("include/libavformat/avformat.h").exists() }
                ?.resolve("lib")
        }
        KiteCodecTarget.LinuxX64, KiteCodecTarget.LinuxArm64 -> {
            sequenceOf(
                "/usr/lib", "/usr/local/lib",
                "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
            ).map(::File).firstOrNull { it.resolve("libavformat.so").exists() }
        }
        else -> null
    }
}
