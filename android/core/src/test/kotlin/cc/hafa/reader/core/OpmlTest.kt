package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpmlTest {
    @Test
    fun `lists feeds with their folders`() {
        val source = """<?xml version="1.0"?><opml version="2.0"><head><title>x</title></head><body>
            <outline text="News" title="News">
              <outline type="rss" text="One" xmlUrl="https://one.example/feed"/>
            </outline>
            <outline type="rss" title="Two" text="Ignored" xmlUrl="two.example/rss"/>
            <outline type="rss" xmlUrl="https://three.example/feed"/>
            </body></opml>"""
        assertEquals(
            listOf(
                OpmlFeed("https://one.example/feed", "One", "News"),
                OpmlFeed("two.example/rss", "Two", ""),
                OpmlFeed("https://three.example/feed", "", ""),
            ),
            parseOpml(source),
        )
        assertEquals(emptyList(), parseOpml("not opml"))
    }

    @Test
    fun `attribute names in any case are read, and a damaged file gives what it can`() {
        val source = """<opml><body><outline TEXT="Tech"><outline XMLURL="https://one.example/feed" Title="One">
            <outline xmlurl="https://two.example/feed" title="Two"></body>"""
        assertEquals(
            listOf(OpmlFeed("https://one.example/feed", "One", "Tech"), OpmlFeed("https://two.example/feed", "Two", "Tech")),
            parseOpml(source),
        )
    }

    @Test
    fun `an empty title falls back to the text`() {
        val source = """<opml><body><outline title="" text="Tech"><outline title="" text="One" xmlUrl="https://one.example/feed"/></outline></body></opml>"""
        assertEquals(listOf(OpmlFeed("https://one.example/feed", "One", "Tech")), parseOpml(source))
    }
}

class BuildOpmlTest {
    private val feeds = listOf(
        OpmlFeed("https://a.example/feed?x=1&y=2", "A & \"B\" <c>", ""),
        OpmlFeed("https://b.example/feed", "B", "Tech"),
        OpmlFeed("https://c.example/feed", "C", "News"),
    )

    @Test
    fun `groups become folder outlines, loose feeds follow, attributes are escaped`() {
        val text = buildOpml(feeds)
        assertContains(text, """text="A &amp; &quot;B&quot; &lt;c&gt;"""")
        assertContains(text, """xmlUrl="https://a.example/feed?x=1&amp;y=2"""")
        assertTrue(text.indexOf("News") < text.indexOf("Tech"))
        assertTrue(text.indexOf("Tech") < text.indexOf("a.example"))
    }

    @Test
    fun `an export reads back as the same feeds`() {
        assertEquals(feeds.sortedBy { feed -> feed.url }, parseOpml(buildOpml(feeds)).sortedBy { feed -> feed.url })
    }
}
