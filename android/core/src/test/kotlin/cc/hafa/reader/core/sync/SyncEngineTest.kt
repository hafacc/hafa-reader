package cc.hafa.reader.core.sync

import cc.hafa.reader.core.DEFAULT_SETTINGS
import cc.hafa.reader.core.FakeFetcher
import cc.hafa.reader.core.Feed
import cc.hafa.reader.core.FetchOutcome
import cc.hafa.reader.core.MemoryPreferences
import cc.hafa.reader.core.MemoryStore
import cc.hafa.reader.core.ReaderModel
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.feedId
import cc.hafa.reader.core.rss
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An account in memory that behaves like Firestore for sync's purposes. */
private class FakeAccount : AccountStore {
    val subscriptions = linkedMapOf<String, SubscriptionDocument>()
    val settings = linkedMapOf<String, SettingsDocument>()
    val entries = linkedMapOf<String, Pair<EntryDocument, Long>>()
    val bodies = linkedMapOf<String, String>()
    val bodyExpiries = linkedMapOf<String, Long?>()
    var account: AccountDocument? = null
    // Writes that changed only an entry's expiry.
    var expiryWrites = 0
    var commits = 0
    // How many of the next commits fail, as when offline or rejected.
    var failingCommits = 0
    private var serverTime = 1_000L
    private val subscriptionListeners = mutableListOf<(Listing<SubscriptionDocument>) -> Unit>()
    private val settingsListeners = mutableListOf<(Listing<SettingsDocument>, AccountCopy?) -> Unit>()
    private val entryListeners = mutableListOf<Pair<Long, (List<EntryChange>) -> Unit>>()

    override fun listenSubscriptions(
        reconciled: () -> Boolean,
        onListing: (Listing<SubscriptionDocument>) -> Unit,
        onError: (Throwable) -> Unit,
    ): Stop {
        subscriptionListeners.add(onListing)
        onListing(Listing(subscriptions.toList(), subscriptions.keys.toSet()))
        return Stop { subscriptionListeners.remove(onListing) }
    }

    override fun listenSettings(
        reconciled: () -> Boolean,
        onListing: (Listing<SettingsDocument>, AccountCopy?) -> Unit,
        onError: (Throwable) -> Unit,
    ): Stop {
        settingsListeners.add(onListing)
        settingsErrors.add(onError)
        onListing(Listing(settings.toList(), settings.keys.toSet()), AccountCopy(account))
        return Stop { settingsListeners.remove(onListing) }
    }

    override suspend fun newestEntrySyncedAt(): Long = entries.values.maxOfOrNull { (_, syncedAt) -> syncedAt } ?: 0

    override fun listenEntries(sinceMs: Long, onChanges: (List<EntryChange>) -> Unit, onError: (Throwable) -> Unit): Stop {
        val listener = sinceMs to onChanges
        entryListeners.add(listener)
        val changes = entries.filter { (_, value) -> value.second > sinceMs }.map { (id, value) -> EntryChange(id, value.first, value.second) }
        onChanges(changes)
        return Stop { entryListeners.remove(listener) }
    }

    override suspend fun readEntriesPage(after: PageCursor?, limit: Int): List<Pair<String, EntryDocument>> {
        val ordered = entries.map { (id, value) -> id to value.first }
            .sortedWith(compareByDescending<Pair<String, EntryDocument>> { (_, document) -> document.published }.thenByDescending { (id, _) -> id })
        val start = if (after == null) {
            0
        } else {
            ordered.indexOfFirst { (id, document) -> document.published < after.published || (document.published == after.published && id < after.id) }
                .let { index -> if (index == -1) ordered.size else index }
        }
        return ordered.drop(start).take(limit)
    }

    override suspend fun readExpiryPage(after: String, limit: Int): List<ExpiryRow> {
        if (failingExpiryPages > 0) {
            failingExpiryPages -= 1
            error("page read failed")
        }
        return readExpiryRows(after, limit)
    }

