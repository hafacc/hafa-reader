package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SanitizeTest {
    @Test
    fun `scripts and styles go, captions are added under pictures`() {
        val html = """<p style="color:red" onclick="x()">Hi<script>alert(1)</script></p>
            <a href="/comic"><img src="/c.png" title="The joke"></a><form><input></form>"""
        val clean = sanitize(html, "https://comic.example/today")
        assertFalse("script" in clean)
        assertFalse("style" in clean)
        assertFalse("onclick" in clean)
        assertFalse("input" in clean)
        assertContains(clean, "https://comic.example/c.png")
        assertContains(clean, """<p class="alt-text">The joke</p>""")
        assertContains(clean, "</a>")
    }

    @Test
    fun `short alt text is not a caption`() {
        assertFalse("alt-text" in sanitize("""<img src="https://x.example/a.png" alt="a cat">"""))
        assertContains(
            sanitize("""<img src="https://x.example/a.png" alt="a cat looking at a dog">"""),
            "a cat looking at a dog</p>",
        )
    }

    @Test
    fun `excerpts are plain text cut to length`() {
        assertEquals("Hello world", excerpt("<p>Hello</p>\n<p>world</p>"))
        assertEquals(5, excerpt("abcdefgh", 5).length)
        assertEquals("Shown", excerpt("<style>p{}</style><script>x()</script><noscript>no</noscript><template>t</template><p>Shown</p>"))
    }

    @Test
    fun `script links, handlers and embedded pages are removed`() {
        val html = """<a href="javascript:alert(1)">a</a><a href="data:text/html,x">b</a>
            <img src="data:image/png;base64,AA" onerror="x()"><iframe src="https://evil.example/"></iframe>
            <style>p{}</style><form action="https://evil.example/"><input></form><div onmouseover="x()">c</div>"""
        val clean = sanitize(html, "https://site.example/post")
        for (unsafe in listOf("javascript:", "data:text", "onerror", "onmouseover", "iframe", "<style", "<form", "<input")) {
            assertFalse(unsafe in clean, unsafe)
        }
        assertContains(clean, "c</div>")
        // Pictures written into the page itself stay, as on the web.
        assertContains(clean, "src=\"data:image/png;base64,AA\"")
    }

    @Test
    fun `links move to https and srcset keeps data pictures whole while upgrading the rest`() {
        val clean = sanitize(
            """<a href="http://site.example/x">x</a><img srcset="data:image/png;base64,AA,BB 1x, http://cdn.example/a.png 2x, ftp://f.example/b.png 3x">""",
            "https://site.example/post",
        )
        assertContains(clean, "href=\"https://site.example/x\"")
        assertContains(clean, "srcset=\"data:image/png;base64,AA,BB 1x, https://cdn.example/a.png 2x\"")
    }

    @Test
    fun `srcset candidates are read as browsers read them`() {
        assertEquals(
            listOf(SrcsetCandidate("a.png", "1x"), SrcsetCandidate("data:x,y", ""), SrcsetCandidate("b.png", "640w")),
            srcsetCandidates(" a.png 1x,data:x,y, b.png 640w"),
        )
    }

    @Test
    fun `classes, ids and styles go and srcset addresses resolve`() {
        val clean = sanitize(
            """<p class="x" id="y" style="color:red">t</p><img src="a.png" srcset="a.png 1x, /b.png 2x">""",
            "https://site.example/blog/post",
        )
        assertFalse("class=" in clean)
        assertFalse("id=" in clean)
        assertFalse("style=" in clean)
        assertContains(clean, "srcset=\"https://site.example/blog/a.png 1x, https://site.example/b.png 2x\"")
    }

    @Test
    fun `article pictures move to https`() {
        val clean = sanitize("""<img src="http://p.example/a.png" srcset="http://p.example/a.png 1x, http://p.example/b.png 2x">""", "https://s.example/post")
        assertFalse("http://" in clean)
        assertContains(clean, "https://p.example/b.png 2x")
    }

    @Test
    fun `plain bodies are escaped paragraphs`() {
        assertEquals("<p>a &lt;b&gt; &amp; c</p>", plainBody(" a <b> & c "))
        assertEquals("", plainBody("  "))
    }

    @Test
    fun `relative addresses resolve against the article and need one`() {
        val clean = sanitize("""<a href="../other">x</a><img src="pic.png">""", "https://site.example/blog/post")
        assertContains(clean, "https://site.example/other")
        assertContains(clean, "https://site.example/blog/pic.png")
        assertFalse("pic.png" in sanitize("""<img src="pic.png">"""))
    }
}
