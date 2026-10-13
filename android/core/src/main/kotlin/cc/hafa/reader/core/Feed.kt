package cc.hafa.reader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlin.math.abs

/** One article from a feed. */
data class FeedEntry(
    /** Identifier unique within the feed. */
    val guid: String,
    /** Headline. */
    val title: String,
    /** Link to the original article. */
    val url: String,
    /** Milliseconds since the epoch; zero when the feed gives no readable date. */
    val published: Long,
    /** Writer's name. */
    val author: String,
    /** Lead image address. */
    val imageUrl: String,
    /** The feed's short description of the article, as plain text. */
    val summary: String,
    /** Sections, topics and tags the feed lists for the article. */
    val categories: List<String>,
    /** Article body as HTML, not yet sanitized. */
    val html: String,
)

/** A feed's own details and its current entries. */
data class ParsedFeed(
    /** The feed's name. */
    val title: String,
    /** Address of the site the feed belongs to. */
    val siteUrl: String,
    /** Address of the feed's own small picture; empty if it names none. */
    val iconUrl: String,
    /** Articles the feed lists now. */
    val entries: List<FeedEntry>,
)

/** Thrown when text is none of the supported feed formats. */
class NotAFeedException(cause: Throwable? = null) : Exception("not a feed", cause)

private val TAG_PATTERN = Regex("<[^>]*>")
private val DECIMAL_PATTERN = Regex("&#(\\d+);")
private val HEX_PATTERN = Regex("&#x([\\da-f]+);", RegexOption.IGNORE_CASE)
private val NAMED_PATTERN = Regex("&([a-z]+);", RegexOption.IGNORE_CASE)
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)
private const val MAX_CODE_POINT = 0x10FFFF
private val IMAGE_PATTERN = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

private fun codePointOr(code: Long?, fallback: String): String =
    if (code != null && code > 0 && code <= MAX_CODE_POINT) {
        String(Character.toChars(code.toInt()))
    } else {
        fallback
    }

/** Text of an HTML snippet with tags removed and common entities decoded. */
fun plainText(html: String): String =
    html
        .replace(TAG_PATTERN, " ")
        .replace(DECIMAL_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(), match.value) }
        .replace(HEX_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(16), match.value) }
        .replace(NAMED_PATTERN) { match -> NAMED_ENTITIES[match.groupValues[1].lowercase()] ?: match.value }
        .replace(JS_SPACE_RUN, " ")
        .let(::jsTrim)

/** An address resolved against a base, kept only when it is a web address. */
internal fun resolveWebAddress(address: String, base: String): String =
    if (address.isEmpty()) {
        ""
    } else {
        base.toHttpUrlOrNull()?.resolve(address)?.toString()?.let(::httpsOnly) ?: ""
    }

private fun localName(element: Element): String = element.tagName().substringAfterLast(':')

private fun childrenNamed(parent: Element, name: String): List<Element> =
    parent.children().filter { child -> localName(child) == name }

// Deepest nesting read for text; deeper markup is ignored rather than overflowing the stack.
private const val MAX_TEXT_DEPTH = 64

/** An element's own text, or else its children's texts joined, as the web parser reads a node. */
private fun textOf(element: Element, depth: Int = 0): String {
    val own = jsTrim(raw(element.textNodes().joinToString("") { node -> node.wholeText }))
    return if (own.isNotEmpty() || depth >= MAX_TEXT_DEPTH) {
        own
    } else {
        element.children().map { child -> textOf(child, depth + 1) }.filter { part -> part.isNotEmpty() }.joinToString(" ")
    }
}

// The XML parser would decode numeric character references, which must stay in guids as the
// web's parser leaves them; they are hidden from it behind this character and put back after.
private const val REFERENCE_MARK = '\uE000'

/** Text with numeric character references put back as written. */
private fun raw(text: String): String = text.replace(REFERENCE_MARK, '&')

/** An attribute's value with numeric character references as written. */
private fun attrOf(element: Element, name: String): String = raw(element.attr(name))

/** Numeric character references decoded, as text is shown; guids keep them. */
private fun displayText(text: String): String =
    text
        .replace(DECIMAL_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(), match.value) }
        .replace(HEX_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(16), match.value) }

/** The first non-empty text among the children with a name. */
private fun childText(parent: Element, name: String): String =
    childrenNamed(parent, name).map { child -> textOf(child) }.firstOrNull { part -> part.isNotEmpty() } ?: ""

