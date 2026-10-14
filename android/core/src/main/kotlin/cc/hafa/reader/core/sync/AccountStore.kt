package cc.hafa.reader.core.sync

/**
 * Account copies a listener saw, skipping this device's own unsent writes.
 * The first complete listing from the server names every document in
 * [present]; later ones leave it null.
 */
data class Listing<T>(
    /** Parsed account copies, by document key. */
    val remote: List<Pair<String, T>>,
    /** Every document the account has, when this listing is complete. */
    val present: Set<String>?,
)

/** One changed entry from the entry listener. */
data class EntryChange(
    /** Entry id. */
    val id: String,
    /** Its parsed document; null when it cannot be read. */
    val document: EntryDocument?,
    /** Server time of its last write, in milliseconds. */
    val syncedAt: Long,
)

/** Entries looked up by id: which exist, and the readable ones. */
data class IdLookup(
    /** Ids the account has. */
    val known: Set<String>,
    /** Parsed documents of those. */
    val documents: List<Pair<String, EntryDocument>>,
)

/** One document write; every write is stamped with the server time. */
sealed interface DocumentWrite {
    /** A subscription or its tombstone at `users/{uid}/subscriptions/{id}`. */
    data class Subscription(
        /** Feed id. */
        val id: String,
        /** The document. */
        val document: SubscriptionDocument,
    ) : DocumentWrite

    /** View settings or their tombstone at `users/{uid}/settings/{settingsDocumentId(key)}`. */
    data class Settings(
        /** The document, which holds its key. */
        val document: SettingsDocument,
    ) : DocumentWrite

    /**
     * An entry at `users/{uid}/entries/{id}`, merged into what is there or
     * replacing it; a merge without `expireAt` removes the stored one.
     */
    data class Entry(
        /** Entry id. */
        val id: String,
        /** The document. */
        val document: EntryDocument,
        /** Whether to merge into the stored fields rather than replace them. */
        val merge: Boolean,
    ) : DocumentWrite

    /** The account's own settings at `users/{uid}/settings/account`. */
    data class Account(
        /** The document. */
        val document: AccountDocument,
    ) : DocumentWrite

    /** Only the record of what the account's entries were stamped with, merged into its settings document. */
    data class AccountStamped(
        /** The retention the entries now all carry, in days; 0 for forever. */
        val stampedDays: Int,
    ) : DocumentWrite
}

/** The account settings a settings listing found. */
data class AccountCopy(
    /** The parsed document; null when the account has none (or none readable). */
    val document: AccountDocument?,
)

/** An account entry as a pass applying the retention reads it. */
data class ExpiryRow(
    /** Entry id. */
    val id: String,
    /** Its parsed document; null when it cannot be read. */
    val document: EntryDocument?,
    /** Its stored expiry, in milliseconds; null for none. */
    val expireAt: Long?,
)

/** A write the rules or the data refuse, which no retry fixes. */
class RefusedWriteException(cause: Throwable) : Exception("write refused", cause)

/** Stops a listener. */
fun interface Stop {
    /** Stop listening. */
    fun stop()
}

/**
 * One signed-in account's documents in Firestore, as sync uses them; the app
 * implements it with the Firebase SDK and tests with a fake. Listener
 * callbacks may come on any thread.
 */
interface AccountStore {
    /** Listen to `subscriptions`; [reconciled] says whether a complete listing has already been taken. */
    fun listenSubscriptions(reconciled: () -> Boolean, onListing: (Listing<SubscriptionDocument>) -> Unit, onError: (Throwable) -> Unit): Stop

    /**
     * Listen to `settings`: the views' keyed by view key, and the account's own;
     * the account copy is null while it is unknown (only cached, or with this
     * device's write still unsent).
     */
    fun listenSettings(
        reconciled: () -> Boolean,
        onListing: (Listing<SettingsDocument>, AccountCopy?) -> Unit,
        onError: (Throwable) -> Unit,
    ): Stop

    /** The newest server write time among the account's entries, read from the server; zero for none. */
    suspend fun newestEntrySyncedAt(): Long

    /** Listen to entries written after [sinceMs] by the server's clock; the first changes come even when there are none. */
    fun listenEntries(sinceMs: Long, onChanges: (List<EntryChange>) -> Unit, onError: (Throwable) -> Unit): Stop

    /** A page of entries newest first, after [after], read from the server. */
    suspend fun readEntriesPage(after: PageCursor?, limit: Int): List<Pair<String, EntryDocument>>

    /** Entries with these ids, at most [IN_LIMIT], read from the server. */
    suspend fun entriesWithIds(ids: List<String>): IdLookup

    /** A page of entries in document id order after [after] (empty for the first), read from the server. */
    suspend fun readExpiryPage(after: String, limit: Int): List<ExpiryRow>

    /** Change only the `expireAt` of these entries, in one batch of at most [BATCH_LIMIT], leaving `syncedAt` alone. */
    suspend fun setExpiries(changes: List<Pair<String, ExpiryChange>>)

    /** Write documents in one batch, at most [BATCH_LIMIT]; throws [RefusedWriteException] when the rules or the data refuse it. */
    suspend fun commit(writes: List<DocumentWrite>)

    /** Delete the account's entries of a feed, as the server lists them. */
    suspend fun deleteEntriesOfFeed(feedId: String)

    /** Whether a shared body exists, asked of the server. */
    suspend fun bodyExists(hash: String): Boolean

    /** Create a shared body, expiring at [expireAt] when given. */
    suspend fun createBody(hash: String, text: String, expireAt: Long?)

    /** A shared body's text; empty when there is none. */
    suspend fun readBody(hash: String): String

    /** Wait until every write handed to the store has reached the server. */
    suspend fun waitForPendingWrites()

    /** Delete every subscription, entry and settings document of the account. */
    suspend fun deleteAccountData()
}

/** Small values sync keeps on this device. */
interface SyncPreferences {
    /** A stored value, or null. */
    fun get(key: String): String?

    /** Store a value; null removes it. */
    fun set(key: String, value: String?)
}
