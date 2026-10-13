package cc.hafa.reader.ui

import cc.hafa.reader.core.ALT_TEXT_CLASS
import cc.hafa.reader.core.bestSrcsetCandidate
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** How a run of text in an article is shown. */
enum class TextKind {
    /** A paragraph. */
    Body,

    /** A heading. */
    Heading,

    /** A picture's title or alt text, under the picture. */
    Caption,

    /** A figure's caption. */
    FigureCaption,
}

/** One cell of a table. */
data class TableCell(
    /** Inline HTML of the cell. */
    val html: String,
    /** Whether it heads its row or column. */
    val header: Boolean,
)

/** One piece of an article body, drawn one after another. */
sealed interface HtmlBlock {
    /** Inline HTML shown as styled text. */
    data class Text(
        /** Inline HTML: text, emphasis and links. */
        val html: String,
        /** How it is shown. */
        val kind: TextKind,
        /** Text put before it, such as a list bullet. */
        val prefix: String,
        /** How many quotes it sits in. */
        val quoteDepth: Int,
        /** Heading level, 1 to 6; zero when not a heading. */
        val level: Int = 0,
    ) : HtmlBlock

    /** Preformatted text, such as code. */
    data class Code(
        /** The text, with its spacing kept. */
        val text: String,
    ) : HtmlBlock

    /** A picture. */
    data class Image(
        /** Picture address. */
        val source: String,
        /** Where tapping it goes; empty for nowhere. */
        val link: String,
        /** What the picture shows, for screen readers. */
        val alt: String = "",
    ) : HtmlBlock

    /** A table, row by row. */
    data class Table(
        /** Rows of cells. */
        val rows: List<List<TableCell>>,
    ) : HtmlBlock

    /** A dividing line. */
    data object Rule : HtmlBlock
}

private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "aside", "main", "figure", "figcaption",
    "blockquote", "ul", "ol", "li", "pre", "hr", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
    "dl", "dt", "dd", "details", "summary", "caption",
) + HEADINGS
private const val BLOCK_SELECTOR = "img, p, div, ul, ol, blockquote, pre, table, figure, h1, h2, h3, h4, h5, h6, hr"

/** Where the walk is: inside which link, quote and list item. */
private class Place(val link: String, val quoteDepth: Int, var prefix: String)

private class Walker(private val blocks: MutableList<HtmlBlock>) {
    fun walk(element: Element, place: Place) {
        val inline = StringBuilder()
        var hasText = false
        fun flush() {
            if (hasText) {
                blocks.add(HtmlBlock.Text(inline.toString().trim(), TextKind.Body, place.prefix, place.quoteDepth))
                place.prefix = ""
            }
            inline.clear()
            hasText = false
        }
        for (node in element.childNodes()) {
            if (node is TextNode) {
                inline.append(node.outerHtml())
                hasText = hasText || !node.isBlank
            } else if (node is Element) {
                val name = node.normalName()
                if (name == "img") {
                    flush()
                    val best = bestSrcsetCandidate(node.attr("srcset"))
                    val source = best.ifEmpty { node.absUrl("src").ifEmpty { node.attr("src") } }
                    if (source.isNotEmpty()) {
                        blocks.add(HtmlBlock.Image(source, place.link, node.attr("alt").trim()))
                    }
                } else if (name == "br") {
                    inline.append("<br>")
                } else if (name == "hr") {
                    flush()
                    blocks.add(HtmlBlock.Rule)
                } else if (node.hasClass(ALT_TEXT_CLASS)) {
                    flush()
                    blocks.add(HtmlBlock.Text(node.html(), TextKind.Caption, "", place.quoteDepth))
                } else if (name in HEADINGS && node.selectFirst("img") == null) {
                    flush()
                    blocks.add(HtmlBlock.Text(node.html(), TextKind.Heading, "", place.quoteDepth, name.drop(1).toInt()))
                } else if (name == "figcaption" && node.selectFirst(BLOCK_SELECTOR) == null) {
                    flush()
                    if (node.text().isNotBlank()) {
                        blocks.add(HtmlBlock.Text(node.html(), TextKind.FigureCaption, "", place.quoteDepth))
                    }
                } else if (name == "table") {
                    flush()
                    val rows = node.select("tr")
                        .map { row ->
                            row.children()
                                .filter { cell -> cell.normalName() == "td" || cell.normalName() == "th" }
                                .map { cell -> TableCell(cell.html(), cell.normalName() == "th") }
                        }
                        .filter { cells -> cells.isNotEmpty() }
                    if (rows.isNotEmpty()) {
                        blocks.add(HtmlBlock.Table(rows))
                    }
                } else if (name == "pre") {
                    flush()
                    blocks.add(HtmlBlock.Code(node.wholeText().trimEnd()))
                } else if (name == "blockquote") {
                    flush()
                    walk(node, Place(place.link, place.quoteDepth + 1, ""))
                } else if (name == "ul" || name == "ol") {
                    flush()
                    node.children().filter { child -> child.normalName() == "li" }.forEachIndexed { index, item ->
                        val bullet = if (name == "ol") "${index + 1}. " else "• "
                        walk(item, Place(place.link, place.quoteDepth, bullet))
                    }
                } else if (name in BLOCK_TAGS || node.selectFirst(BLOCK_SELECTOR) != null) {
                    flush()
                    val link = if (name == "a") node.absUrl("href") else place.link
                    walk(node, Place(link, place.quoteDepth, place.prefix))
                } else {
                    inline.append(node.outerHtml())
                    hasText = hasText || node.text().isNotBlank()
                }
            }
        }
        flush()
    }
}

/** Split sanitized article HTML into blocks to draw: text, code, pictures and lines. */
fun htmlBlocks(html: String): List<HtmlBlock> {
    val blocks = mutableListOf<HtmlBlock>()
    Walker(blocks).walk(Jsoup.parseBodyFragment(html).body(), Place("", 0, ""))
    return blocks
}
