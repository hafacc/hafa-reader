package cc.hafa.reader.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveTest {
    private fun known(address: String): List<String> = knownFeedUrls(address.toHttpUrl())

    @Test
    fun `services with a feed address pattern`() {
        assertEquals(
            listOf("https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"),
            known("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"),
        )
        assertEquals(
            listOf("https://www.youtube.com/feeds/videos.xml?playlist_id=PL123"),
            known("https://www.youtube.com/playlist?list=PL123"),
        )
        assertEquals(listOf("https://www.reddit.com/r/rust/.rss"), known("https://www.reddit.com/r/rust/"))
        assertEquals(
            listOf("https://github.com/rust-lang/rust/releases.atom", "https://github.com/rust-lang/rust/commits.atom"),
            known("https://github.com/rust-lang/rust"),
        )
        assertEquals(listOf("https://medium.com/feed/@someone"), known("https://medium.com/@someone"))
        assertEquals(listOf("https://writer.medium.com/feed"), known("https://writer.medium.com/"))
        assertEquals(listOf("https://staff.tumblr.com/rss"), known("https://staff.tumblr.com/"))
        assertEquals(
            listOf("https://bsky.app/profile/someone.bsky.social/rss"),
            known("https://bsky.app/profile/someone.bsky.social"),
        )
        assertEquals(listOf("https://mastodon.social/@Gargron.rss"), known("https://mastodon.social/@Gargron"))
    }

    @Test
    fun `pages with no known pattern give nothing`() {
        assertEquals(emptyList(), known("https://example.com/about"))
    }

    @Test
    fun `finds feed links in a page head`() {
        val html = """<head>
            <link rel="stylesheet" href="/a.css">
            <link rel="alternate" type="application/rss+xml" title="Posts" href="/posts.xml">
            <link href='https://other.example/atom?x=1&amp;y=2' type='application/atom+xml' rel='alternate'>
          </head>"""
        assertEquals(
            listOf("https://example.com/posts.xml", "https://other.example/atom?x=1&y=2"),
            discoverFeedLinks(html, "https://example.com/blog/"),
        )
    }

    @Test
    fun `candidates start with the address itself and drop repeats`() {
        val found = feedCandidates("https://example.com/")
        assertEquals("https://example.com/", found[0])
        assertContains(found, "https://example.com/feed")
        assertEquals(found.size, found.toSet().size)
        val reddit = feedCandidates("https://www.reddit.com/r/rust/")
        assertEquals("https://www.reddit.com/r/rust/.rss", reddit[1])
    }

    @Test
    fun `finds a site's pictures, touch icons first and web addresses only`() {
        val html = """<head>
            <link rel="shortcut icon" href="/old.ico">
            <link rel="icon" type="image/png" href="/icon.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
            <link rel="icon" href="/logo.svg">
            <link rel="icon" href="javascript:alert(1)">
            <link rel="stylesheet" href="/a.css">
          </head>"""
        assertEquals(
            listOf(
                "https://example.com/touch.png",
                "https://example.com/logo.svg",
                "https://example.com/icon.png",
                "https://example.com/old.ico",
            ),
            discoverIconLinks(html, "https://example.com/blog/"),
        )
        assertEquals(emptyList(), discoverIconLinks("<head></head>", "https://example.com/"))
    }

    @Test
    fun `finds feed links whose attributes are not quoted`() {
        val html = "<head><link rel=alternate type=application/rss+xml href=/index.xml></head>"
        assertEquals(listOf("https://example.com/index.xml"), discoverFeedLinks(html, "https://example.com/"))
    }

    @Test
    fun `discovery upgrades http to https and drops other schemes`() {
        val html = """<head>
            <link rel="alternate" type="application/rss+xml" href="http://s.example/rss">
            <link rel="alternate" type="application/atom+xml" href="javascript:alert(1)">
            <link rel="icon" href="http://s.example/i.png">
          </head>"""
        assertEquals(listOf("https://s.example/rss"), discoverFeedLinks(html, "https://s.example/"))
        assertEquals(listOf("https://s.example/i.png"), discoverIconLinks(html, "https://s.example/"))
        val found = feedCandidates("http://s.example/blog")
        assertEquals("https://s.example/blog", found[0])
        assertTrue(found.all { address -> address.startsWith("https://") })
        assertEquals(emptyList(), feedCandidates("ftp://s.example/"))
    }

    @Test
    fun `http addresses move to https and their default port with them`() {
        assertEquals("https://a.example/feed", httpsOnly("http://a.example/feed"))
        assertEquals("https://a.example:8080/feed", httpsOnly("http://a.example:8080/feed"))
        assertEquals("https://a.example/feed", httpsOnly("https://a.example/feed"))
    }

    @Test
    fun `favicons and site addresses come from the origin`() {
        assertEquals("https://example.com:8443/favicon.ico", faviconFor("https://example.com:8443/a/b"))
        assertEquals("https://example.com/", siteAddress("https://example.com/feed.xml"))
        assertEquals("", faviconFor("not an address"))
    }
}
