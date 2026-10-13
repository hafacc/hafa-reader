package cc.hafa.reader.core

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpFeedFetcherTest {
    private val server = MockWebServer()
    // The local test server speaks plain http, which the app itself never uses.
    private val fetcher = HttpFeedFetcher(OkHttpClient(), httpsOnly = false)

    @BeforeTest
    fun start() {
        server.start()
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    @Test
    fun `validators are sent, and one a request cannot carry is dropped`() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(304, fetcher.fetch(server.url("/feed").toString(), "\"v1\"", "", FETCH_TIMEOUT_MS).status)
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(304, fetcher.fetch(server.url("/feed").toString(), "\"caf\u00e9\"", "", FETCH_TIMEOUT_MS).status)
        assertNull(server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `plain http addresses are refused without a request`() = runTest {
        val outcome = HttpFeedFetcher(OkHttpClient()).fetch(server.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(0, outcome.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `requests say they come from a browser and take any type`() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        fetcher.fetch(server.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        val request = server.takeRequest()
        assertEquals(USER_AGENT, request.headers["User-Agent"])
        assertEquals("*/*", request.headers["Accept"])
    }

    @Test
    fun `an answer over the size limit has no text`() = runTest {
        server.enqueue(MockResponse.Builder().body("x".repeat((MAX_BODY_BYTES + 1).toInt())).build())
        server.enqueue(MockResponse.Builder().body("<rss/>").build())
        val large = fetcher.fetch(server.url("/large").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(200, large.status)
        assertEquals("", large.text)
        assertEquals("<rss/>", fetcher.fetch(server.url("/small").toString(), "", "", FETCH_TIMEOUT_MS).text)
    }
}
