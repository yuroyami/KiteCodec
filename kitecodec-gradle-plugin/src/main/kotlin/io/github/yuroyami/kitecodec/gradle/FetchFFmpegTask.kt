package io.github.yuroyami.kitecodec.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads one prebuilt FFmpeg archive, verifies its SHA-256, and unpacks it into [destDir]
 * (an `{include,lib}` tree under the Gradle user-home cache). Idempotent: it skips when the static
 * libs are already present, so it costs nothing on subsequent builds.
 *
 * The archive is downloaded and unpacked via siblings of [destDir] that are only moved into place
 * once fully verified, so a killed build never leaves a half-populated cache directory behind.
 */
@DisableCachingByDefault(
    because = "the output lives in a shared Gradle-user-home cache and re-downloading beats " +
        "shuttling a multi-hundred-MB FFmpeg tree through the build cache",
)
abstract class FetchFFmpegTask : DefaultTask() {

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val sha256Url: Property<String>

    /**
     * SHA-256 pinned via `kitecodec { ffmpeg { pinnedSha256 } }`. When present it is authoritative
     * and [sha256Url] is never fetched.
     */
    @get:Input
    @get:Optional
    abstract val expectedSha256: Property<String>

    /**
     * `kitecodec.ffmpeg.allowUnverified` Gradle property (default false). When true, a checksum
     * that cannot be obtained downgrades from a build failure to a prominent warning.
     */
    @get:Input
    abstract val allowUnverified: Property<Boolean>

