package cc.hafa.reader.core

import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/** The details of an article that filters look at. */
interface FilterEntry {
    /** Headline. */
    val title: String

    /** The feed's short description, as plain text. */
    val summary: String

    /** Sections, topics and tags the feed lists for the article. */
    val categories: List<String>
}

/** Order of the article list. */
enum class Sort {
    /** Latest first. */
    Newest,

    /** Earliest first. */
    Oldest,
}

/** How one feed, group or the main list is shown. */
data class ViewSettings(
    /** Order of the list. */
    val sort: Sort = Sort.Newest,
    /** Words or phrases that hide an article when its title or summary has one. */
    val hideWords: List<String> = emptyList(),
    /** When not empty, only articles whose title or summary has one of these stay. */
    val onlyWords: List<String> = emptyList(),
    /** Topics turned off; an article whose topics are all off is hidden. */
    val offTopics: List<String> = emptyList(),
)

/** Settings before anything is changed. */
val DEFAULT_SETTINGS = ViewSettings()

/** Whether [settings] turn [topic] off, ignoring case. */
fun topicOff(settings: ViewSettings, topic: String): Boolean = settings.offTopics.any { known -> known.equals(topic, ignoreCase = true) }

/** A topic the articles carry, with how many have it. */
data class TopicCount(
    /** The topic as the last article carrying it spelled it. */
    val topic: String,
    /** Articles that carry it. */
    val count: Int,
)

// Same word and whitespace sets as subtube's pattern language, so a phrase
// means the same on every platform and in every engine.
private const val WORD = "A-Za-z0-9_"
private const val SPACE =
    "\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
private val WORD_CHARACTER = Regex("^[$WORD]$")
private val SPACE_CHARACTER = Regex("^[$SPACE]$")
private val SPECIAL = "\\^$.|?*+()[]{}".toSet()

/** Most word lists whose matchers are kept; past it the kept ones are dropped. */
private const val MATCHER_LIMIT = 64

/** Matchers already built, so each list of words is compiled once. */
private val matchers = ConcurrentHashMap<String, (String) -> Boolean>()

private fun isSpace(character: String?): Boolean = character != null && SPACE_CHARACTER.matches(character)

private fun isWordCharacter(character: String?): Boolean = character != null && WORD_CHARACTER.matches(character)

/** A string's characters as whole code points, like `Array.from` on a JavaScript string. */
private fun codePoints(text: String): List<String> =
    text.codePoints().toArray().map { point -> String(Character.toChars(point)) }

private fun trimSpace(characters: List<String>): List<String> {
    var start = 0
    var end = characters.size
    while (start < end && isSpace(characters[start])) {
        start += 1
    }
    while (end > start && isSpace(characters[end - 1])) {
        end -= 1
    }
    return characters.subList(start, end)
}

private fun escapeBody(characters: List<String>): String {
    val body = StringBuilder()
    for ((index, character) in characters.withIndex()) {
        if (isSpace(character)) {
            if (!isSpace(characters.getOrNull(index - 1))) {
                body.append("[$SPACE]+")
            }
        } else if (character.length == 1 && character[0] in SPECIAL) {
            body.append('\\').append(character)
        } else {
            body.append(character)
        }
    }
    return body.toString()
}

/**
 * One phrase as a pattern: runs of whitespace match any whitespace, and a
 * word boundary is required at an end only when that end is a word character.
 */
private fun phrasePattern(phrase: String): String {
    val trimmed = trimSpace(codePoints(phrase))
    return if (trimmed.isEmpty()) {
        ""
    } else {
        val before = if (isWordCharacter(trimmed.first())) "(?<![$WORD])" else ""
        val after = if (isWordCharacter(trimmed.last())) "(?![$WORD])" else ""
        "$before${escapeBody(trimmed)}$after"
    }
}

/** A function telling whether a text has any of the words; built once per list. */
internal fun matcherFor(words: List<String>): (String) -> Boolean {
    val key = words.joinToString("\n")
    val known = matchers[key]
    return if (known != null) {
        known
    } else {
        if (matchers.size >= MATCHER_LIMIT) {
            matchers.clear()
        }
        matchers.getOrPut(key) { buildMatcher(words) }
    }
}

private fun buildMatcher(words: List<String>): (String) -> Boolean {
    val parts = words.map(::phrasePattern).toMutableSet()
    parts.remove("")
    return if (parts.isEmpty()) {
        { _: String -> false }
    } else {
        val pattern = Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
        val matcher: (String) -> Boolean = { text -> pattern.containsMatchIn(text) }
        matcher
    }
}

/** Whether an article's title or summary has any of the words. */
fun hasWord(words: List<String>, entry: FilterEntry): Boolean {
    val matcher = matcherFor(words)
    return matcher(entry.title) || matcher(entry.summary)
}

