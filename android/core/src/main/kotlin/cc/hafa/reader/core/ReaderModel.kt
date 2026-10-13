package cc.hafa.reader.core

import cc.hafa.reader.core.sync.FEED_TOMBSTONE
import cc.hafa.reader.core.sync.LocalChange
import cc.hafa.reader.core.sync.LocalData
import cc.hafa.reader.core.sync.RemoteChange
import cc.hafa.reader.core.sync.SETTINGS_TOMBSTONE
import cc.hafa.reader.core.sync.SavedSettings
import cc.hafa.reader.core.sync.SyncHost
import cc.hafa.reader.core.sync.SyncSink
import cc.hafa.reader.core.sync.bodyMayBeGone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Where the reader keeps feeds, articles, bodies and view settings. */
interface ReaderStore {
    /** Every feed. */
    suspend fun loadFeeds(): List<Feed>

    /** Every article, without bodies. */
    suspend fun loadEntries(): List<Entry>

    /** The saved settings of every feed, group and main view, by view key, with when each changed. */
    suspend fun loadSettings(): Map<String, SavedSettings>

    /** Save the settings for one view, changed at [updatedAt]. */
    suspend fun putSettings(key: String, settings: ViewSettings, updatedAt: Long)

    /** Remove the settings of one view. */
    suspend fun deleteSettings(key: String)

    /** Save feeds. */
    suspend fun putFeeds(feeds: List<Feed>)

    /** Remove a feed with all of its entries, bodies and settings. */
    suspend fun deleteFeed(feedId: String)

    /** Save new entries together with their bodies, by entry id. */
    suspend fun putEntries(entries: List<Entry>, bodies: Map<String, String>)

    /** Save changed entries, leaving their bodies alone. */
    suspend fun updateEntries(entries: List<Entry>)

    /** An article body, or an empty string if there is none. */
    suspend fun getBody(entryId: String): String

    /** When each removed feed or setting was removed, by tombstone key. */
    suspend fun loadTombstones(): Map<String, Long>

    /** Save removal times, by tombstone key. */
    suspend fun putTombstones(tombstones: Map<String, Long>)

    /** Forget removals, by tombstone key. */
    suspend fun deleteTombstones(keys: Collection<String>)

    /** Keep an article body, as one downloaded from the account. */
    suspend fun putBody(entryId: String, html: String)

    /** Remove every feed, entry, body, setting and tombstone. */
    suspend fun clear()

    /** Save checked feeds and the new entries they brought, with their bodies, together. */
    suspend fun putChecks(feeds: List<Feed>, entries: List<Entry>, bodies: Map<String, String>) {
        putFeeds(feeds)
        putEntries(entries, bodies)
    }
}

/** Small choices remembered on the device. */
interface ReaderPreferences {
    /** The unread-only switch. */
    var unreadOnly: Boolean

    /** Key of the last view shown; see [viewKey]. */
    var lastView: String
}

/** How an OPML import went. */
data class ImportResult(
    /** Feeds the file lists. */
    val found: Int,
    /** How many of them were new. */
    val added: Int,
)

/** Shortest time the refreshing marks stay on, so a quick check is still seen. */
private const val MIN_REFRESH_MS = 700L
private const val MAX_DISCOVERY_TRIES = 12
private val SCHEME_PATTERN = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/** Whether text starts with a scheme such as `https://`. */
fun hasScheme(text: String): Boolean = SCHEME_PATTERN.containsMatchIn(text)

/**
 * What a person typed or a file listed, as an address: https added when no
 * scheme is given, plain http upgraded to https, and empty when it is not a
 * web address; the same text the web app makes of it, so feed ids match.
 */
fun normalizeUrl(input: String): String {
    val trimmed = jsTrim(input)
    return upgradedAddress(if (hasScheme(trimmed)) trimmed else "https://$trimmed")
}

private val logger: Logger = Logger.getLogger("cc.hafa.reader")

/** Longest article body kept, in characters. */
const val MAX_STORED_BODY_CHARS = 500_000

/** Longest article title kept, in characters. */
const val MAX_TITLE_CHARS = 2_000

/** Longest article summary or excerpt kept, in characters. */
const val MAX_SUMMARY_CHARS = 20_000

/** An entry with its title, summary and excerpt cut to the lengths kept. */
fun cappedEntry(entry: Entry): Entry =
    if (entry.title.length <= MAX_TITLE_CHARS && entry.summary.length <= MAX_SUMMARY_CHARS && entry.excerpt.length <= MAX_SUMMARY_CHARS) {
        entry
    } else {
        entry.copy(
            title = entry.title.take(MAX_TITLE_CHARS),
            summary = entry.summary.take(MAX_SUMMARY_CHARS),
            excerpt = entry.excerpt.take(MAX_SUMMARY_CHARS),
        )
    }

private fun hostOf(address: String): String = address.toHttpUrlOrNull()?.host ?: address

/**
 * The reader: everything the screens show, and the actions that change it.
 *
 * State changes take effect at once in [state]; saving them runs in [scope],
 * one write at a time in the order they were made. Checking feeds goes
 * through one shared [CheckQueue], at most [PULL_CONCURRENCY] at once, with
 * conditional requests.
 *
 * Reading rules: opening an article does not mark it read, leaving it does;
 * a read article stays listed until the view changes or feeds are refreshed;
 * marking an open article unread by hand sticks when it is left. The sidebar
 * order is worked out at load and after each refresh, and held in between.
 *
 * [savingData] says whether the device asks to save data (Data Saver on
 * Android); then no neighbor's body is downloaded and no picture fetched ahead.
 */
