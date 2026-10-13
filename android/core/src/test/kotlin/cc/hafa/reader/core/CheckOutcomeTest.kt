package cc.hafa.reader.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckOutcomeTest {
    private val now = Instant.parse("2026-01-05T12:00:00Z").toEpochMilli()
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun rss(guids: List<String>, built: String = "Mon, 05 Jan 2026 12:00:00 GMT"): String =
        "<rss><channel><title>Feed</title><link>https://site.example/</link><lastBuildDate>$built</lastBuildDate>" +
            guids.joinToString("") { guid -> "<item><title>$guid</title><guid>$guid</guid><link>/$guid</link></item>" } +
            "</channel></rss>"

    private fun answer(text: String, status: Int = 200, etag: String = "", lastModified: String = "", hint: Long = 0, url: String = "https://feed.example/rss") =
        FetchOutcome(status = status, text = text, etag = etag, lastModified = lastModified, hint = hint, url = url)

    private val feedUrl = "https://feed.example/rss"

    @Test
    fun `a first look hands the whole feed on`() {
        val result = checkOutcome(FeedSchedule(), feedUrl, answer(rss(listOf("a", "b"))), now)
        assertEquals(listOf("a", "b"), result.pulled?.entries?.map { entry -> entry.guid })
        assertEquals(listOf("a", "b"), result.schedule.guids)
    }

    @Test
    fun `a changed body with nothing new is not handed on`() {
        val first = checkOutcome(FeedSchedule(), feedUrl, answer(rss(listOf("a", "b"))), now)
        val rebuilt = rss(listOf("a", "b"), "Mon, 05 Jan 2026 13:00:00 GMT")
        val second = checkOutcome(first.schedule, feedUrl, answer(rebuilt), now + hour)
        assertNull(second.pulled)
        assertEquals(sha256Hex(rebuilt), second.schedule.hash)
        val third = checkOutcome(second.schedule, feedUrl, answer(rss(listOf("c", "a"))), now + 2 * hour)
        assertEquals(2, third.pulled?.entries?.size)
    }

    @Test
    fun `an unchanged body still keeps the answer's new validators`() {
        val text = rss(listOf("a"))
        val first = checkOutcome(FeedSchedule(), feedUrl, answer(text, etag = "\"1\""), now)
        val second = checkOutcome(
            first.schedule,
            feedUrl,
            answer(text, etag = "\"2\"", lastModified = "Mon, 05 Jan 2026 13:00:00 GMT"),
            now + hour,
        )
        assertNull(second.pulled)
        assertEquals("\"2\"", second.schedule.etag)
        assertEquals("Mon, 05 Jan 2026 13:00:00 GMT", second.schedule.lastModified)
    }

    @Test
    fun `links resolve against the address after redirects`() {
        val result = checkOutcome(FeedSchedule(), "http://old.example/feed", answer(rss(listOf("a")), url = "https://new.example/rss"), now)
        assertEquals("https://new.example/a", result.pulled?.entries?.first()?.url)
    }

    @Test
    fun `a window of only new items is checked again soon`() {
        val first = checkOutcome(FeedSchedule(), feedUrl, answer(rss(listOf("a", "b"))), now)
        val outran = checkOutcome(first.schedule, feedUrl, answer(rss(listOf("c", "d"))), now + day)
        assertEquals(now + day + MIN_INTERVAL_MS, outran.schedule.nextFetch)
        val partly = checkOutcome(first.schedule, feedUrl, answer(rss(listOf("c", "a"))), now + day)
        assertTrue(partly.schedule.nextFetch > now + day + MIN_INTERVAL_MS)
    }

    @Test
    fun `a feed refused for not being on https says so`() {
        val refused = checkOutcome(FeedSchedule(), feedUrl, FetchOutcome(status = 0, insecure = true), now)
        assertEquals(FeedProblem.NotHttps.code, refused.schedule.problem)
        assertEquals(1, refused.schedule.failures)
    }

    @Test
    fun `a 304 is a quiet check and an unreadable body a failure`() {
        val known = FeedSchedule(lastChecked = now - hour, hash = "h", guids = listOf("a"))
        val quiet = checkOutcome(known, feedUrl, answer("", status = 304), now)
        assertNull(quiet.pulled)
        assertEquals(now, quiet.schedule.lastChecked)
        assertEquals(0, quiet.schedule.failures)
        val broken = checkOutcome(known, feedUrl, answer("<html>nope</html>"), now)
        assertNull(broken.pulled)
        assertEquals(1, broken.schedule.failures)
        assertEquals(FeedProblem.Unreadable.code, broken.schedule.problem)
        assertEquals(now - hour, broken.schedule.lastChecked)
    }

    @Test
    fun `new details alone are handed on`() {
        val first = checkOutcome(FeedSchedule(), feedUrl, answer(rss(listOf("a"))), now)
        val renamed = rss(listOf("a")).replace("<title>Feed</title>", "<title>New</title>")
        val second = checkOutcome(first.schedule, feedUrl, answer(renamed), now + hour)
        assertEquals("New", second.pulled?.title)
    }

    @Test
    fun `a failure waits at least as long as the site asks`() {
        val limited = checkOutcome(FeedSchedule(), feedUrl, answer("", status = 429, hint = 3 * hour), now)
        assertEquals(now + 3 * hour, limited.schedule.nextFetch)
        assertEquals("status:429", limited.schedule.problem)
        val plain = checkOutcome(FeedSchedule(), feedUrl, answer("", status = 500), now)
        assertEquals(now + retryInterval(0), plain.schedule.nextFetch)
    }
}
