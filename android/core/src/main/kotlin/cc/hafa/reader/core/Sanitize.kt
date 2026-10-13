package cc.hafa.reader.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

private const val MIN_ALT_LENGTH = 12
private const val EXCERPT_LENGTH = 220

/** Class of the paragraph added under a picture to show its title or alt text. */
const val ALT_TEXT_CLASS = "alt-text"

private val safelist: Safelist =
    Safelist.relaxed()
        .addTags(
            "abbr", "article", "aside", "del", "details", "figcaption", "figure", "footer",
            "header", "hr", "ins", "main", "mark", "s", "section", "summary", "time",
        )
        .addTags("picture", "source")
        .addAttributes("img", "srcset")
        .addProtocols("img", "src", "data")
        .addAttributes("source", "srcset", "type", "media")
        .addAttributes(":all", "dir", "lang")

private fun caption(image: Element): String {
    val title = image.attr("title").trim()
    val alt = image.attr("alt").trim()
    return title.ifEmpty { if (alt.length > MIN_ALT_LENGTH) alt else "" }
}

/**
 * Make article HTML safe to show, with links and pictures resolved against
 * [baseUrl]. An image's title or longer alt text, where web comics hide their
 * jokes, is added beneath it as plain text.
 */
fun sanitize(html: String, baseUrl: String = ""): String {
    val dirty = Jsoup.parseBodyFragment(html, baseUrl)
    val clean = Cleaner(safelist).clean(dirty)
    for (image in clean.select("img[srcset], source[srcset]")) {
        image.attr("srcset", absoluteSrcset(image.attr("srcset"), baseUrl))
    }
    // Plain http is never used, so pictures and links move to https.
    for (image in clean.select("img[src]")) {
        image.attr("src", httpsOnly(image.attr("src")))
    }
    for (link in clean.select("a[href]")) {
        link.attr("href", httpsOnly(link.attr("href")))
    }
    for (image in clean.select("img")) {
        val text = caption(image)
        if (text.isNotEmpty()) {
            val note = Element("p").addClass(ALT_TEXT_CLASS).text(text)
            (image.closest("a") ?: image).after(note)
        }
    }
    return clean.body().html()
}

/** Plain text as one paragraph of HTML, for an article with no stored body; empty for no text. */
fun plainBody(text: String): String {
    val escaped = text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return if (escaped.isEmpty()) "" else "<p>$escaped</p>"
}

/**
 * A `srcset` with each candidate's address made absolute against a web base
 * and moved to https; `data:` pictures stay, and any other kind is dropped.
 */
fun absoluteSrcset(srcset: String, base: String): String {
    val usable = base.toHttpUrlOrNull()
    val kept = srcsetCandidates(srcset).mapNotNull { candidate ->
        val address = candidate.address
        val resolved = if (address.startsWith("data:")) {
            address
        } else {
            (usable?.resolve(address) ?: address.toHttpUrlOrNull())?.toString()?.let(::httpsOnly)
        }
        resolved?.let { found -> candidate.copy(address = found) }
    }
    return joinSrcset(kept)
}

/** The start of an article's text with markup removed. */
fun excerpt(html: String, limit: Int = EXCERPT_LENGTH): String {
    val document = Jsoup.parse(html)
    document.select("style, script, noscript, template").remove()
    return document.text().replace(Regex("\\s+"), " ").trim().take(limit)
}