    /** `gradle.startParameter.isOffline`, captured at configuration time (configuration-cache safe). */
    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputDirectory
    abstract val destDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Download and verify a prebuilt FFmpeg build for one target."
    }

    @TaskAction
    fun run() {
        val dest = destDir.get().asFile
        if (isComplete(dest)) {
            logger.info("FFmpeg already present at $dest, skipping download.")
            return
        }
        dest.parentFile.mkdirs()
        // [destDir] lives in the shared Gradle user home, so it is NOT owned by this build: several
        // subprojects wire a task each for the same target, and a second Gradle invocation in
        // another terminal writes the very same directory. Without a cross-process lock the
        // deleteRecursively()/move() below can pull the tree out from under a link task that is
        // reading it. Hold an exclusive lock on a sibling file for the whole fetch, and re-check
        // completeness once inside. The process we waited on has very likely just done the work.
        withDirectoryLock(dest) {
            if (isComplete(dest)) {
                logger.info("FFmpeg was populated at $dest while waiting for the cache lock.")
                return@withDirectoryLock
            }
            if (offline.get()) {
                throw GradleException(
                    "kitecodec: Gradle is running in offline mode and the FFmpeg build for this target " +
                        "is not in the cache ($dest). Re-run without --offline to download " +
                        "${downloadUrl.get()}.",
                )
            }
            // Siblings of dest so the final rename stays on one filesystem (atomic where supported).
            val archive = File(dest.parentFile, "${dest.name}.zip.part")
            val staging = File(dest.parentFile, "${dest.name}.staging")
            try {
                logger.lifecycle("[KiteCodec] downloading FFmpeg: ${downloadUrl.get()}")
                withRetry("download ${downloadUrl.get()}") { download(downloadUrl.get(), archive) }
                verifyChecksum(archive)
                staging.deleteRecursively()
                staging.mkdirs()
                unzip(archive, staging)
                check(staging.resolve("lib/libavformat.a").exists()) {
                    "The FFmpeg archive did not contain lib/libavformat.a (unpacked to $staging). " +
                        "The Release asset layout may be wrong; expected {include,lib} at the archive root."
                }
                // Written last, inside staging, so it only ever becomes visible together with a
                // fully unpacked tree: the marker is what makes "already present" mean "complete"
                // rather than "some files happen to be there".
                staging.resolve(MARKER).writeText(downloadUrl.get())
                dest.deleteRecursively()
                moveIntoPlace(staging, dest)
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }
    }

    /** A cache entry counts as usable only when the marker AND the libs it vouches for are there. */
    private fun isComplete(dest: File): Boolean =
        dest.resolve(MARKER).isFile && dest.resolve("lib/libavformat.a").exists()

    /**
     * Runs [block] holding an exclusive OS-level lock on `<dest>.lock`, so concurrent Gradle
     * processes (and tasks from sibling subprojects) serialise on this one cache directory.
     */
    private fun <T> withDirectoryLock(dest: File, block: () -> T): T {
        val lockFile = File(dest.parentFile, "${dest.name}.lock")
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                val lock = channel.lock()
                try {
                    return block()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun verifyChecksum(archive: File) {
        val pinned = expectedSha256.orNull?.trim()?.takeIf { it.isNotEmpty() }
        val expected = pinned ?: fetchPublishedChecksum() ?: return // only reachable when unverified is allowed
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GradleException(
                "FFmpeg checksum mismatch for ${downloadUrl.get()}\n" +
                    "  expected${if (pinned != null) " (pinned)" else ""}: $expected\n" +
                    "  actual:   $actual",
            )
        }
    }

    /**
     * Fetches the `.sha256` published next to the asset. Returns null only when the checksum is
     * unobtainable and `kitecodec.ffmpeg.allowUnverified=true`. Otherwise the build fails, because
     * an unverified native binary must never be linked into a consumer app silently.
     */
    private fun fetchPublishedChecksum(): String? {
        val fetched = runCatching { withRetry("fetch ${sha256Url.get()}") { fetchText(sha256Url.get()) } }
            .getOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.isNotEmpty() }
        if (fetched != null) return fetched
        if (allowUnverified.get()) {
            logger.warn(
                """
                |[KiteCodec] WARNING: could not obtain a SHA-256 for ${downloadUrl.get()}.
                |[KiteCodec] The archive's integrity is UNVERIFIED because kitecodec.ffmpeg.allowUnverified=true.
                |[KiteCodec] Its native code will be linked into your binaries as-is. Prefer pinning the
                |[KiteCodec] checksum via kitecodec { ffmpeg { pinnedSha256.put(<asset>, <sha256>) } }.
                """.trimMargin(),
            )
            return null
        }
        throw GradleException(
            "kitecodec: could not obtain the SHA-256 for ${downloadUrl.get()} " +
                "(no readable ${sha256Url.get()}), so the archive cannot be verified. " +
                "Pin the checksum via kitecodec { ffmpeg { pinnedSha256.put(<asset>, <sha256>) } }, " +
                "or — if you accept linking an unverified binary — set the Gradle property " +
                "kitecodec.ffmpeg.allowUnverified=true.",
        )
    }

    /** Retries [action] up to [MAX_ATTEMPTS] times with linear backoff on transient IO failures. */
    private fun <T> withRetry(what: String, action: () -> T): T {
        var lastFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return action()
            } catch (e: IOException) {
                lastFailure = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    val backoffMs = RETRY_BACKOFF_MS * (attempt + 1)
                    logger.info("[KiteCodec] attempt ${attempt + 1} to $what failed (${e.message}), retrying in ${backoffMs}ms")
                    Thread.sleep(backoffMs)
                }
            }
        }
        throw GradleException("kitecodec: failed to $what after $MAX_ATTEMPTS attempts", lastFailure)
    }

    private fun download(url: String, into: File) {
        open(url).inputStream.use { input ->
            into.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun fetchText(url: String): String =
        open(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Opens an HTTPS connection, following redirects (GitHub -> object store). HTTPS only. */
    private fun open(url: String): HttpURLConnection {
        var current = URI(url)
        repeat(MAX_REDIRECTS) {
            val uri = current
            // Scheme is null for a relative URI; `equals` on a platform type would NPE, and the
            // point of the check is to refuse anything that is not plainly https anyway.
            check("https".equals(uri.scheme, ignoreCase = true)) {
                "Refusing non-HTTPS URL while fetching $url: $current"
            }
            val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
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
                    // Location may legitimately be relative (RFC 7231 §7.1.2). Resolve it against
                    // the URL we just requested instead of parsing it as an absolute URI and
                    // failing on a null scheme. The https check above still gates the result.
                    current = uri.resolve(location)
                }
                else -> {
                    conn.disconnect()
                    throw IOException("HTTP $code fetching $current")
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

    /** Renames the fully verified [staging] tree to [dest], atomically where the OS supports it. */
    private fun moveIntoPlace(staging: File, dest: File) {
        try {
            Files.move(staging.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), dest.toPath())
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 1_000L

        /** Written last into the staged tree; its presence is what marks a cache entry complete. */
        const val MARKER = ".kitecodec-complete"
    }
}
