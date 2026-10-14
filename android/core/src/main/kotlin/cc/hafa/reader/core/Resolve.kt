package cc.hafa.reader.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val LINK_TAG_PATTERN = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTRIBUTE_PATTERN = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'<>`]+))")
private val FEED_TYPES = listOf(
    "application/rss+xml",
    "application/atom+xml",
    "application/feed+json",
    "application/json",
)
private val COMMON_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")
private val YOUTUBE_CHANNEL_PATTERN = Regex("^/channel/(UC[\\w-]{20,})")
private val MASTODON_PATH_PATTERN = Regex("^/@[\\w.-]+/?$")
private val BLUESKY_PATH_PATTERN = Regex("^/profile/([^/]+)/?$")
private val REDDIT_PATH_PATTERN = Regex("^/(r|user|u)/([\\w-]+)/?$")
private val GITHUB_PATH_PATTERN = Regex("^/([\\w.-]+)/([\\w.-]+)/?$")
private val WHITESPACE_PATTERN = Regex("\\s+")
private val TRAILING_SLASH_PATTERN = Regex("/$")
private const val PIPE_STAND_IN = "\uE001"
private const val ESCAPED_PIPE_STAND_IN = "%EE%80%81"
private const val DELETE = 0x7f
private const val BYTE_MASK = 0xff
private val MEDIUM_PATH_PATTERN = Regex("^/(@?[\\w.-]+)/?$")

/**
 * An address with plain http upgraded to https, written out the one way every
 * client writes it (lower-case host, default port dropped, unsafe characters
 * escaped), as the shared `upgradedAddress` does; empty when it is not a web
 * address.
 */
fun upgradedAddress(address: String): String = browserWritten(httpsOnly(address)) { url -> url.isHttps }

/** An http or https address that [accept] takes, written out as browsers write it; empty for anything else. */
internal fun browserWritten(address: String, accept: (HttpUrl) -> Boolean): String {
    // OkHttp escapes "|" where browsers keep it, so it is carried through as a private character
    val written = address.replace("|", PIPE_STAND_IN).toHttpUrlOrNull()?.takeIf(accept)?.toString() ?: ""
    val hash = written.indexOf('#')
    val whole = if (hash < 0) written else written.substring(0, hash + 1) + browserFragment(written.substring(hash + 1))
    return whole.replace(ESCAPED_PIPE_STAND_IN, "|").replace(PIPE_STAND_IN, "|")
}

/** A fragment escaped as browsers escape it, which OkHttp leaves alone. */
private fun browserFragment(fragment: String): String =
    buildString {
        for (character in fragment) {
            if (character.toString() == PIPE_STAND_IN) {
                append(character)
            } else if (character.code <= ' '.code || character in "\"<>`" || character.code >= DELETE) {
                character.toString().toByteArray(Charsets.UTF_8).forEach { byte -> append("%%%02X".format(byte.toInt() and BYTE_MASK)) }
            } else {
                append(character)
            }
        }
    }

/**
 * An address moved to https: plain http is never used, so `http://` becomes
 * `https://` (its default port with it). Other text is returned unchanged.
 */
fun httpsOnly(address: String): String {
    val url = address.toHttpUrlOrNull()
    return if (url == null || url.scheme == "https") {
        address
    } else {
        val builder = url.newBuilder().scheme("https")
        if (url.port == HttpUrl.defaultPort("http")) {
            builder.port(HttpUrl.defaultPort("https"))
        }
        builder.build().toString()
    }
}

/** The scheme, host and port of an address, as a browser's `URL.origin` gives it. */
fun HttpUrl.origin(): String {
    val portPart = if (port == HttpUrl.defaultPort(scheme)) "" else ":$port"
    return "$scheme://$host$portPart"
}

