package cc.hafa.reader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val SEARCH_ADDRESS = "https://cloud.feedly.com/v3/search/feeds"
private const val SEARCH_COUNT = "8"
private val FEED_PREFIX = Regex("^feed/")

/** A feed found by searching, ready to subscribe to. */
data class FeedHit(
    /** The feed's address. */
    val url: String,
    /** The feed's name. */
    val title: String,
    /** What the feed is about. */
    val description: String,
    /** Address of the feed's picture; empty if none. */
    val icon: String,
    /** How many Feedly readers follow it. */
    val subscribers: Long,
    /** Address of the feed's site; empty if unknown. */
    val siteUrl: String = "",
)

/** The Feedly search address for a query. */
fun feedSearchAddress(query: String): String =
    SEARCH_ADDRESS.toHttpUrl().newBuilder()
        .addQueryParameter("count", SEARCH_COUNT)
        .addQueryParameter("query", query.trim())
        .build()
        .toString()

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content

/**
 * The feeds in a Feedly search answer; empty when it cannot be read. A plain
 * http feed keeps its http address, to be tried over https before it is shown.
 */
fun parseFeedSearch(text: String): List<FeedHit> {
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        null
    }
    val results = ((parsed as? JsonObject)?.get("results") as? JsonArray) ?: JsonArray(emptyList())
    return results.filterIsInstance<JsonObject>().mapNotNull { hit ->
        val id = hit.string("feedId")
        // A feed's id is "feed/" and its address; other ids are not feeds.
        if (id == null || !FEED_PREFIX.containsMatchIn(id)) {
            null
        } else {
            FeedHit(
                url = webAddress(id.replace(FEED_PREFIX, "")),
                title = hit.string("title") ?: id,
                description = hit.string("description") ?: "",
                icon = upgradedAddress(hit.string("iconUrl") ?: ""),
                subscribers = (hit["subscribers"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0,
                siteUrl = hit.string("website") ?: "",
            )
        }
    }
        .filter { hit -> hit.url.isNotEmpty() }
        .distinctBy { hit -> hit.url }
}
