import com.alananasss.kittytune.data.artist.ReleaseCatalog
import com.alananasss.kittytune.data.spotify.SpotifyAlbum
import com.alananasss.kittytune.data.spotify.SpotifyArtistRef
import com.alananasss.kittytune.domain.Playlist
import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import com.alananasss.kittytune.ui.profile.ArtistRelease
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseCatalogTest {
    private val artist = User(id = 1L, username = "Artist", avatarUrl = null)
    private fun track(id: Long, name: String, album: String? = null) = Track(
        id = id, title = name, artworkUrl = null, durationMs = 1000L, user = artist,
        publisherMetadata = album?.let { Track.PublisherMetadata(albumTitle = it) },
    )
    private fun record(id: Long, title: String, tracks: List<Track>? = null) = Playlist(
        id = id, title = title, artworkUrl = null, calculatedArtworkUrl = null, trackCount = tracks?.size, user = artist, tracks = tracks,
    )

    @Test
    fun `album suppresses all member tracks even when their titles differ from the album`() {
        val first = track(1, "Opening")
        val second = track(2, "Finale")
        val album = record(10, "Album", listOf(first, second))
        val releases = ArtistRelease.latestOf(listOf(first, second), listOf(album), 10)
        assertEquals(listOf(ArtistRelease.Record(album)), releases)
    }

    @Test
    fun `album metadata suppresses members when catalog tracks have not loaded`() {
        assertTrue(ReleaseCatalog.belongsTo(track(1, "Opening", "Album"), record(10, "Album")))
        val otherArtist = record(10, "Album").copy(user = artist.copy(username = "Another artist"))
        assertFalse(ReleaseCatalog.belongsTo(track(1, "Opening", "Album"), otherArtist))
    }

    @Test
    fun `a standalone song is retained when only its title matches a record`() {
        val song = track(1, "Album")
        val album = record(10, "Album")
        assertEquals(2, ArtistRelease.latestOf(listOf(song), listOf(album), 10).size)
    }

    @Test
    fun `provider album id removes members without using album title heuristics`() {
        val song = track(1, "Different title").copy(source = "spotify", publisherMetadata = Track.PublisherMetadata(albumId = "real-id"))
        val album = record(10, "Album").copy(urn = "spotify:album:real-id", permalink = "real-id")
        assertTrue(ReleaseCatalog.belongsTo(song, album))
        assertFalse(ReleaseCatalog.belongsTo(song, album.copy(urn = "spotify:album:other", permalink = "other")))
    }

    @Test
    fun `repeated records are shown once and chronological release order wins`() {
        val old = record(10, "Old").copy(releaseDate = "2025-01-01")
        val recent = track(1, "Single").copy(releaseDate = "2026-10-17")
        val releases = ArtistRelease.latestOf(listOf(recent), listOf(old, old), 10)
        assertEquals(listOf(ArtistRelease.Song(recent), ArtistRelease.Record(old)), releases)
    }

    @Test
    fun `spotify album preserves actual artist type and total track count`() {
        val playlist = SpotifyAlbum(id = "album", name = "Album", artists = listOf(SpotifyArtistRef("artist", "Actual artist")), releaseType = "EP", totalTracks = 7).toPlaylist()
        assertEquals("Actual artist", playlist.user?.username)
        assertEquals("ep", playlist.setType)
        assertEquals(7, playlist.trackCount)
        assertEquals("spotify:album:album", playlist.urn)
    }
}
