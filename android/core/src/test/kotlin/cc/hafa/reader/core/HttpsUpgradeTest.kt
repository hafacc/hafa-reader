package cc.hafa.reader.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The shared `https.json` fixture, and trying http feeds over https with a fake fetcher. */
class HttpsUpgradeTest {
    private val fixture: JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.classLoader.getResource("https.json")).readText()).jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.texts(name: String): List<String> = getValue(name).jsonArray.map { item -> item.jsonPrimitive.content }

    @Test
    fun `plain http addresses are told apart as the fixture says`() {
        for (case in fixture.getValue("plainHttp").jsonArray.map { item -> item.jsonObject }) {
            val input = case.text("input")
            assertEquals(case.getValue("output").jsonPrimitive.boolean, isPlainHttp(input), input)
        }
    }

    @Test
    fun `https addresses are written as http as the fixture says`() {
        for (case in fixture.getValue("plainAddress").jsonArray.map { item -> item.jsonObject }) {
            assertEquals(case.text("output"), plainAddress(case.text("input")), case.text("input"))
        }
    }

    @Test
    fun `addresses are written out as the fixture says`() {
        for (case in fixture.getValue("webAddress").jsonArray.map { item -> item.jsonObject }) {
            assertEquals(case.text("output"), webAddress(case.text("input")), case.text("name"))
        }
    }

    @Test
    fun `only public hosts are tried over https, as the fixture says`() {
        for (case in fixture.getValue("probeAllowed").jsonArray.map { item -> item.jsonObject }) {
            val input = case.text("input")
            assertEquals(case.getValue("output").jsonPrimitive.boolean, probeAllowed(input), input)
        }
    }

    @Test
    fun `answers settle a probe as the fixture says`() {
        for (case in fixture.getValue("settledAnswer").jsonArray.map { item -> item.jsonObject }) {
            val outcome = FetchOutcome(status = case.getValue("status").jsonPrimitive.int, insecure = case.getValue("insecure").jsonPrimitive.boolean)
            assertEquals(case.getValue("settled").jsonPrimitive.boolean, settledAnswer(outcome), case.text("name"))
        }
        assertFalse(settledAnswer(null))
    }

    @Test
    fun `feeds count as the same as the fixture says`() {
        for (case in fixture.getValue("sameFeed").jsonArray.map { item -> item.jsonObject }) {
            val known = case.getValue("known").jsonObject
            val found = case.getValue("found").jsonObject
            val links = found.texts("links")
            val parsed = ParsedFeed(
                title = found.text("title"),
                siteUrl = found.text("siteUrl"),
                iconUrl = "",
                entries = found.texts("guids").mapIndexed { index, guid ->
                    FeedEntry(guid, "", links.getOrElse(index) { "" }, 0, "", "", "", emptyList(), "")
                },
            )
            val knownFeed = KnownFeed(known.text("url"), known.text("title"), known.text("siteUrl"), known.texts("guids"), known.texts("links"))
            assertEquals(case.getValue("same").jsonPrimitive.boolean, sameFeed(knownFeed, parsed), case.text("name"))
        }
    }

    @Test
    fun `web addresses keep http and https and drop the rest`() {
        assertEquals("http://example.com/rss", webAddress(" HTTP://Example.com/rss "))
        assertEquals("https://example.com/", webAddress("https://example.com"))
        assertEquals("", webAddress("ftp://example.com/rss"))
        assertEquals("", webAddress(""))
    }

    private val known = KnownFeed("http://blog.example/rss", "Blog", "")
    private val feed = "<rss><channel><title>Blog</title><link>https://blog.example/</link><item><title>a</title><guid>a</guid></item></channel></rss>"

    @Test
    fun `the https address is fetched and a matching feed taken`() = runTest {
        val fetcher = FakeFetcher()
        fetcher.answers["https://blog.example/rss"] = FetchOutcome(status = 200, text = feed, url = "https://blog.example/rss")
        val match = probeHttps(known, fetcher)
        assertEquals(listOf("https://blog.example/rss"), fetcher.requests)
        assertEquals("https://blog.example/rss", match?.url)
        assertEquals("Blog", match?.feed?.title)
    }

    @Test
    fun `failures, redirects off https, non-feeds and other feeds are refused`() = runTest {
        val outcomes = listOf(
            FetchOutcome(status = 0),
            FetchOutcome(status = 404),
            FetchOutcome(status = 0, insecure = true),
            FetchOutcome(status = 200, text = "<html><body>Hello</body></html>"),
            FetchOutcome(status = 200, text = "<rss><channel><title>Parked</title><link>https://parking.example/</link></channel></rss>"),
        )
        for (outcome in outcomes) {
            assertNull(probeHttps(known, { _, _, _, _ -> outcome }), outcome.toString())
        }
    }

    @Test
    fun `nothing is fetched for an address that is not plain http`() = runTest {
        val fetcher = FakeFetcher()
        assertNull(probeHttps(known.copy(url = "https://blog.example/rss"), fetcher))
        assertNull(probeHttps(known.copy(url = "ftp://blog.example/rss"), fetcher))
        assertEquals(emptyList(), fetcher.requests)
    }

    @Test
    fun `work runs a few at a time and keeps its order`() = runTest {
        var running = 0
        var most = 0
        val results = mapConcurrently((1..7).toList(), 3) { item ->
            running += 1
            most = maxOf(most, running)
            delay(10L - item)
            running -= 1
            item * 10
        }
        assertEquals((1..7).map { item -> item * 10 }, results)
        assertEquals(3, most)
    }
}
