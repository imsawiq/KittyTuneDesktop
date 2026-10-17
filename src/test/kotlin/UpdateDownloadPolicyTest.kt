import com.alananasss.kittytune.data.updates.UpdateDownloadPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateDownloadPolicyTest {
    @Test fun `ignored range starts a replacement instead of corrupting installer`() {
        assertEquals(0, UpdateDownloadPolicy.resumeOffset(1200, 200, null))
    }

    @Test fun `partial response must start at requested byte`() {
        assertEquals(1200, UpdateDownloadPolicy.resumeOffset(1200, 206, "bytes 1200-2999/3000"))
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadPolicy.resumeOffset(1200, 206, "bytes 0-2999/3000")
        }
    }

    @Test fun `malformed partial ranges are rejected before appending`() {
        for (range in listOf("bytes 1200-1000/3000", "bytes 1200-2999/2000", "bytes 1200-2999/**", "bytes 1200-2999/99999999999999999999999")) {
            assertFailsWith<IllegalArgumentException> { UpdateDownloadPolicy.resumeOffset(1200, 206, range) }
        }
        assertEquals(1200, UpdateDownloadPolicy.resumeOffset(1200, 206, "bytes 1200-2999/*"))
    }

    @Test fun `truncated or empty installers cannot be installed`() {
        assertFailsWith<IllegalArgumentException> { UpdateDownloadPolicy.requireComplete(999, 1000) }
        assertFailsWith<IllegalArgumentException> { UpdateDownloadPolicy.requireComplete(0, -1) }
        UpdateDownloadPolicy.requireComplete(1000, 1000)
    }

    @Test fun `release filename cannot escape update directory`() {
        for (name in listOf("../bad.msi", "..\\bad.msi", "C:bad.msi", "bad\n.msi")) {
            assertFailsWith<IllegalArgumentException> { UpdateDownloadPolicy.safeAssetName(name) }
        }
        assertEquals("KittyTune-1.4.3.msi", UpdateDownloadPolicy.safeAssetName("KittyTune-1.4.3.msi"))
    }
}
