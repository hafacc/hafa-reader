package cc.hafa.reader.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleBodyTest {
    @Test
    fun `paragraphs, headings, rules and code become their own blocks`() {
        val blocks = htmlBlocks("<h2>Title</h2><p>One <b>two</b></p><hr><pre>  x = 1\n</pre><p> </p>")
        assertEquals(
            listOf(
                HtmlBlock.Text("Title", TextKind.Heading, "", 0, 2),
                HtmlBlock.Text("One <b>two</b>", TextKind.Body, "", 0),
                HtmlBlock.Rule,
                HtmlBlock.Code("  x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun `list items get bullets or numbers, and quotes nest`() {
        val blocks = htmlBlocks("<ol><li>first</li><li>second</li></ol><ul><li>dot</li></ul><blockquote><blockquote><p>deep</p></blockquote></blockquote>")
        assertEquals(
            listOf(
                HtmlBlock.Text("first", TextKind.Body, "1. ", 0),
                HtmlBlock.Text("second", TextKind.Body, "2. ", 0),
                HtmlBlock.Text("dot", TextKind.Body, "• ", 0),
                HtmlBlock.Text("deep", TextKind.Body, "", 2),
            ),
            blocks,
        )
    }

    @Test
    fun `a linked picture keeps its link and its caption follows`() {
        val blocks = htmlBlocks(
            """<p><a href="https://comic.example/1"><img src="https://comic.example/1.png"></a></p><p class="alt-text">The joke</p>""",
        )
        assertEquals(
            listOf(
                HtmlBlock.Image("https://comic.example/1.png", "https://comic.example/1"),
                HtmlBlock.Text("The joke", TextKind.Caption, "", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `a heading holding a picture shows the picture`() {
        assertEquals(
            listOf(HtmlBlock.Image("https://x.example/a.png", ""), HtmlBlock.Text("Head", TextKind.Body, "", 0)),
            htmlBlocks("""<h1><img src="https://x.example/a.png">Head</h1>"""),
        )
    }

    @Test
    fun `tables keep their rows and header cells, and figures their captions`() {
        val blocks = htmlBlocks(
            "<table><tr><th>Name</th><th>Size</th></tr><tr><td>a</td><td><b>1</b></td></tr></table>" +
                "<figure><img src=\"https://x.example/p.png\" alt=\"A cat\"><figcaption>Our cat</figcaption></figure>",
        )
        assertEquals(
            listOf(
                HtmlBlock.Table(
                    listOf(
                        listOf(TableCell("Name", true), TableCell("Size", true)),
                        listOf(TableCell("a", false), TableCell("<b>1</b>", false)),
                    ),
                ),
                HtmlBlock.Image("https://x.example/p.png", "", "A cat"),
                HtmlBlock.Text("Our cat", TextKind.FigureCaption, "", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `the largest srcset picture is shown`() {
        assertEquals(
            listOf(HtmlBlock.Image("https://x.example/l.png", "")),
            htmlBlocks("""<img src="https://x.example/s.png" srcset="https://x.example/s.png 400w, https://x.example/l.png 1200w">"""),
        )
    }
}

class ArticleDateTest {
    @Test
    fun `article dates read like the web's, in 24-hour time with the locale's month`() {
        val moment = java.time.Instant.parse("2026-10-12T08:06:00Z").toEpochMilli()
        assertEquals("Oct 12, 2026, 08:06", articleDate(moment, java.time.ZoneOffset.UTC, java.util.Locale.US))
        assertEquals("Oct 12, 2026, 20:06", articleDate(moment + 12 * 3_600_000, java.time.ZoneOffset.UTC, java.util.Locale.US))
        assertTrue(articleDate(moment, java.time.ZoneOffset.UTC, java.util.Locale.forLanguageTag("nl")).startsWith("okt"))
    }
}
