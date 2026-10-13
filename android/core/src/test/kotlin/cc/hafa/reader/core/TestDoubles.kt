package cc.hafa.reader.core

import cc.hafa.reader.core.sync.SavedSettings
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class MemoryStore : ReaderStore {
    /** Saves of entries to fail before they work again. */
    var failingSaves = 0

    val feeds = linkedMapOf<String, Feed>()
    val entries = linkedMapOf<String, Entry>()
    val bodies = mutableMapOf<String, String>()
    val settings = mutableMapOf<String, ViewSettings>()
    val settingsTimes = mutableMapOf<String, Long>()
    val tombstones = mutableMapOf<String, Long>()

    override suspend fun loadFeeds() = feeds.values.toList()

    override suspend fun loadEntries() = entries.values.toList()

    override suspend fun loadSettings() = settings.mapValues { (key, value) -> SavedSettings(value, settingsTimes[key] ?: 0) }

    override suspend fun putSettings(key: String, settings: ViewSettings, updatedAt: Long) {
        this.settings[key] = settings
        settingsTimes[key] = updatedAt
    }

    override suspend fun loadTombstones() = tombstones.toMap()

    override suspend fun putTombstones(tombstones: Map<String, Long>) {
        this.tombstones.putAll(tombstones)
    }

    override suspend fun deleteTombstones(keys: Collection<String>) {
        keys.forEach(tombstones::remove)
    }

    override suspend fun putBody(entryId: String, html: String) {
        bodies[entryId] = html
    }

    override suspend fun clear() {
        feeds.clear()
        entries.clear()
        bodies.clear()
        settings.clear()
        settingsTimes.clear()
        tombstones.clear()
    }

    override suspend fun deleteSettings(key: String) {
        settings.remove(key)
    }

    override suspend fun putFeeds(feeds: List<Feed>) {
        feeds.forEach { feed -> this.feeds[feed.id] = feed }
    }

    override suspend fun deleteFeed(feedId: String) {
        feeds.remove(feedId)
        settings.remove("feed:$feedId")
        entries.values.filter { entry -> entry.feedId == feedId }.forEach { entry ->
            entries.remove(entry.id)
            bodies.remove(entry.id)
        }
    }

    override suspend fun putEntries(entries: List<Entry>, bodies: Map<String, String>) {
        if (failingSaves > 0) {
            failingSaves -= 1
            throw IllegalStateException("disk full")
        }
        entries.forEach { entry ->
            this.entries[entry.id] = entry
            this.bodies[entry.id] = bodies[entry.id] ?: ""
        }
    }

    override suspend fun updateEntries(entries: List<Entry>) {
        entries.forEach { entry -> this.entries[entry.id] = entry }
    }

    override suspend fun getBody(entryId: String) = bodies[entryId] ?: ""
}

internal class MemoryPreferences : ReaderPreferences {
    override var unreadOnly = true
    override var lastView = "all"
}

/** Answers by address; anything unknown does not answer. */
internal class FakeFetcher : FeedFetcher {
    val answers = mutableMapOf<String, FetchOutcome>()
    val requests = mutableListOf<String>()

    /** While set, every fetch waits for it. */
    var gate: CompletableDeferred<Unit>? = null

    /** Fetches of these addresses wait for their gate. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** While set, every fetch throws it. */
    var failure: Exception? = null

    /** Addresses that answer only when given the full time. */
    val slow = mutableSetOf<String>()

    override suspend fun fetch(url: String, etag: String, lastModified: String, timeoutMs: Long): FetchOutcome {
        requests.add(url)
        return if (url in slow && timeoutMs < FETCH_TIMEOUT_MS) {
            FetchOutcome(status = 0, timedOut = true)
        } else {
            gate?.await()
            gates[url]?.await()
            failure?.let { error -> throw error }
            answers[url] ?: answers[url.toHttpUrlOrNull()?.toString()] ?: FetchOutcome(status = 0)
        }
    }
}

