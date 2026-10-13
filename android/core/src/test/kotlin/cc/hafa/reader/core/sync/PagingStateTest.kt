package cc.hafa.reader.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagingStateTest {
    @Test
    fun `recent means the last 90 days`() {
        assertEquals(0L, recentCutoff(90L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun `a full page leaves its last time open`() {
        assertEquals(SettledRange(30, 50), settledRange(50, listOf(40, 30), 2))
    }

    @Test
    fun `a short page settles everything older`() {
        assertNull(settledRange(50, listOf(40), 2).low)
    }

    @Test
    fun `unlisted finds local entries the account lacks in the range`() {
        val entries = listOf(10L, 30L, 40L, 60L).map { published -> sampleEntry().copy(id = "at-$published", published = published) }
        assertEquals(emptyList(), unlisted(entries, setOf("at-40"), SettledRange(30, 50)).map { entry -> entry.id })
        assertEquals(listOf("at-10", "at-30"), unlisted(entries, setOf("at-40"), SettledRange(null, 50)).map { entry -> entry.id })
    }

    @Test
    fun `hash is hex SHA-256 of the text`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", bodyHash("abc"))
        assertEquals("", bodyHash(""))
    }

    @Test
    fun `downloads are checked against their hash`() {
        val hash = bodyHash("<p>Hello</p>")
        assertTrue(matches(hash, "<p>Hello</p>"))
        assertFalse(matches(hash, "<p>Forged</p>"))
        assertFalse(matches("", ""))
    }

    @Test
    fun `size caps`() {
        assertFalse(shareable(""))
        assertTrue(shareable("a".repeat(BODY_MAX_CHARS)))
        assertFalse(shareable("a".repeat(BODY_MAX_CHARS + 1)))
        assertFalse(shareable("😀".repeat(BODY_MAX_CHARS / 2 + 1)))
    }

    @Test
    fun `state round trip`() {
        val state = SyncState(
            cursor = 5,
            merged = true,
            page = PageCursor(3, "x"),
            pending = listOf("a"),
            account = AccountDocument(90, 7, stampedDays = 30),
            restamp = Restamp(90, "e"),
        )
        assertEquals(state, parseState(state.toText()))
    }

    @Test
    fun `anything unreadable starts over`() {
        assertEquals(SyncState(), parseState(null))
        assertEquals(SyncState(), parseState("{"))
        assertEquals(SyncState(pending = listOf("b")), parseState("""{"cursor":"x","pending":[1,"b"]}"""))
    }

    @Test
    fun `each account has its own key`() {
        assertNotEquals(stateKey("one"), stateKey("two"))
    }

    @Test
    fun `the entry listener starts an overlap before the cursor`() {
        assertEquals(6000L, listenFrom(7000, 1000))
        assertEquals(0L, listenFrom(500, 1000))
        assertEquals(0L, listenFrom(0, 1000))
    }

    @Test
    fun `a different account waits for a choice only when this device holds data`() {
        assertTrue(needsChoice("a", "b", true))
        assertFalse(needsChoice("a", "b", false))
        assertFalse(needsChoice("a", "a", true))
        assertFalse(needsChoice("", "b", true))
    }

    @Test
    fun `removals alone count as an account's data`() {
        val empty = LocalData(emptyList(), emptyList(), emptyMap(), emptyMap())
        assertFalse(hasLocalData(empty))
        assertTrue(hasLocalData(empty.copy(tombstones = mapOf("feed:x" to 1L))))
    }
}