private fun firstDate(node: Element, fields: List<String>): Long {
    var found = 0L
    for (field in fields) {
        if (found == 0L) {
            found = parseFeedDate(childText(node, field))
        }
    }
    return found
}

private fun firstImage(html: String, base: String): String {
    val source = IMAGE_PATTERN.find(html)?.groupValues?.get(1)
    return if (source.isNullOrEmpty()) "" else resolveWebAddress(decodeEntities(source), base)
}

/** Text with common HTML entities decoded, as an attribute value reads in a browser. */
fun decodeHtmlEntities(text: String): String = decodeEntities(text)

private fun decodeEntities(text: String): String =
    text
        .replace(DECIMAL_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(), match.value) }
        .replace(HEX_PATTERN) { match -> codePointOr(match.groupValues[1].toLongOrNull(16), match.value) }
        .replace(NAMED_PATTERN) { match -> NAMED_ENTITIES[match.groupValues[1].lowercase()] ?: match.value }

private fun categoryNames(elements: List<Element>): List<String> {
    val names = mutableListOf<String>()
    for (element in elements) {
        val name = displayText(attrOf(element, "term").trim().ifEmpty { textOf(element) })
        if (name.isNotEmpty() && name !in names) {
            names.add(name)
        }
    }
    return names
}

/** The raw `href` of the alternate link among Atom links; empty if none. */
private fun atomHref(links: List<Element>): String {
    val alternate = links
        .filter { link -> link.attributesSize() > 0 }
        .firstOrNull { link -> !link.hasAttr("rel") || attrOf(link, "rel") == "alternate" }
    return if (alternate != null && alternate.hasAttr("href")) attrOf(alternate, "href") else ""
}

private fun atomLink(links: List<Element>, base: String): String = resolveWebAddress(atomHref(links), base)

/** The Media RSS groups of an item, which YouTube and others put its picture and description in. */
private fun mediaGroups(item: Element): List<Element> = childrenNamed(item, "group")

private fun mediaImage(item: Element, base: String, depth: Int = 0): String {
    var image = ""
    for (thumbnail in childrenNamed(item, "thumbnail")) {
        if (image.isEmpty() && thumbnail.hasAttr("url")) {
            image = resolveWebAddress(attrOf(thumbnail, "url"), base)
        }
    }
    for (content in childrenNamed(item, "content")) {
        val kind = attrOf(content, "medium") + attrOf(content, "type")
        if (image.isEmpty() && content.hasAttr("url") && "image" in kind) {
            image = resolveWebAddress(attrOf(content, "url"), base)
        }
    }
    for (enclosure in childrenNamed(item, "enclosure")) {
        if (image.isEmpty() && enclosure.hasAttr("url") && attrOf(enclosure, "type").startsWith("image/")) {
            image = resolveWebAddress(attrOf(enclosure, "url"), base)
        }
    }
    for (group in mediaGroups(item)) {
        if (image.isEmpty() && depth < MAX_TEXT_DEPTH) {
            image = mediaImage(group, base, depth + 1)
        }
    }
    return image
}

/** An item's Media RSS description as a body and a summary; empty when it has none. */
private data class MediaText(val html: String, val summary: String)

/**
 * An item's Media RSS description. [ownDescription] also reads a description
 * on the item itself, which in RSS would be the item's own description instead.
 */
private fun mediaDescription(item: Element, ownDescription: Boolean): MediaText {
    val candidates = (if (ownDescription) childrenNamed(item, "description") else emptyList()) +
        mediaGroups(item).flatMap { group -> childrenNamed(group, "description") }
    val description = candidates.firstOrNull { candidate -> textOf(candidate).isNotEmpty() }
    return if (description == null) {
        MediaText("", "")
    } else if (description.attr("type") == "html") {
        val html = textOf(description)
        MediaText(html, plainText(html))
    } else {
        val shown = displayText(textOf(description))
        MediaText(plainTextHtml(shown), jsTrim(shown.replace(JS_SPACE_RUN, " ")))
    }
}