/** The inputs and result of the last [wordCounts], so the panel counts again only when a word or an article's text changes. */
private class CountedWords(val entries: List<FilterEntry>, val words: List<String>, val counts: Map<String, Int>) {
    fun matches(otherEntries: List<FilterEntry>, otherWords: List<String>): Boolean =
        words == otherWords && entries.size == otherEntries.size &&
            entries.zip(otherEntries).all { (known, other) -> known.title === other.title && known.summary === other.summary }
}

@Volatile
private var lastWordCounts: CountedWords? = null

/** The last [wordCounts] for these articles and words, if it is still current. */
fun knownWordCounts(entries: List<FilterEntry>, words: List<String>): Map<String, Int>? =
    lastWordCounts?.takeIf { cached -> cached.matches(entries, words) }?.counts

/**
 * How many of the articles each of the words matches in the title or summary. Slow on a large view, so it is meant
 * to run off the main thread; the last result is kept and reused while the words and the articles' text stay the same.
 */
fun wordCounts(entries: List<FilterEntry>, words: List<String>): Map<String, Int> {
    val known = knownWordCounts(entries, words)
    return if (known != null) {
        known
    } else {
        val counts = words.associateWith { word ->
            val matcher = matcherFor(listOf(word))
            entries.count { entry -> matcher(entry.title) || matcher(entry.summary) }
        }
        lastWordCounts = CountedWords(entries, words, counts)
        counts
    }
}

/** The topics the articles carry, with how many articles carry each, commonest first and then by name. */
fun topicCounts(entries: List<FilterEntry>): List<TopicCount> {
    val counts = LinkedHashMap<String, TopicCount>()
    for (entry in entries) {
        val seen = HashSet<String>()
        for (topic in entry.categories) {
            val key = topicKey(topic)
            if (seen.add(key)) {
                val known = counts[key]
                counts[key] = TopicCount(topic, (known?.count ?: 0) + 1)
            }
        }
    }
    val collator = Collator.getInstance()
    return counts.values.sortedWith { first, second -> compareCounted(collator, first.count, first.topic, second.count, second.topic) }
}

private fun compareCounted(collator: Collator, firstCount: Int, firstName: String, secondCount: Int, secondName: String): Int {
    val byCount = secondCount - firstCount
    return if (byCount != 0) byCount else collator.compare(firstName, secondName)
}

/** The [names] with the highest counts first and then by name, the order every list of topics or words is shown in. */
fun byCount(names: List<String>, count: (String) -> Int): List<String> {
    val collator = Collator.getInstance()
    return names
        .map { name -> name to count(name) }
        .sortedWith { (firstName, firstCount), (secondName, secondCount) ->
            compareCounted(collator, firstCount, firstName, secondCount, secondName)
        }
        .map { (name, _) -> name }
}

/**
 * A view's topic [counts], commonest first, with the count of each topic looked up by its trimmed lowercase name;
 * built once per view so ordering an article's topics does not go through all of them again.
 */
class TopicOrder(
    /** The view's topics and how many of its articles carry each, commonest first and then by name. */
    val counts: List<TopicCount>,
) {
    private val byName: Map<String, Int> = counts.associate { item -> topicKey(item.topic) to item.count }

    /** How many of the view's articles carry [topic], ignoring capitals and surrounding space. */
    fun countOf(topic: String): Int = byName[topicKey(topic)] ?: 0
}

private fun topicKey(topic: String): String = topic.trim().lowercase()

/** An article's topics, each once, in the view's [order]: most articles in the view first, then by name. */
fun articleTopics(categories: List<String>, order: TopicOrder): List<String> =
    byCount(categories.distinctBy(::topicKey), order::countOf)

/** Whether an article stays after the settings' filters are applied. */
fun passes(settings: ViewSettings, entry: FilterEntry): Boolean = compiledFilter(settings)(entry)

/** Filters already built, so each settings' topic set and word matchers are made once. */
private val filters = ConcurrentHashMap<ViewSettings, (FilterEntry) -> Boolean>()

private fun compiledFilter(settings: ViewSettings): (FilterEntry) -> Boolean {
    val known = filters[settings]
    return if (known != null) {
        known
    } else {
        if (filters.size >= MATCHER_LIMIT) {
            filters.clear()
        }
        filters.getOrPut(settings) {
            val off = settings.offTopics.map { topic -> topic.lowercase() }.toSet()
            val hide = matcherFor(settings.hideWords)
            val only = if (settings.onlyWords.isEmpty()) null else matcherFor(settings.onlyWords)
            val filter: (FilterEntry) -> Boolean = { entry ->
                val topicsOff = entry.categories.isNotEmpty() &&
                    entry.categories.all { topic -> topicKey(topic) in off }
                val hidden = hide(entry.title) || hide(entry.summary)
                val kept = only == null || only(entry.title) || only(entry.summary)
                !(topicsOff || hidden) && kept
            }
            filter
        }
    }
}
