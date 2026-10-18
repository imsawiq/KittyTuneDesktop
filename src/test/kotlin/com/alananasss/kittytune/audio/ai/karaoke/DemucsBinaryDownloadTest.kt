package com.alananasss.kittytune.audio.ai.karaoke

import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DemucsBinaryDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `assets match supported architectures and reject incompatible binaries`() {
        assertNotNull(DemucsBinaryDownload.artifact("Windows 11", "amd64"))
        assertNotNull(DemucsBinaryDownload.artifact("Mac OS X", "aarch64"))
        assertNotNull(DemucsBinaryDownload.artifact("Linux", "x86_64"))
        assertNull(DemucsBinaryDownload.artifact("Windows 11", "aarch64"))
        assertNull(DemucsBinaryDownload.artifact("Linux", "arm64"))
    }

    @Test fun `zip extracts only the executable to the fixed destination`() {
        val payload = ByteArray(1_000_001) { (it % 239).toByte() }
        val archive = temporary.newFile("cli.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("release/demucs.exe")); zip.write(payload); zip.closeEntry()
        }
        val destination = File(temporary.newFolder("install"), "cli.part")
        DemucsBinaryDownload.extract(archive, destination, "demucs.exe")
        assertContentEquals(payload, destination.readBytes())
        assertFalse(File(temporary.root, "outside.txt").exists())
    }

    @Test fun `tar extracts a regular executable without creating archive paths`() {
        val payload = ByteArray(1_000_001) { (it % 239).toByte() }
        val archive = temporary.newFile("cli.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { output ->
            output.write(header("nested/demucs", payload.size.toLong()))
            output.write(payload)
            output.write(ByteArray(((512 - payload.size % 512) % 512)))
            output.write(ByteArray(1024))
        }
        val destination = temporary.newFile("cli.part")
        DemucsBinaryDownload.extract(archive, destination, "demucs")
        assertContentEquals(payload, destination.readBytes())
        assertFalse(File(temporary.root, "nested").exists())
    }

    @Test fun `oversized tar entry is rejected before opening destination`() {
        val archive = temporary.newFile("huge.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { it.write(header("demucs", 512L * 1024 * 1024)) }
        val destination = File(temporary.root, "untouched")
        assertFailsWith<IllegalArgumentException> { DemucsBinaryDownload.extract(archive, destination, "demucs") }
        assertFalse(destination.exists())
    }

    @Test fun `symlink cannot stand in for executable`() {
        val archive = temporary.newFile("link.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { output ->
            output.write(header("demucs", 0).apply { this[156] = '2'.code.toByte() })
            output.write(ByteArray(1024))
        }
        val destination = File(temporary.root, "untouched")
        assertFailsWith<IllegalArgumentException> { DemucsBinaryDownload.extract(archive, destination, "demucs") }
        assertFalse(destination.exists())
    }

    private fun header(name: String, size: Long) = ByteArray(512).apply {
        name.toByteArray(Charsets.US_ASCII).copyInto(this)
        (size.toString(8).padStart(11, '0') + "\u0000").toByteArray(Charsets.US_ASCII).copyInto(this, 124)
        this[156] = '0'.code.toByte()
    }
}
