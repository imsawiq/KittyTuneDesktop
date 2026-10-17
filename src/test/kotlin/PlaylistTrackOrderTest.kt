package com.alananasss.kittytune.ui.library

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals

class PlaylistTrackOrderTest {
    private fun track(id: Long, title: String = "Track $id") =
        Track(id, title, null, null, null)

    @Test
    fun `oldest and recently added match likes native newest first order`() {
        val likes = listOf(track(3), track(2), track(1))

        assertEquals(listOf(1L, 2L, 3L), sortPlaylistTracks(likes, TrackSortBy.FIRST_ADDED, true).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), sortPlaylistTracks(likes, TrackSortBy.RECENTLY_ADDED, true).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), likes.map { it.id })
    }

    @Test
    fun `playlist native order and duplicate occurrences survive both directions`() {
        val playlist = listOf(track(1), track(2), track(1), track(3))

        assertEquals(playlist, sortPlaylistTracks(playlist, TrackSortBy.FIRST_ADDED, false))
        assertEquals(listOf(3L, 1L, 2L, 1L), sortPlaylistTracks(playlist, TrackSortBy.RECENTLY_ADDED, false).map { it.id })
    }

    @Test
    fun `filtered likes retain correct chronological direction`() {
        val filtered = listOf(track(3, "Match new"), track(2, "Other"), track(1, "Match old"))
            .filter { it.title.orEmpty().contains("Match") }

        assertEquals(listOf(1L, 3L), sortPlaylistTracks(filtered, TrackSortBy.FIRST_ADDED, true).map { it.id })
    }

    @Test
    fun `alphabetical sorting is independent of native direction`() {
        val tracks = listOf(track(1, "zebra"), track(2, "Alpha"))

        for (newestFirst in listOf(false, true)) {
            assertEquals(listOf(2L, 1L), sortPlaylistTracks(tracks, TrackSortBy.TITLE_AZ, newestFirst).map { it.id })
            assertEquals(emptyList(), sortPlaylistTracks(emptyList(), TrackSortBy.FIRST_ADDED, newestFirst))
        }
    }
}
