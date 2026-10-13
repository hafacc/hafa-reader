package cc.hafa.reader.core

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private val ZONE_OFFSETS = mapOf(
    "CET" to "+0100",
    "CEST" to "+0200",
    "EET" to "+0200",
    "EEST" to "+0300",
    "WET" to "+0000",
    "WEST" to "+0100",
    "BST" to "+0100",
    "MSK" to "+0300",
    "JST" to "+0900",
    "KST" to "+0900",
    "AEST" to "+1000",
    "AEDT" to "+1100",
    "NZST" to "+1200",
    "NZDT" to "+1300",
    "HST" to "-1000",
    "AKST" to "-0900",
    "AKDT" to "-0800",
)
private val ZONE_PATTERN = Regex("\\s([A-Z]{3,4})\\s*$")

// Zone words a browser's date parser knows by itself, in minutes east of UTC.
private val KNOWN_ZONES = mapOf(
    "ut" to 0,
    "utc" to 0,
    "gmt" to 0,
    "z" to 0,
    "est" to -300,
    "edt" to -240,
    "cst" to -360,
    "cdt" to -300,
    "mst" to -420,
    "mdt" to -360,
    "pst" to -480,
    "pdt" to -420,
)
private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
private val ISO_PATTERN = Regex(
    "^([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
        "(?:([Tt ])(\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?" +
        "\\s*(Z|z|[+-]\\d{2}(?::?\\d{2})?)?$",
)
private const val MILLIS_PER_MINUTE = 60_000L
private const val FIRST_CENTURY_YEAR = 50

/**
 * Milliseconds since the epoch for a feed date, or zero when it cannot be read.
 *
 * Reads what a browser's `Date.parse` reads in feeds: ISO 8601 and RFC 822
 * forms and the looser English forms, after common time zone names are
 * turned into offsets. A date with a time but no zone is taken in [zone];
 * an ISO date with no time is taken as UTC.
 */
fun parseFeedDate(value: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    val zoneName = ZONE_PATTERN.find(value)?.groupValues?.get(1) ?: ""
    val offset = ZONE_OFFSETS[zoneName]
    val normalized = (if (offset == null) value else value.replace(ZONE_PATTERN, " $offset")).trim()
    return if (normalized.isEmpty()) {
        0L
    } else {
        try {
            parseIso(normalized, zone) ?: parseLoose(normalized, zone) ?: 0L
        } catch (_: DateTimeException) {
            0L
        } catch (_: ArithmeticException) {
            0L
        }
    }
}

private fun offsetMinutes(text: String): Int {
    val sign = if (text.startsWith("-")) -1 else 1
    val digits = text.drop(1).replace(":", "")
    val hours = digits.take(2).toInt()
    val minutes = if (digits.length > 2) digits.drop(2).toInt() else 0
    return sign * (hours * 60 + minutes)
}

private fun toMillis(local: LocalDateTime, offsetMinutes: Int?, zone: ZoneId): Long =
    if (offsetMinutes == null) {
        local.atZone(zone).toInstant().toEpochMilli()
    } else {
        local.toInstant(ZoneOffset.UTC).toEpochMilli() - offsetMinutes * MILLIS_PER_MINUTE
    }

private fun parseIso(text: String, zone: ZoneId): Long? {
    val match = ISO_PATTERN.find(text) ?: return null
    val groups = match.groupValues
    val year = groups[1].toInt()
    val month = groups[2].ifEmpty { "1" }.toInt()
    val day = groups[3].ifEmpty { "1" }.toInt()
    val hasTime = groups[4].isNotEmpty()
    val hour = groups[5].ifEmpty { "0" }.toInt()
    val minute = groups[6].ifEmpty { "0" }.toInt()
    val second = groups[7].ifEmpty { "0" }.toInt()
    val nanos = groups[8].take(9).padEnd(9, '0').toInt()
    val zoneText = groups[9]
    val local = LocalDateTime.of(year, month, day, hour, minute, second, nanos)
    val offset = when {
        zoneText.equals("z", ignoreCase = true) -> 0
        zoneText.isNotEmpty() -> offsetMinutes(zoneText)
        hasTime -> null
        else -> 0
    }
    return toMillis(local, offset, zone)
}

private sealed interface Token {
    data class Number(val value: Int, val length: Int) : Token
    data class Word(val text: String) : Token
    data class Sign(val negative: Boolean) : Token
    data class Symbol(val character: Char) : Token
}

private fun tokenize(text: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isWhitespace() || character == ',') {
            index += 1
        } else if (character == '(') {
            val close = text.indexOf(')', index)
            index = if (close == -1) text.length else close + 1
        } else if (character.isDigit()) {
            val start = index
            while (index < text.length && text[index].isDigit()) {
                index += 1
            }
            val digits = text.substring(start, index)
            tokens.add(Token.Number(digits.take(9).toInt(), digits.length))
        } else if (character.isLetter()) {
            val start = index
            while (index < text.length && text[index].isLetter()) {
                index += 1
            }
            tokens.add(Token.Word(text.substring(start, index).lowercase()))
        } else if ((character == '+' || character == '-') && index + 1 < text.length && text[index + 1].isDigit()) {
            tokens.add(Token.Sign(character == '-'))
            index += 1
        } else {
            tokens.add(Token.Symbol(character))
            index += 1
        }
    }
    return tokens
}

