package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads one prebuilt FFmpeg archive, verifies its SHA-256, and unpacks it into [destDir]
 * (an `{include,lib}` tree under the Gradle user-home cache). Idempotent: it skips when the static
 * libs are already present, so it costs nothing on subsequent builds.
 */
abstract class FetchFFmpegTask : DefaultTask() {

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val sha256Url: Property<String>

    @get:OutputDirectory
    abstract val destDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Download and verify a prebuilt FFmpeg build for one target."
    }

    @TaskAction
    fun run() {
        val dest = destDir.get().asFile
        if (dest.resolve("lib/libavformat.a").exists()) {
            logger.info("FFmpeg already present at $dest, skipping download.")
            return
        }
        dest.mkdirs()
        val archive = File.createTempFile("kitecodec-ffmpeg", ".zip")
        try {
            logger.lifecycle("[KiteCodec] downloading FFmpeg: ${downloadUrl.get()}")
            download(downloadUrl.get(), archive)
            verifyChecksum(archive)
            unzip(archive, dest)
        } finally {
            archive.delete()
        }
        check(dest.resolve("lib/libavformat.a").exists()) {
            "The FFmpeg archive did not contain lib/libavformat.a (unpacked to $dest). " +
                "The Release asset layout may be wrong; expected {include,lib} at the archive root."
        }
    }

    private fun verifyChecksum(archive: File) {
        val expected = runCatching { fetchText(sha256Url.get()) }
            .getOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.isNotEmpty() }
        if (expected == null) {
            logger.warn(
                "[KiteCodec] no .sha256 alongside ${downloadUrl.get()}; skipping the integrity check.",
            )
            return
        }
        val actual = sha256(archive)
        check(actual.equals(expected, ignoreCase = true)) {
            "FFmpeg checksum mismatch for ${downloadUrl.get()}\n" +
                "  expected: $expected\n  actual:   $actual"
        }
    }

    private fun download(url: String, into: File) {
        open(url).inputStream.use { input ->
            into.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun fetchText(url: String): String =
        open(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Opens an HTTP(S) connection, following redirects across protocols (GitHub -> object store). */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "kitecodec-gradle-plugin")
            }
            when (val code = conn.responseCode) {
                in 200..299 -> return conn
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    requireNotNull(location) { "Redirect with no Location header fetching $current" }
                    current = location
                }
                else -> {
                    conn.disconnect()
                    error("HTTP $code fetching $current")
                }
            }
        }
        error("Too many redirects fetching $url")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unzips into [dest], rejecting any entry that would escape it (zip-slip guard). */
    private fun unzip(archive: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                check(out == root || out.path.startsWith(root.path + File.separator)) {
                    "Refusing zip entry outside the target directory: ${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
