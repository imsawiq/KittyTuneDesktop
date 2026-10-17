package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenerEligibilityTest {
    private fun track(id: Long = 10L, source: String = "soundcloud", artist: String = "Artist") =
        Track(id, "Track", null, 90_000L, User(1L, artist, null), source = source)

    @Test fun `local audio never borrows a namesake Spotify audience`() {
        assertNull(listenerLookupArtist(track(source = "local"), "Неизвестный исполнитель"))
        assertNull(listenerLookupArtist(track(id = -5L), "Неизвестный исполнитель"))
    }

    @Test fun `missing and localized unknown credits do not trigger artist lookup`() {
        assertNull(listenerLookupArtist(track(artist = "Неизвестный исполнитель"), "Неизвестный исполнитель"))
        assertNull(listenerLookupArtist(track(artist = "Unknown Artist"), "Неизвестный исполнитель"))
        assertNull(listenerLookupArtist(track(artist = ""), "Неизвестный исполнитель"))
    }

    @Test fun `real provider credits including negative YouTube IDs remain eligible`() {
        assertEquals("Artist", listenerLookupArtist(track(), "Неизвестный исполнитель"))
        assertEquals("Artist", listenerLookupArtist(track(id = -10L, source = "youtube"), "Неизвестный исполнитель"))
    }
}
