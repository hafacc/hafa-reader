package cc.hafa.reader.core.sync

import cc.hafa.reader.core.DEFAULT_SETTINGS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxTest {
    private val saved = SavedSettings(DEFAULT_SETTINGS, 1)

    @Test
    fun `a failed write puts every change back`() {
        val outbox = Outbox()
        outbox.add(
            LocalChange(
                feeds = listOf(sampleFeed()),
                removedFeeds = mapOf("gone" to 3L),
                entries = listOf(sampleEntry().copy(read = true, updatedAt = 5)),
                settings = mapOf("all" to saved),
                removedSettings = mapOf("starred" to 4L),
            ),
        )
        val taken = outbox.take(emptySet())
        val copy = taken.copy(
            feeds = LinkedHashMap(taken.feeds),
            removedFeeds = LinkedHashMap(taken.removedFeeds),
            entries = LinkedHashMap(taken.entries),
            settings = LinkedHashMap(taken.settings),
            removedSettings = LinkedHashMap(taken.removedSettings),
        )
        assertFalse(outbox.hasQueued)
        outbox.restore(taken)
        assertEquals(copy, outbox.take(emptySet()))
    }

    @Test
    fun `a change made during a failed write is kept over the older one`() {
        val outbox = Outbox()
        outbox.add(LocalChange(entries = listOf(sampleEntry().copy(read = true, updatedAt = 5))))
        val taken = outbox.take(emptySet())
        val unread = sampleEntry().copy(read = false, updatedAt = 6)
        outbox.add(LocalChange(entries = listOf(unread), removedFeeds = mapOf("feed-1" to 7L)))
        outbox.restore(taken.copy(feeds = linkedMapOf("feed-1" to sampleFeed())))
        val again = outbox.take(emptySet())
        assertEquals(listOf(unread), again.entries.values.toList())
        assertTrue(again.feeds.isEmpty())
        assertEquals(mapOf("feed-1" to 7L), again.removedFeeds.toMap())
    }

    @Test
    fun `an entry still waiting to be uploaded stays queued`() {
        val outbox = Outbox()
        outbox.add(LocalChange(entries = listOf(sampleEntry())))
        assertEquals(0, outbox.take(setOf("entry-1")).entries.size)
        assertEquals(1, outbox.take(emptySet()).entries.size)
    }
}