class ReaderModel(
    private val store: ReaderStore,
    private val preferences: ReaderPreferences,
    private val fetcher: FeedFetcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val minRefreshMillis: Long = MIN_REFRESH_MS,
    private val onBackgroundWork: () -> Unit = {},
    private val savingData: () -> Boolean = { false },
) {
    private val mutableState = MutableStateFlow(ReaderState())

    /** Everything the reader holds. */
    val state: StateFlow<ReaderState> = mutableState.asStateFlow()

    private val loadLock = Mutex()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val manual: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val iconsTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val applyLock = Mutex()
    // Held around every change to the feeds and the save it queues, so edits and check results never overwrite each other.
    private val feedEdits = Any()

    @Volatile
    private var lastView: Pair<ReaderState, ReaderView>? = null

    // Loads the open article's body, then its neighbors'; replaced on each open and close.
    @Volatile
    private var openJob: Job? = null
    // Guards syncSink and earlyChanges, and keeps changes in the order they were made.
    private val syncLock = Any()

    @Volatile
    private var syncSink: SyncSink? = null

    // Changes made before sync is set, handed to it then; null once set.
    private var earlyChanges: MutableList<LocalChange>? = mutableListOf()

    /**
     * Where changes made here are sent; null until sync has started. Changes
     * made before it is set are kept and handed to it when it is, so the
     * signed-in account still gets them; setting null, as when sync cannot
     * start, drops them.
     */
    var sync: SyncSink?
        get() = syncSink
        set(value) {
            synchronized(syncLock) {
                syncSink = value
                val waiting = earlyChanges
                earlyChanges = null
                if (value != null) {
                    waiting?.forEach(value::queue)
                }
            }
        }

    /** Send a change made here to sync, or keep it until sync is set. */
    private fun sendToSync(change: LocalChange) {
        synchronized(syncLock) {
            val target = syncSink
            if (target != null) {
                target.queue(change)
            } else {
                earlyChanges?.add(change)
            }
        }
    }

    /** What sync reads and writes on this device. */
    val syncHost: SyncHost = object : SyncHost {
        override fun local(): LocalData {
            val current = state.value
            return LocalData(
                feeds = current.feeds,
                entries = current.entries,
                settings = current.settings.mapValues { (key, settings) -> SavedSettings(settings, current.settingsTimes[key] ?: 0) },
                tombstones = current.tombstones,
            )
        }

        override suspend fun apply(change: RemoteChange) = applyRemote(change)

        override suspend fun body(entryId: String): String = store.getBody(entryId)

        override suspend fun clear() = clearLocal()
    }

    private val queue = CheckQueue(scope, clock, ::entryFeed, ::checkFeed, ::applyChecks)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // One failed save must not stop the ones after it.
                    logger.log(Level.WARNING, "saving failed", failure)
                }
            }
        }
    }

    /** Read the stored feeds, articles and settings once; later calls do nothing. */
    suspend fun load() {
        loadLock.withLock {
            if (!state.value.loaded) {
                val feeds = store.loadFeeds()
                val entries = store.loadEntries()
                val saved = store.loadSettings()
                val settings = saved.mapValues { (_, item) -> item.settings }
                val tombstones = store.loadTombstones()
                update { current ->
                    current.copy(
                        feeds = feeds,
                        entries = entries,
                        settings = settings,
                        settingsTimes = saved.mapValues { (_, item) -> item.updatedAt },
                        tombstones = tombstones,
                        unreadOnly = preferences.unreadOnly,
                        frozenRank = frozenRankOf(feeds, entries, settings),
                        loaded = true,
                    )
                }
                select(selectionFromKey(preferences.lastView))
            }
        }
    }

    /** Wait until every change made so far is saved. */
    suspend fun awaitWrites() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    /** What the screens show now. */
    fun view(): ReaderView = viewOf(state.value)

    /** What the screens show for [state], worked out once per state. */
    fun viewOf(state: ReaderState): ReaderView {
        val cached = lastView
        return if (cached != null && sameView(cached.first, state)) {
            cached.second
        } else {
            deriveView(state).also { derived -> lastView = state to derived }
        }
    }

    /** Whether two states show the same view: only the body, refresh count or busy marks differ. */
    private fun sameView(first: ReaderState, second: ReaderState): Boolean =
        first === second || (
            first.entries === second.entries && first.feeds === second.feeds &&
                first.settings === second.settings && first.pinned === second.pinned &&
                first.heldTopics === second.heldTopics && first.frozenRank === second.frozenRank &&
                first.selection == second.selection && first.unreadOnly == second.unreadOnly &&
                first.currentId == second.currentId
            )

    /** Show a feed, a group or one of the main lists, and remember the choice. */
    fun select(selection: Selection) {
        val before = state.value
        val chosen = if (isValid(before, selection)) selection else Selection.All
        update { current -> current.copy(selection = chosen) }
        if (viewKey(chosen) != viewKey(before.selection)) {
            if (before.currentId.isNotEmpty()) {
                close()
            }
            update { current -> current.copy(pinned = emptySet(), heldTopics = emptyMap()) }
        }
        preferences.lastView = viewKey(chosen)
    }

    /** Turn the unread-only switch on or off, and remember it; turning it on closes an open article already read. */
    fun setUnreadOnly(next: Boolean) {
        update { current -> current.copy(unreadOnly = next, pinned = emptySet(), heldTopics = emptyMap()) }
        if (next && entryById(state.value.currentId)?.read == true) {
            close()
        }
        preferences.unreadOnly = next
    }

    /** Change the current view's settings and save them. */
    fun changeSettings(change: (ViewSettings) -> ViewSettings) {
        val key = viewKey(state.value.selection)
        val next = change(state.value.settings[key] ?: DEFAULT_SETTINGS)
        val now = clock()
        update { current -> current.copy(settings = current.settings + (key to next), settingsTimes = current.settingsTimes + (key to now)) }
        persist { store.putSettings(key, next, now) }
        forget(listOf("$SETTINGS_TOMBSTONE$key"))
        sendToSync(LocalChange(settings = mapOf(key to SavedSettings(next, now))))
    }

    /**
     * Turn a topic off or back on in the current view. From an article the list
     * keeps its rows until the next refresh or view change; from the settings
     * screen everything takes effect at once.
     */
    fun toggleTopic(topic: String, fromArticle: Boolean) {
        val key = viewKey(state.value.selection)
        val before = state.value.settings[key] ?: DEFAULT_SETTINGS
        update { current ->
            current.copy(
                heldTopics = if (fromArticle) {
                    if (key in current.heldTopics) current.heldTopics else current.heldTopics + (key to before.offTopics)
                } else {
                    emptyMap()
                },
            )
        }
        val wasOff = topicOff(before, topic)
        changeSettings { current ->
            current.copy(
                offTopics = if (wasOff) {
                    current.offTopics.filter { known -> !known.equals(topic, ignoreCase = true) }
                } else {
                    current.offTopics + topic
                },
            )
        }
    }

    /**
     * Open an entry. The one that was open is marked read now, since it has been
     * left; the new one is marked only when it is left in turn, so an article
     * that fails to show is never counted as read.
     *
     * Once its body shows, its neighbors' bodies and first pictures are made
     * ready: [heading] is 1 when moving down the list and -1 when moving up.
     * The body of the one ahead is downloaded from the account when this device
     * lacks it and data is not being saved; the one behind uses only a stored body.
     */
    fun open(id: String, heading: Int = 1) {
        val before = state.value
        val previousId = if (before.currentId == id) "" else before.currentId
        openJob?.cancel()
        update { current -> current.copy(currentId = id, body = "", bodyId = "", aheadPictures = emptyList()) }
        if (previousId.isNotEmpty()) {
            leave(previousId)
        }
        val entry = state.value.entries.firstOrNull { candidate -> candidate.id == id }
        if (entry != null) {
            openJob = scope.launch {
                awaitWrites()
                val html = bodyOf(entry, download = true)
                if (html.isNotEmpty() && entry.bodyHash.isNotEmpty() && bodyMayBeGone(entry.addedAt, clock())) {
                    sync?.restoreBody(entry.bodyHash, html)
                }
                val body = shownBody(entry, html)
                update { current -> if (current.currentId == id) current.copy(body = body, bodyId = id) else current }
                prepareNeighbors(id, heading)
            }
        }
    }

    /** Close the open entry, marking it read. */
    fun close() {
        val previousId = state.value.currentId
        openJob?.cancel()
        update { current ->
            current.copy(currentId = "", body = "", bodyId = "", aheadBodies = emptyMap(), aheadPictures = emptyList())
        }
        if (previousId.isNotEmpty()) {
            leave(previousId)
        }
    }

    /** An entry's stored body, else, when [download] is set, the one the account shares under its hash (then kept); empty when there is none. */
    private suspend fun bodyOf(entry: Entry, download: Boolean): String {
        val html = try {
            store.getBody(entry.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A body the store cannot read (such as one too large for it) falls back to the summary.
            logger.log(Level.WARNING, "reading a body failed", failure)
            ""
        }
        val shared = if (download && html.isEmpty() && entry.bodyHash.isNotEmpty()) sync?.fetchBody(entry.bodyHash) ?: "" else ""
        if (shared.isNotEmpty()) {
            persist { store.putBody(entry.id, shared) }
        }
        return html.ifEmpty { shared }
    }

    /** An entry's body as the article shows it: sanitized, or its summary when it has none. */
    private suspend fun shownBody(entry: Entry, html: String): String {
        val base = entry.url.ifEmpty { entryFeed(entry.feedId)?.url ?: "" }
        return withContext(workDispatcher) {
            if (html.isEmpty()) plainBody(entry.summary.ifEmpty { entry.excerpt }) else sanitize(html, base)
        }
    }

    /** Ready the bodies and first pictures of the entries on either side of the open one. */
    private suspend fun prepareNeighbors(id: String, heading: Int) {
        val (ahead, behind) = neighborsOf(view().visible, id, heading)
        val saving = savingData()
        val bodies = mutableMapOf<String, String>()
        val pictures = mutableListOf<String>()
        for ((entry, download) in listOf(ahead to !saving, behind to false)) {
            if (entry != null) {
                val html = bodyOf(entry, download)
                val shown = shownBody(entry, html)
                // A summary that a downloaded body will replace is not shown ahead, so the page does not change under the reader.
                if (html.isNotEmpty() || entry.bodyHash.isEmpty()) {
                    bodies[entry.id] = shown
                }
                pictures.addAll(withContext(workDispatcher) { aheadPictures(entry.imageUrl, shown) })
            }
        }
        update { current ->
            if (current.currentId == id) {
                current.copy(aheadBodies = bodies, aheadPictures = if (saving) emptyList() else pictures)
            } else {
                current
            }
        }
    }

    /** Flip the starred mark on the open entry. */
    fun toggleStar() {
        toggleStarFor(state.value.currentId)
    }

    /** Flip the read mark on the open entry; the choice sticks when the article is left. */
    fun toggleRead() {
        val entry = entryById(state.value.currentId)
        if (entry != null) {
            manual.add(entry.id)
            setRead(listOf(entry.id), !entry.read)
        }
    }

    /** Flip the starred mark on one entry. */
    fun toggleStarFor(id: String) {
        val entry = entryById(id)
        if (entry != null) {
            val changed = entry.copy(starred = !entry.starred, updatedAt = clock())
            update { current -> current.copy(entries = current.entries.map { item -> if (item.id == id) changed else item }) }
            persist { store.updateEntries(listOf(changed)) }
            sendToSync(LocalChange(entries = listOf(changed)))
        }
    }

    /** Flip the read mark on one entry. */
    fun toggleReadFor(id: String) {
        val entry = entryById(id)
        if (entry != null) {
            if (id == state.value.currentId) {
                manual.add(id)
            }
            setRead(listOf(id), !entry.read)
        }
    }

    /** Mark everything in the current view as read: what it lists and what its unread count includes. */
    fun markAllRead() {
        val current = state.value
        val view = viewOf(current)
        val shown = view.visible.mapTo(HashSet()) { entry -> entry.id }
        setRead(
            view.baseEntries
                .filter { entry -> !entry.read && (entry.id in shown || countedInView(current, entry)) }
                .map { entry -> entry.id },
            true,
        )
    }

    /** Give a feed its own name; a blank name is ignored. */
    fun renameFeed(id: String, name: String) {
        val title = name.trim()
        if (title.isNotEmpty()) {
            changeFeeds({ feed -> feed.id == id }) { feed -> feed.copy(title = title, renamed = true) }
        }
    }

    /** Rename a group; if the name is taken, the two groups become one. */
    fun renameFolder(from: String, name: String) {
        val target = name.trim()
        if (target.isNotEmpty() && target != from) {
            changeFeeds({ feed -> feed.folder == from }) { feed -> feed.copy(folder = target) }
            // The renamed group keeps its sidebar place until the next refresh.
            update { current ->
                val rank = current.frozenRank
                val place = rank?.get("folder:$from")
                if (rank != null && place != null && "folder:$target" !in rank) {
                    current.copy(frozenRank = rank + ("folder:$target" to place))
                } else {
                    current
                }
            }
            val settings = state.value.settings
            val old = settings["folder:$from"]
            if (old != null && settings["folder:$target"] == null) {
                val now = clock()
                update { current ->
                    current.copy(
                        settings = current.settings + ("folder:$target" to old),
                        settingsTimes = current.settingsTimes + ("folder:$target" to now),
                    )
                }
                persist { store.putSettings("folder:$target", old, now) }
                forget(listOf("${SETTINGS_TOMBSTONE}folder:$target"))
                sendToSync(LocalChange(settings = mapOf("folder:$target" to SavedSettings(old, now))))
            }
            removeSettings("folder:$from")
            select(Selection.Folder(target))
        }
    }

    /** Delete a group: its feeds stay, but belong to no group. */
    fun deleteFolder(name: String) {
        changeFeeds({ feed -> feed.folder == name }) { feed -> feed.copy(folder = "") }
        removeSettings("folder:$name")
        select(Selection.All)
    }

    /** Move a feed into a group; an empty name takes it out of any group. */
    fun setFolder(id: String, folder: String) {
        changeFeeds({ feed -> feed.id == id }) { feed -> feed.copy(folder = folder.trim()) }
        select(state.value.selection)
    }

    /** Remove a feed and everything stored for it. */
    fun removeFeed(id: String) {
        val now = clock()
        val hadSettings = "feed:$id" in state.value.settings
        dropFeed(id, now)
        val removedSettings = if (hadSettings) {
            remember(mapOf("${SETTINGS_TOMBSTONE}feed:$id" to now))
            mapOf("feed:$id" to now)
        } else {
            emptyMap()
        }
        sendToSync(LocalChange(removedFeeds = mapOf(id to now), removedSettings = removedSettings))
    }

    /** Remove a feed, its entries and its settings here, leaving a tombstone. */
    private fun dropFeed(id: String, removedAt: Long) {
        synchronized(feedEdits) {
            update { current ->
                current.copy(
                    settings = current.settings - "feed:$id",
                    settingsTimes = current.settingsTimes - "feed:$id",
                    feeds = current.feeds.filter { feed -> feed.id != id },
                    entries = current.entries.filter { entry -> entry.feedId != id },
                )
            }
            persist { store.deleteFeed(id) }
        }
        remember(mapOf("$FEED_TOMBSTONE$id" to removedAt))
        select(state.value.selection)
    }

    /** Remove a view's settings, leaving a tombstone so the removal syncs. */
    private fun removeSettings(key: String) {
        if (key in state.value.settings) {
            val now = clock()
            update { current -> current.copy(settings = current.settings - key, settingsTimes = current.settingsTimes - key) }
            persist { store.deleteSettings(key) }
            remember(mapOf("$SETTINGS_TOMBSTONE$key" to now))
            sendToSync(LocalChange(removedSettings = mapOf(key to now)))
        }
    }

    private fun remember(tombstones: Map<String, Long>) {
        update { current -> current.copy(tombstones = current.tombstones + tombstones) }
        persist { store.putTombstones(tombstones) }
    }

    private fun forget(keys: Collection<String>) {
        val present = keys.filter { key -> key in state.value.tombstones }
        if (present.isNotEmpty()) {
            update { current -> current.copy(tombstones = current.tombstones - present.toSet()) }
            persist { store.deleteTombstones(present) }
        }
    }

    /**
     * Check feeds now whatever their schedule says. With no ids, every feed.
     * Requests are still conditional, so unchanged feeds cost little. Read
     * articles kept in the list go at once, and the sidebar order is worked
     * out again when it ends.
     */
    suspend fun refresh(feedIds: List<String> = emptyList()) {
        load()
        // Read articles kept in the list and held topics go as the refresh starts, so the list clears at once.
        update { current ->
            current.copy(refreshCount = current.refreshCount + 1, pinned = emptySet(), heldTopics = emptyMap())
        }
        val open = state.value
        if (open.unreadOnly && open.selection != Selection.Starred && entryById(open.currentId)?.read == true) {
            close()
        }
        val started = clock()
        try {
            val now = clock()
            val chosen = state.value.feeds.filter { feed -> feedIds.isEmpty() || feed.id in feedIds }
            val requests = if (chosen.size <= SMALL_REFRESH) {
                chosen.map { feed -> CheckRequest(feed.id, 1.0, foreground = true, timeoutMs = FOREGROUND_TIMEOUT_MS) }
            } else {
                val plan = planRefresh(chosen, now) { feed -> feed.schedule }
                plan.ordered.mapIndexed { index, feed ->
                    val waited = index < plan.foreground
                    CheckRequest(
                        feed.id,
                        plan.probabilities[index],
                        foreground = waited,
                        timeoutMs = if (waited) FOREGROUND_TIMEOUT_MS else FETCH_TIMEOUT_MS,
                    )
                }
            }
            val checks = queue.enqueue(requests)
            checks.filter { (_, request) -> request.foreground }.forEach { (check, _) -> check.settled.await() }
            if (queue.busy()) {
                onBackgroundWork()
            }
            update { current -> current.copy(frozenRank = frozenRankOf(current.feeds, current.entries, current.settings)) }
        } finally {
            try {
                val remaining = minRefreshMillis - (clock() - started)
                if (remaining > 0) {
                    delay(remaining)
                }
            } finally {
                update { current -> current.copy(refreshCount = current.refreshCount - 1) }
            }
        }
    }

    /** Check the feeds in the current view now. */
    suspend fun refreshView() {
        refresh(view().viewFeedIds)
    }

    /** Check the feeds whose schedule says they are due, through the same queue, and wait until they are saved. */
    suspend fun refreshDue() {
        val now = clock()
        val due = state.value.loaded || store.loadFeeds().any { feed -> feed.schedule.nextFetch <= now }
        if (!due) {
            return
        }
        load()
        val requests = state.value.feeds
            .filter { feed -> feed.schedule.nextFetch <= now }
            .map { feed ->
                CheckRequest(feed.id, refreshProbability(feed.schedule, now), foreground = false, timeoutMs = FETCH_TIMEOUT_MS)
            }
        queue.enqueue(requests).forEach { (check, _) -> check.stored.await() }
    }

    /** Wait until every queued check, including those a refresh left to the background, is saved. */
    suspend fun awaitChecks() {
        queue.awaitIdle()
    }

    /**
     * Subscribe to a feed by address. The address may also be a web page or a
     * profile on a service with feeds; its feed is found and used. Throws
     * [FeedProblemException] with the reason on failure.
     */
    suspend fun addFeed(input: String) {
        load()
        update { current -> current.copy(adding = true) }
        try {
            val start = normalizeUrl(input)
            if (start.toHttpUrlOrNull()?.isHttps != true) {
                throw FeedProblemException(FeedProblem.NotWebAddress)
            }
            val found = discover(start)
            val id = feedId(found.url)
            val feed = if (state.value.feeds.none { feed -> feed.id == id }) {
                Feed(
                    id = id,
                    url = found.url,
                    title = found.parsed.title.ifEmpty { hostOf(found.url) },
                    siteUrl = found.parsed.siteUrl,
                    icon = found.parsed.iconUrl.ifEmpty { findIcon(found.parsed.siteUrl.ifEmpty { found.url }) },
                    renamed = false,
                    folder = "",
                    error = "",
                    updatedAt = clock(),
                )
            } else {
                null
            }
            // The same feed may have come from the account while the site was being looked up.
            val added = feed != null && synchronized(feedEdits) {
                var fresh = false
                update { current ->
                    fresh = current.feeds.none { known -> known.id == id }
                    if (fresh) current.copy(feeds = current.feeds + feed) else current
                }
                if (fresh) {
                    persist { store.putFeeds(listOf(feed)) }
                }
                fresh
            }
            if (feed != null && added) {
                forget(listOf("$FEED_TOMBSTONE$id"))
                sendToSync(LocalChange(feeds = listOf(feed)))
                val now = clock()
                val result = withContext(workDispatcher) { checkOutcome(feed.schedule, feed.url, found.outcome, now) }
                // the sidebar keeps its order, with the new feed last, until the next refresh
                applyChecks(listOf(FinishedCheck(feed, result, now)))
            }
            select(Selection.FeedView(id))
        } finally {
            update { current -> current.copy(adding = false) }
        }
    }

    /**
     * Subscribe to every feed in an OPML file and start checking them. Returns
     * how many feeds the file lists and how many of them were new.
     */
    suspend fun importOpml(source: String): ImportResult {
        load()
        update { current -> current.copy(adding = true) }
        try {
            return importParsed(withContext(workDispatcher) { prepareImport(source) })
        } finally {
            update { current -> current.copy(adding = false) }
        }
    }

    /** OPML feeds with web addresses, each with its id. */
    private fun prepareImport(source: String): List<Pair<OpmlFeed, String>> =
        parseOpml(source)
            .map { item -> item.copy(url = normalizeUrl(item.url)) }
            .filter { item -> item.url.toHttpUrlOrNull() != null }
            .map { item -> item to feedId(item.url) }

    private fun importParsed(items: List<Pair<OpmlFeed, String>>): ImportResult {
        val known = state.value.feeds.mapTo(HashSet()) { feed -> feed.id }
        val added = mutableListOf<Feed>()
        for ((item, id) in items) {
            if (known.add(id)) {
                added.add(
                    Feed(
                        id = id,
                        url = item.url,
                        title = item.title.ifEmpty { hostOf(item.url) },
                        siteUrl = "",
                        icon = faviconFor(item.url),
                        renamed = false,
                        folder = item.folder.trim(),
                        error = "",
                        updatedAt = clock(),
                    ),
                )
            }
        }
        synchronized(feedEdits) {
            update { current ->
                val present = current.feeds.mapTo(HashSet()) { feed -> feed.id }
                current.copy(feeds = current.feeds + added.filter { feed -> feed.id !in present })
            }
            persist { store.putFeeds(added) }
        }
        forget(added.map { feed -> "$FEED_TOMBSTONE${feed.id}" })
        sendToSync(LocalChange(feeds = added))
        scope.launch { refresh() }
        return ImportResult(found = items.size, added = added.size)
    }

    /** Search Feedly for feeds about [query]; empty when nothing is found or it does not answer. */
    suspend fun searchFeeds(query: String): List<FeedHit> {
        update { current -> current.copy(adding = true) }
        try {
            val outcome = fetcher.fetch(feedSearchAddress(query), "", "")
            return if (outcome.status == 200) withContext(workDispatcher) { parseFeedSearch(outcome.text) } else emptyList()
        } finally {
            update { current -> current.copy(adding = false) }
        }
    }

    /**
     * A feed's picture would not load: look once for the one its site
     * announces, and use it; nothing changes when the site names none. The new
     * picture stays on this device, like the details a check finds.
     */
    suspend fun refreshIcon(id: String) {
        val feed = entryFeed(id)
        if (feed != null && iconsTried.add(id)) {
            val icon = siteIcon(feed.siteUrl.ifEmpty { feed.url })
            if (icon != null && icon != feed.icon) {
                synchronized(feedEdits) {
                    var changed: Feed? = null
                    update { current ->
                        current.copy(
                            feeds = current.feeds.map { candidate ->
                                if (candidate.id == id) candidate.copy(icon = icon).also { updated -> changed = updated } else candidate
                            },
                        )
                    }
                    changed?.let { updated -> persist { store.putFeeds(listOf(updated)) } }
                }
            }
        }
    }

    private fun entryFeed(id: String): Feed? = state.value.feeds.firstOrNull { feed -> feed.id == id }

    private fun entryById(id: String): Entry? =
        if (id.isEmpty()) null else state.value.entries.firstOrNull { entry -> entry.id == id }

    private fun update(change: (ReaderState) -> ReaderState) {
        while (true) {
            val before = mutableState.value
            if (mutableState.compareAndSet(before, change(before))) {
                return
            }
        }
    }

    private fun persist(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    private fun changeFeeds(which: (Feed) -> Boolean, change: (Feed) -> Feed) {
        val now = clock()
        synchronized(feedEdits) {
            val changed = state.value.feeds.filter(which).map { feed -> change(feed).copy(updatedAt = now) }
            val byId = changed.associateBy { feed -> feed.id }
            update { current -> current.copy(feeds = current.feeds.map { feed -> byId[feed.id] ?: feed }) }
            if (changed.isNotEmpty()) {
                persist { store.putFeeds(changed) }
                sendToSync(LocalChange(feeds = changed))
            }
        }
    }

    private fun isValid(state: ReaderState, selection: Selection): Boolean =
        when (selection) {
            is Selection.FeedView -> state.feeds.any { feed -> feed.id == selection.id }
            is Selection.Folder -> selection.name in foldersOf(state.feeds)
            else -> true
        }

    private fun leave(id: String) {
        val entry = entryById(id)
        if (entry != null && !(entry.read || id in manual)) {
            setRead(listOf(id), true)
        }
        manual.remove(id)
    }

    private fun setRead(ids: List<String>, read: Boolean) {
        val wanted = ids.toSet()
        val now = clock()
        val changed = state.value.entries
            .filter { entry -> entry.id in wanted }
            .map { entry -> entry.copy(read = read, updatedAt = now) }
        val byId = changed.associateBy { entry -> entry.id }
        update { current ->
            current.copy(
                entries = current.entries.map { entry -> byId[entry.id] ?: entry },
                pinned = if (read) current.pinned + wanted else current.pinned,
            )
        }
        if (changed.isNotEmpty()) {
            persist { store.updateEntries(changed) }
            sendToSync(LocalChange(entries = changed))
        }
    }

    /** Store changes from the account; nothing here is sent back. */
    private suspend fun applyRemote(change: RemoteChange) {
        for ((id, removedAt) in change.removedFeeds) {
            dropFeed(id, removedAt)
        }
        val forgotten = mutableListOf<String>()
        if (change.feeds.isNotEmpty()) {
            val byId = change.feeds.associateBy { feed -> feed.id }
            synchronized(feedEdits) {
                update { current ->
                    val known = current.feeds.mapTo(HashSet()) { feed -> feed.id }
                    current.copy(feeds = current.feeds.map { feed -> byId[feed.id] ?: feed } + change.feeds.filter { feed -> feed.id !in known })
                }
                persist { store.putFeeds(change.feeds) }
            }
            forgotten.addAll(change.feeds.map { feed -> "$FEED_TOMBSTONE${feed.id}" })
        }
        if (change.entries.isNotEmpty()) {
            val incoming = change.entries.map(::cappedEntry)
            val byId = incoming.associateBy { entry -> entry.id }
            update { current ->
                val known = current.entries.mapTo(HashSet()) { entry -> entry.id }
                current.copy(entries = current.entries.map { entry -> byId[entry.id] ?: entry } + incoming.filter { entry -> entry.id !in known })
            }
            persist { store.updateEntries(incoming) }
        }
        for ((key, saved) in change.settings) {
            update { current ->
                current.copy(settings = current.settings + (key to saved.settings), settingsTimes = current.settingsTimes + (key to saved.updatedAt))
            }
            persist { store.putSettings(key, saved.settings, saved.updatedAt) }
            forgotten.add("$SETTINGS_TOMBSTONE$key")
        }
        for ((key, removedAt) in change.removedSettings) {
            update { current -> current.copy(settings = current.settings - key, settingsTimes = current.settingsTimes - key) }
            persist { store.deleteSettings(key) }
            remember(mapOf("$SETTINGS_TOMBSTONE$key" to removedAt))
        }
        forget(forgotten)
        if (change.feeds.isNotEmpty()) {
            select(state.value.selection)
        }
        awaitWrites()
    }

    /** Remove every feed, entry, body, setting and tombstone on this device. */
    private suspend fun clearLocal() {
        manual.clear()
        update { current ->
            current.copy(
                feeds = emptyList(),
                entries = emptyList(),
                settings = emptyMap(),
                settingsTimes = emptyMap(),
                tombstones = emptyMap(),
                pinned = emptySet(),
                heldTopics = emptyMap(),
                currentId = "",
                body = "",
                bodyId = "",
                aheadBodies = emptyMap(),
                aheadPictures = emptyList(),
                frozenRank = emptyMap(),
            )
        }
        persist { store.clear() }
        select(Selection.All)
        awaitWrites()
    }

    /** Fetch and judge one feed; null when the fetch ran out of a time shorter than the full one. */
    private suspend fun checkFeed(feed: Feed, timeoutMs: Long): FinishedCheck? {
        val outcome = fetcher.fetch(feed.url, feed.schedule.etag, feed.schedule.lastModified, timeoutMs)
        return if (outcome.timedOut && timeoutMs < FETCH_TIMEOUT_MS) {
            null
        } else {
            val now = clock()
            FinishedCheck(feed, withContext(workDispatcher) { checkOutcome(feed.schedule, feed.url, outcome, now) }, now)
        }
    }

    /** New articles a check found, before they are saved. */
    private class Pulled(val feedId: String, val entries: List<Entry>, val bodies: Map<String, String>)

    /** Save checks: each feed's schedule, any new articles, its name and picture, and its status, in one write. */
    private suspend fun applyChecks(batch: List<FinishedCheck>) {
        applyLock.withLock {
            val known = state.value.entries.mapTo(HashSet()) { entry -> entry.id }
            val pulled = withContext(workDispatcher) {
                batch.mapNotNull { finished ->
                    finished.result.pulled?.let { parsed -> newEntries(finished.feed.id, parsed, finished.checkedAt, known) }
                }
            }
            synchronized(feedEdits) {
                var savedFeeds = emptyList<Feed>()
                var savedEntries = emptyList<Entry>()
                update { current ->
                    val ids = current.entries.mapTo(HashSet()) { entry -> entry.id }
                    val byId = current.feeds.associateBy { feed -> feed.id }.toMutableMap()
                    val changed = mutableListOf<Feed>()
                    val added = mutableListOf<Entry>()
                    for (finished in batch) {
                        val latest = byId[finished.feed.id]
                        if (latest != null && latest.url == finished.feed.url) {
                            pulled.filter { found -> found.feedId == latest.id }
                                .forEach { found -> found.entries.filterTo(added) { entry -> ids.add(entry.id) } }
                            val updated = describe(latest, finished)
                            byId[latest.id] = updated
                            changed.add(updated)
                        }
                    }
                    savedFeeds = changed
                    savedEntries = added
                    current.copy(
                        feeds = current.feeds.map { feed -> byId[feed.id] ?: feed },
                        entries = if (added.isEmpty()) current.entries else current.entries + added,
                    )
                }
                val bodies = pulled.flatMap { found -> found.bodies.entries }.associate { (id, html) -> id to html }
                if (savedFeeds.isNotEmpty() || savedEntries.isNotEmpty()) {
                    val feeds = savedFeeds
                    val entries = savedEntries
                    persist {
                        try {
                            store.putChecks(feeds, entries, entries.associate { entry -> entry.id to (bodies[entry.id] ?: "") })
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            forgetUnsaved(feeds, entries)
                            throw failure
                        }
                    }
                }
                if (savedEntries.isNotEmpty()) {
                    sendToSync(LocalChange(added = savedEntries))
                }
            }
        }
    }

    /**
     * Undo a batch the store could not save: its new entries leave memory, so
     * they are not taken as known, and its feeds are sent whole on their next check.
     */
    private fun forgetUnsaved(feeds: List<Feed>, entries: List<Entry>) {
        val lost = entries.mapTo(HashSet()) { entry -> entry.id }
        val reset = feeds.mapTo(HashSet()) { feed -> feed.id }
        synchronized(feedEdits) {
            update { current ->
                current.copy(
                    entries = current.entries.filter { entry -> entry.id !in lost },
                    feeds = current.feeds.map { feed ->
                        if (feed.id in reset) feed.copy(schedule = feed.schedule.copy(hash = "", nextFetch = 0)) else feed
                    },
                )
            }
        }
    }

    /**
     * A feed after a check: its schedule and status, and the name, site and
     * picture the feed now gives. Those stay on this device: they keep
     * `updatedAt` and are not sent, so they never outweigh a person's change on
     * another device, and every device reads them from the feed itself.
     */
    private fun describe(latest: Feed, finished: FinishedCheck): Feed {
        val pulled = finished.result.pulled
        val described = latest.copy(
            title = if (pulled != null && !latest.renamed) pulled.title.ifEmpty { latest.title } else latest.title,
            siteUrl = pulled?.siteUrl?.ifEmpty { null } ?: latest.siteUrl,
            icon = pulled?.iconUrl?.ifEmpty { null } ?: latest.icon,
        )
        return described.copy(
            schedule = finished.result.schedule,
            error = shownProblem(finished.result.schedule),
        )
    }

    /** The articles of a pulled feed not in [known], with their bodies. */
    private fun newEntries(feedId: String, parsed: ParsedFeed, now: Long, known: Set<String>): Pulled {
        val added = mutableListOf<Entry>()
        val bodies = mutableMapOf<String, String>()
        for ((index, item) in parsed.entries.withIndex()) {
            val id = entryId(feedId, item.guid)
            if (id !in known && id !in bodies) {
                added.add(
                    Entry(
                        id = id,
                        feedId = feedId,
                        guid = item.guid,
                        title = item.title,
                        url = item.url,
                        published = if (item.published == 0L) now else item.published,
                        dated = item.published != 0L,
                        author = item.author,
                        imageUrl = item.imageUrl,
                        excerpt = item.summary.ifEmpty { excerpt(item.html) },
                        summary = item.summary,
                        categories = item.categories,
                        read = false,
                        starred = false,
                        addedAt = now,
                        bodyHash = if (item.html.isEmpty()) "" else sha256Hex(item.html.take(MAX_STORED_BODY_CHARS)),
                        position = index,
                        // Marks never set here, so any an account already has will win.
                        updatedAt = 0,
                    ),
                )
                bodies[id] = item.html.take(MAX_STORED_BODY_CHARS)
            }
        }
        return Pulled(feedId, added.map(::cappedEntry), bodies)
    }

    /** A feed found from what someone pasted. */
    private data class Found(val url: String, val outcome: FetchOutcome, val parsed: ParsedFeed)

    private suspend fun discover(start: String): Found {
        val queue = ArrayDeque(listOf(start))
        val tried = mutableSetOf<String>()
        // What went wrong with the address as given; the usual feed paths tried after it mostly answer 404, which says less.
        var failure: FeedProblem = FeedProblem.NoAnswer
        while (queue.isNotEmpty() && tried.size < MAX_DISCOVERY_TRIES) {
            val url = queue.removeFirst()
            if (tried.add(url)) {
                val outcome = fetcher.fetch(url, "", "")
                val parsed = if (outcome.status == 200) {
                    try {
                        withContext(workDispatcher) { parseFeed(outcome.text, outcome.url.ifEmpty { url }) }
                    } catch (_: NotAFeedException) {
                        null
                    }
                } else {
                    null
                }
                if (parsed != null) {
                    return Found(url, outcome, parsed)
                }
                if (tried.size == 1) {
                    failure = if (outcome.status == 200) {
                        FeedProblem.NotAFeed
                    } else if (outcome.insecure) {
                        FeedProblem.NotHttps
                    } else if (outcome.status != 0) {
                        FeedProblem.Status(outcome.status)
                    } else {
                        FeedProblem.NoAnswer
                    }
                    val page = if (outcome.status == 200) outcome.text else ""
                    queue.addAll(withContext(workDispatcher) { feedCandidates(outcome.url.ifEmpty { url }, page) })
                }
            }
        }
        throw FeedProblemException(failure)
    }

    /** The picture a site announces, or else the usual favicon address. */
    private suspend fun findIcon(address: String): String = siteIcon(address) ?: faviconFor(address)

    /** The picture a site's home page announces; null when it could not be read or names none. */
    private suspend fun siteIcon(address: String): String? {
        val outcome = fetcher.fetch(siteAddress(address), "", "")
        return if (outcome.status == 200) {
            withContext(workDispatcher) { discoverIconLinks(outcome.text, address).firstOrNull() }
        } else {
            null
        }
    }
}
