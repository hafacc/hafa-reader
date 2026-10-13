package cc.hafa.reader.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import cc.hafa.reader.core.sync.RemoteChange
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal fun rss(title: String, vararg items: String): String =
    "<rss><channel><title>$title</title><link>https://site.example/</link>" +
        items.joinToString("") { item ->
            "<item><title>$item</title><guid>$item</guid><pubDate>Tue, 06 Oct 2026 09:14:00 GMT</pubDate>" +
                "<description>About $item</description></item>"
        } + "</channel></rss>"

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderModelTest {
    private val store = MemoryStore()
    private val preferences = MemoryPreferences()
    private val fetcher = FakeFetcher()
    private var now = 1_800_000_000_000L
    private var saving = false

    private fun TestScope.model(): ReaderModel =
        ReaderModel(
            store,
            preferences,
            fetcher,
            backgroundScope,
            clock = { now },
            workDispatcher = StandardTestDispatcher(testScheduler),
            minRefreshMillis = 0,
            savingData = { saving },
        )

    private suspend fun ReaderModel.addWithItems(address: String, vararg items: String) {
        fetcher.answers[address] = FetchOutcome(status = 200, text = rss(address, *items))
        addFeed(address)
    }

    private fun ReaderModel.titles(): List<String> = view().visible.map { entry -> entry.title }

    @Test
    fun `a refresh cancelled during its shortest showing still ends its mark`() = runTest {
        val model = ReaderModel(store, preferences, fetcher, backgroundScope, clock = { now }, minRefreshMillis = 700)
        model.load()
        val refreshing = launch { model.refresh() }
        runCurrent()
        assertEquals(1, model.state.value.refreshCount)
        refreshing.cancel()
        refreshing.join()
        assertEquals(0, model.state.value.refreshCount)
    }

    @Test
    fun `huge summaries and titles are cut, from feeds and from the account`() = runTest {
        val model = model()
        val huge = "word ".repeat(MAX_SUMMARY_CHARS)
        fetcher.answers["https://a.example/feed"] = FetchOutcome(
            status = 200,
            text = "<rss><channel><title>A</title><item><title>${"t".repeat(MAX_TITLE_CHARS * 2)}</title><guid>one</guid>" +
                "<description>$huge</description></item></channel></rss>",
        )
        model.addFeed("https://a.example/feed")
        model.awaitWrites()
        val entry = store.entries.values.single()
        assertEquals(MAX_TITLE_CHARS, entry.title.length)
        assertEquals(MAX_SUMMARY_CHARS, entry.summary.length)
        assertEquals(MAX_SUMMARY_CHARS, entry.excerpt.length)
        model.syncHost.apply(RemoteChange(entries = listOf(entry.copy(id = "remote", summary = huge, excerpt = huge))))
        assertEquals(MAX_SUMMARY_CHARS, store.entries.getValue("remote").summary.length)
        assertEquals(MAX_SUMMARY_CHARS, model.state.value.entries.first { item -> item.id == "remote" }.excerpt.length)
    }

    @Test
    fun `a feed that arrives from the account while it is being added is listed once`() = runTest {
        val model = model()
        model.load()
        val address = "https://a.example/feed"
        fetcher.answers[address] = FetchOutcome(status = 200, text = rss(address, "one"))
        val siteLookup = CompletableDeferred<Unit>()
        fetcher.gates["https://site.example/"] = siteLookup
        val adding = launch { model.addFeed(address) }
        runCurrent()
        val id = feedId(address)
        val remote = Feed(id, address, "From the account", "", "", renamed = true, folder = "", error = "", updatedAt = 5)
        model.syncHost.apply(RemoteChange(feeds = listOf(remote)))
        siteLookup.complete(Unit)
        adding.join()
        assertEquals(1, model.state.value.feeds.count { feed -> feed.id == id })
        assertEquals("From the account", model.state.value.feeds.single().title)
    }

    @Test
    fun `a picture that fails keeps its address when the site cannot be read`() = runTest {
        val model = model()
        val address = "https://a.example/feed"
        fetcher.answers[address] = FetchOutcome(
            status = 200,
            text = "<rss><channel><title>A</title><link>https://site.example/</link>" +
                "<image><url>https://a.example/icon.png</url></image></channel></rss>",
        )
        model.addFeed(address)
        model.refreshIcon(feedId(address))
        assertEquals("https://a.example/icon.png", model.state.value.feeds.single().icon)
    }

    @Test
    fun `unread only starts on and a missing saved view falls back to All items`() = runTest {
        preferences.lastView = "feed:gone"
        val model = model()
        model.load()
        assertTrue(model.state.value.unreadOnly)
        assertEquals(Selection.All, model.state.value.selection)
    }

    @Test
    fun `adding a feed stores its articles and opens it`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two")
        val state = model.state.value
        assertEquals(1, state.feeds.size)
        assertEquals("https://a.example/feed", state.feeds[0].title)
        assertEquals(Selection.FeedView(state.feeds[0].id), state.selection)
        assertEquals(listOf("one", "two"), model.titles())
        model.awaitWrites()
        assertEquals(2, store.entries.size)
    }

    @Test
    fun `opening does not mark read, leaving does, and the article stays listed until the view changes`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two")
        val first = model.view().visible.first { entry -> entry.title == "one" }
        model.open(first.id)
        runCurrent()
        assertFalse(model.view().current!!.read)
        assertTrue(model.state.value.body.contains("About one"), model.state.value.body)
        model.close()
        assertTrue(model.state.value.entries.first { entry -> entry.id == first.id }.read, "read after leaving")
        assertEquals(listOf("one", "two"), model.titles())
        model.select(Selection.All)
        assertEquals(listOf("two"), model.titles())
    }

    @Test
    fun `marking the open article unread sticks when it is left`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = model.view().visible.first().id
        model.open(id)
        model.toggleRead()
        model.toggleRead()
        assertFalse(model.view().current!!.read)
        model.close()
        assertFalse(model.state.value.entries.first().read)
    }

    @Test
    fun `the unread switch drops kept read articles and starred ignores it`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two")
        val id = model.view().visible.first().id
        model.toggleReadFor(id)
        model.toggleStarFor(id)
        assertEquals(2, model.titles().size)
        model.setUnreadOnly(true)
        assertEquals(1, model.titles().size)
        model.select(Selection.Starred)
        assertEquals(listOf("one"), model.titles())
        assertEquals(false, preferences.unreadOnly.not())
    }

    @Test
    fun `turning unread only on closes an open article already read`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two", "three")
        model.setUnreadOnly(false)
        model.open(model.view().visible[1].id)
        val open = model.state.value.currentId
        model.toggleRead()
        model.setUnreadOnly(true)
        assertEquals("", model.state.value.currentId)
        assertFalse(model.view().visible.any { entry -> entry.id == open })
    }

    @Test
    fun `turning unread only on keeps an open unread article open`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.setUnreadOnly(false)
        model.open(model.view().visible.first().id)
        val open = model.state.value.currentId
        model.setUnreadOnly(true)
        assertEquals(open, model.state.value.currentId)
    }

    @Test
    fun `refresh adds new articles, keeps checks conditional and clears kept read ones`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.markAllRead()
        assertEquals(listOf("one"), model.titles())
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = rss("Renamed", "one", "two"), etag = "v2")
        now += 60_000
        model.refresh()
        assertEquals(listOf("two"), model.titles())
        val feed = model.state.value.feeds.first()
        assertEquals("Renamed", feed.title)
        assertEquals("v2", feed.schedule.etag)
        assertTrue(feed.schedule.nextFetch > now)
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 304)
        model.refresh()
        assertEquals(2, model.state.value.entries.size)
    }

    @Test
    fun `a large refresh waits for likely feeds, and a slow one finishes in the background`() = runTest {
        val model = model()
        val addresses = (1..7).map { index -> "https://site$index.example/feed" }
        for (address in addresses) {
            model.addWithItems(address, "item")
        }
        val slow = addresses.first()
        fetcher.slow.add(slow)
        fetcher.answers[slow] = FetchOutcome(status = 200, text = rss(slow, "item", "late"))
        model.select(Selection.All)
        now += 2 * MAX_INTERVAL_MS
        model.refresh()
        val slowFeed = model.state.value.feeds.first { feed -> feed.url == slow }
        assertEquals(0, slowFeed.schedule.failures)
        model.awaitChecks()
        assertTrue(model.state.value.entries.any { entry -> entry.title == "late" })
        assertEquals(0, model.state.value.feeds.first { feed -> feed.url == slow }.schedule.failures)
    }

    @Test
    fun `new items feed the posting pattern`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val first = model.state.value.feeds.first().schedule.model.pattern!!
        assertEquals(1.0, first.hourOfDay.sum(), 1e-9)
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = rss("A", "one", "two"))
        model.refresh()
        assertEquals(2.0, model.state.value.feeds.first().schedule.model.pattern!!.hourOfDay.sum(), 1e-9)
    }

    @Test
    fun `a feed removed while its check is under way does not come back`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = feedId("https://a.example/feed")
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = rss("A", "one", "two"))
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val refreshing = launch { model.refresh() }
        runCurrent()
        model.removeFeed(id)
        gate.complete(Unit)
        refreshing.join()
        model.awaitWrites()
        assertTrue(model.state.value.entries.isEmpty())
        assertTrue(model.state.value.feeds.isEmpty())
        assertTrue(store.entries.isEmpty())
        assertTrue(store.feeds.isEmpty())
    }

    @Test
    fun `a rename made while a check is under way is kept`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = feedId("https://a.example/feed")
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 304)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val refreshing = launch { model.refresh() }
        runCurrent()
        model.renameFeed(id, "Mine")
        model.setFolder(id, "News")
        gate.complete(Unit)
        refreshing.join()
        model.awaitWrites()
        val feed = model.state.value.feeds.single()
        assertEquals("Mine", feed.title)
        assertEquals("News", feed.folder)
        assertEquals("Mine", store.feeds[id]!!.title)
    }

    @Test
    fun `a check that breaks releases its feed without saving anything`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val before = model.state.value.feeds.single().schedule
        fetcher.failure = IllegalStateException("broken")
        model.refresh()
        assertEquals(before, model.state.value.feeds.single().schedule)
    }

    @Test
    fun `entries a failed save lost are not taken as known, and their feed is sent whole again`() = runTest {
        val model = model()
        store.failingSaves = 1
        model.addWithItems("https://a.example/feed", "one")
        model.awaitWrites()
        assertTrue(model.state.value.entries.isEmpty())
        assertEquals("", model.state.value.feeds.single().schedule.hash)
        model.refresh()
        model.awaitWrites()
        assertEquals(listOf("one"), store.entries.values.map { entry -> entry.title })
    }

    private suspend fun ReaderModel.addPictured() {
        val text = "<rss><channel><title>P</title>" + (6..8).joinToString("") { day ->
            "<item><title>day $day</title><guid>$day</guid><pubDate>Tue, 0$day Oct 2026 09:14:00 GMT</pubDate>" +
                "<description><![CDATA[<p>Day $day</p><img src=\"https://p.example/$day.png\">]]></description></item>"
        } + "</channel></rss>"
        fetcher.answers["https://p.example/feed"] = FetchOutcome(status = 200, text = text)
        addFeed("https://p.example/feed")
    }

    @Test
    fun `opening an article readies its neighbors' bodies and first pictures`() = runTest {
        val model = model()
        model.addPictured()
        model.setUnreadOnly(false)
        val middle = model.view().visible[1]
        model.open(middle.id, heading = 1)
        runCurrent()
        val (ahead, behind) = neighborsOf(model.view().visible, middle.id, 1)
        assertEquals(listOf("day 6", "day 8"), listOf(ahead!!.title, behind!!.title))
        assertEquals(setOf(ahead.id, behind.id), model.state.value.aheadBodies.keys)
        assertTrue("Day 6" in model.state.value.aheadBodies.getValue(ahead.id))
        assertEquals(listOf("https://p.example/6.png", "https://p.example/8.png"), model.state.value.aheadPictures)
        model.close()
        assertTrue(model.state.value.aheadBodies.isEmpty())
        assertTrue(model.state.value.aheadPictures.isEmpty())
    }

    @Test
    fun `no pictures are fetched ahead while saving data`() = runTest {
        val model = model()
        model.addPictured()
        saving = true
        model.open(model.view().visible.first().id)
        runCurrent()
        assertEquals(1, model.state.value.aheadBodies.size)
        assertEquals(emptyList(), model.state.value.aheadPictures)
    }

    @Test
    fun `an article with no stored body shows its summary as plain text`() = runTest {
        val model = model()
        val text = "<rss><channel><title>T</title><item><guid>g</guid><title>t</title>" +
            "<description>Fish &amp;lt;3 chips</description></item></channel></rss>"
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = text)
        model.addFeed("https://a.example/feed")
        val id = model.view().visible.single().id
        model.awaitWrites()
        store.bodies[id] = ""
        model.open(id)
        runCurrent()
        assertEquals("<p>Fish &lt;3 chips</p>", model.state.value.body)
    }

    @Test
    fun `marking the open article unread from the list sticks when it is left`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = model.view().visible.single().id
        model.open(id)
        model.toggleReadFor(id)
        model.toggleReadFor(id)
        model.close()
        assertFalse(model.state.value.entries.single().read)
    }

    @Test
    fun `a failed save does not stop later ones`() = runTest {
        val model = model()
        store.failingSaves = 1
        model.addWithItems("https://a.example/feed", "one")
        model.addWithItems("https://b.example/feed", "two")
        model.awaitWrites()
        assertEquals(listOf("two"), store.entries.values.map { entry -> entry.title })
    }

    @Test
    fun `an article with no link of its own keeps its relative pictures`() = runTest {
        val model = model()
        val text = "<rss><channel><title>T</title><item><guid>g</guid><title>t</title>" +
            "<description><![CDATA[<img src=\"/pic.png\">]]></description></item></channel></rss>"
        fetcher.answers["https://a.example/blog/feed"] = FetchOutcome(status = 200, text = text)
        model.addFeed("https://a.example/blog/feed")
        model.open(model.view().visible.single().id)
        runCurrent()
        assertTrue("https://a.example/pic.png" in model.state.value.body, model.state.value.body)
    }

    @Test
    fun `a pasted address is written out as the web writes it before its feed id is made`() = runTest {
        val model = model()
        fetcher.answers["https://example.com/"] = FetchOutcome(status = 200, text = rss("E", "one"))
        model.addFeed("Example.com")
        assertEquals(feedId("https://example.com/"), model.state.value.feeds.single().id)
    }

    @Test
    fun `the background check reads nothing when no feed is due`() = runTest {
        val model = model()
        model.refreshDue()
        assertFalse(model.state.value.loaded)
    }

    @Test
    fun `read articles kept in the list go as soon as a refresh starts`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two")
        model.markAllRead()
        model.toggleTopic("x", fromArticle = true)
        assertEquals(2, model.titles().size)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 304)
        val refreshing = launch { model.refresh() }
        runCurrent()
        assertTrue(model.state.value.refreshing)
        assertEquals(emptyList(), model.titles())
        assertTrue(model.state.value.heldTopics.isEmpty())
        gate.complete(Unit)
        refreshing.join()
    }

    @Test
    fun `a renamed group keeps its sidebar place until the next refresh`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one", "two")
        model.addWithItems("https://b.example/feed", "three")
        model.setFolder(feedId("https://a.example/feed"), "A")
        model.setFolder(feedId("https://b.example/feed"), "B")
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 304)
        fetcher.answers["https://b.example/feed"] = FetchOutcome(status = 304)
        model.refresh()
        assertEquals(listOf("A", "B"), model.view().tree.map { row -> row.name })
        model.renameFolder("A", "Z")
        assertEquals(listOf("Z", "B"), model.view().tree.map { row -> row.name })
    }

    @Test
    fun `marking starred read marks every starred article, whatever the filters`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "budget", "sports")
        model.changeSettings { settings -> settings.copy(hideWords = listOf("budget")) }
        model.state.value.entries.forEach { entry -> model.toggleStarFor(entry.id) }
        model.select(Selection.Starred)
        model.markAllRead()
        assertTrue(model.state.value.entries.all { entry -> entry.read })
    }

    @Test
    fun `a renamed feed keeps its name`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = model.state.value.feeds.first().id
        model.renameFeed(id, "Mine")
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = rss("Theirs", "two"))
        model.refresh()
        assertEquals("Mine", model.state.value.feeds.first().title)
    }

    @Test
    fun `a feed shows as failing only after two failures in a row`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 500)
        model.refresh()
        assertEquals("", model.state.value.feeds.first().error)
        model.refresh()
        assertEquals("status:500", model.state.value.feeds.first().error)
        assertEquals(2, model.state.value.feeds.first().schedule.failures)
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 304)
        model.refresh()
        assertEquals("", model.state.value.feeds.first().error)
    }

    @Test
    fun `only due feeds are checked in the background`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        fetcher.requests.clear()
        model.refreshDue()
        assertEquals(emptyList(), fetcher.requests)
        now += MAX_INTERVAL_MS
        model.refreshDue()
        assertEquals(listOf("https://a.example/feed"), fetcher.requests)
    }

    @Test
    fun `the sidebar order holds until the next refresh`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.addWithItems("https://b.example/feed", "two", "three")
        model.refresh()
        fun order() = model.view().tree.map { row -> row.name }
        assertEquals(listOf("https://b.example/feed", "https://a.example/feed"), order())
        model.select(Selection.All)
        model.markAllRead()
        fetcher.answers["https://a.example/feed"] = FetchOutcome(status = 200, text = rss("https://a.example/feed", "one", "four"))
        fetcher.answers["https://b.example/feed"] = FetchOutcome(status = 304)
        model.refreshDue()
        assertEquals(listOf("https://b.example/feed", "https://a.example/feed"), order())
        now += MAX_INTERVAL_MS
        model.refresh()
        assertEquals(listOf("https://a.example/feed", "https://b.example/feed"), order())
    }

    @Test
    fun `groups come first, and a group is listed while it has unread feeds`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.addWithItems("https://b.example/feed", "two")
        val bId = feedId("https://b.example/feed")
        model.setFolder(bId, "News")
        val tree = model.view().tree
        assertTrue(tree.first() is TreeRow.Group)
        model.select(Selection.All)
        val rows = sidebarRows(model.state.value, model.view())
        assertEquals(listOf("News", "https://b.example/feed", "https://a.example/feed"), rows.map { row -> row.label })
        model.markAllRead()
        assertEquals(emptyList(), sidebarRows(model.state.value, model.view()))
    }

    @Test
    fun `adding a feed keeps the sidebar order, with the new feed last`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.addWithItems("https://b.example/feed", "two", "three")
        model.refresh()
        val before = model.view().tree.map { row -> row.name }
        assertEquals(listOf("https://b.example/feed", "https://a.example/feed"), before)
        val rank = model.state.value.frozenRank
        model.addWithItems("https://c.example/feed", "four", "five", "six")
        assertEquals(rank, model.state.value.frozenRank)
        assertEquals(before + "https://c.example/feed", model.view().tree.map { row -> row.name })
    }

    @Test
    fun `a page is searched for its feed`() = runTest {
        val model = model()
        fetcher.answers["https://blog.example/"] = FetchOutcome(
            status = 200,
            text = """<html><head><link rel="alternate" type="application/rss+xml" href="/posts.rss"></head></html>""",
        )
        fetcher.answers["https://blog.example/posts.rss"] = FetchOutcome(status = 200, text = rss("Blog", "post"))
        model.addFeed("blog.example")
        assertEquals("https://blog.example/posts.rss", model.state.value.feeds.single().url)
        assertEquals("Blog", model.state.value.feeds.single().title)
    }

    @Test
    fun `only web addresses can be added, and http is fetched as https`() = runTest {
        val model = model()
        val refused = assertFailsWith<FeedProblemException> { model.addFeed("ftp://files.example/feed") }
        assertEquals(FeedProblem.NotWebAddress, refused.problem)
        fetcher.answers["https://plain.example/feed"] = FetchOutcome(status = 200, text = rss("P", "one"))
        model.addFeed("http://plain.example/feed")
        assertEquals("https://plain.example/feed", model.state.value.feeds.single().url)
        assertTrue(fetcher.requests.none { url -> url.startsWith("http://") })
    }

    @Test
    fun `a small refresh waits for every feed with the short time`() = runTest {
        val timeouts = mutableListOf<Long>()
        val model = ReaderModel(
            store,
            preferences,
            { url, _, _, timeoutMs ->
                timeouts.add(timeoutMs)
                fetcher.answers[url] ?: FetchOutcome(status = 0)
            },
            backgroundScope,
            clock = { now },
            workDispatcher = StandardTestDispatcher(testScheduler),
            minRefreshMillis = 0,
            savingData = { saving },
        )
        model.addWithItems("https://a.example/feed", "one")
        timeouts.clear()
        model.refresh()
        assertEquals(listOf(FOREGROUND_TIMEOUT_MS), timeouts)
    }

    @Test
    fun `a feed that redirects to plain http is refused as not on https`() = runTest {
        val model = model()
        fetcher.answers["https://moved.example/feed"] = FetchOutcome(status = 0, insecure = true)
        val failure = assertFailsWith<FeedProblemException> { model.addFeed("https://moved.example/feed") }
        assertEquals(FeedProblem.NotHttps, failure.problem)
        assertTrue(model.state.value.feeds.isEmpty())
    }

    @Test
    fun `an address with no feed gives the reason`() = runTest {
        val model = model()
        fetcher.answers["https://nothing.example/"] = FetchOutcome(status = 200, text = "<html></html>")
        val failure = assertFailsWith<FeedProblemException> { model.addFeed("https://nothing.example/") }
        assertEquals(FeedProblem.NotAFeed, failure.problem)
        assertFalse(model.state.value.adding)
    }

    @Test
    fun `a site that refuses the address is the reason, not the usual feed paths tried after it`() = runTest {
        val model = model()
        fetcher.answers["https://refusing.example/"] = FetchOutcome(status = 403)
        fetcher.answers["https://refusing.example/feed"] = FetchOutcome(status = 404)
        fetcher.answers["https://refusing.example/rss"] = FetchOutcome(status = 404)
        val failure = assertFailsWith<FeedProblemException> { model.addFeed("https://refusing.example/") }
        assertEquals(FeedProblem.Status(403), failure.problem)
    }

    @Test
    fun `a search asks Feedly and reads its hits`() = runTest {
        val model = model()
        fetcher.answers[feedSearchAddress("cooking")] = FetchOutcome(
            status = 200,
            text = """{"results":[{"feedId":"feed/https://c.example/feed","title":"C"}]}""",
        )
        assertEquals(listOf("https://c.example/feed"), model.searchFeeds("cooking").map { hit -> hit.url })
        assertEquals(emptyList(), model.searchFeeds("nothing"))
        assertFalse(model.state.value.adding)
    }

    private suspend fun ReaderModel.addTagged() {
        val text = "<rss><channel><title>T</title>" +
            "<item><title>politics story</title><guid>p</guid><category>Politics</category></item>" +
            "<item><title>sports story</title><guid>s</guid><category>Sports</category></item>" +
            "</channel></rss>"
        fetcher.answers["https://t.example/feed"] = FetchOutcome(status = 200, text = text)
        addFeed("https://t.example/feed")
        select(Selection.All)
    }

    @Test
    fun `a topic turned off from an article is held until the list is refreshed`() = runTest {
        val model = model()
        model.addTagged()
        model.toggleTopic("politics", fromArticle = true)
        // The chips show the change at once, from the view's settings, while the list holds its rows.
        assertTrue(topicOff(model.view().viewSettings, "Politics"))
        assertFalse(topicOff(model.view().viewSettings, "Sports"))
        assertEquals(2, model.titles().size)
        model.toggleTopic("Sports", fromArticle = true)
        assertTrue(topicOff(model.view().viewSettings, "Sports"))
        assertEquals(2, model.titles().size)
        assertEquals(mapOf("all" to emptyList<String>()), model.state.value.heldTopics)
        fetcher.answers["https://t.example/feed"] = FetchOutcome(status = 304)
        model.refresh()
        assertEquals(emptyList(), model.titles())
    }

    @Test
    fun `held topics end with a view change, the unread switch or a toggle from settings`() = runTest {
        val model = model()
        model.addTagged()
        model.toggleTopic("Politics", fromArticle = true)
        model.setUnreadOnly(true)
        assertEquals(listOf("sports story"), model.titles())
        model.toggleTopic("Politics", fromArticle = true)
        assertEquals(listOf("sports story"), model.titles())
        model.toggleTopic("Sports", fromArticle = false)
        assertEquals(listOf("politics story"), model.titles())
        model.toggleTopic("Sports", fromArticle = true)
        model.select(Selection.Starred)
        model.select(Selection.All)
        assertEquals(2, model.titles().size)
        assertTrue(model.state.value.heldTopics.isEmpty())
    }

    @Test
    fun `in a feed view a topic toggles that feed's own settings`() = runTest {
        val model = model()
        model.addTagged()
        val id = feedId("https://t.example/feed")
        model.select(Selection.FeedView(id))
        model.toggleTopic("Sports", fromArticle = false)
        model.select(Selection.All)
        assertEquals(listOf("politics story"), model.titles())
    }

    @Test
    fun `every topic is listed`() = runTest {
        val model = model()
        val items = (1..30).joinToString("") { index -> "<item><title>t$index</title><guid>$index</guid><category>topic $index</category></item>" }
        fetcher.answers["https://many.example/feed"] = FetchOutcome(status = 200, text = "<rss><channel><title>M</title>$items</channel></rss>")
        model.addFeed("https://many.example/feed")
        assertEquals(30, model.view().topics.size)
    }

    @Test
    fun `an OPML import adds new feeds with their groups`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val result = model.importOpml(
            """<opml><body><outline title="Tech"><outline xmlUrl="https://a.example/feed"/>
               <outline title="B" xmlUrl="https://b.example/feed"/></outline>
               <outline xmlUrl="javascript:alert(1)"/></body></opml>""",
        )
        assertEquals(ImportResult(found = 2, added = 1), result)
        val added = model.state.value.feeds.first { feed -> feed.url == "https://b.example/feed" }
        assertEquals("Tech", added.folder)
        assertEquals("B", added.title)
        assertFalse(added.renamed)
    }

    @Test
    fun `an imported feed takes its own title after its first check`() = runTest {
        val model = model()
        fetcher.answers["https://b.example/feed"] = FetchOutcome(status = 200, text = rss("Own name", "one"))
        model.importOpml("""<opml><body><outline title="Old name" xmlUrl="https://b.example/feed"/></body></opml>""")
        assertEquals("Old name", model.state.value.feeds.single().title)
        model.refresh()
        assertEquals("Own name", model.state.value.feeds.single().title)
    }

    @Test
    fun `unread counts leave out what each feed's and view's words hide`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "budget news", "sports", "weather")
        model.addWithItems("https://b.example/feed", "budget plan", "music")
        val aId = feedId("https://a.example/feed")
        val bId = feedId("https://b.example/feed")
        model.select(Selection.FeedView(aId))
        model.changeSettings { settings -> settings.copy(hideWords = listOf("sports")) }
        model.setFolder(aId, "News")
        model.setFolder(bId, "News")
        model.select(Selection.Folder("News"))
        model.changeSettings { settings -> settings.copy(hideWords = listOf("weather")) }
        model.select(Selection.All)
        model.changeSettings { settings -> settings.copy(hideWords = listOf("music")) }
        val view = model.view()
        assertEquals(2, view.unreadByFeed[aId])
        assertEquals(2, view.unreadByFeed[bId])
        assertEquals(3, (view.tree.single() as TreeRow.Group).unread)
        assertEquals(3, view.unreadTotal)
    }

    @Test
    fun `under unread only a feed whose unread articles are all hidden is not listed`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "sports")
        model.addWithItems("https://b.example/feed", "music")
        model.select(Selection.FeedView(feedId("https://a.example/feed")))
        model.changeSettings { settings -> settings.copy(hideWords = listOf("sports")) }
        model.select(Selection.All)
        assertEquals(listOf("https://b.example/feed"), sidebarRows(model.state.value, model.view()).map { row -> row.label })
    }

    @Test
    fun `mark all read also marks what the count includes but the list does not show`() = runTest {
        val model = model()
        model.addTagged()
        model.toggleTopic("Politics", fromArticle = false)
        model.toggleTopic("Politics", fromArticle = true)
        assertEquals(listOf("sports story"), model.titles())
        assertEquals(2, model.view().unreadTotal)
        model.markAllRead()
        assertTrue(model.state.value.entries.all { entry -> entry.read })
    }

    @Test
    fun `a feed's own words apply in every view and the view's words in its own`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "budget news", "sports")
        model.changeSettings { settings -> settings.copy(hideWords = listOf("budget")) }
        assertEquals(listOf("sports"), model.titles())
        model.select(Selection.All)
        assertEquals(listOf("sports"), model.titles())
        model.awaitWrites()
        assertEquals(listOf("budget"), store.settings["feed:${feedId("https://a.example/feed")}"]!!.hideWords)
    }

    @Test
    fun `renaming a group onto another merges them and deleting one keeps its feeds`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        model.addWithItems("https://b.example/feed", "two")
        model.setFolder(feedId("https://a.example/feed"), "A")
        model.setFolder(feedId("https://b.example/feed"), "B")
        model.renameFolder("A", "B")
        assertEquals(listOf("B"), model.view().folders)
        assertEquals(Selection.Folder("B"), model.state.value.selection)
        model.deleteFolder("B")
        assertEquals(emptyList(), model.view().folders)
        assertEquals(2, model.state.value.feeds.size)
    }

    @Test
    fun `unsubscribing removes the feed and its articles`() = runTest {
        val model = model()
        model.addWithItems("https://a.example/feed", "one")
        val id = model.state.value.feeds.first().id
        model.removeFeed(id)
        assertEquals(Selection.All, model.state.value.selection)
        assertTrue(model.state.value.entries.isEmpty())
        model.awaitWrites()
        assertTrue(store.feeds.isEmpty())
        assertNotEquals(0, preferences.lastView.length)
    }
}
