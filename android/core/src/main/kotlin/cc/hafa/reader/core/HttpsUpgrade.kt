package cc.hafa.reader.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Most https addresses tried at once. */
const val PROBE_CONCURRENCY = 6

/**
 * Most tries of a plain http feed's https address in one run that get no
 * definite answer; after that it waits for the app to start again.
 */
const val UNSETTLED_TRIES = 3

private val TRAILING_DOT = Regex("\\.$")

// A last label that is a number, as in an IPv4 address or the forms browsers read as one.
private val NUMBER_LABEL = Regex("^(?:0x[0-9a-f]*|[0-9]+)$", RegexOption.IGNORE_CASE)

// Names that only mean something on the local network.
private val LOCAL_NAMES = listOf("localhost", "local", "internal", "home.arpa", "lan")

private val SPACES_PATTERN = Regex("\\s+")
private val SCHEME_PATTERN = Regex("^https?://", RegexOption.IGNORE_CASE)
private const val SECURE_SCHEME = "https://"

/** Whether an address is plain `http`, as the shared `isPlainHttp` says. */
fun isPlainHttp(address: String): Boolean = jsTrim(address).toHttpUrlOrNull()?.isHttps == false

/**
 * An `https` address written as plain `http`, to find a feed followed at its
 * http address; empty for anything else, as the shared `plainAddress` says.
 */
fun plainAddress(address: String): String {
    val trimmed = jsTrim(address)
    val secure = if (isPlainHttp(trimmed)) "" else upgradedAddress(trimmed)
    return if (secure.isEmpty()) "" else "http://" + secure.removePrefix(SECURE_SCHEME)
}

/**
 * An `http` or `https` address written out the way browsers write it (the
 * scheme and host in lower case, the scheme's own default port left out, an
 * international name in its `xn--` form, the fragment kept); empty for
 * anything else, and for an address with a user name or password, which
 * browsers refuse to fetch. Plain http is kept as it is, so it can be shown
 * as http and tried over https before it is used, as the shared `webAddress` does.
 */
fun webAddress(address: String): String =
    browserWritten(jsTrim(address)) { url -> url.username.isEmpty() && url.password.isEmpty() }

/**
 * Whether a plain http address may be tried at its https address: its host
 * is a public name, not an IP address (nor a number browsers read as one), a
 * name of one label, `localhost`, or a name under `.local`, `.internal`,
 * `.home.arpa` or `.lan`, and it has no user name or password, as the shared
 * `probeAllowed` says. Addresses from a feed list or a search come from
 * others, and trying them must not reach this device or its network.
 */
fun probeAllowed(address: String): Boolean {
    val url = jsTrim(address).toHttpUrlOrNull()
    val host = (url?.host ?: "").replace(TRAILING_DOT, "")
    val labels = host.split(".")
    return url != null &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        labels.size > 1 &&
        ':' !in host &&
        !NUMBER_LABEL.matches(labels.last()) &&
        LOCAL_NAMES.none { name -> host == name || host.endsWith(".$name") }
}

/** What is known of a plain http feed before its https address is tried. */
data class KnownFeed(
    /** Its plain http address. */
    val url: String,
    /** Its name; empty when unknown. */
    val title: String,
    /** Its site's address; empty when unknown. */
    val siteUrl: String,
    /** Guids of its articles already held. */
    val guids: List<String> = emptyList(),
    /** Links of its articles already held. */
    val links: List<String> = emptyList(),
)

/** A plain http feed's https address, found to serve the same feed. */
data class HttpsMatch(
    /** The https address. */
    val url: String,
    /** What it served. */
    val feed: ParsedFeed,
    /** The answer it came in. */
    val outcome: FetchOutcome,
)

// Statuses that ask to try again later rather than say anything of the address.
private val LATER_STATUSES = setOf(408, 425, 429)

/**
 * Whether a probe's answer settles if a feed has an https address: any answer
 * from the site, a redirect off https included, except one asking to try again
 * later (a timeout, too many requests, or a server error). No answer at all,
 * as when offline or the fetch timed out, settles nothing.
 */
fun settledAnswer(outcome: FetchOutcome?): Boolean =
    outcome != null && (outcome.insecure || (outcome.status != 0 && outcome.status < 500 && outcome.status !in LATER_STATUSES))

private fun normalTitle(title: String): String = title.lowercase().split(SPACES_PATTERN).filter { word -> word.isNotEmpty() }.joinToString(" ")

private fun withoutScheme(address: String): String = address.replace(SCHEME_PATTERN, "")

private fun siteHost(address: String): String = address.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: ""

/**
 * Whether a feed read over https looks like the plain http one it stands in
 * for: the same name (ignoring capitals and spacing), an article in common (by
 * guid or link, ignoring the scheme), or a site on the same host as the known
 * site or, failing that, as the http address itself (ignoring `www.`).
 */
fun sameFeed(known: KnownFeed, found: ParsedFeed): Boolean {
    val title = normalTitle(known.title)
    val guids = known.guids.mapTo(HashSet(), ::withoutScheme)
    val links = known.links.filter { link -> link.isNotEmpty() }.mapTo(HashSet(), ::withoutScheme)
    val shared = found.entries.any { entry ->
        withoutScheme(entry.guid) in guids || (entry.url.isNotEmpty() && withoutScheme(entry.url) in links)
    }
    val knownHost = siteHost(known.siteUrl).ifEmpty { siteHost(known.url) }
    val foundHost = siteHost(found.siteUrl)
    return (title.isNotEmpty() && title == normalTitle(found.title)) || shared || (foundHost.isNotEmpty() && foundHost == knownHost)
}

/**
 * Try a plain http feed at its https address. It counts only when the answer
 * is a 200 that stays on https, parses as a feed and passes [sameFeed];
 * otherwise, or when [known] is not plain http or not [probeAllowed], there
 * is no match, and then nothing is fetched.
 */
suspend fun probeHttps(known: KnownFeed, fetcher: FeedFetcher): HttpsMatch? {
    val url = if (isPlainHttp(known.url) && probeAllowed(known.url)) upgradedAddress(known.url) else ""
    val outcome = if (url.isEmpty()) null else fetcher.fetch(url, "", "")
    val feed = if (outcome != null && outcome.status == 200 && !outcome.insecure) {
        try {
            parseFeed(outcome.text, outcome.url.ifEmpty { url })
        } catch (_: NotAFeedException) {
            null
        }
    } else {
        null
    }
    return if (outcome != null && feed != null && sameFeed(known, feed)) HttpsMatch(url, feed, outcome) else null
}

/** Run [work] on every item, at most [limit] at once, keeping the order. */
suspend fun <Item, Result> mapConcurrently(items: List<Item>, limit: Int, work: suspend (Item) -> Result): List<Result> {
    val permits = Semaphore(limit)
    return coroutineScope {
        items.map { item -> async { permits.withPermit { work(item) } } }.awaitAll()
    }
}