    private fun readExpiryRows(after: String, limit: Int): List<ExpiryRow> =
        entries.keys.sorted().filter { id -> id > after }.take(limit).map { id ->
            val document = entries.getValue(id).first
            ExpiryRow(id, document, document.expireAt)
        }

    override suspend fun setExpiries(changes: List<Pair<String, ExpiryChange>>) {
        for ((id, change) in changes) {
            val (document, syncedAt) = entries.getValue(id)
            val expireAt = when (change) {
                is ExpiryChange.At -> change.time
                ExpiryChange.Clear -> null
            }
            entries[id] = document.copy(expireAt = expireAt) to syncedAt
            expiryWrites += 1
        }
    }

    /** Another device chooses a retention. */
    fun chooseElsewhere(document: AccountDocument) {
        account = document
        settingsListeners.toList().forEach { listener -> listener(Listing(emptyList(), null), AccountCopy(document)) }
    }

    override suspend fun entriesWithIds(ids: List<String>): IdLookup {
        val found = ids.filter { id -> id in entries }
        return IdLookup(found.toSet(), found.map { id -> id to entries.getValue(id).first })
    }

    // Entry ids whose writes the rules refuse.
    val refusedEntries = mutableSetOf<String>()

    override suspend fun commit(writes: List<DocumentWrite>) {
        if (failingCommits > 0) {
            failingCommits -= 1
            error("write failed")
        }
        if (writes.any { write -> write is DocumentWrite.Entry && write.id in refusedEntries }) {
            throw RefusedWriteException(IllegalStateException("permission denied"))
        }
        commits += 1
        val changedSubscriptions = mutableListOf<Pair<String, SubscriptionDocument>>()
        val changedSettings = mutableListOf<Pair<String, SettingsDocument>>()
        val changedEntries = mutableListOf<EntryChange>()
        var accountChanged = false
        for (write in writes) {
            serverTime += 1
            when (write) {
                is DocumentWrite.Subscription -> {
                    subscriptions[write.id] = write.document
                    changedSubscriptions.add(write.id to write.document)
                }
                is DocumentWrite.Settings -> {
                    settings[write.document.key] = write.document
                    changedSettings.add(write.document.key to write.document)
                }
                is DocumentWrite.Entry -> {
                    val before = entries[write.id]?.first
                    val document = if (write.merge && before != null) {
                        write.document.copy(bodyHash = write.document.bodyHash.ifEmpty { before.bodyHash })
                    } else {
                        write.document
                    }
                    entries[write.id] = document to serverTime
                    changedEntries.add(EntryChange(write.id, document, serverTime))
                }
                is DocumentWrite.Account -> {
                    account = write.document
                    accountChanged = true
                }
                is DocumentWrite.AccountStamped -> {
                    account = account?.copy(stampedDays = write.stampedDays)
                    accountChanged = true
                }
            }
        }
        // own writes come back, as Firestore listeners deliver them
        if (changedSubscriptions.isNotEmpty()) {
            subscriptionListeners.toList().forEach { listener -> listener(Listing(changedSubscriptions, null)) }
        }
        if (changedSettings.isNotEmpty() || accountChanged) {
            settingsListeners.toList().forEach { listener -> listener(Listing(changedSettings, null), AccountCopy(account)) }
        }
        if (changedEntries.isNotEmpty()) {
            entryListeners.toList().forEach { (_, listener) -> listener(changedEntries) }
        }
    }

    /** Another device changes an entry. */
    fun writeElsewhere(id: String, document: EntryDocument) {
        serverTime += 1
        entries[id] = document to serverTime
        entryListeners.toList().forEach { (_, listener) -> listener(listOf(EntryChange(id, document, serverTime))) }
    }

    override suspend fun deleteEntriesOfFeed(feedId: String) {
        entries.entries.removeIf { (_, value) -> value.first.feedId == feedId }
    }

    // Bodies looked up on the server.
    var bodyLookups = 0

    override suspend fun bodyExists(hash: String): Boolean {
        bodyLookups += 1
        return hash in bodies
    }

    override suspend fun createBody(hash: String, text: String, expireAt: Long?) {
        bodies[hash] = text
        bodyExpiries[hash] = expireAt
    }

