package com.alananasss.kittytune.data.artist.portrait

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNull

class ArtistPortraitCutoutsTest {
    @Test fun `local and malformed URLs never initialize model inference or download`() = runBlocking {
        for (url in listOf("file:///private/photo.png", "data:image/png;base64,abcd", "C:/photo.png", "", "https://name:secret@example.org/a.png")) {
            assertNull(ArtistPortraitCutouts.cutout(url))
        }
    }
}
