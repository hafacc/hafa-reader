package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** How long sync waits, in milliseconds; tests shorten them. */
data class SyncTiming(
    /** Wait after a local change before writing, so bursts go in one batch. */
    val flushDelayMs: Long = 1500,
    /** Wait before trying the account again after a failed read. */
    val retryMs: Long = 30_000,
    /** Pause between pages of entries older than the recent window. */
    val olderPauseMs: Long = 2000,
    /** The entry listener starts this far before the newest change seen, in case of clock skew. */
    val overlapMs: Long = 10 * 60 * 1000,
)

// Longest wait between upload tries, as a power of two times the retry wait.
private const val MAX_BACKOFF_SHIFT = 3

private val logger: Logger = Logger.getLogger("cc.hafa.reader.sync")

/**
 * Sync for one signed-in account; the same steps as the web's session in
 * `web/src/lib/sync/firebase.ts`.
 *
 * It listens to the account's subscriptions, settings and entries, merges
 * them with what is here by last write wins, and writes local changes in
 * batches shortly after they happen. The first pass reads every account
 * entry newest first, the last 90 days straight away and older pages after,
 * uploading this device's entries the account lacks; progress is saved so a
 * restart carries on. Nothing that came from the account is sent back.
 */
class Session(
    private val store: AccountStore,
    private val uid: String,
    private val host: SyncHost,
    private val preferences: SyncPreferences,
    private val parentScope: CoroutineScope,
    private val online: StateFlow<Boolean>,
    private val clock: () -> Long,
    private val onStatus: () -> Unit,
    private val timing: SyncTiming = SyncTiming(),
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val bodyLock = Mutex()
    private val stops = mutableListOf<Stop>()
    private val outbox = Outbox()
    private val flushRetrying = AtomicBoolean(false)
    private val checkedBodies: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val broken: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val subscriptionsReady = CompletableDeferred<Unit>()
    private val outstanding = AtomicInteger(0)

    private val stateLock = Any()

    @Volatile
    private var state: SyncState = parseState(preferences.get(stateKey(uid)))

    @Volatile
    private var cursorHeld = false

    @Volatile
    private var flushJob: Job? = null

    @Volatile
    private var creating: Job? = null

    @Volatile
    private var subscriptionsReconciled = false

    @Volatile
    private var settingsReconciled = false

    @Volatile
    private var stopped = false

    @Volatile
    private var merging = false

    @Volatile
    private var writeFailed = false

    // Whether the pass applying a retention to the account's entries is running.
    private val restamping = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    // Failed tries in a row at uploading the entries found here, which sets the wait before the next.
    private val createFailures = AtomicInteger(0)

    /** Whether entries older than the recent window are still loading. */
    @Volatile
    var loadingOlder = false
        private set

    /** Whether the account's feeds have come from the server and are stored here. */
    @Volatile
    var feedsKnown = false
        private set

    // Whether the account's settings from the server, the entry listener's first changes, and the recent window of entries are stored here.
    @Volatile
    private var settingsIn = false

    @Volatile
    private var entriesIn = false

    @Volatile
    private var recentIn = false

    /**
     * Whether what the account had when this session began is stored here: its
     * feeds and settings from the server, the entry listener's first changes,
     * and the recent window of entries; older ones may still be loading.
     */
    val caughtUp: Boolean get() = feedsKnown && settingsIn && entriesIn && (recentIn || state.merged)

    /** Whether something failed: a write, or a listener that has not come back yet. */
    val failed: Boolean get() = writeFailed || broken.isNotEmpty()

    /** Whether anything is being read or written. */
    val busy: Boolean get() = merging || restamping.get() || outstanding.get() > 0 || flushJob != null || creating != null

    /** How long the account keeps entries, as known here. */
    val retention: RetentionDays get() = retentionFromDays(state.account.retentionDays)

    /** Listen for account changes, then load and upload what is missing. */
    fun start() {
        startUploads()
        startListening()
    }

    /**
     * Only send this device's changes and upload the entries it found, with no
     * listeners and no pass over the account, as for a process started to check
     * feeds in the background; [startListening] adds the rest.
     */
    fun startUploads() {
        if (uploading.compareAndSet(false, true)) {
            scope.launch {
                for (task in tasks) {
                    try {
                        task()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        fail(failure)
                    }
                }
            }
            retryCreates()
        }
    }

    /** Listen for account changes, read the account's entries once and carry on any retention pass; once per session. */
    fun startListening() {
        startUploads()
        if (listening.compareAndSet(false, true)) {
            listenSubscriptions()
            listenSettings()
            listenEntries()
            scope.launch {
                try {
                    mergeAll()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
            resumeRestamp()
        }
    }

    /**
     * Choose how long the account keeps entries: saved to the account for every
     * device, then applied to the entries already there by a pass this device runs.
     */
    fun setRetention(retention: RetentionDays) {
        val days = retentionDays(retention)
        if (days != state.account.retentionDays && !stopped) {
            updateState { current ->
                current.copy(account = current.account.copy(retentionDays = days, updatedAt = clock()), restamp = Restamp(days, ""))
            }
            writeAccount()
            resumeRestamp()
            onStatus()
        }
    }

    private fun writeAccount() {
        val document = state.account
        scope.launch {
            try {
                commit(listOf(DocumentWrite.Account(document)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure)
            }
        }
    }

    /** Take up the account's retention when it changed elsewhere, or send this device's newer one. */
    private fun onAccount(copy: AccountCopy?) {
        if (copy != null) {
            val remote = copy.document
            if (accountWinner(state.account, remote) == AccountWinner.Local) {
                writeAccount()
            } else if (remote != null) {
                // the same choice may carry a newer record of what was stamped
                updateState { current -> current.copy(account = remote) }
                onStatus()
            }
            restampIfBehind()
        }
    }

    /**
     * Start a pass when the account's entries were last stamped with another
     * retention than its own, as when the device that chose it stopped before
     * finishing; a pass already under way here goes on instead.
     */
    private fun restampIfBehind() {
        val account = state.account
        if (state.restamp == null && needsRestamp(account)) {
            updateState { current -> current.copy(restamp = Restamp(account.retentionDays, "")) }
            resumeRestamp()
        }
    }

    /** Record in the account that its entries now all carry a retention, so no device runs the pass again. */
    private fun markStamped(days: Int) {
        if (state.account.retentionDays == days) {
            updateState { current -> current.copy(account = current.account.copy(stampedDays = days)) }
            scope.launch {
                try {
                    commit(listOf(DocumentWrite.AccountStamped(days)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
        }
    }

    private fun resumeRestamp() {
        if (state.restamp != null && !stopped && restamping.compareAndSet(false, true)) {
            onStatus()
            scope.launch {
                try {
                    retrying { restampAll() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    fail(failure)
                } finally {
                    restamping.set(false)
                    onStatus()
                }
            }
        }
    }

    /**
     * Set the expiry of each of the account's entries to the retention chosen
     * here, a page at a time in id order, carrying on from where the pass
     * stopped. Each entry is read once; only those that change are written.
     */
    private suspend fun restampAll() {
        var pass = state.restamp
        while (pass != null && !stopped) {
            val rows = store.readExpiryPage(pass.after, BATCH_LIMIT)
            val retention = retentionFromDays(pass.retentionDays)
            val changes = rows.mapNotNull { row ->
                row.document?.let { document -> restampedExpiry(document, row.expireAt, retention) }?.let { change -> row.id to change }
            }
            if (changes.isNotEmpty()) {
                store.setExpiries(changes)
            }
            val done = pass
            val last = rows.lastOrNull()
            val finished = last == null || rows.size < BATCH_LIMIT
            var current = false
            updateState { saved ->
                // a newer choice made meanwhile starts its own pass over
                current = saved.restamp == done
                if (!current) {
                    saved
                } else if (finished) {
                    saved.copy(restamp = null)
                } else {
                    saved.copy(restamp = done.copy(after = last.id))
                }
            }
            if (current && finished) {
                markStamped(done.retentionDays)
            }
            pass = state.restamp
        }
    }

    /** Stop listening and forget this account's progress on this device. */
    fun stop() {
        stopped = true
        synchronized(stops) {
            stops.forEach { listener -> listener.stop() }
            stops.clear()
        }
        flushJob?.cancel()
        flushJob = null
        job.cancel()
        // under the lock, so a save already under way cannot write the progress back after this
        synchronized(stateLock) {
            forgetProgress(preferences, uid)
        }
    }

    /** Write queued changes now rather than after the usual wait, as when the app goes to the background or signs out. */
    fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        // also what a failed flush put back, which otherwise waits for its retry
        if (synchronized(this) { outbox.hasQueued }) {
            flush()
        }
    }

    /** Take local changes; writes go out shortly in batches. */
    fun queue(change: LocalChange) {
        val anything = synchronized(this) {
            outbox.add(change)
            val added = addPending(change.added)
            added || outbox.hasQueued
        }
        if (anything) {
            scheduleFlush()
        }
    }

    private fun addPending(added: List<Entry>): Boolean {
        if (added.isNotEmpty()) {
            updateState { current -> current.copy(pending = (current.pending + added.map { entry -> entry.id }).distinct()) }
        }
        return added.isNotEmpty()
    }

    /** Try again to upload entries found here, as when back online. */
    fun retryCreates() {
        if (creating == null && state.pending.isNotEmpty() && !stopped) {
            creating = scope.launch {
                try {
                    createPending()
                    createFailures.set(0)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.log(Level.FINE, "upload waits for the next try", failure)
                    retryCreatesLater()
                } finally {
                    creating = null
                    onStatus()
                }
            }
        }
    }

    /**
     * Try the upload again after a wait that doubles with each failure in a
     * row, up to eight times the retry wait: the network can be back
     * before Firestore has reconnected.
     */
    private fun retryCreatesLater() {
        val failures = createFailures.incrementAndGet()
        scope.launch {
            retryLater()
            delay(timing.retryMs * ((1L shl minOf(failures - 1, MAX_BACKOFF_SHIFT)) - 1))
            // the slot taken by the failed try is free once its finally has run
            creating?.join()
            retryCreates()
        }
    }

    /** Change the saved progress and save it, one change at a time. */
    private fun updateState(change: (SyncState) -> SyncState) {
        synchronized(stateLock) {
            state = change(state)
            // a stopped session's late work must not overwrite or revive its progress
            if (!stopped) {
                preferences.set(stateKey(uid), state.toText())
            }
        }
    }

    private fun fail(failure: Throwable) {
        if (!stopped) {
            logger.log(Level.WARNING, "sync failed", failure)
            writeFailed = true
            onStatus()
        }
    }

    /** Handle a snapshot after the ones before it; a failure is reported and the rest still run. */
    private fun serially(task: suspend () -> Unit) {
        tasks.trySend(task)
    }

    /** Keep a listener running: one that stops with an error is reported and started again after a wait. */
    private fun listen(name: String, subscribe: (onError: (Throwable) -> Unit) -> Stop) {
        if (!stopped) {
            val listener = subscribe { failure ->
                // a listener's failure shows until it is heard again, not as a failed write
                if (!stopped) {
                    logger.log(Level.WARNING, "listener $name stopped", failure)
                    broken.add(name)
                    onStatus()
                }
                scope.launch {
                    delay(timing.retryMs)
                    listen(name, subscribe)
                }
            }
            synchronized(stops) { stops.add(listener) }
        }
    }

    /** Note that a listener is working again. */
    private fun heard(name: String) {
        if (broken.remove(name)) {
            onStatus()
        }
    }

    private fun scheduleFlush() {
        if (flushJob == null && !stopped) {
            flushJob = scope.launch {
                delay(timing.flushDelayMs)
                flushJob = null
                flush()
                retryCreates()
            }
            onStatus()
        }
    }

    private fun listenSubscriptions() {
        listen("subscriptions") { onError ->
            store.listenSubscriptions({ subscriptionsReconciled }, { listing ->
                heard("subscriptions")
                serially { onSubscriptions(listing) }
            }, onError)
        }
    }

    private suspend fun onSubscriptions(listing: Listing<SubscriptionDocument>) {
        subscriptionsReconciled = subscriptionsReconciled || listing.present != null
        val local = host.local()
        val localFeeds = local.feeds.associateBy { feed -> feed.id }
        val tombstones = tombstonesOf(local.tombstones, FEED_TOMBSTONE)
        val merged = mergeRecords(localFeeds, { feed -> feed.updatedAt }, tombstones, listing.remote, listing.present)
        val removed = LinkedHashMap(unknownRemovals(localFeeds.keys, tombstones, listing.remote))
        val pulledFeeds = mutableListOf<Feed>()
        for ((id, copy) in merged.pull) {
            if (copy.deleted) {
                removed[id] = copy.updatedAt
            } else {
                pulledFeeds.add(feedFromDocument(id, copy, localFeeds[id]))
            }
        }
        applyRemote(RemoteChange(feeds = pulledFeeds, removedFeeds = removed))
        queue(LocalChange(feeds = merged.push.map { (_, feed) -> feed }, removedFeeds = merged.pushRemoved.toMap()))
        if (subscriptionsReconciled) {
            subscriptionsReady.complete(Unit)
            if (!feedsKnown) {
                feedsKnown = true
                onStatus()
            }
        }
    }

    private fun listenSettings() {
        listen("settings") { onError ->
            store.listenSettings({ settingsReconciled }, { listing, account ->
                heard("settings")
                serially {
                    onAccount(account)
                    onSettings(listing)
                }
            }, onError)
        }
    }

    private suspend fun onSettings(listing: Listing<SettingsDocument>) {
        settingsReconciled = settingsReconciled || listing.present != null
        val local = host.local()
        val merged = mergeRecords(local.settings, { saved -> saved.updatedAt }, tombstonesOf(local.tombstones, SETTINGS_TOMBSTONE), listing.remote, listing.present)
        val pulled = LinkedHashMap<String, SavedSettings>()
        val removed = LinkedHashMap<String, Long>()
        for ((key, copy) in merged.pull) {
            if (copy.deleted) {
                removed[key] = copy.updatedAt
            } else {
                pulled[key] = settingsFromDocument(copy)
            }
        }
        applyRemote(RemoteChange(settings = pulled, removedSettings = removed))
        queue(LocalChange(settings = merged.push.toMap(), removedSettings = merged.pushRemoved.toMap()))
        if (settingsReconciled && !settingsIn) {
            settingsIn = true
            onStatus()
        }
    }

    private fun listenEntries() {
        listen("entries") { onError ->
            val listener = AtomicReference<Stop?>(null)
            val cancelled = AtomicBoolean(false)
            scope.launch {
                try {
                    val since = entriesStart()
                    if (!cancelled.get()) {
                        listener.set(store.listenEntries(since, { changes ->
                            heard("entries")
                            serially {
                                try {
                                    onEntries(changes)
                                    if (!entriesIn) {
                                        entriesIn = true
                                        onStatus()
                                    }
                                } catch (failure: Exception) {
                                    cursorHeld = true
                                    throw failure
                                }
                            }
                        }, onError))
                        // stopped while the listener was being set up
                        if (cancelled.get()) {
                            listener.getAndSet(null)?.stop()
                        }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    onError(failure)
                }
            }
            Stop {
                cancelled.set(true)
                listener.getAndSet(null)?.stop()
            }
        }
    }

    /**
     * When the entry listener starts: the saved cursor less an overlap. With no
     * cursor yet, the newest server write time in the account is read and
     * saved first, so a device's wrong clock cannot hide changes.
     */
    private suspend fun entriesStart(): Long {
        if (state.cursor == 0L) {
            retrying {
                val newest = store.newestEntrySyncedAt()
                updateState { current -> current.copy(cursor = newest) }
            }
        }
        return listenFrom(state.cursor, timing.overlapMs)
    }

    private suspend fun onEntries(changes: List<EntryChange>) {
        val remote = changes.mapNotNull { change -> change.document?.let { document -> change.id to document } }
        val newest = changes.maxOfOrNull { change -> change.syncedAt } ?: 0
        if (remote.isNotEmpty()) {
            mergeRemote(remote)
        }
        if (!cursorHeld) {
            updateState { current -> if (newest > current.cursor) current.copy(cursor = newest) else current }
        }
    }

    /** Store account entries that are newer here, and queue local ones that are newer there. */
    private suspend fun mergeRemote(remote: List<Pair<String, EntryDocument>>) {
        if (remote.isNotEmpty()) {
            val local = host.local()
            val merged = mergeEntries(local.entries.associateBy { entry -> entry.id }, remote, droppedFeedIds(local.tombstones.keys))
            applyRemote(RemoteChange(entries = merged.pull))
            if (merged.push.isNotEmpty()) {
                queue(LocalChange(entries = merged.push))
            }
        }
    }

    private suspend fun applyRemote(change: RemoteChange) {
        if (change.hasChanges && !stopped) {
            host.apply(change)
        }
    }

    /**
     * Read every account entry, newest first, merging each page and uploading
     * this device's entries in the span it settles. The first 90 days come
     * straight away; older pages follow with a pause between them. Progress
     * is saved, so a restart carries on where it stopped.
     */
    private suspend fun mergeAll() {
        val cutoff = recentCutoff(clock())
        val seen = HashSet<String>()
        val upper: Long? = state.page?.published
        merging = !state.merged
        onStatus()
        try {
            mergePages(cutoff, seen, upper)
        } finally {
            merging = false
            loadingOlder = false
            onStatus()
        }
    }

    private suspend fun mergePages(cutoff: Long, seen: HashSet<String>, start: Long?) {
        var upper = start
        if (!state.merged) {
            subscriptionsReady.await()
        }
        while (!(state.merged || stopped)) {
            val page = readPage(state.page)
            page.forEach { (id, _) -> seen.add(id) }
            mergeRemote(page)
            val range = settledRange(upper, page.map { (_, stored) -> stored.published }, PAGE_SIZE)
            retrying { create(unlisted(host.local().entries, seen, range)) }
            val last = page.lastOrNull()
            if (stopped) {
                // the page may not be stored or its entries uploaded; nothing is recorded
            } else if (range.low == null || last == null) {
                updateState { current -> current.copy(merged = true, page = null) }
            } else {
                updateState { current -> current.copy(page = PageCursor(last.second.published, last.first)) }
                upper = range.low
            }
            val reached = upper
            if (!state.merged && reached != null && reached < cutoff) {
                recentIn = true
                loadingOlder = true
                onStatus()
                delay(timing.olderPauseMs)
            }
        }
    }

    /** One page of account entries after a cursor, retrying until it is read or sync stops. */
    private suspend fun readPage(after: PageCursor?): List<Pair<String, EntryDocument>> {
        var page: List<Pair<String, EntryDocument>>? = null
        while (page == null && !stopped) {
            try {
                page = store.readEntriesPage(after, PAGE_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.FINE, "page read failed, retrying", failure)
                retryLater()
            }
        }
        return page ?: emptyList()
    }

    /** Wait until the network is back, or the retry wait passes. */
    private suspend fun retryLater() {
        withTimeoutOrNull(timing.retryMs) {
            if (online.value) {
                delay(timing.retryMs)
            } else {
                online.first { available -> available }
            }
        }
    }

    /** Run a task until it succeeds or sync stops, waiting between tries. */
    private suspend fun retrying(task: suspend () -> Unit) {
        var done = false
        while (!(done || stopped)) {
            try {
                task()
                done = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.FINE, "retrying", failure)
                retryLater()
            }
        }
    }

    private suspend fun createPending() {
        val ids = state.pending.toSet()
        create(host.local().entries.filter { entry -> entry.id in ids })
        updateState { current -> current.copy(pending = current.pending.filter { id -> id !in ids }) }
    }

    /**
     * Upload entries the account may lack. Each is looked up first, by id, so
     * an entry another device already added is merged instead of overwritten.
     */
    private suspend fun create(found: List<Entry>) {
        val local = host.local()
        val wanted = subscribedEntries(found, local.feeds.map { feed -> feed.id }.toSet(), local.tombstones).filter(::storableEntry)
        val missing = mutableListOf<Entry>()
        val remote = mutableListOf<Pair<String, EntryDocument>>()
        for (group in wanted.chunked(IN_LIMIT)) {
            val lookup = store.entriesWithIds(group.map { entry -> entry.id })
            remote.addAll(lookup.documents)
            missing.addAll(group.filter { entry -> entry.id !in lookup.known })
        }
        mergeRemote(remote)
        for (group in missing.chunked(BATCH_LIMIT)) {
            commit(group.map { entry -> DocumentWrite.Entry(entry.id, entryDocument(entry, retention), merge = false) })
            group.forEach(::shareBody)
        }
    }

    /** Upload an entry's body unless the account already has it. */
    private fun shareBody(entry: Entry) {
        uploadIfMissing(entry.bodyHash) { host.body(entry.id) }
    }

    /** Upload a body this device holds if the account no longer has it, as after it expired there. */
    fun restoreBody(hash: String, text: String) {
        uploadIfMissing(hash) { text }
    }

    /** Note a body just downloaded, so it is not looked up again this session. */
    fun bodyPresent(hash: String) {
        checkedBodies.add(hash)
    }

    /** Look a body up by hash and upload this device's text when the account lacks it; one at a time, once a session per hash. */
    private fun uploadIfMissing(hash: String, body: suspend () -> String) {
        if (hash.isNotEmpty() && checkedBodies.add(hash)) {
            scope.launch {
                bodyLock.withLock {
                    try {
                        if (!store.bodyExists(hash)) {
                            val text = body()
                            if (shareable(text) && matches(hash, text)) {
                                store.createBody(hash, text, bodyExpiry(retention, clock()))
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        checkedBodies.remove(hash)
                        logger.log(Level.FINE, "body upload failed", failure)
                    }
                }
            }
        }
    }

    /**
     * Write everything queued, in batches; never waits for the server. A
     * failed write puts its changes back, to be tried again later; a feed's
     * account entries are removed only once its unsubscribe is written.
     */
    private fun flush() {
        if (stopped) {
            return
        }
        // an entry still waiting to be uploaded keeps its mark change for a later flush
        val taken = synchronized(this) { outbox.take(state.pending.toSet()) }
        val writes = mutableListOf<DocumentWrite>()
        taken.feeds.values.filter(::storableFeed).forEach { feed -> writes.add(DocumentWrite.Subscription(feed.id, subscriptionDocument(feed))) }
        for ((id, removedAt) in taken.removedFeeds) {
            writes.add(DocumentWrite.Subscription(id, subscriptionTombstone(removedAt)))
        }
        for ((key, saved) in taken.settings.filterKeys(::storableKey)) {
            writes.add(DocumentWrite.Settings(settingsDocument(key, saved, saved.updatedAt)))
        }
        for ((key, removedAt) in taken.removedSettings.filterKeys(::storableKey)) {
            writes.add(DocumentWrite.Settings(settingsDocument(key, null, removedAt)))
        }
        taken.entries.values.filter(::storableEntry).forEach { entry ->
            writes.add(DocumentWrite.Entry(entry.id, entryDocument(entry, retention), merge = true))
        }
        // Handed to the store before this returns, and outlives the session, so a sign-out that waits for
        // the store's pending writes still sends what was queued.
        parentScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                commit(writes)
                taken.removedFeeds.keys.forEach(::dropRemoteEntries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(this@Session) { outbox.restore(taken) }
                fail(failure)
                retryFlush()
            }
        }
        onStatus()
    }

    /** Write what a failed flush put back once the network is back or after a wait. */
    private fun retryFlush() {
        if (!stopped && flushRetrying.compareAndSet(false, true)) {
            scope.launch {
                try {
                    retryLater()
                } finally {
                    flushRetrying.set(false)
                }
                if (synchronized(this@Session) { outbox.hasQueued }) {
                    scheduleFlush()
                }
            }
        }
    }

    /**
     * Commit writes in batches of at most [BATCH_LIMIT], each stamped with the
     * server time. When the rules or the data refuse a batch, which no retry
     * fixes, each write is tried on its own and the refused ones are dropped,
     * so the valid changes still land.
     */
    private suspend fun commit(writes: List<DocumentWrite>) {
        for (group in writes.chunked(BATCH_LIMIT)) {
            outstanding.incrementAndGet()
            onStatus()
            try {
                try {
                    store.commit(group)
                } catch (refused: RefusedWriteException) {
                    commitEach(group, refused)
                }
                writeFailed = false
            } finally {
                outstanding.decrementAndGet()
                onStatus()
            }
        }
    }

    /** Commit refused writes one at a time, dropping each the rules or the data refuse. */
    private suspend fun commitEach(group: List<DocumentWrite>, refusal: RefusedWriteException) {
        if (group.size == 1) {
            logger.log(Level.WARNING, "write refused, dropped", refusal)
        } else {
            for (write in group) {
                try {
                    store.commit(listOf(write))
                } catch (refused: RefusedWriteException) {
                    logger.log(Level.WARNING, "write refused, dropped", refused)
                }
            }
        }
    }

    /** Delete the account's entries of an unsubscribed feed. */
    private fun dropRemoteEntries(feedId: String) {
        scope.launch {
            retrying {
                // subscribed again meanwhile: its entries are wanted after all
                if ("$FEED_TOMBSTONE$feedId" in host.local().tombstones) {
                    store.deleteEntriesOfFeed(feedId)
                }
            }
        }
    }
}
