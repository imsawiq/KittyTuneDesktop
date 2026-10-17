package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.CommentCollection

/** Refill the already visible window before replacing a differently sorted comment list. */
internal suspend fun loadCommentWindow(
    firstPage: CommentCollection,
    minimumCount: Int,
    nextPage: suspend (String) -> CommentCollection,
): CommentCollection {
    val comments = firstPage.collection.distinctBy { it.id }.toMutableList()
    val ids = comments.mapTo(mutableSetOf()) { it.id }
    val visitedPages = mutableSetOf<String>()
    var next = firstPage.next_href
    while (comments.size < minimumCount && next != null) {
        val url = next
        if (!visitedPages.add(url)) {
            next = null
            break
        }
        val page = nextPage(url)
        val added = page.collection.filter { ids.add(it.id) }
        comments.addAll(added)
        next = page.next_href
        if (added.isEmpty()) {
            next = null
            break
        }
    }
    return CommentCollection(comments, next)
}
