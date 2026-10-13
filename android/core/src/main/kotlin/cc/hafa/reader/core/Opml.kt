package cc.hafa.reader.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.Collator

/** A feed listed in an OPML file. */
data class OpmlFeed(
    /** The feed's address as the file gives it. */
    val url: String,
    /** The name the file gives the feed; empty if none. */
    val title: String,
    /** The folder the feed sits in; empty for none. */
    val folder: String,
)

/** An attribute's value whatever the case of its name, as exporters vary; null when absent. */
private fun attribute(element: Element, name: String): String? =
    element.attributes().firstOrNull { candidate -> candidate.key.equals(name, ignoreCase = true) }?.value

private fun collect(parent: Element, folder: String, feeds: MutableList<OpmlFeed>) {
    for (child in parent.children()) {
        if (child.tagName().lowercase() == "outline") {
            val url = attribute(child, "xmlUrl") ?: ""
            val title = attribute(child, "title")?.ifEmpty { null } ?: attribute(child, "text") ?: ""
            if (url.isEmpty()) {
                collect(child, title, feeds)
            } else {
                feeds.add(OpmlFeed(url, title, folder))
                // the lenient parse nests outlines that follow an unclosed one inside it
                collect(child, folder, feeds)
            }
        } else {
            // wrappers such as head and body, which a damaged file may move or drop
            collect(child, folder, feeds)
        }
    }
}

/**
 * List the feeds in OPML text, with the folder each sits in. The parser is
 * lenient, so the outlines of most damaged files are still read.
 */
fun parseOpml(source: String): List<OpmlFeed> {
    val document = Jsoup.parse(source, "", Parser.xmlParser())
    val feeds = mutableListOf<OpmlFeed>()
    val root = document.getElementsByTag("opml").firstOrNull() ?: document
    collect(root, "", feeds)
    return feeds
}

private fun escapeAttribute(text: String): String =
    text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private fun outlineOf(feed: OpmlFeed, indent: String): String {
    val title = escapeAttribute(feed.title)
    return "$indent<outline type=\"rss\" text=\"$title\" title=\"$title\" xmlUrl=\"${escapeAttribute(feed.url)}\"/>"
}

/** Write feeds as OPML text, listing each group's feeds inside a folder outline and loose feeds after. */
fun buildOpml(feeds: List<OpmlFeed>): String {
    val collator = Collator.getInstance()
    val folders = feeds.map { feed -> feed.folder }.filter { name -> name.isNotEmpty() }.distinct().sortedWith(collator)
    val groups = folders.map { name ->
        val label = escapeAttribute(name)
        val inside = feeds.filter { feed -> feed.folder == name }.map { feed -> outlineOf(feed, "      ") }
        (listOf("    <outline text=\"$label\" title=\"$label\">") + inside + "    </outline>").joinToString("\n")
    }
    val loose = feeds.filter { feed -> feed.folder.isEmpty() }.map { feed -> outlineOf(feed, "    ") }
    return (
        listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<opml version=\"2.0\">",
            "  <head><title>H Reader</title></head>",
            "  <body>",
        ) + groups + loose + listOf("  </body>", "</opml>", "")
        ).joinToString("\n")
}

/** The feeds to export, with their names and groups. */
fun opmlFeedsOf(feeds: List<Feed>): List<OpmlFeed> = feeds.map { feed -> OpmlFeed(feed.url, feed.title, feed.folder) }
