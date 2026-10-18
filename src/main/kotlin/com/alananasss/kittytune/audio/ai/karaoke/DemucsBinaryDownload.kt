package com.alananasss.kittytune.audio.ai.karaoke

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Release assets verified against GitHub's v0.3.4 asset digests, before extracting executable code. */
internal object DemucsBinaryDownload {
    // https://api.github.com/repos/nikhilunni/demucs-rs/releases/tags/v0.3.4
    internal data class Artifact(val name: String, val size: Long, val sha256: String) {
        val url get() = "https://github.com/nikhilunni/demucs-rs/releases/download/v0.3.4/$name"
    }

    fun artifact(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): Artifact? {
        val system = os.lowercase()
        val cpu = arch.lowercase()
        return when {
            system.contains("win") && cpu in listOf("amd64", "x86_64") -> Artifact(
                "demucs-x86_64-pc-windows-msvc.zip", 11_036_578,
                "67e77186295a00758df0b760f3345fd0b9081b328f923ecc35307f6190f472d1")
            system.contains("mac") && cpu in listOf("aarch64", "arm64") -> Artifact(
                "demucs-aarch64-apple-darwin.tar.gz", 9_505_724,
                "bb6a6346392892ef3f172df0b60d97f4cb54aa38f7306ec129a60d786d261225")
            system.contains("linux") && cpu in listOf("amd64", "x86_64") -> Artifact(
                "demucs-x86_64-unknown-linux-gnu.tar.gz", 10_650_858,
                "38bb1edb812bdf7a22116cb71b0e3473662b12064b3d8c8e7a6a56507c9a867d")
            else -> null
        }
    }

    suspend fun install(
        client: OkHttpClient,
        dataDirectory: File,
        activeCall: AtomicReference<Call?>,
        progress: (Long, Long) -> Unit,
    ): Long {
        val asset = requireNotNull(artifact()) { "Demucs CLI is unavailable for this operating system and architecture" }
        val directory = File(dataDirectory, "bin").apply { mkdirs() }
        val archive = Files.createTempFile(directory.toPath(), "demucs-", ".archive").toFile()
        val executable = Files.createTempFile(directory.toPath(), "demucs-", ".part").toFile()
        val call = client.newCall(Request.Builder().url(asset.url).header("User-Agent", "KittyTuneDesktop").build())
        activeCall.set(call)
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code} downloading Demucs CLI" }
                require(response.body.contentLength().let { it < 0 || it == asset.size }) { "Unexpected Demucs archive size" }
                archive.outputStream().buffered().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= asset.size) { "Demucs archive exceeds expected size" }
                            output.write(buffer, 0, count)
                            progress(received, asset.size)
                        }
                    }
                }
            }
            require(archive.length() == asset.size && HTDemucsModelIntegrity.sha256(archive) == asset.sha256) {
                "Demucs archive checksum does not match"
            }
            val name = if (asset.name.endsWith(".zip")) "demucs.exe" else "demucs"
            extract(archive, executable, name)
            currentCoroutineContext().ensureActive()
            check(executable.setExecutable(true, true)) { "Cannot make Demucs CLI executable" }
            val target = File(directory, name).toPath()
            try {
                Files.move(executable.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(executable.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
            return asset.size
        } finally {
            activeCall.compareAndSet(call, null)
            archive.delete()
            executable.delete()
        }
    }

    /** Extract only the named executable to a fixed destination, never archive paths or symlinks. */
    internal fun extract(archive: File, destination: File, name: String) {
        if (name.endsWith(".exe")) {
            ZipFile(archive).use { zip ->
                val entry = zip.entries().asSequence().take(4096).firstOrNull {
                    !it.isDirectory && it.name.substringAfterLast('/') == name
                } ?: error("Demucs executable is missing from archive")
                require(entry.size in 1_000_001L..MAX_EXECUTABLE_BYTES) { "Invalid Demucs executable size" }
                zip.getInputStream(entry).use { copyExecutable(it, destination, entry.size) }
            }
        } else {
            GZIPInputStream(archive.inputStream().buffered()).use { input ->
                var expanded = 0L
                repeat(4096) {
                    val header = input.readNBytes(512)
                    require(header.size == 512 && header.any { it != 0.toByte() }) { "Demucs executable is missing from archive" }
                    val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8)
                        ?: error("Invalid Demucs tar header")
                    require(size in 0..MAX_EXPANDED_BYTES - expanded) { "Demucs archive is too large" }
                    val entryName = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                    if (entryName.substringAfterLast('/') == name && header[156].toInt() in listOf(0, '0'.code)) {
                        require(size > 1_000_000L) { "Invalid Demucs executable size" }
                        copyExecutable(input, destination, size)
                        return
                    }
                    val padding = (512 - size % 512) % 512
                    input.skipNBytes(size + padding)
                    expanded += size + padding
                }
                error("Demucs archive contains too many entries")
            }
        }
    }

    private fun copyExecutable(input: InputStream, destination: File, size: Long) {
        require(size <= MAX_EXECUTABLE_BYTES) { "Demucs executable is too large" }
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                require(count > 0) { "Demucs executable is truncated" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private const val MAX_EXECUTABLE_BYTES = 64L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
}
