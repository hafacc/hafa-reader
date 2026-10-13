package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FilterTest {
    private data class Story(
        override val title: String,
        override val summary: String,
        override val categories: List<String>,
    ) : FilterEntry

    private val story = Story(
        title = "Senate Passes Budget After Long Night",
        summary = "Lawmakers ended a standoff over spending.",
        categories = listOf("U.S.", "Politics"),
    )

    @Test
    fun `with nothing set everything stays`() {
        assertTrue(passes(DEFAULT_SETTINGS, story))
    }

    @Test
    fun `hide words remove articles whose title or summary has one`() {
        assertFalse(passes(ViewSettings(hideWords = listOf("budget")), story))
        assertFalse(passes(ViewSettings(hideWords = listOf("standoff")), story))
        assertTrue(passes(ViewSettings(hideWords = listOf("sports")), story))
    }

    @Test
    fun `words match whole words and ignore capitals`() {
        assertFalse(hasWord(listOf("bud"), story))
        assertTrue(hasWord(listOf("SENATE passes"), story))
        assertTrue(hasWord(listOf("long night"), story))
    }

    @Test
    fun `spaces match any whitespace and boundaries sit only at word ends`() {
        val spaced = story.copy(title = "Senate  passes budget\tafter")
        assertTrue(hasWord(listOf("passes budget after"), spaced))
        val abbreviation = story.copy(title = "Plans for the U.S.A. tour")
        assertTrue(hasWord(listOf("u.s."), abbreviation))
        assertTrue(hasWord(listOf(".s.a"), abbreviation))
        assertFalse(hasWord(listOf("plan"), abbreviation))
        assertFalse(hasWord(listOf("a tour"), abbreviation))
    }

    @Test
    fun `any of several words is enough, and blank ones count for nothing`() {
        assertTrue(hasWord(listOf("poll", "budget", "crossword"), story))
        assertFalse(hasWord(listOf("poll", "quiz"), story))
        assertFalse(hasWord(listOf("", "  "), story))
        assertFalse(passes(ViewSettings(onlyWords = listOf("", " ")), story))
    }

    @Test
    fun `only words keep articles that have one of them`() {
        assertTrue(passes(ViewSettings(onlyWords = listOf("world", "senate")), story))
        assertFalse(passes(ViewSettings(onlyWords = listOf("world")), story))
    }

    @Test
    fun `hide wins over only`() {
        assertFalse(passes(ViewSettings(onlyWords = listOf("senate"), hideWords = listOf("budget")), story))
    }

    @Test
    fun `an article is hidden when all of its topics are off`() {
        assertTrue(passes(ViewSettings(offTopics = listOf("politics")), story))
        assertFalse(passes(ViewSettings(offTopics = listOf("Politics", "u.s.")), story))
        val untagged = story.copy(categories = emptyList())
        assertTrue(passes(ViewSettings(offTopics = listOf("politics")), untagged))
    }

    @Test
    fun `topics are counted, commonest first`() {
        val other = story.copy(categories = listOf("Politics", "Business"))
        assertEquals(
            listOf(TopicCount("Politics", 3), TopicCount("Business", 2), TopicCount("U.S.", 1)),
            topicCounts(listOf(story, other, other)),
        )
    }

    @Test
    fun `a list of words is compiled once and reused`() {
        val first = matcherFor(listOf("reuse check"))
        repeat(50) { hasWord(listOf("reuse check"), story) }
        assertSame(first, matcherFor(listOf("reuse check")))
    }

    @Test
    fun `words still match after many lists have been used`() {
        repeat(200) { index -> hasWord(listOf("word $index"), story) }
        assertTrue(hasWord(listOf("budget"), story))
    }

    @Test
    fun `characters with a meaning in patterns are taken literally`() {
        val priced = story.copy(title = "Costs (about \$5) rose [again]?")
        assertTrue(hasWord(listOf("(about \$5)"), priced))
        assertTrue(hasWord(listOf("[again]?"), priced))
        assertFalse(hasWord(listOf("a.out"), priced))
    }
}
