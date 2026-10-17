import com.alananasss.kittytune.data.local.PlaybackQueueSnapshot
import com.alananasss.kittytune.domain.Track
import com.google.gson.Gson
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaybackQueueSnapshotTest {
    private val gson = Gson()

    private fun track(id: Long) = Track(id, "Track $id", null, 120_000L, null)

    @Test
    fun `restart preserves shuffled upcoming order and can recover original order`() {
        val original = (1L..5L).map(::track)
        val displayed = listOf(original[0], original[3], original[1], original[4], original[2])

        val restored = roundTrip(PlaybackQueueSnapshot.capture(displayed, original))

        assertEquals(listOf(1L, 4L, 2L, 5L, 3L), restored.tracks.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), restored.originalTracks().map { it.id })
        assertSame(restored.tracks[1], restored.originalTracks()[3])
    }

    @Test
    fun `restart remembers the second occurrence of an identical track`() {
        val first = track(7L)
        val second = first.copy()
        val other = track(8L)
        val original = listOf(first, other, second)
        val displayed = listOf(second, first, other)

        val restored = roundTrip(PlaybackQueueSnapshot.capture(displayed, original))

        assertEquals(1, restored.currentIndex(savedIndex = 1, trackId = 7L))
        assertEquals(listOf(1, 2, 0), restored.originalOrder)
        assertSame(restored.tracks[1], restored.originalTracks()[0])
        assertSame(restored.tracks[0], restored.originalTracks()[2])
    }

    @Test
    fun `legacy array caches still restore without reordering`() {
        val original = listOf(track(3L), track(1L), track(2L))

        val restored = PlaybackQueueSnapshot.decode(gson.toJson(original), gson)

        assertEquals(original, restored.tracks)
        assertEquals(original, restored.originalTracks())
        assertEquals(1, restored.currentIndex(savedIndex = -1, trackId = 1L))
    }

    @Test
    fun `stale saved index falls back to matching track`() {
        val snapshot = PlaybackQueueSnapshot(listOf(track(1L), track(2L)))

        assertEquals(1, snapshot.currentIndex(savedIndex = 0, trackId = 2L))
        assertEquals(0, snapshot.currentIndex(savedIndex = 10, trackId = 1L))
        assertEquals(-1, snapshot.currentIndex(savedIndex = 0, trackId = 9L))
    }

    @Test
    fun `invalid original permutation keeps the complete displayed queue`() {
        val tracks = listOf(track(1L), track(2L), track(3L))

        assertEquals(tracks, PlaybackQueueSnapshot(tracks, listOf(0, 0, 2)).originalTracks())
        assertEquals(tracks, PlaybackQueueSnapshot(tracks, listOf(0, 1, 9)).originalTracks())
        assertEquals(tracks, PlaybackQueueSnapshot(tracks, listOf(0)).originalTracks())
    }

    @Test
    fun `same track instance repeated twice retains both occurrences`() {
        val repeated = track(1L)
        val other = track(2L)
        val original = listOf(repeated, other, repeated)
        val displayed = listOf(repeated, repeated, other)

        val restored = roundTrip(PlaybackQueueSnapshot.capture(displayed, original))

        assertEquals(listOf(1L, 2L, 1L), restored.originalTracks().map { it.id })
        assertEquals(listOf(0, 2, 1), restored.originalOrder)
    }

    private fun roundTrip(snapshot: PlaybackQueueSnapshot): PlaybackQueueSnapshot =
        PlaybackQueueSnapshot.decode(gson.toJson(snapshot), gson)
}
