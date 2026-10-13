package cc.hafa.reader.core

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

private val CHARSET_PATTERN = Regex("charset\\s*=\\s*[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
private val XML_ENCODING_PATTERN = Regex("^\\s*<\\?xml\\b[^>]*\\bencoding\\s*=\\s*[\"']([\\w.:-]+)[\"']")

// Bytes enough to hold any XML declaration.
private const val DECLARATION_BYTES = 256
private const val BYTE_ORDER_MARK = '\uFEFF'

private fun byteOrderMarkLabel(bytes: ByteArray): String {
    val first = bytes.getOrNull(0)?.toInt()?.and(0xFF)
    val second = bytes.getOrNull(1)?.toInt()?.and(0xFF)
    val third = bytes.getOrNull(2)?.toInt()?.and(0xFF)
    return if (first == 0xEF && second == 0xBB && third == 0xBF) {
        "UTF-8"
    } else if (first == 0xFE && second == 0xFF) {
        "UTF-16BE"
    } else if (first == 0xFF && second == 0xFE) {
        "UTF-16LE"
    } else {
        ""
    }
}

// Labels browsers read as windows-1252, which agrees with them on every byte they share.
private val WINDOWS_1252_LABELS = setOf("iso-8859-1", "iso8859-1", "latin1", "l1", "us-ascii", "ascii", "iso_8859-1", "cp819")

private fun charsetNamed(label: String): Charset? =
    try {
        Charset.forName(if (label.lowercase() in WINDOWS_1252_LABELS) "windows-1252" else label)
    } catch (_: IllegalCharsetNameException) {
        null
    } catch (_: UnsupportedCharsetException) {
        null
    }

/**
 * Text of a response body in the character set it names: a byte order mark
 * first, then the `Content-Type` charset, then an XML declaration's encoding,
 * else UTF-8. An unknown name falls through to the next; a leading byte order
 * mark is dropped.
 */
fun decodeBody(bytes: ByteArray, contentType: String): String {
    val declaration = String(bytes, 0, minOf(bytes.size, DECLARATION_BYTES), Charsets.ISO_8859_1)
    val charset = listOf(
        byteOrderMarkLabel(bytes),
        CHARSET_PATTERN.find(contentType)?.groupValues?.get(1) ?: "",
        XML_ENCODING_PATTERN.find(declaration)?.groupValues?.get(1) ?: "",
    ).filter { label -> label.isNotEmpty() }.firstNotNullOfOrNull(::charsetNamed) ?: Charsets.UTF_8
    return String(bytes, charset).removePrefix(BYTE_ORDER_MARK.toString())
}
