package io.github.yuroyami.kitecodec

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Finds and loads `libkitecodec_jni` for a desktop JVM.
 *
 * Three sources, tried in this order:
 * 1. `-Dkitecodec.jni.path=/abs/path` wins outright. The JNI boundary tests use it to load a
 *    deliberately broken library, so it must beat everything else.
 * 2. `System.loadLibrary`, which reads `java.library.path`. This is how a packager (jpackage, a
 *    distro package, a Gradle run task) supplies its own copy without unpacking anything.
 * 3. The copy bundled in this jar under `native/<os>-<arch>/`, extracted once to a temp file.
 *    This is what makes `implementation("...:kitecodec-core")` enough for a desktop app.
 */
internal actual object JniLibrary {
    actual val isAndroid: Boolean = false

    @Volatile private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val override = System.getProperty("kitecodec.jni.path")
            if (!override.isNullOrBlank()) System.load(override) else loadBundledOrInstalled()
            loaded = true
        }
    }

    private fun loadBundledOrInstalled() {
        val installed = runCatching { System.loadLibrary(LIBRARY_NAME) }
        if (installed.isSuccess) return
        val resource = "/$RESOURCE_ROOT/$platformDirectory/$fileName"
        val stream = JniLibrary::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "kitecodec_jni is neither on java.library.path nor bundled at $resource. " +
                    "This build of kitecodec-core carries no native library for $platformDirectory; " +
                    "supply one with -Dkitecodec.jni.path or -Djava.library.path.",
            )
        val extracted = stream.use { input ->
            val directory = Files.createTempDirectory("kitecodec-jni").toFile().apply { deleteOnExit() }
            File(directory, fileName).also { target ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target.deleteOnExit()
            }
        }
        System.load(extracted.absolutePath)
    }

    /** `macos-arm64`, `linux-x64`, `windows-x64`: the same names the vendored FFmpeg trees use. */
    private val platformDirectory: String by lazy {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val os = when {
            "mac" in name || "darwin" in name -> "macos"
            "win" in name -> "windows"
            else -> "linux"
        }
        val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
        "$os-$cpu"
    }

    private val fileName: String by lazy {
        when {
            platformDirectory.startsWith("macos") -> "lib$LIBRARY_NAME.dylib"
            platformDirectory.startsWith("windows") -> "$LIBRARY_NAME.dll"
            else -> "lib$LIBRARY_NAME.so"
        }
    }

    private const val LIBRARY_NAME = "kitecodec_jni"
    private const val RESOURCE_ROOT = "kitecodec-native"
}
