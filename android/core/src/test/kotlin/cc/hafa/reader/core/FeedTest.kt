package cc.hafa.reader.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FeedTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)) { "missing fixture $name" }.readText()

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `parses RSS 2_0 with content, media and relative links`() {
        val feed = parseFeed(fixture("rss.xml"), "https://example.com/rss")
        assertEquals("Example Blog", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals("https://example.com/icon.png", feed.iconUrl)
        assertEquals(2, feed.entries.size)
        val (first, second) = feed.entries
        assertEquals("post-1", first.guid)
        assertEquals("https://example.com/posts/first", first.url)
        assertEquals("Ada", first.author)
        assertEquals("Short & sweet summary", first.summary)
        assertEquals(listOf("Elections", "World"), first.categories)
        assertEquals(millis("2026-10-06T09:14:00Z"), first.published)
        assertContains(first.html, "Full <b>body</b>")
        assertEquals("https://example.com/img/a.png", first.imageUrl)
        assertEquals("https://example.com/posts/second", second.guid)
        assertEquals("https://example.com/thumb.jpg", second.imageUrl)
        assertEquals(0L, second.published)
    }

    @Test
    fun `parses Atom`() {
        val feed = parseFeed(fixture("atom.xml"), "https://atom.example.org/feed.xml")
        assertEquals("Atom Example", feed.title)
        assertEquals("https://atom.example.org/", feed.siteUrl)
        assertEquals("https://atom.example.org/atom.ico", feed.iconUrl)
        val entry = feed.entries.first()
        assertEquals("tag:atom.example.org,2026:1", entry.guid)
        assertEquals("https://atom.example.org/entries/1", entry.url)
        assertEquals("Grace", entry.author)
        assertEquals("<p>Hello</p>", entry.html)
    }

    @Test
    fun `parses JSON Feed`() {
        val feed = parseFeed(fixture("feed.json"), "https://json.example.net/feed.json")
        assertEquals("JSON Example", feed.title)
        val entry = feed.entries.first()
        assertEquals("42", entry.guid)
        assertEquals("https://json.example.net/42.png", entry.imageUrl)
        assertEquals("Douglas", entry.author)
    }

    @Test
    fun `rejects text that is not a feed`() {
        assertFailsWith<NotAFeedException> { parseFeed("<html><body>hi</body></html>", "https://x.test/") }
        assertFailsWith<NotAFeedException> { parseFeed("{not json", "https://x.test/") }
    }

    @Test
    fun `ids are stable and differ by input`() {
        val feed = feedId("https://example.com/rss")
        assertEquals(64, feed.length)
        assertEquals(entryId(feed, "a"), entryId(feed, "a"))
        assertNotEquals(entryId(feed, "a"), entryId(feed, "b"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test
    fun `only web addresses are kept as links and images`() {
        val source = """<rss><channel><title>t</title><link>javascript:alert(1)</link>
            <item><title>x</title><link>javascript:alert(1)</link><guid>g1</guid></item>
            <item><title>y</title><link>https://ok.example/y</link><enclosure url="data:image/png;base64,AA" type="image/png"/></item>
            </channel></rss>"""
        val feed = parseFeed(source, "https://ok.example/feed")
        assertEquals("", feed.siteUrl)
        assertEquals("", feed.entries[0].url)
        assertEquals("https://ok.example/y", feed.entries[1].url)
        assertEquals("", feed.entries[1].imageUrl)
    }

    @Test
    fun `a site link survives an Atom link placed before it, and xhtml bodies are not empty`() {
        val rss = """<rss xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>t</title>
            <atom:link href="https://w.example/feed" rel="self"/><link>https://w.example/</link></channel></rss>"""
        assertEquals("https://w.example/", parseFeed(rss, "https://w.example/feed").siteUrl)
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello</p><p>there</p></div></content></entry></feed>"""
        assertContains(parseFeed(atom, "https://a.example/feed").entries[0].html, "Hello")
    }

    @Test
    fun `items that identify nothing are dropped and untitled ones keep a stable id`() {
        val source = """<rss><channel><title>t</title><item><description>no id at all</description></item>
            <item><title>Same title</title></item></channel></rss>"""
        val first = parseFeed(source, "https://x.example/feed")
        val second = parseFeed(source, "https://x.example/feed")
        assertEquals(1, first.entries.size)
        assertEquals(second.entries[0].guid, first.entries[0].guid)
    }

    @Test
    fun `an impossible character code does not spoil the feed`() {
        val source = """<rss><channel><title>t</title><item><title>x</title><guid>1</guid>
            <description><![CDATA[bad &#99999999; and &#x110000; but fine &#233;]]></description></item></channel></rss>"""
        val feed = parseFeed(source, "https://x.example/feed")
        assertEquals(1, feed.entries.size)
        assertContains(feed.entries[0].summary, "fine é")
    }

    @Test
    fun `Atom and JSON summaries are the feed's own summary, not the whole body`() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
            <content type="html">&lt;p&gt;long body&lt;/p&gt;</content></entry></feed>"""
        assertEquals("", parseFeed(atom, "https://a.example/feed").entries[0].summary)
    }

    @Test
    fun `dates come from the usual fields and from common time zone names`() {
        val source = """<rss xmlns:a10="http://www.w3.org/2005/Atom"><channel><title>t</title>
            <item><title>a</title><guid>a</guid><a10:updated>2026-10-06T09:14:00Z</a10:updated></item>
            <item><title>b</title><guid>b</guid><pubDate>Tue, 06 Oct 2026 11:14:00 CEST</pubDate></item>
            <item><title>c</title><guid>c</guid><pubDate>sometime last week</pubDate></item>
            </channel></rss>"""
        val (first, second, third) = parseFeed(source, "https://x.example/feed").entries
        assertEquals(millis("2026-10-06T09:14:00Z"), first.published)
        assertEquals(millis("2026-10-06T09:14:00Z"), second.published)
        assertEquals(0L, third.published)
    }

    @Test
    fun `dates in the forms browsers read`() {
        val utc = ZoneOffset.UTC
        val expected = millis("2026-10-06T09:14:00Z")
        val forms = listOf(
            "Tue, 06 Oct 2026 09:14:00 GMT",
            "Tue, 06 Oct 2026 09:14:00 +0000",
            "Tue, 6 Oct 2026 05:14:00 EDT",
            "Tue, 06 Oct 2026 02:14 PDT",
            "06 Oct 2026 10:14:00 CET",
            "Tue, 06 Oct 26 09:14:00 UT",
            "2026-10-06T09:14:00Z",
            "2026-10-06T11:14:00+02:00",
            "2026-10-06T09:14:00.000Z",
            "2026-10-06 09:14:00",
            "October 6, 2026 9:14 am",
            "Tue Oct 06 2026 11:14:00 GMT+0200 (Central European Summer Time)",
            "10/06/2026 09:14:00",
        )
        for (form in forms) {
            assertEquals(expected, parseFeedDate(form, utc), form)
        }
        assertEquals(millis("2026-10-06T00:00:00Z"), parseFeedDate("2026-10-06", ZoneOffset.ofHours(5)))
        assertEquals(millis("2026-10-06T04:14:00Z"), parseFeedDate("2026-10-06T09:14:00", ZoneOffset.ofHours(5)))
        assertEquals(0L, parseFeedDate("", utc))
        assertEquals(0L, parseFeedDate("2026-13-45", utc))
        assertEquals(0L, parseFeedDate("06 Oct 2026 at noon", utc))
    }

    @Test
    fun `a JSON Feed item with a numeric id keeps it as its guid`() {
        val source = """{"version":"https://jsonfeed.org/version/1.1","items":[{"id":42,"content_text":"x"}]}"""
        val feed = parseFeed(source, "https://j.example/feed.json")
        assertEquals(1, feed.entries.size)
        assertEquals("42", feed.entries[0].guid)
    }

    @Test
    fun `JSON Feed plain text is shown as text, with its line breaks`() {
        val source = """{"version":"https://jsonfeed.org/version/1.1","items":[{"id":"1","content_text":"Use <b> for bold\nand & more"}]}"""
        assertEquals("Use &lt;b&gt; for bold<br>and &amp; more", parseFeed(source, "https://j.example/feed.json").entries[0].html)
    }

    @Test
    fun `JSON that is not a JSON Feed is not a feed`() {
        assertFailsWith<NotAFeedException> { parseFeed("""{"title":"api","data":[1,2]}""", "https://j.example/api") }
        assertEquals(emptyList(), parseFeed("""{"items":[]}""", "https://j.example/feed.json").entries)
    }

    @Test
    fun `Atom XHTML content keeps its markup and all its text`() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello <a href="/x">link</a> &amp; more</p></div></content>
            <media:content xmlns:media="http://search.yahoo.com/mrss/" url="https://a.example/i.png" medium="image"/></entry></feed>"""
        val entry = parseFeed(atom, "https://a.example/feed").entries.first()
        assertContains(entry.html, """<p>Hello <a href="/x">link</a> &amp; more</p>""")
        assertEquals("https://a.example/i.png", entry.imageUrl)
    }

    @Test
    fun `image addresses taken from article HTML have their entities decoded`() {
        val rss = """<rss><channel><title>t</title>
            <item><guid>1</guid><description><![CDATA[<img src="https://i.example/x.png?w=1&amp;h=2">]]></description></item>
            <item><guid>2</guid><description>&lt;img src="https://i.example/y.png?w=1&amp;amp;h=2&amp;#38;q=3"&gt;</description></item>
            <item><guid>3</guid><enclosure type="image/png" url="https://i.example/e.png?w=1&amp;h=2"/></item>
            </channel></rss>"""
        assertEquals(
            listOf("https://i.example/x.png?w=1&h=2", "https://i.example/y.png?w=1&h=2&q=3", "https://i.example/e.png?w=1&h=2"),
            parseFeed(rss, "https://s.example/feed").entries.map { item -> item.imageUrl },
        )
    }

    @Test
    fun `numeric character references are decoded in titles, authors and topics but not guids`() {
        val rss = """<rss><channel><title>Tom&#8217;s &amp;amp; blog</title>
            <item><title>It&#8217;s &#x2014; done</title><guid>post&#8217;1</guid><author>Ren&#233;e</author><category>Q&#38;A</category></item>
            <item><title>Only&#8217;title</title></item>
            </channel></rss>"""
        val feed = parseFeed(rss, "https://s.example/feed")
        assertEquals("Tom\u2019s &amp; blog", feed.title)
        val (first, second) = feed.entries
        assertEquals("It\u2019s \u2014 done", first.title)
        assertEquals("Ren\u00e9e", first.author)
        assertEquals(listOf("Q&A"), first.categories)
        assertEquals("post&#8217;1", first.guid)
        assertEquals("Only\u2019title", second.title)
        assertEquals("Only&#8217;title", second.guid)
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>A&#8217;s</title><entry><id>1</id><title>B&#8217;s</title><author><name>C&#8217;s</name></author></entry></feed>"""
        val parsedAtom = parseFeed(atom, "https://a.example/feed")
        assertEquals("A\u2019s", parsedAtom.title)
        assertEquals("B\u2019s", parsedAtom.entries[0].title)
        assertEquals("C\u2019s", parsedAtom.entries[0].author)
    }

    @Test
    fun `an entry id comes from the guid as written, references and all`() {
        val rss = """<rss><channel><title>t</title><item><title>x</title><guid>a&#8217;b</guid></item></channel></rss>"""
        val guid = parseFeed(rss, "https://s.example/feed").entries.single().guid
        assertEquals("a&#8217;b", guid)
        assertEquals(entryId("feed", "a&#8217;b"), entryId("feed", guid))
        assertNotEquals(entryId("feed", "a\u2019b"), entryId("feed", guid))
    }

    @Test
    fun `plain http addresses in a feed are upgraded to https, other schemes dropped, and guids from links are upgraded too`() {
        val rss = """<rss><channel><title>t</title><link>http://s.example/</link><image><url>http://s.example/i.png</url></image>
            <item><title>a</title><link>http://s.example/a</link><enclosure type="image/png" url="http://s.example/e.png"/></item>
            <item><title>b</title><link>javascript:alert(1)</link>
            <description><![CDATA[<img src="http://s.example/x.png">]]></description></item>
            <item><title>c</title><guid>c</guid><link>/c</link></item>
            </channel></rss>"""
        val feed = parseFeed(rss, "http://s.example/feed")
        assertEquals("https://s.example/", feed.siteUrl)
        assertEquals("https://s.example/i.png", feed.iconUrl)
        val (first, second, third) = feed.entries
        assertEquals("https://s.example/a", first.url)
        assertEquals("https://s.example/a", first.guid)
        assertEquals("https://s.example/e.png", first.imageUrl)
        assertEquals("", second.url)
        assertEquals("https://s.example/x.png", second.imageUrl)
        assertEquals("https://s.example/c", third.url)
    }

    @Test
    fun `links, pictures and the site from a feed are https only`() {
        val rss = """<rss><channel><title>t</title><link>http://s.example/</link><image><url>http://s.example/i.png</url></image>
            <item><guid>1</guid><title>x</title><link>http://s.example/1</link><enclosure type="image/png" url="http://s.example/e.png"/></item>
            </channel></rss>"""
        val feed = parseFeed(rss, "https://s.example/feed")
        assertEquals("https://s.example/", feed.siteUrl)
        assertEquals("https://s.example/i.png", feed.iconUrl)
        assertEquals("https://s.example/1", feed.entries[0].url)
        assertEquals("https://s.example/e.png", feed.entries[0].imageUrl)
    }

    @Test
    fun `plain text drops tags and decodes entities`() {
        assertEquals("a & b <c> é", plainText("<p>a &amp; b</p> &lt;c&gt; &#xe9;"))
    }
}
