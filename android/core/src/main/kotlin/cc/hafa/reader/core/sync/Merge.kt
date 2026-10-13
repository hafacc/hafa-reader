package cc.hafa.reader.core.sync

import cc.hafa.reader.core.Entry

/** A record's version: when it last changed and whether that change removed it. */
interface Version {
    /** When it last changed, by the changing device's clock. */
    val updatedAt: Long

    /** Whether that change removed it. */
    val deleted: Boolean
}

/** A plain [Version]. */
data class RecordVersion(
    override val updatedAt: Long,
    override val deleted: Boolean,
) : Version

/** What to do with one record: take the account's copy, send this device's, or nothing. */
enum class Decision {
    /** Take the account's copy. */
    Pull,

    /** Send this device's. */
    Push,

    /** Nothing. */
    Keep,
}

/** Prefix of a tombstone key for an unsubscribed feed. */
const val FEED_TOMBSTONE = "feed:"

/** Prefix of a tombstone key for removed view settings. */
const val SETTINGS_TOMBSTONE = "settings:"

/**
 * Last write wins by `updatedAt`. A removal nobody else knows about is not
 * sent, and a removal this device never had is not taken; equal times keep
 * what is here, so a change that came from the account is never sent back.
 */
fun decide(local: Version?, remote: Version?): Decision =
    if (local == null) {
        if (remote == null || remote.deleted) Decision.Keep else Decision.Pull
    } else if (remote == null) {
        if (local.deleted) Decision.Keep else Decision.Push
    } else if (local.updatedAt > remote.updatedAt) {
        Decision.Push
    } else if (local.updatedAt < remote.updatedAt) {
        Decision.Pull
    } else {
        Decision.Keep
    }

/** The version of a local record, from the record's time or its tombstone time. */
fun localVersion(updatedAt: Long?, tombstone: Long?): Version? =
    if (updatedAt != null) {
        RecordVersion(updatedAt, deleted = false)
    } else if (tombstone != null) {
        RecordVersion(tombstone, deleted = true)
    } else {
        null
    }

/** Entries to store here and entries to send, from a set of account documents. */
data class EntryMerge(
    /** New entries, or local ones with the account's newer marks applied. */
    val pull: List<Entry>,
    /** Local entries whose marks are newer than the account's. */
    val push: List<Entry>,
)

/**
 * Merge account entries into local ones by last write wins on the read and
 * starred marks. Entries of feeds this device has unsubscribed from are
 * skipped. A local entry keeps its body hash when the account has none.
 */
fun mergeEntries(local: Map<String, Entry>, remote: Iterable<Pair<String, EntryDocument>>, droppedFeeds: Set<String>): EntryMerge {
    val pull = mutableListOf<Entry>()
    val push = mutableListOf<Entry>()
    for ((id, stored) in remote) {
        val mine = local[id]
        val decision = decide(mine?.let { entry -> RecordVersion(entry.updatedAt, false) }, RecordVersion(stored.updatedAt, false))
        if (decision == Decision.Pull && mine != null) {
            pull.add(
                mine.copy(
                    read = stored.read,
                    starred = stored.starred,
                    updatedAt = stored.updatedAt,
                    bodyHash = mine.bodyHash.ifEmpty { stored.bodyHash },
                ),
            )
        } else if (decision == Decision.Pull && stored.feedId !in droppedFeeds) {
            pull.add(entryFromDocument(id, stored))
        } else if (decision == Decision.Push && mine != null) {
            push.add(mine)
        }
    }
    return EntryMerge(pull, push)
}

/** The entries whose feed is still subscribed here and not removed, the only ones worth uploading. */
fun subscribedEntries(entries: List<Entry>, feedIds: Set<String>, tombstones: Map<String, Long>): List<Entry> {
    val dropped = droppedFeedIds(tombstones.keys)
    return entries.filter { entry -> entry.feedId in feedIds && entry.feedId !in dropped }
}

/** Feed ids with a tombstone, from tombstone keys. */
fun droppedFeedIds(tombstones: Iterable<String>): Set<String> =
    tombstones.filter { key -> key.startsWith(FEED_TOMBSTONE) }.map { key -> key.removePrefix(FEED_TOMBSTONE) }.toSet()

/**
 * Account removals this device has no record of at all, with when. A device
 * that never had a feed still keeps its tombstone, so the feed's entries are
 * not pulled from the account later.
 */
fun <Remote : Version> unknownRemovals(
    localKeys: Set<String>,
    tombstones: Map<String, Long>,
    remote: Iterable<Pair<String, Remote>>,
): Map<String, Long> {
    val removals = linkedMapOf<String, Long>()
    for ((key, copy) in remote) {
        if (copy.deleted && key !in localKeys && key !in tombstones) {
            removals[key] = copy.updatedAt
        }
    }
    return removals
}

/** What to do with records of one kind, from the account's copies of them. */
data class RecordMerge<Value, Remote>(
    /** Account copies to store here, live or removed. */
    val pull: List<Pair<String, Remote>>,
    /** Local records to send. */
    val push: List<Pair<String, Value>>,
    /** Local removals to send, with when. */
    val pushRemoved: List<Pair<String, Long>>,
)

/**
 * Merge records by last write wins. [remote] holds the account copies seen;
 * when [present] is given it names every record the account has, so local
 * records missing from it are sent too.
 */
fun <Value, Remote : Version> mergeRecords(
    local: Map<String, Value>,
    timeOf: (Value) -> Long,
    tombstones: Map<String, Long>,
    remote: Iterable<Pair<String, Remote>>,
    present: Set<String>? = null,
): RecordMerge<Value, Remote> {
    val pull = mutableListOf<Pair<String, Remote>>()
    val push = mutableListOf<Pair<String, Value>>()
    val pushRemoved = mutableListOf<Pair<String, Long>>()
    for ((key, copy) in remote) {
        val mine = local[key]
        val tombstone = tombstones[key]
        val decision = decide(localVersion(mine?.let(timeOf), tombstone), copy)
        if (decision == Decision.Pull) {
            pull.add(key to copy)
        } else if (decision == Decision.Push && mine != null) {
            push.add(key to mine)
        } else if (decision == Decision.Push && tombstone != null) {
            pushRemoved.add(key to tombstone)
        }
    }
    if (present != null) {
        for ((key, mine) in local) {
            if (key !in present) {
                push.add(key to mine)
            }
        }
    }
    return RecordMerge(pull, push, pushRemoved)
}

/** Tombstones with a prefix, keyed by what follows it. */
fun tombstonesOf(tombstones: Map<String, Long>, prefix: String): Map<String, Long> =
    tombstones.filterKeys { key -> key.startsWith(prefix) }.mapKeys { (key, _) -> key.removePrefix(prefix) }
