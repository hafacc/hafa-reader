package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecodeTest {
    // "café" in windows-1252.
    private val cafe = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())

    @Test
    fun `the Content-Type charset decides the text`() {
        assertEquals("café", decodeBody(cafe, "application/rss+xml; charset=windows-1252"))
    }

    @Test
    fun `an XML declaration's encoding is used when the header names none`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>".toByteArray() + cafe
        assertTrue(decodeBody(bytes, "text/xml").endsWith("<t>café"))
    }

    @Test
    fun `latin1 and ASCII labels read as windows-1252, as browsers do`() {
        val quote = byteArrayOf(0x93.toByte(), 0x68, 0x69, 0x94.toByte())
        assertEquals("\u201chi\u201d", decodeBody(quote, "text/xml; charset=iso-8859-1"))
        assertEquals("\u201chi\u201d", decodeBody(quote, "text/xml; charset=US-ASCII"))
        assertEquals("\u201chi\u201d", decodeBody(quote, "text/xml; charset=latin1"))
    }

    @Test
    fun `UTF-8 is the default, a byte order mark wins and unknown names fall through`() {
        val utf8 = "café".toByteArray(Charsets.UTF_8)
        assertEquals("café", decodeBody(utf8, ""))
        assertEquals("café", decodeBody(utf8, "text/xml; charset=no-such-set"))
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals("café", decodeBody(marked, "text/xml; charset=windows-1252"))
    }
}
