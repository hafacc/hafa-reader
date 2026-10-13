package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Sort
import cc.hafa.reader.core.ViewSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 24L * 60 * 60 * 1000

class DocumentsTest {
    @Test
    fun `text is cut to the account's limits without splitting a character`() {
        assertEquals("ab", cutText("ab\uD83D\uDE00", 3))
        assertEquals("ab\uD83D\uDE00", cutText("ab\uD83D\uDE00c", 4))
        val long = "x".repeat(FieldLimits.ADDRESS + 1)
        val feed = subscriptionDocument(sampleFeed().copy(title = "t".repeat(3000), folder = "f".repeat(3000), icon = long, siteUrl = long))
        assertEquals(FieldLimits.TITLE, feed.title.length)
        assertEquals(FieldLimits.FOLDER, feed.folder.length)
        assertEquals("", feed.icon)
        assertEquals("", feed.siteUrl)
        val entry = entryDocument(
            sampleEntry().copy(
                author = "a".repeat(3000),
                excerpt = "e".repeat(3000),
                summary = "s".repeat(30_000),
                categories = List(150) { index -> "topic $index" },
                url = long,
                imageUrl = long,
            ),
        )
        assertEquals(FieldLimits.AUTHOR, entry.author.length)
        assertEquals(FieldLimits.EXCERPT, entry.excerpt.length)
        assertEquals(FieldLimits.SUMMARY, entry.summary.length)
        assertEquals(FieldLimits.CATEGORIES, entry.categories.size)
        assertEquals("", entry.url)
        assertEquals("", entry.imageUrl)
        val words = List(600) { index -> "word $index" }
        val settings = settingsDocument("all", SavedSettings(ViewSettings(Sort.Newest, words, words, words), 1), 1)
        assertEquals(FieldLimits.WORDS, settings.hideWords.size)
        assertEquals(FieldLimits.WORDS, settings.onlyWords.size)
        assertEquals(FieldLimits.WORDS, settings.offTopics.size)
    }

    @Test
    fun `subscriptions round trip keeps this device's own fields`() {
        val feed = sampleFeed().copy(folder = "News", renamed = true)
        val document = subscriptionDocument(feed)
        assertEquals(false, document.deleted)
        val back = feedFromDocument(feed.id, document, feed.copy(error = "broken"))
        assertEquals(feed.copy(error = "broken"), back)
    }

    @Test
    fun `another device's change keeps this device's own name, site and picture`() {
        val local = sampleFeed().copy(title = "New name", siteUrl = "https://a.example/", icon = "https://a.example/icon.png")
        val older = subscriptionDocument(sampleFeed().copy(title = "Old name", siteUrl = "", icon = "", folder = "News"))
        val back = feedFromDocument(local.id, older, local)
        assertEquals("New name", back.title)
        assertEquals("https://a.example/", back.siteUrl)
        assertEquals("https://a.example/icon.png", back.icon)
        assertEquals("News", back.folder)
    }

    @Test
    fun `a name given by hand on another device is taken`() {
        val local = sampleFeed().copy(title = "Feed's own name")
        val back = feedFromDocument(local.id, subscriptionDocument(sampleFeed().copy(title = "My name", renamed = true)), local)
        assertEquals("My name", back.title)
        assertTrue(back.renamed)
    }

    @Test
    fun `a key is kept only while its encoded document id fits Firestore's limit`() {
        assertTrue(storableKey("folder:News"))
        // 400 characters, but each emoji is four bytes, encoded as twelve
        val wide = "folder:" + "\uD83D\uDE00".repeat(200)
        assertTrue(wide.length <= FieldLimits.KEY)
        assertEquals(false, storableKey(wide))
    }

    @Test
    fun `a feed new to this device has no problem shown`() {
        val back = feedFromDocument("feed-1", subscriptionDocument(sampleFeed()))
        assertEquals("", back.error)
    }

    @Test
    fun `tombstones parse and carry no feed`() {
        assertEquals(subscriptionTombstone(7), parseSubscription(subscriptionTombstone(7).toFields()))
    }

    @Test
    fun `documents without a time or address are rejected`() {
        assertNull(parseSubscription(mapOf("url" to "https://a")))
        assertNull(parseSubscription(mapOf("updatedAt" to 1L, "url" to "")))
    }

    @Test
    fun `entries round trip`() {
        val entry = sampleEntry().copy(bodyHash = "abc", starred = true)
        val document = entryDocument(entry)
        assertNull(document.expireAt)
        assertEquals(entry, entryFromDocument(entry.id, document))
        assertEquals(document, parseEntry(document.toFields()))
    }

    @Test
    fun `a finite retention sets the expiry from when it was added`() {
        assertEquals(30L * 24 * 60 * 60 * 1000, entryDocument(sampleEntry().copy(addedAt = 0), 30).expireAt)
    }

    @Test
    fun `starred entries and forever never expire`() {
        assertNull(entryDocument(sampleEntry().copy(starred = true), 30).expireAt)
        assertNull(entryExpiry(0, starred = false, retention = null))
        assertEquals(DAY * 90, entryExpiry(0, starred = false, retention = 90))
    }

