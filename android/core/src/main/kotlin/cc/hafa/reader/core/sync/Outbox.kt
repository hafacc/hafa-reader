package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed

/** Changes taken from the outbox for one write. */
data class OutboxBatch(
    /** Feeds added or edited, by id. */
    val feeds: LinkedHashMap<String, Feed> = LinkedHashMap(),
    /** Feeds unsubscribed, with when. */
    val removedFeeds: LinkedHashMap<String, Long> = LinkedHashMap(),
    /** Entries whose read or starred mark changed, by id. */
    val entries: LinkedHashMap<String, Entry> = LinkedHashMap(),
    /** View settings saved, by view key. */
    val settings: LinkedHashMap<String, SavedSettings> = LinkedHashMap(),
    /** View settings removed, with when. */
    val removedSettings: LinkedHashMap<String, Long> = LinkedHashMap(),
)

/** Put taken changes back in [queued], except where [queued] or its [opposite] has a newer change for the same id. */
private fun <Value> putBack(queued: MutableMap<String, Value>, opposite: Map<String, *>, taken: Map<String, Value>) {
    for ((id, value) in taken) {
        if (id !in queued && id !in opposite) {
            queued[id] = value
        }
    }
}

/**
 * Local changes waiting to be written to the account, the newest per feed,
 * entry or view; the same as the web's `outbox.ts`. Changes are taken out
 * for a write and put back if it fails, unless a newer change for the same
 * thing came in meanwhile. Not thread-safe: callers hold one lock around it.
 */
class Outbox {
    private var queued = OutboxBatch()

    /** Whether anything waits to be written. */
    val hasQueued: Boolean
        get() = queued.feeds.isNotEmpty() || queued.removedFeeds.isNotEmpty() || queued.entries.isNotEmpty() ||
            queued.settings.isNotEmpty() || queued.removedSettings.isNotEmpty()

    /** Take a change, replacing any older one for the same feed, entry or view. */
    fun add(change: LocalChange) {
        for (feed in change.feeds) {
            queued.removedFeeds.remove(feed.id)
            queued.feeds[feed.id] = feed
        }
        for ((id, removedAt) in change.removedFeeds) {
            queued.feeds.remove(id)
            queued.removedFeeds[id] = removedAt
        }
        change.entries.forEach { entry -> queued.entries[entry.id] = entry }
        for ((key, saved) in change.settings) {
            queued.removedSettings.remove(key)
            queued.settings[key] = saved
        }
        for ((key, removedAt) in change.removedSettings) {
            queued.settings.remove(key)
            queued.removedSettings[key] = removedAt
        }
    }

    /** Take everything queued out for a write, except entries in [held], which stay. */
    fun take(held: Set<String>): OutboxBatch {
        val taken = queued
        queued = OutboxBatch()
        for (id in taken.entries.keys.filter { id -> id in held }) {
            queued.entries[id] = taken.entries.getValue(id)
            taken.entries.remove(id)
        }
        return taken
    }

    /** Put back a batch whose write failed, skipping anything changed again since. */
    fun restore(batch: OutboxBatch) {
        putBack(queued.feeds, queued.removedFeeds, batch.feeds)
        putBack(queued.removedFeeds, queued.feeds, batch.removedFeeds)
        putBack(queued.entries, emptyMap<String, Entry>(), batch.entries)
        putBack(queued.settings, queued.removedSettings, batch.settings)
        putBack(queued.removedSettings, queued.settings, batch.removedSettings)
    }
}
