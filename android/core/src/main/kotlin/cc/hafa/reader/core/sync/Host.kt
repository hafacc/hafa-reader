package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed

/** How sync is doing, for the status shown in the app. */
enum class SyncStatus {
    /** Signed out. */
    Off,

    /** Starting, or finding out who is signed in. */
    Connecting,

    /** Reading or writing. */
    Syncing,

    /** Everything sent and received. */
    Synced,

    /** No network; changes wait. */
    Offline,

    /** A read or write failed. */
    Error,

    /** A different account than last time signed in; waits for the person to choose. */
    Waiting,
}

/** What the sync engine tells the app about itself. */
data class SyncReport(
    /** How sync is doing. */
    val status: SyncStatus = SyncStatus.Off,
    /** The signed-in account's email; empty when signed out. */
    val email: String = "",
    /** Whether entries older than the recent window are still being loaded. */
    val loadingOlder: Boolean = false,
    /** How long the signed-in account keeps entries; null for forever. */
    val retention: RetentionDays = null,
    /** Address of the signed-in account's photo; empty when it has none. */
    val photoUrl: String = "",
    /** Whether the signed-in account's feeds have arrived from the server. */
    val feedsKnown: Boolean = false,
)

/** Everything stored on this device, as sync reads it. */
data class LocalData(
    /** Subscribed feeds. */
    val feeds: List<Feed>,
    /** Stored articles. */
    val entries: List<Entry>,
    /** View settings by view key. */
    val settings: Map<String, SavedSettings>,
    /** Removal times, by tombstone key (`feed:{id}` or `settings:{viewKey}`). */
    val tombstones: Map<String, Long>,
)

/** Changes from the account for the app to store; none of them are sent back. */
data class RemoteChange(
    /** Feeds to add or update, with this device's own fields kept. */
    val feeds: List<Feed> = emptyList(),
    /** Feeds unsubscribed elsewhere, with when; their entries are dropped. */
    val removedFeeds: Map<String, Long> = emptyMap(),
    /** Entries to add, or to update in place. */
    val entries: List<Entry> = emptyList(),
    /** View settings to save, by view key. */
    val settings: Map<String, SavedSettings> = emptyMap(),
    /** View settings removed elsewhere, with when. */
    val removedSettings: Map<String, Long> = emptyMap(),
) {
    /** Whether the change has anything to store. */
    val hasChanges: Boolean
        get() = feeds.isNotEmpty() || removedFeeds.isNotEmpty() || entries.isNotEmpty() ||
            settings.isNotEmpty() || removedSettings.isNotEmpty()
}

/** Changes made on this device for sync to send. */
data class LocalChange(
    /** Feeds added or edited. */
    val feeds: List<Feed> = emptyList(),
    /** Feeds unsubscribed, with when. */
    val removedFeeds: Map<String, Long> = emptyMap(),
    /** Entries whose read or starred mark changed. */
    val entries: List<Entry> = emptyList(),
    /** Entries this device just found; uploaded unless the account has them. */
    val added: List<Entry> = emptyList(),
    /** View settings saved, by view key. */
    val settings: Map<String, SavedSettings> = emptyMap(),
    /** View settings removed, with when. */
    val removedSettings: Map<String, Long> = emptyMap(),
)

/** What the sync engine needs from the app. */
interface SyncHost {
    /** What is stored on this device now. */
    fun local(): LocalData

    /** Store changes from the account, here and in the local database. */
    suspend fun apply(change: RemoteChange)

    /** An entry's stored body; empty when there is none. */
    suspend fun body(entryId: String): String

    /** Remove everything stored on this device, before another account's data comes in. */
    suspend fun clear()
}

/**
 * Whether this device holds anything of an account's: feeds, entries,
 * settings, or removals, which would otherwise be sent to another account.
 */
fun hasLocalData(local: LocalData): Boolean =
    local.feeds.isNotEmpty() || local.entries.isNotEmpty() || local.settings.isNotEmpty() || local.tombstones.isNotEmpty()

/** What the reader tells sync about changes made here; does nothing while signed out. */
interface SyncSink {
    /** Send local changes when signed in; ignored otherwise. */
    fun queue(change: LocalChange)

    /** Download an article body shared under a hash; empty when not signed in, not found or not matching. */
    suspend fun fetchBody(hash: String): String

    /** Upload a body this device holds if the account no longer has it, as after it expired there; ignored when signed out. */
    fun restoreBody(hash: String, text: String)
}