    override suspend fun readBody(hash: String) = bodies[hash] ?: ""

    // Whether emptying the account fails, as when offline.
    var deleteDataFails = false
    // How many of the next pages of a retention pass fail to read.
    var failingExpiryPages = 0
    private val settingsErrors = mutableListOf<(Throwable) -> Unit>()

    /** The settings listener stops with an error, as Firestore listeners do, and is never called again. */
    fun breakSettingsListener() {
        val failing = settingsErrors.toList()
        settingsListeners.clear()
        settingsErrors.clear()
        failing.forEach { onError -> onError(IllegalStateException("listener failed")) }
    }

    /** Listeners now running: subscriptions, settings and entries. */
    val listenerCount: Int get() = subscriptionListeners.size + settingsListeners.size + entryListeners.size

    override suspend fun waitForPendingWrites() = Unit

    override suspend fun deleteAccountData() {
        check(!deleteDataFails) { "could not empty the account" }
        subscriptions.clear()
        settings.clear()
        entries.clear()
    }
}

private class FakeAuth(signedIn: String? = null) : AccountAuth {
    override val state = MutableStateFlow(AuthState(known = true, uid = signedIn, email = signedIn?.let { uid -> "$uid@example.com" } ?: ""))
    var deleted = false
    var nextUid = "alice"
    var chooserClosed = false
    var deleteFails = false

    override suspend fun signIn(): SignInResult {
        state.value = AuthState(known = true, uid = nextUid, email = "$nextUid@example.com", photoUrl = "https://lh3.googleusercontent.com/a/$nextUid=s96-c")
        return SignInResult.SignedIn
    }

    override suspend fun signOut() {
        state.value = AuthState(known = true)
    }

    override suspend fun ensureRecentSignIn() {
        check(!chooserClosed) { "sign-in for deleting was closed" }
    }

    override suspend fun deleteUser() {
        check(!deleteFails) { "could not delete the sign-in" }
        deleted = true
    }
}

private class MemorySyncPreferences : SyncPreferences {
    val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class Backend(val accounts: Map<String, FakeAccount>) : SyncBackend {
    override val online: StateFlow<Boolean> = MutableStateFlow(true)

    override fun storeFor(uid: String): AccountStore = accounts.getValue(uid)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val store = MemoryStore()
    private val fetcher = FakeFetcher()
    private val alice = FakeAccount()
    private val bob = FakeAccount()
    private val auth = FakeAuth()
    private val syncPreferences = MemorySyncPreferences()
    private val timing = SyncTiming(flushDelayMs = 100, retryMs = 1000, olderPauseMs = 10, overlapMs = 0)

    private var now = 1_800_000_000_000L

    private fun TestScope.reader(): ReaderModel =
        ReaderModel(
            store,
            MemoryPreferences(),
            fetcher,
            backgroundScope,
            clock = { now },
            workDispatcher = StandardTestDispatcher(testScheduler),
            minRefreshMillis = 0,
        )

    private fun TestScope.engine(model: ReaderModel): SyncEngine =
        SyncEngine(auth, Backend(mapOf("alice" to alice, "bob" to bob)), model.syncHost, syncPreferences, backgroundScope, timing = timing)
            .also { engine -> model.sync = engine }

    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private suspend fun ReaderModel.addWithItems(address: String, vararg items: String) {
        fetcher.answers[address] = FetchOutcome(status = 200, text = rss(address, *items))
        addFeed(address)
    }

    @Test
    fun `the first sign-in uploads this device's feeds, entries, settings and bodies`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one", "two")
        model.changeSettings { settings -> settings.copy(hideWords = listOf("x")) }
        model.awaitWrites()
        engine.signIn()
        settle()
        val id = feedId("https://a.example/feed")
        assertEquals("https://a.example/feed", alice.subscriptions.getValue(id).url)
        assertEquals(2, alice.entries.size)
        assertEquals(listOf("x"), alice.settings.getValue("feed:$id").hideWords)
        assertEquals(2, alice.bodies.size)
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
        assertEquals("alice@example.com", engine.ui.value.report.email)
        assertEquals("https://lh3.googleusercontent.com/a/alice=s96-c", engine.ui.value.report.photoUrl)
    }

