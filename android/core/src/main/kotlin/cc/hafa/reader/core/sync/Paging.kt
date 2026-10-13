package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.sha256Hex

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Days of entries a new device loads before anything older. */
const val RECENT_DAYS = 90

/** Entries read from the account per page. */
const val PAGE_SIZE = 500

/** Most ids one Firestore `in` query takes. */
const val IN_LIMIT = 30

/** Most writes in one Firestore batch. */
const val BATCH_LIMIT = 500

/** Where a page of entries, newest first, ended. */
data class PageCursor(
    /** Date of the last entry read. */
    val published: Long,
    /** Id of the last entry read. */
    val id: String,
)

/** Earliest publish time that still counts as recent. */
fun recentCutoff(now: Long): Long = now - RECENT_DAYS * DAY_MS

/** Publish times a page settles, newest first; [low] is exclusive, null for everything older. */
data class SettledRange(
    /** Exclusive lower bound; null when the page was the last. */
    val low: Long?,
    /** Inclusive upper bound; null for no bound. */
    val high: Long?,
)

/**
 * Publish times a page settles, newest first: [upper] down to just above the
 * last item's time, or everything when the page was the last. Items sharing
 * the last time may run onto the next page, so that time stays open.
 */
fun settledRange(upper: Long?, page: List<Long>, pageSize: Int): SettledRange {
    val last = page.lastOrNull()
    val low = if (page.size < pageSize || last == null) null else last
    return SettledRange(low, upper)
}

/** Local entries in a settled range that the account did not list. */
fun unlisted(local: Iterable<Entry>, seen: Set<String>, range: SettledRange): List<Entry> =
    local.filter { entry ->
        (range.low == null || entry.published > range.low) &&
            (range.high == null || entry.published <= range.high) &&
            entry.id !in seen
    }

/** Most UTF-16 code units a shared body may have; `firebase/firestore.rules` has the same cap. */
const val BODY_MAX_CHARS = 250_000

/** Collection of shared article bodies. */
const val BODIES = "bodies"

/** The hash a body is stored under: hex SHA-256 of its text; empty for no text. */
fun bodyHash(body: String): String = if (body.isEmpty()) "" else sha256Hex(body)

/** Whether a body may be shared: not empty and under the size cap. */
fun shareable(body: String): Boolean = body.isNotEmpty() && body.length <= BODY_MAX_CHARS

/** Whether downloaded text is really the body stored under a hash. */
fun matches(hash: String, body: String): Boolean = hash.isNotEmpty() && bodyHash(body) == hash
