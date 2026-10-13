package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderAndPicturesTest {
    private fun entry(id: String, published: Long, addedAt: Long, position: Int) = Entry(
        id = id, feedId = "f", guid = id, title = id, url = "", published = published, dated = true, author = "",
        imageUrl = "", excerpt = "", summary = "", categories = emptyList(), read = false, starred = false,
        addedAt = addedAt, position = position,
    )

    @Test
    fun `equal dates go by later sighting, then place in the feed, then id`() {
        val entries = listOf(
            entry("d", 100, 1, 0),
            entry("c", 100, 2, 1),
            entry("b", 100, 2, 0),
            entry("a", 200, 1, 5),
            entry("e", 100, 2, 1),
        )
        assertEquals(listOf("a", "b", "c", "e", "d"), entries.sortedWith(NEWEST_FIRST).map { item -> item.id })
        assertEquals(listOf("d", "e", "c", "b", "a"), entries.sortedWith(NEWEST_FIRST.reversed()).map { item -> item.id })
        assertEquals(entries.sortedWith(NEWEST_FIRST), entries.reversed().sortedWith(NEWEST_FIRST))
    }

    @Test
    fun `the same date, sighting and place fall back to guid and address before id`() {
        fun item(id: String, guid: String, url: String) =
            entry(id, 100, 1, 0).copy(guid = guid, url = url)
        val entries = listOf(item("a", "g2", "u1"), item("b", "g1", "u2"), item("c", "g1", "u1"))
        assertEquals(listOf("c", "b", "a"), entries.sortedWith(NEWEST_FIRST).map { found -> found.id })
    }

    @Test
    fun `a lead image the body already shows is hidden, however its address is written`() {
        val image = "https://i.example/x.png?w=1&#038;h=2"
        assertFalse(showsLeadImage(image, """<p><img src="https://i.example/x.png?w=1&amp;h=2"></p>"""))
        assertFalse(showsLeadImage("https://i.example/y.png", """<picture><source srcset="https://i.example/y.png 2x"></picture>"""))
        assertTrue(showsLeadImage(image, """<p><img src="https://i.example/other.png"></p>"""))
        assertTrue(showsLeadImage("https://i.example/z.png", ""))
        assertFalse(showsLeadImage("", "<p>text</p>"))
    }

    @Test
    fun `the largest srcset picture is chosen`() {
        assertEquals("https://i.example/l.png", bestSrcsetCandidate("https://i.example/s.png 320w, https://i.example/l.png 1280w, https://i.example/m.png 640w"))
        assertEquals("b.png", bestSrcsetCandidate("a.png 1x, b.png 2x"))
        assertEquals("", bestSrcsetCandidate(""))
    }
}
