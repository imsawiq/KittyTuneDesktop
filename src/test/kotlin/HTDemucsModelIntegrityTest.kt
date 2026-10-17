import com.alananasss.kittytune.audio.ai.karaoke.HTDemucsModelIntegrity
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HTDemucsModelIntegrityTest {
    @Test fun `partial or error response is never accepted as model weights`() {
        val file = Files.createTempFile("htdemucs-test-", ".part").toFile()
        try {
            file.writeText("Unauthorized")
            assertFailsWith<IllegalArgumentException> { HTDemucsModelIntegrity.requireValid(file) }
        } finally { file.delete() }
    }

    @Test fun `correct size alone cannot turn corrupt weights into a ready model`() {
        val file = Files.createTempFile("htdemucs-test-", ".part").toFile()
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(HTDemucsModelIntegrity.SIZE) }
            assertFailsWith<IllegalArgumentException> { HTDemucsModelIntegrity.requireValid(file) }
        } finally { file.delete() }
    }

    @Test fun `checksum is calculated over complete file bytes`() {
        val file = Files.createTempFile("htdemucs-test-", ".part").toFile()
        try {
            file.writeText("hello")
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HTDemucsModelIntegrity.sha256(file))
        } finally { file.delete() }
    }
}