/** What the loose parser has gathered so far. */
private class LooseDate {
    val numbers = mutableListOf<Token.Number>()
    var month: Int? = null
    var hour: Int? = null
    var minute = 0
    var second = 0
    var millis = 0
    var meridiem: String? = null
    var offset: Int? = null
    var sawNumber = false
}

private fun readTime(tokens: List<Token>, start: Int, date: LooseDate): Int {
    var index = start
    date.hour = (tokens[index] as Token.Number).value
    index += 2
    val minute = tokens.getOrNull(index) as? Token.Number ?: throw DateTimeException("no minute")
    date.minute = minute.value
    index += 1
    if (tokens.getOrNull(index) == Token.Symbol(':')) {
        val second = tokens.getOrNull(index + 1) as? Token.Number ?: throw DateTimeException("no second")
        date.second = second.value
        index += 2
        if (tokens.getOrNull(index) == Token.Symbol('.')) {
            val fraction = tokens.getOrNull(index + 1) as? Token.Number ?: throw DateTimeException("no fraction")
            date.millis = fraction.value.toString().padStart(fraction.length, '0').take(3).padEnd(3, '0').toInt()
            index += 2
        }
    }
    return index
}

private fun readOffset(tokens: List<Token>, start: Int, date: LooseDate): Int {
    val sign = tokens[start] as Token.Sign
    val number = tokens[start + 1] as Token.Number
    var index = start + 2
    val minutes = if (number.length >= 3) {
        number.value / 100 * 60 + number.value % 100
    } else if (tokens.getOrNull(index) == Token.Symbol(':') && tokens.getOrNull(index + 1) is Token.Number) {
        val after = tokens[index + 1] as Token.Number
        index += 2
        number.value * 60 + after.value
    } else {
        number.value * 60
    }
    date.offset = if (sign.negative) -minutes else minutes
    return index
}

private fun readWord(word: String, date: LooseDate): Boolean {
    val monthIndex = if (word.length >= 3) MONTHS.indexOf(word.take(3)) else -1
    val zone = KNOWN_ZONES[word]
    val isDay = word.length >= 3 && word.take(3) in DAYS
    // Like a browser, an unknown word is ignored before the first number and spoils the date after it.
    return when {
        monthIndex != -1 && date.month == null -> {
            date.month = monthIndex + 1
            true
        }
        word == "am" || word == "pm" -> {
            date.meridiem = word
            true
        }
        zone != null -> {
            date.offset = zone
            true
        }
        isDay || word == "t" -> true
        else -> !date.sawNumber
    }
}

private fun parseLoose(text: String, zone: ZoneId): Long? {
    val tokens = tokenize(text)
    val date = LooseDate()
    var index = 0
    var valid = true
    while (valid && index < tokens.size) {
        val token = tokens[index]
        if (token is Token.Number && tokens.getOrNull(index + 1) == Token.Symbol(':') && date.hour == null) {
            date.sawNumber = true
            index = readTime(tokens, index, date)
        } else if (token is Token.Sign && (date.hour != null || date.offset != null) && tokens.getOrNull(index + 1) is Token.Number) {
            index = readOffset(tokens, index, date)
        } else if (token is Token.Number) {
            date.sawNumber = true
            date.numbers.add(token)
            index += 1
        } else if (token is Token.Word) {
            valid = readWord(token.text, date)
            index += 1
        } else {
            index += 1
        }
    }
    return if (valid) build(date, zone) else null
}

private fun fullYear(number: Token.Number): Int =
    if (number.length <= 2) {
        if (number.value < FIRST_CENTURY_YEAR) 2000 + number.value else 1900 + number.value
    } else {
        number.value
    }

private fun build(date: LooseDate, zone: ZoneId): Long? {
    val numbers = date.numbers
    val month = date.month
    var year: Int? = null
    var monthNumber: Int? = month
    var day: Int? = null
    if (month != null && numbers.size == 2) {
        val (first, second) = numbers
        if (first.length >= 3 || first.value > 31) {
            year = fullYear(first)
            day = second.value
        } else {
            day = first.value
            year = fullYear(second)
        }
    } else if (month == null && numbers.size == 3) {
        val (first, second, third) = numbers
        if (first.length >= 3 || first.value > 31) {
            year = fullYear(first)
            monthNumber = second.value
            day = third.value
        } else {
            monthNumber = first.value
            day = second.value
            year = fullYear(third)
        }
    }
    return if (year == null || monthNumber == null || day == null) {
        null
    } else {
        var hour = date.hour ?: 0
        if (date.meridiem == "am" && hour == 12) {
            hour = 0
        } else if (date.meridiem == "pm" && hour < 12) {
            hour += 12
        }
        val local = LocalDateTime.of(year, monthNumber, day, hour, date.minute, date.second, date.millis * 1_000_000)
        toMillis(local, date.offset, zone)
    }
}