    @Test
    fun `a mark made on another device arrives and is not sent back`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        val (id, pair) = alice.entries.entries.single().toPair()
        val commits = alice.commits
        alice.writeElsewhere(id, pair.first.copy(read = true, starred = true, updatedAt = pair.first.updatedAt + 1_000_000))
        settle()
        val entry = model.state.value.entries.single()
        assertTrue(entry.read)
        assertTrue(entry.starred)
        assertEquals(commits, alice.commits)
    }

    @Test
    fun `marks and unsubscribes made here go to the account`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        model.toggleStarFor(model.state.value.entries.single().id)
        settle()
        assertTrue(alice.entries.values.single().first.starred)
        model.removeFeed(feedId("https://a.example/feed"))
        settle()
        assertTrue(alice.subscriptions.values.single().deleted)
        assertTrue(alice.entries.isEmpty())
    }

    @Test
    fun `a new device takes the account's feeds and entries`() = runTest {
        val first = reader()
        val firstEngine = engine(first)
        first.addWithItems("https://a.example/feed", "one")
        firstEngine.signIn()
        settle()
        firstEngine.signOut()
        settle()
        val secondStore = MemoryStore()
        val second = ReaderModel(secondStore, MemoryPreferences(), fetcher, backgroundScope, workDispatcher = StandardTestDispatcher(testScheduler), minRefreshMillis = 0)
        second.load()
        val secondPreferences = MemorySyncPreferences()
        val secondEngine = SyncEngine(auth, Backend(mapOf("alice" to alice)), second.syncHost, secondPreferences, backgroundScope, timing = timing)
        second.sync = secondEngine
        secondEngine.signIn()
        settle()
        assertEquals(listOf("https://a.example/feed"), second.state.value.feeds.map { feed -> feed.url })
        assertEquals(1, second.state.value.entries.size)
        second.open(second.state.value.entries.single().id)
        settle()
        assertTrue("About one" in second.state.value.body)
    }

    @Test
    fun `another account waits for a choice, and replacing takes only that account's data`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        engine.signOut()
        settle()
        auth.nextUid = "bob"
        engine.signIn()
        settle()
        assertEquals(SyncStatus.Waiting, engine.ui.value.report.status)
        assertTrue(bob.subscriptions.isEmpty())
        engine.replaceLocal()
        settle()
        assertTrue(model.state.value.feeds.isEmpty())
        assertTrue(bob.subscriptions.isEmpty())
        assertEquals(Selection.All, model.state.value.selection)
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
    }

    @Test
    fun `deleting the account empties it, deletes the sign-in and keeps this device's data`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        engine.deleteAccount()
        settle()
        assertTrue(alice.subscriptions.isEmpty())
        assertTrue(alice.entries.isEmpty())
        assertTrue(auth.deleted)
        assertEquals(SyncStatus.Off, engine.ui.value.report.status)
        assertFalse(model.state.value.feeds.isEmpty())
        assertFalse(engine.ui.value.deleting)
    }

    @Test
    fun `a change made before sync has started still reaches the account`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        // the app starts again, still signed in: the reader loads before sync is ready
        val restarted = ReaderModel(store, MemoryPreferences(), fetcher, backgroundScope, workDispatcher = StandardTestDispatcher(testScheduler), minRefreshMillis = 0)
        restarted.load()
        restarted.toggleStarFor(restarted.state.value.entries.single().id)
        val restartedEngine = SyncEngine(FakeAuth("alice"), Backend(mapOf("alice" to alice)), restarted.syncHost, syncPreferences, backgroundScope, timing = timing)
        restarted.sync = restartedEngine
        settle()
        assertTrue(alice.entries.values.single().first.starred)
    }

    @Test
    fun `an account delete that fails before emptying it keeps syncing`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        alice.deleteDataFails = true
        engine.deleteAccount()
        model.toggleStarFor(model.state.value.entries.single().id)
        settle()
        assertTrue(engine.ui.value.deleteFailed)
        assertFalse(auth.deleted)
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
        assertEquals("alice@example.com", engine.ui.value.report.email)
        assertTrue(alice.entries.values.single().first.starred)
    }

    @Test
    fun `an account delete that fails after emptying it signs out without uploading again`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        auth.deleteFails = true
        engine.deleteAccount()
        settle()
        assertTrue(engine.ui.value.deleteFailed)
        assertFalse(auth.deleted)
        assertEquals(SyncStatus.Off, engine.ui.value.report.status)
        assertTrue(alice.entries.isEmpty())
        assertTrue(alice.subscriptions.isEmpty())
        assertFalse(model.state.value.entries.isEmpty())
    }

    @Test
    fun `closing the chooser before an account delete leaves the account alone`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        auth.chooserClosed = true
        engine.deleteAccount()
        settle()
        assertTrue(engine.ui.value.deleteFailed)
        assertEquals(1, alice.subscriptions.size)
        assertEquals(1, alice.entries.size)
        model.toggleStarFor(model.state.value.entries.single().id)
        settle()
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
        assertTrue(alice.entries.values.single().first.starred)
    }

    @Test
    fun `a failed write is tried again`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        alice.failingCommits = 1
        model.toggleStarFor(model.state.value.entries.single().id)
        advanceTimeBy(500)
        runCurrent()
        assertFalse(alice.entries.values.single().first.starred)
        assertEquals(SyncStatus.Error, engine.ui.value.report.status)
        settle()
        assertTrue(alice.entries.values.single().first.starred)
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
    }

    @Test
    fun `a feed's account entries stay until its unsubscribe is written`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        alice.failingCommits = 1
        model.removeFeed(feedId("https://a.example/feed"))
        advanceTimeBy(500)
        runCurrent()
        assertFalse(alice.subscriptions.values.single().deleted)
        assertEquals(1, alice.entries.size)
        settle()
        assertTrue(alice.subscriptions.values.single().deleted)
        assertTrue(alice.entries.isEmpty())
    }

    @Test
    fun `signing out right after a change still sends it`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        model.toggleStarFor(model.state.value.entries.single().id)
        engine.signOut()
        settle()
        assertTrue(alice.entries.values.single().first.starred)
        assertEquals("", engine.ui.value.report.photoUrl)
    }

    @Test
    fun `feeds, entries and settings too long for the account stay on this device`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.load()
        engine.signIn()
        settle()
        val longUrl = "https://a.example/" + "x".repeat(9000)
        val feed = Feed(feedId(longUrl), longUrl, "Long", "", "", renamed = false, folder = "", error = "", updatedAt = 1)
        val longGuid = "g".repeat(9000)
        model.syncHost.apply(RemoteChange(feeds = listOf(feed)))
        model.sync?.queue(LocalChange(feeds = listOf(feed), entries = listOf(sampleEntry().copy(guid = longGuid))))
        model.sync?.queue(LocalChange(settings = mapOf("folder:" + "k".repeat(1200) to SavedSettings(DEFAULT_SETTINGS, 1))))
        settle()
        assertTrue(alice.subscriptions.isEmpty())
        assertTrue(alice.entries.isEmpty())
        assertTrue(alice.settings.isEmpty())
    }

    @Test
    fun `a feed's own new name is kept here and not sent`() = runTest {
        val model = reader()
        val engine = engine(model)
        val address = "https://a.example/feed"
        model.addWithItems(address, "one")
        engine.signIn()
        settle()
        val id = feedId(address)
        val before = model.state.value.feeds.single()
        fetcher.answers[address] = FetchOutcome(status = 200, text = rss("Renamed by the site", "one", "two"))
        model.refresh()
        settle()
        val after = model.state.value.feeds.single()
        assertEquals("Renamed by the site", after.title)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(address, alice.subscriptions.getValue(id).title)
        assertEquals(2, alice.entries.size)
    }

    @Test
    fun `choosing a retention saves it and stamps the account's entries, starred ones excepted`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one", "two")
        engine.signIn()
        settle()
        val starred = model.state.value.entries.first()
        model.toggleStarFor(starred.id)
        settle()
        val syncedBefore = alice.entries.mapValues { (_, value) -> value.second }
        engine.setRetention(30)
        settle()
        assertEquals(30, alice.account?.retentionDays)
        assertEquals(30, engine.ui.value.report.retention)
        for (entry in model.state.value.entries) {
            val expected = if (entry.id == starred.id) null else entry.addedAt + 30L * 24 * 60 * 60 * 1000
            assertEquals(expected, alice.entries.getValue(entry.id).first.expireAt)
        }
        assertEquals(1, alice.expiryWrites)
        // only the expiry changed, so other devices download nothing again
        assertEquals(syncedBefore, alice.entries.mapValues { (_, value) -> value.second })
        assertEquals(null, parseState(syncPreferences.get(stateKey("alice"))).restamp)
        engine.setRetention(null)
        settle()
        assertTrue(alice.entries.values.all { (document, _) -> document.expireAt == null })
        assertEquals(0, alice.account?.retentionDays)
    }

    @Test
    fun `starring clears an entry's expiry and unstarring sets it again`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        engine.setRetention(90)
        settle()
        val entry = model.state.value.entries.single()
        val expiry = entry.addedAt + 90L * 24 * 60 * 60 * 1000
        assertEquals(expiry, alice.entries.getValue(entry.id).first.expireAt)
        model.toggleStarFor(entry.id)
        settle()
        assertEquals(null, alice.entries.getValue(entry.id).first.expireAt)
        model.toggleStarFor(entry.id)
        settle()
        assertEquals(expiry, alice.entries.getValue(entry.id).first.expireAt)
    }

    @Test
    fun `a newer retention from another device is taken up, and uploads follow it`() = runTest {
        val model = reader()
        val engine = engine(model)
        engine.signIn()
        settle()
        alice.chooseElsewhere(AccountDocument(365, 5, stampedDays = 365))
        settle()
        assertEquals(365, engine.ui.value.report.retention)
        model.addWithItems("https://a.example/feed", "one")
        settle()
        val entry = model.state.value.entries.single()
        assertEquals(entry.addedAt + 365L * 24 * 60 * 60 * 1000, alice.entries.getValue(entry.id).first.expireAt)
        assertTrue(alice.bodyExpiries.values.all { expiry -> expiry != null })
        // the other device made no pass of its own here
        assertEquals(0, alice.expiryWrites)
    }

    @Test
    fun `an older retention in the account is replaced by this device's`() = runTest {
        val model = reader()
        val engine = engine(model)
        engine.signIn()
        settle()
        engine.setRetention(30)
        settle()
        val chosen = alice.account
        alice.chooseElsewhere(AccountDocument(90, 1, stampedDays = 90))
        settle()
        assertEquals(chosen, alice.account)
        assertEquals(30, engine.ui.value.report.retention)
    }

    /** Sign in, upload one article, sign in again, let its body expire from the account, then open it [age] after it was added. */
    private suspend fun TestScope.lookupsAfterOpening(age: Long): ReaderModel {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        engine.signOut()
        settle()
        engine.signIn()
        settle()
        alice.bodies.clear()
        alice.bodyLookups = 0
        now += age
        model.open(model.state.value.entries.single().id)
        settle()
        return model
    }

    @Test
    fun `opening an article older than a body can last uploads this device's copy again`() = runTest {
        lookupsAfterOpening((365L + 7) * 24 * 60 * 60 * 1000 + 60_000)
        assertEquals(1, alice.bodyLookups)
        assertEquals(1, alice.bodies.size)
    }

    @Test
    fun `a newer article's body cannot have expired, so nothing is looked up`() = runTest {
        lookupsAfterOpening((365L + 7) * 24 * 60 * 60 * 1000 - 60_000)
        assertEquals(0, alice.bodyLookups)
        assertTrue(alice.bodies.isEmpty())
    }

    @Test
    fun `a retention pass cut short by signing out finishes after signing in again`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        alice.failingExpiryPages = 1
        engine.setRetention(30)
        runCurrent()
        engine.signOut()
        settle()
        assertEquals(Restamp(30, ""), parseState(syncPreferences.get(stateKey("alice"))).restamp)
        assertTrue(alice.entries.values.all { (document, _) -> document.expireAt == null })
        engine.signIn()
        settle()
        val entry = model.state.value.entries.single()
        assertEquals(entry.addedAt + 30L * 24 * 60 * 60 * 1000, alice.entries.getValue(entry.id).first.expireAt)
        assertEquals(null, parseState(syncPreferences.get(stateKey("alice"))).restamp)
    }

    @Test
    fun `a device finishes a pass another device left, and records it in the account`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        assertEquals(0, alice.expiryWrites)
        // another device chose 90 days and stopped before stamping this entry
        alice.chooseElsewhere(AccountDocument(90, 5, stampedDays = 0))
        settle()
        val entry = model.state.value.entries.single()
        assertEquals(entry.addedAt + 90L * 24 * 60 * 60 * 1000, alice.entries.getValue(entry.id).first.expireAt)
        assertEquals(90, alice.account?.stampedDays)
        assertEquals(null, parseState(syncPreferences.get(stateKey("alice"))).restamp)
    }

    @Test
    fun `an account whose entries carry its retention runs no pass`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        alice.account = AccountDocument(30, 5, stampedDays = 30)
        engine.signIn()
        settle()
        assertEquals(0, alice.expiryWrites)
    }

    @Test
    fun `signing out sends a change whose write failed, without waiting for its retry`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one")
        engine.signIn()
        settle()
        alice.failingCommits = 1
        model.toggleStarFor(model.state.value.entries.single().id)
        advanceTimeBy(timing.flushDelayMs + 1)
        runCurrent()
        assertFalse(alice.entries.values.single().first.starred)
        engine.signOut()
        runCurrent()
        assertTrue(alice.entries.values.single().first.starred)
    }

    @Test
    fun `entries found here are uploaded again after a failed try`() = runTest {
        val model = reader()
        val engine = engine(model)
        engine.signIn()
        settle()
        alice.failingCommits = 1
        model.addWithItems("https://a.example/feed", "one")
        settle()
        assertEquals(1, alice.entries.size)
    }

    @Test
    fun `a listener that fails and comes back leaves no error behind`() = runTest {
        val model = reader()
        val engine = engine(model)
        engine.signIn()
        settle()
        alice.breakSettingsListener()
        runCurrent()
        assertEquals(SyncStatus.Error, engine.ui.value.report.status)
        settle()
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
    }

    @Test
    fun `in the background only changes are sent, until the app is on screen`() = runTest {
        val model = reader()
        val engine = SyncEngine(auth, Backend(mapOf("alice" to alice, "bob" to bob)), model.syncHost, syncPreferences, backgroundScope, timing = timing, inBackground = true)
            .also { started -> model.sync = started }
        engine.signIn()
        settle()
        model.addWithItems("https://a.example/feed", "one")
        settle()
        assertEquals(1, alice.entries.size)
        assertEquals(0, alice.listenerCount)
        assertFalse(engine.ui.value.report.feedsKnown)
        engine.enterForeground()
        settle()
        assertEquals(3, alice.listenerCount)
        assertTrue(engine.ui.value.report.feedsKnown)
    }

    @Test
    fun `a write the rules refuse is dropped and the rest of its batch still lands`() = runTest {
        val model = reader()
        val engine = engine(model)
        model.addWithItems("https://a.example/feed", "one", "two")
        engine.signIn()
        settle()
        val (refused, kept) = model.state.value.entries
        alice.refusedEntries.add(refused.id)
        model.toggleStarFor(refused.id)
        model.toggleStarFor(kept.id)
        settle()
        assertTrue(alice.entries.getValue(kept.id).first.starred)
        assertFalse(alice.entries.getValue(refused.id).first.starred)
        assertEquals(SyncStatus.Synced, engine.ui.value.report.status)
        val commits = alice.commits
        settle()
        assertEquals(commits, alice.commits)
    }
}
