package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AheadPicturesTest {
    @Test
    fun `the lead picture comes first unless the body shows it`() {
        val body = "<p>x</p><img src=\"https://a.example/1.png\">"
        assertEquals(listOf("https://a.example/lead.png", "https://a.example/1.png"), aheadPictures("https://a.example/lead.png", body))
        assertEquals(listOf("https://a.example/1.png"), aheadPictures("https://a.example/1.png", body))
    }

    @Test
    fun `only https pictures are taken, the largest srcset candidate first, at most four`() {
        val body = "<img src=\"http://a.example/plain.png\">" +
            "<img src=\"data:image/png;base64,AAAA\">" +
            "<img src=\"https://a.example/small.png\" srcset=\"https://a.example/small.png 320w, https://a.example/big.png 1280w\">" +
            (1..5).joinToString("") { number -> "<img src=\"https://a.example/$number.png\">" }
        assertEquals(
            listOf("https://a.example/big.png", "https://a.example/1.png", "https://a.example/2.png", "https://a.example/3.png"),
            aheadPictures("", body),
        )
    }

    @Test
    fun `neighbors follow the way the reader is moving`() {
        val rows = listOf("a", "b", "c").map { id ->
            Entry(id, "f", id, id, "", 0, false, "", "", "", "", emptyList(), read = false, starred = false, addedAt = 0)
        }
        assertEquals(Neighbors(rows[2], rows[0]), neighborsOf(rows, "b", 1))
        assertEquals(Neighbors(rows[0], rows[2]), neighborsOf(rows, "b", -1))
        assertEquals(Neighbors(null, rows[1]), neighborsOf(rows, "c", 1))
        assertEquals(Neighbors(null, null), neighborsOf(rows, "missing", 1))
    }
}
