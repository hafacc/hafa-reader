package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedSearchTest {
    private val answer = """
        {"queryType":"term","results":[
          {"feedId":"feed/https://cooking.example/feed","title":"Cooking","description":"Recipes daily",
           "iconUrl":"https://cooking.example/icon.png","subscribers":12345,"website":"https://cooking.example/"},
          {"feedId":"feed/http://plain.example/rss"},
          {"title":"no id, skipped"},
          "not a hit"
        ]}
    """.trimIndent()

    @Test
    fun `hits are read with the feed prefix dropped, gaps filled and http kept for a later try`() {
        assertEquals(
            listOf(
                FeedHit("https://cooking.example/feed", "Cooking", "Recipes daily", "https://cooking.example/icon.png", 12345, "https://cooking.example/"),
                FeedHit("http://plain.example/rss", "feed/http://plain.example/rss", "", "", 0),
            ),
            parseFeedSearch(answer),
        )
    }

    @Test
    fun `only feed ids with web addresses count, and repeats are dropped`() {
        val answer = """{"results":[
          {"feedId":"feed/http://a.example/rss","title":"A"},
          {"feedId":"feed/https://a.example/rss","title":"A again"},
          {"feedId":"feed/https://a.example/rss","title":"A once more"},
          {"feedId":"topic/cooking","title":"Not a feed"},
          {"feedId":"feed/ftp://b.example/rss","title":"Not web"}
        ]}"""
        assertEquals(listOf("http://a.example/rss" to "A", "https://a.example/rss" to "A again"), parseFeedSearch(answer).map { hit -> hit.url to hit.title })
    }

    @Test
    fun `an unreadable answer has no hits`() {
        assertEquals(emptyList(), parseFeedSearch("<html>"))
        assertEquals(emptyList(), parseFeedSearch("""{"results":"nope"}"""))
    }

    @Test
    fun `the query is trimmed and encoded`() {
        assertEquals(
            "https://cloud.feedly.com/v3/search/feeds?count=8&query=slow%20cooking%20%26%20more",
            feedSearchAddress("  slow cooking & more "),
        )
    }
}
