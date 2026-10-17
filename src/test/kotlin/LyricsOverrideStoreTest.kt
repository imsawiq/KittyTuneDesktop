import com.alananasss.kittytune.ui.player.LyricsOverrideStore
import com.alananasss.kittytune.ui.player.LyricsSearchTrack
import com.alananasss.kittytune.ui.player.ManualLyricsSearch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsOverrideStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(directory: File) = LyricsOverrideStore(
        read = { key -> File(directory, key).takeIf { it.exists() }?.readText() },
        write = { key, value -> File(directory, key).writeText(value) },
    )

    @Test
    fun `import raw content survives reopening storage for a negative local track id`() {
        val directory = temporaryFolder.newFolder()
        val raw = "[00:01.00]Первый куплет\n[00:02.00]Second line"
        val original = assertNotNull(ManualLyricsSearch.localResult(
            LyricsSearchTrack("-123", "Track", "Artist", null, 180_000L), raw,
        ))

        store(directory).put(-123L, original)
        val restored = assertNotNull(store(directory).get(-123L))

        assertEquals(original, restored)
        assertEquals(raw, restored.rawContent)
        assertEquals("LOCAL", restored.provider)
        assertNull(store(directory).get(123L))
    }

    @Test
    fun `another track cannot replace the first tracks imported content`() {
        val directory = temporaryFolder.newFolder()
        val first = assertNotNull(ManualLyricsSearch.localResult(
            LyricsSearchTrack("10", "First", "Artist", null, 100_000L), "First lyrics",
        ))
        val second = first.copy(id = "20", rawContent = "Second lyrics")

        store(directory).put(10L, first)
        store(directory).put(20L, second)

        assertEquals(first, store(directory).get(10L))
        assertEquals(second, store(directory).get(20L))
    }

    @Test
    fun `online selection does not replace the separately saved import`() {
        val directory = temporaryFolder.newFolder()
        val imported = assertNotNull(ManualLyricsSearch.localResult(
            LyricsSearchTrack("-10", "Track", "Artist", null, 100_000L), "Imported lyrics",
        ))
        val online = imported.copy(id = "online", provider = "GENIUS", rawContent = "Online lyrics")

        store(directory).put(-10L, imported)
        store(directory).put(-10L, online)

        assertEquals(online, store(directory).get(-10L))
        assertEquals(imported, store(directory).getImported(-10L))
    }

    @Test
    fun `legacy local override remains available as an import`() {
        val directory = temporaryFolder.newFolder()
        val imported = assertNotNull(ManualLyricsSearch.localResult(
            LyricsSearchTrack("10", "Track", "Artist", null, 100_000L), "Imported lyrics",
        ))
        store(directory).put(10L, imported)
        File(directory, "imported_10").delete()

        assertEquals(imported, store(directory).getImported(10L))
        store(directory).put(10L, imported.copy(provider = "GENIUS"))
        assertEquals(imported, store(directory).getImported(10L))
    }

    @Test
    fun `corrupt override is ignored so automatic lookup remains available`() {
        val directory = temporaryFolder.newFolder()
        File(directory, "override_10").writeText("{broken")

        assertNull(store(directory).get(10L))
    }
}
