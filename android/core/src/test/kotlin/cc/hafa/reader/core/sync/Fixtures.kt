package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed

/** A feed for tests, with any fields replaced through [copy]. */
fun sampleFeed(): Feed =
    Feed(
        id = "feed-1",
        url = "https://example.com/feed",
        title = "Example",
        siteUrl = "https://example.com/",
        icon = "",
        renamed = false,
        folder = "",
        error = "",
        updatedAt = 100,
    )

/** An entry for tests, with any fields replaced through [copy]. */
fun sampleEntry(): Entry =
    Entry(
        id = "entry-1",
        feedId = "feed-1",
        guid = "guid-1",
        title = "Title",
        url = "https://example.com/1",
        published = 1000,
        dated = true,
        author = "",
        imageUrl = "",
        excerpt = "Start",
        summary = "",
        categories = listOf("News"),
        read = false,
        starred = false,
        addedAt = 2000,
        position = 0,
        bodyHash = "",
        updatedAt = 2000,
    )
