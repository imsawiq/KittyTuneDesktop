package com.alananasss.kittytune.data.library

import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

class LikedTrackOrderTest {
    private fun track(id: Long) = Track(id, "Track $id", null, null, null, likedAt = id * 1_000)

    @Test fun `arrangement preserves chronology and includes new likes without stale entries`() {
        val likes = listOf(track(4), track(3), track(2), track(1))
        val arranged = orderLikedTracks(likes, listOf(1, 99, 3, 2, 1))
        assertEquals(listOf(4L, 1L, 3L, 2L), arranged.map { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), likes.map { it.id })
        assertEquals(likes.associate { it.id to it.likedAt }, arranged.associate { it.id to it.likedAt })
    }

    @Test fun `completed drag survives a new store instance and further sort changes`() = runBlocking {
        val prefs = NamedPrefs("qa_liked_order_${UUID.randomUUID()}")
        try {
            val likes = listOf(track(3), track(2), track(1))
            val store = LikedTrackOrder(prefs)
            store.move(likes, 1, 3)
            store.persist()
            val reopened = LikedTrackOrder(prefs)
            assertEquals(listOf(1L, 3L, 2L), orderLikedTracks(likes, reopened.ids.value).map { it.id })
            assertEquals(listOf(3L, 2L, 1L), likes.map { it.id })
            store.move(likes, 99, 3)
            assertEquals(listOf(1L, 3L, 2L), store.ids.value)
        } finally { prefs.clear() }
    }

    @Test fun `logout clears both current and persisted arrangement`() = runBlocking {
        val prefs = NamedPrefs("qa_liked_order_${UUID.randomUUID()}")
        try {
            val store = LikedTrackOrder(prefs)
            store.move(listOf(track(2), track(1)), 1, 2)
            store.persist()
            store.clear()
            store.persist()
            assertEquals(emptyList(), LikedTrackOrder(prefs).ids.value)
        } finally { prefs.clear() }
    }
}