    @Test
    fun `a body outlives the longest retention by a week`() {
        assertEquals(5 + DAY * 372, bodyExpiry(30, 5))
        assertNull(bodyExpiry(null, 5))
    }

    @Test
    fun `a body may be gone once it is older than a body lasts`() {
        assertTrue(bodyMayBeGone(0, DAY * 372))
        assertFalse(bodyMayBeGone(0, DAY * 372 - 1))
    }

    @Test
    fun `re-stamping changes only expiries that are wrong`() {
        val document = entryDocument(sampleEntry().copy(addedAt = 0))
        assertEquals(ExpiryChange.At(DAY * 30), restampedExpiry(document, null, 30))
        assertNull(restampedExpiry(document, DAY * 30, 30))
        assertEquals(ExpiryChange.Clear, restampedExpiry(document, DAY * 30, null))
        assertEquals(ExpiryChange.Clear, restampedExpiry(document.copy(starred = true), DAY * 30, 30))
        assertNull(restampedExpiry(document, null, null))
    }

    @Test
    fun `account settings parse only offered choices and the newer copy wins`() {
        assertEquals(AccountDocument(90, 4), parseAccount(mapOf("retentionDays" to 90L, "updatedAt" to 4L)))
        assertEquals(AccountDocument(0, 4), parseAccount(mapOf("retentionDays" to 0L, "updatedAt" to 4L)))
        assertEquals(AccountDocument(30, 4, 90), parseAccount(mapOf("retentionDays" to 30L, "updatedAt" to 4L, "stampedDays" to 90L)))
        assertEquals(AccountDocument(30, 4, 0), parseAccount(mapOf("retentionDays" to 30L, "updatedAt" to 4L, "stampedDays" to 45L)))
        assertTrue(needsRestamp(AccountDocument(30, 4, 0)))
        assertFalse(needsRestamp(AccountDocument(30, 4, 30)))
        assertNull(parseAccount(mapOf("retentionDays" to 45L, "updatedAt" to 4L)))
        assertNull(parseAccount(mapOf("retentionDays" to 30L)))
        assertEquals(null, retentionFromDays(0))
        assertEquals(365, retentionFromDays(365))
        val local = AccountDocument(30, 10)
        assertEquals(AccountWinner.Remote, accountWinner(local, AccountDocument(90, 11)))
        assertEquals(AccountWinner.Local, accountWinner(local, AccountDocument(90, 9)))
        assertEquals(AccountWinner.Local, accountWinner(local, null))
        assertEquals(AccountWinner.Same, accountWinner(AccountDocument(0, 0), null))
    }

    @Test
    fun `bad fields fall back to defaults`() {
        val parsed = parseEntry(
            mapOf("feedId" to "feed-1", "updatedAt" to 3L, "categories" to listOf("ok", 4), "read" to "yes", "published" to Double.NaN),
        )
        assertEquals(listOf("ok"), parsed?.categories)
        assertEquals(false, parsed?.read)
        assertEquals(0L, parsed?.published)
        assertEquals(false, parsed?.dated)
    }

    @Test
    fun `an entry without a feed is rejected`() {
        assertNull(parseEntry(mapOf("updatedAt" to 3L)))
    }

    @Test
    fun `settings round trip`() {
        val saved = SavedSettings(ViewSettings(Sort.Oldest, listOf("a"), emptyList(), listOf("b")), 4)
        val document = settingsDocument("folder:News", saved, 4)
        assertEquals(document, parseSettings(document.toFields()))
        assertEquals(saved, settingsFromDocument(document))
    }

    @Test
    fun `settings tombstones`() {
        val document = settingsDocument("all", null, 8)
        assertTrue(document.deleted)
        assertEquals(8, document.updatedAt)
    }

    @Test
    fun `document ids hold no slash`() {
        assertEquals("folder%3AA%2FB", settingsDocumentId("folder:A/B"))
        assertEquals("feed%3A%C3%A9", settingsDocumentId("feed:é"))
    }

    @Test
    fun `account feeds and entries have plain http upgraded`() {
        val feed = parseSubscription(
            subscriptionDocument(
                sampleFeed().copy(url = "http://old.example/rss", siteUrl = "http://old.example/", icon = "http://old.example/icon.png"),
            ).toFields(),
        )
        assertEquals("https://old.example/rss", feed?.url)
        assertEquals("https://old.example/", feed?.siteUrl)
        assertEquals("https://old.example/icon.png", feed?.icon)
        val entry = parseEntry(entryDocument(sampleEntry().copy(url = "http://old.example/1", imageUrl = "http://old.example/1.png")).toFields())
        assertEquals("https://old.example/1", entry?.url)
        assertEquals("https://old.example/1.png", entry?.imageUrl)
    }

    @Test
    fun `an account feed with no web address is dropped`() {
        assertNull(parseSubscription(subscriptionDocument(sampleFeed().copy(url = "ftp://old.example/rss")).toFields()))
    }
}