private fun hostIs(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

private fun youtube(url: HttpUrl): List<String> {
    val channel = YOUTUBE_CHANNEL_PATTERN.find(url.encodedPath)?.groupValues?.get(1)
    val playlist = url.queryParameter("list")
    return if (channel != null) {
        listOf("https://www.youtube.com/feeds/videos.xml?channel_id=$channel")
    } else if (url.encodedPath == "/playlist" && !playlist.isNullOrEmpty()) {
        listOf("https://www.youtube.com/feeds/videos.xml?playlist_id=$playlist")
    } else {
        emptyList()
    }
}

private fun medium(url: HttpUrl): List<String> =
    if (url.host == "medium.com") {
        val name = MEDIUM_PATH_PATTERN.find(url.encodedPath)?.groupValues?.get(1)
        if (name == null) emptyList() else listOf("https://medium.com/feed/$name")
    } else {
        listOf("${url.origin()}/feed")
    }

/**
 * Feed addresses a service is known to offer for a page on it, most likely
 * first. Nothing here is checked; the caller tries each in turn.
 */
fun knownFeedUrls(url: HttpUrl): List<String> {
    val host = url.host.lowercase()
    val path = url.encodedPath
    val reddit = REDDIT_PATH_PATTERN.find(path)?.groupValues
    val github = GITHUB_PATH_PATTERN.find(path)?.groupValues
    val bluesky = BLUESKY_PATH_PATTERN.find(path)?.groupValues
    return if (hostIs(host, "youtube.com")) {
        youtube(url)
    } else if (hostIs(host, "reddit.com") && reddit != null) {
        listOf("https://www.reddit.com/${reddit[1]}/${reddit[2]}/.rss")
    } else if (host == "github.com" && github != null) {
        val base = "https://github.com/${github[1]}/${github[2]}"
        listOf("$base/releases.atom", "$base/commits.atom")
    } else if (hostIs(host, "medium.com")) {
        medium(url)
    } else if (hostIs(host, "tumblr.com")) {
        listOf("${url.origin()}/rss")
    } else if (host == "bsky.app" && bluesky != null) {
        listOf("https://bsky.app/profile/${bluesky[1]}/rss")
    } else if (MASTODON_PATH_PATTERN.containsMatchIn(path)) {
        listOf("${url.origin()}${path.replace(TRAILING_SLASH_PATTERN, "")}.rss")
    } else {
        emptyList()
    }
}

private fun attributesOf(tag: String): Map<String, String> {
    val attributes = mutableMapOf<String, String>()
    for (match in ATTRIBUTE_PATTERN.findAll(tag)) {
        val name = match.groupValues[1]
        attributes[name.lowercase()] = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
    }
    return attributes
}

private fun relOf(attributes: Map<String, String>): List<String> =
    (attributes["rel"] ?: "").lowercase().split(WHITESPACE_PATTERN)

private fun feedLinkOf(tag: String, base: String): String? {
    val attributes = attributesOf(tag)
    val kind = (attributes["type"] ?: "").lowercase()
    val href = attributes["href"] ?: ""
    return if (href.isNotEmpty() && "alternate" in relOf(attributes) && kind in FEED_TYPES) {
        base.toHttpUrlOrNull()?.resolve(href.replace("&amp;", "&"))?.toString()?.let(::httpsOnly)
    } else {
        null
    }
}

/** Feed addresses a web page announces in its head. */
fun discoverFeedLinks(html: String, base: String): List<String> =
    LINK_TAG_PATTERN.findAll(html).mapNotNull { match -> feedLinkOf(match.value, base) }.toList()

private fun iconRank(rel: List<String>): Double =
    if ("apple-touch-icon" in rel) {
        0.0
    } else if ("apple-touch-icon-precomposed" in rel) {
        1.0
    } else if ("icon" in rel) {
        if ("shortcut" in rel) 3.0 else 2.0
    } else {
        -1.0
    }

private fun iconAddress(tag: String, base: String): Pair<String, Double>? {
    val attributes = attributesOf(tag)
    val href = attributes["href"] ?: ""
    val rank = iconRank(relOf(attributes))
    val url = if (href.isNotEmpty() && rank != -1.0) {
        base.toHttpUrlOrNull()?.resolve(href.replace("&amp;", "&"))
    } else {
        null
    }
    return if (url == null) {
        null
    } else {
        httpsOnly(url.toString()) to rank + if (url.encodedPath.endsWith(".svg")) 0.0 else 0.5
    }
}

/**
 * Small pictures a web page announces in its head, best first: touch icons
 * (large and sharp), then icons, with the SVG ones ahead of the rest.
 */
fun discoverIconLinks(html: String, base: String): List<String> =
    LINK_TAG_PATTERN.findAll(html)
        .mapNotNull { match -> iconAddress(match.value, base) }
        .sortedBy { (_, rank) -> rank }
        .map { (href, _) -> href }
        .distinct()
        .toList()

/** Paths many sites serve their feed at. */
fun commonFeedUrls(url: HttpUrl): List<String> = COMMON_PATHS.map { path -> "${url.origin()}$path" }

/**
 * Addresses to try, in order, when someone pastes [input] and asks for its
 * feed: the address itself, what its service is known to offer, what the page
 * announces (when its [html] is given), then common paths. Plain http is
 * upgraded to https; empty when [input] is not a web address.
 */
fun feedCandidates(input: String, html: String = ""): List<String> {
    val url = httpsOnly(input).toHttpUrlOrNull()
    return if (url == null) {
        emptyList()
    } else {
        val ordered = listOf(url.toString()) +
            knownFeedUrls(url) +
            discoverFeedLinks(html, url.toString()) +
            commonFeedUrls(url)
        ordered.distinct()
    }
}

/** The address a site usually serves its small picture at. */
fun faviconFor(address: String): String {
    val url = address.toHttpUrlOrNull()
    return if (url == null) "" else "${url.origin()}/favicon.ico"
}

/** The home page of the site an address belongs to. */
fun siteAddress(address: String): String {
    val url = address.toHttpUrlOrNull()
    return if (url == null) address else "${url.origin()}/"
}
