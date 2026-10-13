package cc.hafa.reader.core

import java.security.MessageDigest

/** Hex SHA-256 of a string's UTF-8 bytes. */
fun sha256Hex(source: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

// What JavaScript's trim() and \s count as white space, byte-order mark included, so text reads as on the web.
private const val JS_SPACE_CHARACTERS = "\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

/** A run of white space as JavaScript's `\s+` matches it. */
internal val JS_SPACE_RUN = Regex("[$JS_SPACE_CHARACTERS]+")

private val JS_SPACE_ENDS = Regex("^[$JS_SPACE_CHARACTERS]+|[$JS_SPACE_CHARACTERS]+$")

/** Text with white space taken off both ends as JavaScript's `trim()` does. */
internal fun jsTrim(text: String): String = text.replace(JS_SPACE_ENDS, "")

/** Identifier of a feed, from its address. */
fun feedId(feedUrl: String): String = sha256Hex(jsTrim(feedUrl))

/** Identifier of an entry, the same on every device. */
fun entryId(feed: String, guid: String): String = sha256Hex("$feed\n$guid")
