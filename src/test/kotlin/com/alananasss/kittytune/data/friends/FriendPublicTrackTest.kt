package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FriendPublicTrackTest {
    private fun track() = Track(7, "Song", "https://cdn.example.org/art.jpg", 120_000, User(3, "Artist", null),
        permalinkUrl = "https://soundcloud.com/artist/song")

    @Test fun `private and local tracks are never shared and secret artwork is omitted`() {
        assertNotNull(FriendPublicTrack.of(track()))
        assertNull(FriendPublicTrack.of(track().copy(sharing = "private")))
        assertNull(FriendPublicTrack.of(track().copy(secretToken = "s-secret")))
        assertNull(FriendPublicTrack.of(track().copy(source = "local")))
        assertNull(FriendPublicTrack.of(track().copy(permalinkUrl = "file:///C:/Users/person/music.wav")))
        assertNull(FriendPublicTrack.of(track().copy(artworkUrl = "https://cdn.example.org/art?token=secret"))!!.artwork)
    }

    @Test fun `private address credentials query tokens and secret track paths are refused`() {
        for (url in listOf("http://localhost/song", "http://127.0.0.1/song", "http://192.168.1.1/song",
            "http://[::1]/song", "http://home.local/song", "http://localhost./song", "http://home.local./song",
            "https://name:password@example.org/song",
            "https://soundcloud.com/a/b?secret_token=s-secret", "https://soundcloud.com/a/b/s-secret")) {
            assertNull(FriendPublicTrack.publicUrl(url), url)
        }
        assertNotNull(FriendPublicTrack.publicUrl("https://www.youtube.com/watch?v=abcdefghijk", true))
        assertNotNull(FriendPublicTrack.publicUrl("https://music.youtube.com/watch?v=abcdefghijk", true))
    }

    @Test fun `normalized Unicode URLs and unknown credential bearing source strings cannot enlarge or leak into frames`() {
        val url = "https://example.org/" + "猫".repeat(900)
        assertNull(FriendPublicTrack.publicUrl(url))
        assertNull(FriendPublicTrack.of(track().copy(source = "file:C:/Users/private")))
        assertNull(FriendPublicTrack.of(track().copy(source = "soundcloud:secret=token")))
        assertNull(FriendPublicTrack.of(track().copy(sharing = " PRIVATE ")))
    }

    @Test fun `public DTO does not carry private source fields and retains intended artist`() {
        val safe = FriendPublicTrack.of(track().copy(title = "Someone - Song",
            publisherMetadata = Track.PublisherMetadata(artist = "Actual artist", albumTitle = "Album")))!!
        assertEquals(safe, FriendPublicTrack.of(safe.toTrack()))
        assertNull(safe.toTrack().secretToken)
        assertNull(safe.toTrack().media)
        assertEquals("Album", safe.toTrack().publisherMetadata?.albumTitle)
    }
}
