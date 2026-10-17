package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.Comment
import com.alananasss.kittytune.domain.CommentCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommentWindowTest {
    private fun page(ids: List<Long>, next: String? = null) = CommentCollection(
        ids.map { Comment(it, "Comment $it", "2026-10-17T00:00:00Z", null, null) },
        next,
    )

    @Test
    fun `sorting refills the loaded window before returning replacement`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = loadCommentWindow(page(listOf(1, 2), "page2"), 5) { url ->
            requests.add(url)
            when (url) {
                "page2" -> page(listOf(3, 4), "page3")
                "page3" -> page(listOf(5, 6), "page4")
                else -> error("Fetched past the loaded window")
            }
        }

        assertEquals(listOf("page2", "page3"), requests)
        assertEquals((1L..6L).toList(), result.collection.map { it.id })
        assertEquals("page4", result.next_href)
    }

    @Test
    fun `shorter result exhausts pagination and keeps all available comments`() = runBlocking {
        val result = loadCommentWindow(page(listOf(1, 2), "last"), 20) {
            page(listOf(2, 3))
        }

        assertEquals(listOf(1L, 2L, 3L), result.collection.map { it.id })
        assertNull(result.next_href)
    }

    @Test
    fun `duplicate only and repeated pages terminate without infinite requests`() = runBlocking {
        var requests = 0
        val duplicateResult = loadCommentWindow(page(listOf(1), "duplicate"), 20) {
            requests++
            page(listOf(1), "duplicate")
        }
        assertEquals(1, requests)
        assertNull(duplicateResult.next_href)

        requests = 0
        val repeatedResult = loadCommentWindow(page(listOf(1), "repeated"), 20) {
            requests++
            page(listOf(2), "repeated")
        }
        assertEquals(1, requests)
        assertNull(repeatedResult.next_href)
    }

    @Test
    fun `empty result completes without requesting nonexistent next page`() = runBlocking {
        val result = loadCommentWindow(page(emptyList()), 20) { error("No next page") }

        assertEquals(emptyList(), result.collection)
        assertNull(result.next_href)
    }

    @Test
    fun `pagination failures and cancellation never return partial replacement`() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> {
            loadCommentWindow(page(listOf(1), "next"), 20) { error("Network failed") }
        }
        assertFailsWith<CancellationException> {
            loadCommentWindow(page(listOf(1), "next"), 20) { throw CancellationException() }
        }
    }
}