private fun parseRssItem(item: Element, base: String): FeedEntry {
    val link = resolveWebAddress(childText(item, "link"), base)
    val title = childText(item, "title")
    val media = mediaDescription(item, ownDescription = false)
    val html = childText(item, "encoded").ifEmpty { childText(item, "description") }.ifEmpty { media.html }
    val published = firstDate(
        item,
        listOf("pubDate", "date", "published", "issued", "created", "updated", "modified"),
    )
    return FeedEntry(
        guid = childText(item, "guid").ifEmpty { link }.ifEmpty { title },
        title = displayText(title).ifEmpty { link },
        url = link,
        published = published,
        author = displayText(childText(item, "creator").ifEmpty { childText(item, "author") }),
        imageUrl = mediaImage(item, base).ifEmpty { firstImage(html, base) },
        summary = plainText(childText(item, "description")).ifEmpty { media.summary },
        categories = categoryNames(childrenNamed(item, "category")),
        html = html,
    )
}

/** An Atom text construct; XHTML keeps its markup rather than only its words. */
private fun atomText(element: Element): String =
    if (element.attr("type") == "xhtml") {
        jsTrim(raw(element.children().joinToString("") { child -> child.outerHtml() }))
    } else {
        textOf(element)
    }

/** An Atom entry's own content: not a Media RSS content, which has an address. */
private fun atomContent(entry: Element): String =
    childrenNamed(entry, "content").firstOrNull { content -> !content.hasAttr("url") }?.let(::atomText) ?: ""

/** An Atom entry's summary. */
private fun atomSummary(entry: Element): String =
    childrenNamed(entry, "summary").map(::atomText).firstOrNull { part -> part.isNotEmpty() } ?: ""

private fun parseAtomEntry(entry: Element, base: String): FeedEntry {
    val link = atomLink(childrenNamed(entry, "link"), base)
    val title = childText(entry, "title")
    val media = mediaDescription(entry, ownDescription = true)
    val summary = atomSummary(entry)
    val html = atomContent(entry).ifEmpty { summary }.ifEmpty { media.html }
    val published = firstDate(entry, listOf("published", "issued", "created", "updated", "modified"))
    val author = childrenNamed(entry, "author").firstOrNull()
    return FeedEntry(
        guid = childText(entry, "id").ifEmpty { link }.ifEmpty { title },
        title = displayText(title).ifEmpty { link },
        url = link,
        published = published,
        author = if (author == null) "" else displayText(childText(author, "name")),
        imageUrl = mediaImage(entry, base).ifEmpty { firstImage(html, base) },
        summary = plainText(summary).ifEmpty { media.summary },
        categories = categoryNames(childrenNamed(entry, "category")),
        html = html,
    )
}

/** A JSON number as a browser writes it; empty for anything else. */
private fun numberText(value: JsonPrimitive): String {
    val whole = value.longOrNull
    val fraction = value.doubleOrNull
    return if (whole != null) {
        whole.toString()
    } else if (fraction != null && fraction.isFinite()) {
        if (fraction == Math.rint(fraction) && abs(fraction) < 1e21) fraction.toLong().toString() else fraction.toString()
    } else {
        ""
    }
}

/** HTML that shows plain text as it is, line breaks included. */
private fun plainTextHtml(source: String): String =
    source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace(LINE_BREAK_PATTERN, "<br>")

private val LINE_BREAK_PATTERN = Regex("\r\n?|\n")
private val JSON_FEED_VERSION_PATTERN = Regex("^https?://jsonfeed\\.org/version/")

/** Whether a JSON object is a JSON Feed: it names the version or lists items. */
private fun isJsonFeed(source: JsonObject): Boolean {
    val version = source["version"]
    return source["items"] is JsonArray ||
        (version is JsonPrimitive && version.isString && JSON_FEED_VERSION_PATTERN.containsMatchIn(version.content))
}

/** Text of a JSON value the way the web parser reads it: strings and numbers, first of a list. */
private fun jsonText(value: JsonElement?, depth: Int = 0): String =
    if (depth > MAX_TEXT_DEPTH) {
        ""
    } else {
        when (value) {
            is JsonPrimitive -> if (value.isString) jsTrim(value.content) else numberText(value)
            is JsonObject -> value.values.map { part -> jsonText(part, depth + 1) }.filter { part -> part.isNotEmpty() }.joinToString(" ")
            is JsonArray -> value.map { part -> jsonText(part, depth + 1) }.firstOrNull { part -> part.isNotEmpty() } ?: ""
            else -> ""
        }
    }

private fun jsonList(value: JsonElement?): List<JsonElement> =
    when (value) {
        is JsonArray -> value.toList()
        null, JsonNull -> emptyList()
        else -> listOf(value)
    }

