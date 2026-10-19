package com.alananasss.kittytune.ui.library

import com.alananasss.kittytune.domain.Track

enum class TrackSortBy {
    FIRST_ADDED, RECENTLY_ADDED, TITLE_AZ, ARTIST_AZ, CUSTOM
}

/** Likes and downloads arrive newest first; playlist rows retain their editable native order. */
internal fun sortPlaylistTracks(
    tracks: List<Track>,
    sortBy: TrackSortBy,
    newestFirst: Boolean,
): List<Track> = when (sortBy) {
    TrackSortBy.CUSTOM -> tracks
    TrackSortBy.FIRST_ADDED -> if (newestFirst) tracks.reversed() else tracks
    TrackSortBy.RECENTLY_ADDED -> if (newestFirst) tracks else tracks.reversed()
    TrackSortBy.TITLE_AZ -> tracks.sortedBy { it.title?.lowercase().orEmpty() }
    TrackSortBy.ARTIST_AZ -> tracks.sortedBy { it.user?.username?.lowercase().orEmpty() }
}
