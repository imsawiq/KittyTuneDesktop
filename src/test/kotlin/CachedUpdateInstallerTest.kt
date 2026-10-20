package com.alananasss.kittytune.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachedUpdateInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `finds completed download in updates child of cache`() {
        val cache = temporaryFolder.newFolder("cache")
        val installer = File(cache, "updates/update_KittyTune-1.4.4.MSI")
        installer.parentFile.mkdirs()
        installer.writeText("fixture")

        assertEquals(installer, findCachedUpdateInstaller(cache, listOf(".msi")))
    }

    @Test fun `newest installer wins across current and legacy locations`() {
        val cache = temporaryFolder.newFolder("cache")
        val legacy = File(cache, "update_old.msi").apply { writeText("fixture"); setLastModified(1_000) }
        val current = File(cache, "updates/update_new.msi").apply {
            parentFile.mkdirs(); writeText("fixture"); setLastModified(2_000)
        }
        assertEquals(current, findCachedUpdateInstaller(cache, listOf(".msi")))
        legacy.setLastModified(3_000)
        assertEquals(legacy, findCachedUpdateInstaller(cache, listOf(".msi")))
    }

    @Test fun `ignores directories unsupported extensions and unrelated nested caches`() {
        val cache = temporaryFolder.newFolder("cache")
        File(cache, "update_directory.msi").mkdirs()
        File(cache, "updates").mkdirs()
        File(cache, "updates/update_incomplete.msi.part").writeText("fixture")
        File(cache, "updates/KittyTune.msi").writeText("fixture")
        File(cache, "updates/update_linux.deb").writeText("fixture")
        File(cache, "other/update_hidden.msi").apply { parentFile.mkdirs(); writeText("fixture") }

        assertNull(findCachedUpdateInstaller(cache, listOf(".msi")))
        assertNull(findCachedUpdateInstaller(File(cache, "missing"), listOf(".msi")))
    }

    @Test fun `cleanup candidates include partial downloads in both cache locations only`() {
        val cache = temporaryFolder.newFolder("cache")
        val legacy = File(cache, "update_legacy.msi").apply { writeText("fixture") }
        val partial = File(cache, "updates/update_current.msi.part").apply {
            parentFile.mkdirs(); writeText("fixture")
        }
        File(cache, "updates/cover.png").writeText("unrelated")
        File(cache, "updates/update_directory.msi").mkdirs()
        File(cache, "other/update_unrelated.msi").apply { parentFile.mkdirs(); writeText("unrelated") }

        assertEquals(setOf(legacy, partial), cachedUpdateFiles(cache).toSet())
    }
}