private fun parseJsonFeed(source: JsonObject, base: String): ParsedFeed {
    val entries = mutableListOf<FeedEntry>()
    for (item in jsonList(source["items"]).filterIsInstance<JsonObject>()) {
        val url = resolveWebAddress(jsonText(item["url"]), base)
        val html = jsonText(item["content_html"]).ifEmpty { plainTextHtml(jsonText(item["content_text"])) }
        val title = jsonText(item["title"])
        val published = listOf("date_published", "date_modified")
            .map { field -> parseFeedDate(jsonText(item[field])) }
            .firstOrNull { date -> date != 0L } ?: 0L
        val authors = jsonList(item["authors"]).filterIsInstance<JsonObject>()
        entries.add(
            FeedEntry(
                guid = jsonText(item["id"]).ifEmpty { url }.ifEmpty { title },
                title = title.ifEmpty { url },
                url = url,
                published = published,
                author = authors.firstOrNull()?.let { author -> jsonText(author["name"]) } ?: "",
                imageUrl = resolveWebAddress(jsonText(item["image"]), base).ifEmpty { firstImage(html, base) },
                summary = plainText(jsonText(item["summary"])),
                categories = jsonList(item["tags"]).map { tag -> jsonText(tag) }.filter { tag -> tag.isNotEmpty() },
                html = html,
            ),
        )
    }
    return ParsedFeed(
        title = jsonText(source["title"]),
        siteUrl = resolveWebAddress(jsonText(source["home_page_url"]), base),
        iconUrl = resolveWebAddress(jsonText(source["favicon"]).ifEmpty { jsonText(source["icon"]) }, base),
        entries = entries,
    )
}

private fun parseXml(source: String, feedUrl: String): ParsedFeed {
    val document = Jsoup.parse(source.replace("&#", "$REFERENCE_MARK#"), "", Parser.xmlParser())
    document.outputSettings().prettyPrint(false)
    val roots = document.children()
    val rss = roots.firstOrNull { root -> localName(root) == "rss" }
    val channel = rss?.let { root -> childrenNamed(root, "channel").firstOrNull() }
    val rdf = roots.firstOrNull { root -> localName(root) == "RDF" }
    val atom = roots.firstOrNull { root -> localName(root) == "feed" }
    return if (channel != null) {
        val image = childrenNamed(channel, "image").firstOrNull()
        ParsedFeed(
            title = displayText(childText(channel, "title")),
            siteUrl = resolveWebAddress(childText(channel, "link"), feedUrl),
            iconUrl = resolveWebAddress(if (image == null) "" else childText(image, "url"), feedUrl),
            entries = childrenNamed(channel, "item").map { item -> parseRssItem(item, feedUrl) },
        )
    } else if (rdf != null) {
        val rdfChannel = childrenNamed(rdf, "channel").firstOrNull()
        ParsedFeed(
            title = if (rdfChannel == null) "" else displayText(childText(rdfChannel, "title")),
            siteUrl = resolveWebAddress(if (rdfChannel == null) "" else childText(rdfChannel, "link"), feedUrl),
            iconUrl = "",
            entries = childrenNamed(rdf, "item").map { item -> parseRssItem(item, feedUrl) },
        )
    } else if (atom != null) {
        ParsedFeed(
            title = displayText(childText(atom, "title")),
            siteUrl = atomLink(childrenNamed(atom, "link"), feedUrl),
            iconUrl = resolveWebAddress(childText(atom, "icon").ifEmpty { childText(atom, "logo") }, feedUrl),
            entries = childrenNamed(atom, "entry").map { entry -> parseAtomEntry(entry, feedUrl) },
        )
    } else {
        throw NotAFeedException()
    }
}

/**
 * Parse RSS 2.0, RSS 1.0, Atom or JSON Feed text.
 *
 * Relative links are resolved against [feedUrl]. An entry whose date is missing
 * or unreadable has `published` zero. Throws [NotAFeedException] when the text
 * is none of the supported formats.
 */
fun parseFeed(source: String, feedUrl: String): ParsedFeed {
    val trimmed = jsTrim(source)
    val result = if (trimmed.startsWith("{")) {
        val json = try {
            Json.parseToJsonElement(trimmed)
        } catch (error: IllegalArgumentException) {
            throw NotAFeedException(error)
        }
        if (json !is JsonObject || !isJsonFeed(json)) {
            throw NotAFeedException()
        } else {
            parseJsonFeed(json, feedUrl)
        }
    } else {
        parseXml(trimmed, feedUrl)
    }
    return result.copy(entries = result.entries.filter { entry -> entry.guid.isNotEmpty() })
}
