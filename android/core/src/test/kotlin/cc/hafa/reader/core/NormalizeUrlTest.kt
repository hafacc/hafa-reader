package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Addresses written out as the web app writes them, so both make the same feed ids. */
class NormalizeUrlTest {
    // What the web's `normalizeUrl` (web/src/lib/urls.ts) returned for each input, run in bun.
    private val webResults = listOf(
        "https://Example.com" to "https://example.com/",
        "HTTPS://example.com/a b" to "https://example.com/a%20b",
        "example.com" to "https://example.com/",
        "  http://example.com/feed  " to "https://example.com/feed",
        "http://example.com:80/x" to "https://example.com/x",
        "http://example.com:8080/x" to "https://example.com:8080/x",
        "https://example.com:443/x" to "https://example.com/x",
        "https://EXAMPLE.com/Path?Q=1#Frag" to "https://example.com/Path?Q=1#Frag",
        "https://example.com/a%20b" to "https://example.com/a%20b",
        "https://example.com/über" to "https://example.com/%C3%BCber",
        "https://bücher.example/feed" to "https://xn--bcher-kva.example/feed",
        "https://example.com/?q=a b&x='y'" to "https://example.com/?q=a%20b&x=%27y%27",
        "https://example.com/a|b{c}" to "https://example.com/a|b%7Bc%7D",
        "https://example.com/./a/../b" to "https://example.com/b",
        "https://example.com\\a\\b" to "https://example.com/a/b",
        "ftp://example.com/feed" to "",
        "" to "",
        "not a url" to "",
        "https://user:pass@example.com/" to "https://user:pass@example.com/",
        "https://example.com." to "https://example.com./",
        "https://example.com/feed?x=%7e" to "https://example.com/feed?x=%7e",
        "mailto:x@example.com" to "https://mailto:x@example.com/",
        "https://example.com/a^b`c" to "https://example.com/a%5Eb%60c",
        "https://[::1]:8443/feed" to "https://[::1]:8443/feed",
        "https://example.com/a?b=c|d#e|f" to "https://example.com/a?b=c|d#e|f",
        "https://example.com/a%7Cb" to "https://example.com/a%7Cb",
        "https://exa|mple.com/" to "",
        "https://example.com/a?q=\"x\"<y>" to "https://example.com/a?q=%22x%22%3Cy%3E",
        "https://example.com/a#x y`z" to "https://example.com/a#x%20y%60z",
        "https://example.com/{x}?{y}#{z}" to "https://example.com/%7Bx%7D?{y}#{z}",
        "https://example.com/a#über \"q\"<>" to "https://example.com/a#%C3%BCber%20%22q%22%3C%3E",
        "https://example.com/a#x%20y" to "https://example.com/a#x%20y",
    )

    @Test
    fun matchesTheWebApp() {
        val differences = webResults.mapNotNull { (input, expected) ->
            val actual = normalizeUrl(input)
            if (actual == expected) null else "$input: web $expected, android $actual"
        }
        assertEquals(emptyList(), differences)
    }

    @Test
    fun feedSearchAddressesAreWrittenTheSameWay() {
        val hits = parseFeedSearch(
            """{"results":[{"feedId":"feed/http://Example.com","iconUrl":"http://Example.com/i.png"},{"feedId":"feed/https://example.com/"}]}""",
        )
        assertEquals(listOf("https://example.com/"), hits.map { hit -> hit.url })
        assertEquals("https://example.com/i.png", hits.single().icon)
    }
}
