package cc.hafa.reader.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeTest {
    private fun version(updatedAt: Long, deleted: Boolean = false) = RecordVersion(updatedAt, deleted)

    @Test
    fun `newer wins`() {
        assertEquals(Decision.Push, decide(version(2), version(1)))
        assertEquals(Decision.Pull, decide(version(1), version(2)))
    }

    @Test
    fun `equal times change nothing, so pulled changes are not sent back`() {
        assertEquals(Decision.Keep, decide(version(1), version(1)))
    }

    @Test
    fun `records only on one side`() {
        assertEquals(Decision.Pull, decide(null, version(1)))
        assertEquals(Decision.Push, decide(version(1), null))
        assertEquals(Decision.Keep, decide(null, version(1, true)))
        assertEquals(Decision.Keep, decide(version(1, true), null))
        assertEquals(Decision.Keep, decide(null, null))
    }

    @Test
    fun `a newer unsubscribe beats an older subscription and back`() {
        val tombstone = localVersion(null, 5)
        assertEquals(Decision.Push, decide(tombstone, version(4)))
        assertEquals(Decision.Pull, decide(tombstone, version(6)))
        assertEquals(Decision.Pull, decide(version(4), tombstone))
    }

    @Test
    fun `a record outranks its tombstone`() {
        assertEquals(version(1), localVersion(1, 9))
        assertNull(localVersion(null, null))
    }

    @Test
    fun `adds new, updates older, pushes newer, skips equal`() {
        val older = sampleEntry().copy(id = "older", updatedAt = 1, bodyHash = "h")
        val newer = sampleEntry().copy(id = "newer", updatedAt = 9)
        val same = sampleEntry().copy(id = "same", updatedAt = 3)
        val local = listOf(older, newer, same).associateBy { item -> item.id }
        val fresh = sampleEntry().copy(id = "fresh")
        val result = mergeEntries(
            local,
            listOf(
                "older" to entryDocument(older.copy(read = true, updatedAt = 2, bodyHash = "")),
                "newer" to entryDocument(newer.copy(updatedAt = 1)),
                "same" to entryDocument(same),
                "fresh" to entryDocument(fresh),
            ),
            emptySet(),
        )
        assertEquals(listOf(older.copy(read = true, updatedAt = 2), fresh), result.pull)
        assertEquals(listOf(newer), result.push)
    }

    @Test
    fun `an account entry beats one this device has only just found`() {
        val found = sampleEntry().copy(updatedAt = 0)
        val result = mergeEntries(mapOf(found.id to found), listOf(found.id to entryDocument(found.copy(read = true, updatedAt = 5))), emptySet())
        assertEquals(listOf(found.copy(read = true, updatedAt = 5)), result.pull)
        assertEquals(emptyList(), result.push)
    }

    @Test
    fun `skips new entries of unsubscribed feeds`() {
        val result = mergeEntries(
            emptyMap(),
            listOf("gone" to entryDocument(sampleEntry().copy(feedId = "gone-feed"))),
            droppedFeedIds(listOf("feed:gone-feed", "settings:all")),
        )
        assertEquals(emptyList(), result.pull)
    }

    @Test
    fun `pulls newer, pushes newer records and removals`() {
        val local = mapOf("old" to 1L, "new" to 5L)
        val result = mergeRecords(
            local,
            { time -> time },
            mapOf("gone" to 9L),
            listOf("old" to version(2), "new" to version(3), "gone" to version(4), "elsewhere" to version(1, true)),
        )
        assertEquals(listOf("old" to version(2)), result.pull)
        assertEquals(listOf("new" to 5L), result.push)
        assertEquals(listOf("gone" to 9L), result.pushRemoved)
    }

    @Test
    fun `a complete listing sends records the account lacks`() {
        val result = mergeRecords(mapOf("listed" to 1L, "missing" to 1L), { time -> time }, emptyMap(), listOf("listed" to version(1)), setOf("listed"))
        assertEquals(listOf("missing" to 1L), result.push)
    }

    @Test
    fun `unknownRemovals keeps account removals this device never had`() {
        val removals = unknownRemovals(
            setOf("kept"),
            mapOf("known" to 1L),
            listOf("kept" to version(2, true), "known" to version(3, true), "live" to version(4), "never" to version(5, true)),
        )
        assertEquals(mapOf("never" to 5L), removals)
    }

    @Test
    fun `tombstonesOf strips the prefix`() {
        assertEquals(mapOf("a" to 1L), tombstonesOf(mapOf("feed:a" to 1L, "settings:all" to 2L), "feed:"))
    }

    @Test
    fun `only entries of feeds still subscribed are uploaded`() {
        val kept = sampleEntry().copy(id = "kept", feedId = "live")
        val removed = sampleEntry().copy(id = "removed", feedId = "gone")
        val orphan = sampleEntry().copy(id = "orphan", feedId = "unknown")
        assertEquals(listOf(kept), subscribedEntries(listOf(kept, removed, orphan), setOf("live", "gone"), mapOf("feed:gone" to 5L)))
    }
}
