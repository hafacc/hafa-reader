package cc.hafa.reader.sync

import cc.hafa.reader.core.sync.ACCOUNT_SETTINGS_ID
import cc.hafa.reader.core.sync.AccountCopy
import cc.hafa.reader.core.sync.AccountStore
import cc.hafa.reader.core.sync.BATCH_LIMIT
import cc.hafa.reader.core.sync.BODIES
import cc.hafa.reader.core.sync.DocumentWrite
import cc.hafa.reader.core.sync.EntryChange
import cc.hafa.reader.core.sync.EntryDocument
import cc.hafa.reader.core.sync.ExpiryChange
import cc.hafa.reader.core.sync.ExpiryRow
import cc.hafa.reader.core.sync.IdLookup
import cc.hafa.reader.core.sync.Listing
import cc.hafa.reader.core.sync.PageCursor
import cc.hafa.reader.core.sync.RefusedWriteException
import cc.hafa.reader.core.sync.SettingsDocument
import cc.hafa.reader.core.sync.Stop
import cc.hafa.reader.core.sync.SubscriptionDocument
import cc.hafa.reader.core.sync.parseAccount
import cc.hafa.reader.core.sync.parseEntry
import cc.hafa.reader.core.sync.parseSettings
import cc.hafa.reader.core.sync.parseSubscription
import cc.hafa.reader.core.sync.settingsDocumentId
import cc.hafa.reader.core.sync.toFields
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// Errors the rules or the data give, which no retry fixes.
private val REFUSALS = setOf(
    FirebaseFirestoreException.Code.PERMISSION_DENIED,
    FirebaseFirestoreException.Code.INVALID_ARGUMENT,
    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
)

private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_MILLI = 1_000_000

private fun timestamp(millis: Long): Timestamp =
    Timestamp(Math.floorDiv(millis, MILLIS_PER_SECOND), (Math.floorMod(millis, MILLIS_PER_SECOND) * NANOS_PER_MILLI).toInt())

private fun millis(value: Timestamp?): Long = value?.let { time -> time.seconds * MILLIS_PER_SECOND + time.nanoseconds / NANOS_PER_MILLI } ?: 0

// One thread keeps snapshots in order and their parsing off the main thread.
private val snapshotExecutor: Executor = Executors.newSingleThreadExecutor()

private fun syncedAt(snapshot: DocumentSnapshot): Long = millis(snapshot.getTimestamp("syncedAt"))

/**
 * The parsed account copies in a listener snapshot, skipping this device's
 * own unsent writes. The first snapshot from the server after [reconciled]
 * was false lists every document, and `present` then names them all.
 */
private fun <Remote> readSnapshot(
    snapshot: QuerySnapshot,
    reconciled: Boolean,
    parse: (DocumentSnapshot) -> Pair<String, Remote>?,
): Listing<Remote> {
    val complete = !(reconciled || snapshot.metadata.isFromCache)
    val documents = if (complete) {
        snapshot.documents
    } else {
        snapshot.documentChanges.filter { change -> change.type != DocumentChange.Type.REMOVED }.map { change -> change.document }
    }
    val remote = mutableListOf<Pair<String, Remote>>()
    val present = HashSet<String>()
    for (document in documents) {
        val parsed = parse(document)
        if (parsed != null) {
            present.add(parsed.first)
            if (!document.metadata.hasPendingWrites()) {
                remote.add(parsed)
            }
        }
    }
    return Listing(remote, if (complete) present else null)
}

/** One account's documents in Firestore, under `users/{uid}`, and the shared `bodies`. */
class FirestoreAccountStore(private val db: FirebaseFirestore, private val uid: String) : AccountStore {
    private fun collection(name: String): CollectionReference = db.collection("users").document(uid).collection(name)

    private val entries: CollectionReference get() = collection("entries")

    override fun listenSubscriptions(
        reconciled: () -> Boolean,
        onListing: (Listing<SubscriptionDocument>) -> Unit,
        onError: (Throwable) -> Unit,
    ): Stop {
        val registration = collection("subscriptions").addSnapshotListener(snapshotExecutor, MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                onError(error)
            } else if (snapshot != null) {
                onListing(readSnapshot(snapshot, reconciled()) { document -> parseSubscription(document.data.orEmpty())?.let { parsed -> document.id to parsed } })
            }
        }
        return Stop(registration::remove)
    }

    override fun listenSettings(
        reconciled: () -> Boolean,
        onListing: (Listing<SettingsDocument>, AccountCopy?) -> Unit,
        onError: (Throwable) -> Unit,
    ): Stop {
        val registration = collection("settings").addSnapshotListener(snapshotExecutor, MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                onError(error)
            } else if (snapshot != null) {
                val views = readSnapshot(snapshot, reconciled()) { document ->
                    if (document.id == ACCOUNT_SETTINGS_ID) null else parseSettings(document.data.orEmpty())?.let { parsed -> parsed.key to parsed }
                }
                onListing(views, accountCopy(snapshot))
            }
        }
        return Stop(registration::remove)
    }

    /** The account settings in a snapshot; null while unknown: only cached and missing, or this device's write still unsent. */
    private fun accountCopy(snapshot: QuerySnapshot): AccountCopy? {
        val document = snapshot.documents.firstOrNull { item -> item.id == ACCOUNT_SETTINGS_ID }
        val known = document != null || !snapshot.metadata.isFromCache
        return if (known && document?.metadata?.hasPendingWrites() != true) {
            AccountCopy(document?.data?.let(::parseAccount))
        } else {
            null
        }
    }

    override suspend fun newestEntrySyncedAt(): Long {
        val newest = entries.orderBy("syncedAt", Query.Direction.DESCENDING).limit(1).get(Source.SERVER).await()
        return newest.documents.firstOrNull()?.let(::syncedAt) ?: 0
    }

    override fun listenEntries(sinceMs: Long, onChanges: (List<EntryChange>) -> Unit, onError: (Throwable) -> Unit): Stop {
        val registration = entries.whereGreaterThan("syncedAt", timestamp(sinceMs)).addSnapshotListener(snapshotExecutor) { snapshot, error ->
            if (error != null) {
                onError(error)
            } else if (snapshot != null) {
                val changes = snapshot.documentChanges
                    .filter { change -> change.type != DocumentChange.Type.REMOVED && !change.document.metadata.hasPendingWrites() }
                    .map { change -> EntryChange(change.document.id, parseEntry(change.document.data), syncedAt(change.document)) }
                if (changes.isNotEmpty()) {
                    onChanges(changes)
                }
            }
        }
        return Stop(registration::remove)
    }

    override suspend fun readEntriesPage(after: PageCursor?, limit: Int): List<Pair<String, EntryDocument>> {
        val ordered = entries.orderBy("published", Query.Direction.DESCENDING).orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
        val page = if (after == null) ordered else ordered.startAfter(after.published, after.id)
        return page.limit(limit.toLong()).get(Source.SERVER).await().documents.mapNotNull { document ->
            parseEntry(document.data.orEmpty())?.let { parsed -> document.id to parsed }
        }
    }

    override suspend fun readExpiryPage(after: String, limit: Int): List<ExpiryRow> {
        val ordered = entries.orderBy(FieldPath.documentId())
        val page = if (after.isEmpty()) ordered else ordered.startAfter(after)
        return page.limit(limit.toLong()).get(Source.SERVER).await().documents.map { document ->
            ExpiryRow(document.id, parseEntry(document.data.orEmpty()), document.getTimestamp("expireAt")?.let(::millis))
        }
    }

    override suspend fun setExpiries(changes: List<Pair<String, ExpiryChange>>) {
        val batch = db.batch()
        for ((id, change) in changes) {
            val value: Any = when (change) {
                is ExpiryChange.At -> timestamp(change.time)
                ExpiryChange.Clear -> FieldValue.delete()
            }
            batch.update(entries.document(id), "expireAt", value)
        }
        batch.commit().await()
    }

    override suspend fun entriesWithIds(ids: List<String>): IdLookup {
        val found = entries.whereIn(FieldPath.documentId(), ids).get(Source.SERVER).await().documents
        return IdLookup(
            found.map { document -> document.id }.toSet(),
            found.mapNotNull { document -> parseEntry(document.data.orEmpty())?.let { parsed -> document.id to parsed } },
        )
    }

    override suspend fun commit(writes: List<DocumentWrite>) {
        val batch = db.batch()
        for (write in writes) {
            when (write) {
                is DocumentWrite.Subscription ->
                    batch.set(collection("subscriptions").document(write.id), write.document.toFields() + ("syncedAt" to FieldValue.serverTimestamp()))
                is DocumentWrite.Settings ->
                    batch.set(
                        collection("settings").document(settingsDocumentId(write.document.key)),
                        write.document.toFields() + ("syncedAt" to FieldValue.serverTimestamp()),
                    )
                is DocumentWrite.Account ->
                    batch.set(
                        collection("settings").document(ACCOUNT_SETTINGS_ID),
                        mapOf(
                            "retentionDays" to write.document.retentionDays,
                            "updatedAt" to write.document.updatedAt,
                            "stampedDays" to write.document.stampedDays,
                            "syncedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                is DocumentWrite.AccountStamped ->
                    batch.set(
                        collection("settings").document(ACCOUNT_SETTINGS_ID),
                        mapOf("stampedDays" to write.stampedDays, "syncedAt" to FieldValue.serverTimestamp()),
                        SetOptions.merge(),
                    )
                is DocumentWrite.Entry -> {
                    val fields = write.document.toFields().toMutableMap()
                    val expiry = write.document.expireAt
                    if (expiry != null) {
                        fields["expireAt"] = timestamp(expiry)
                    } else if (write.merge) {
                        // a merge leaves fields it does not name, so an expiry that should be gone is removed
                        fields["expireAt"] = FieldValue.delete()
                    }
                    fields["syncedAt"] = FieldValue.serverTimestamp()
                    if (write.merge) {
                        batch.set(entries.document(write.id), fields, SetOptions.merge())
                    } else {
                        batch.set(entries.document(write.id), fields)
                    }
                }
            }
        }
        try {
            batch.commit().await()
        } catch (failure: FirebaseFirestoreException) {
            throw if (failure.code in REFUSALS) RefusedWriteException(failure) else failure
        }
    }

    override suspend fun deleteEntriesOfFeed(feedId: String) {
        val found = entries.whereEqualTo("feedId", feedId).get(Source.SERVER).await().documents
        for (group in found.chunked(BATCH_LIMIT)) {
            val batch = db.batch()
            group.forEach { document -> batch.delete(document.reference) }
            batch.commit().await()
        }
    }

    override suspend fun bodyExists(hash: String): Boolean = db.collection(BODIES).document(hash).get(Source.SERVER).await().exists()

    override suspend fun createBody(hash: String, text: String, expireAt: Long?) {
        val fields = if (expireAt == null) mapOf("text" to text) else mapOf("text" to text, "expireAt" to timestamp(expireAt))
        db.collection(BODIES).document(hash).set(fields).await()
    }

    override suspend fun readBody(hash: String): String = db.collection(BODIES).document(hash).get().await().getString("text") ?: ""

    override suspend fun waitForPendingWrites() {
        db.waitForPendingWrites().await()
    }

    override suspend fun deleteAccountData() {
        for (name in listOf("subscriptions", "entries", "settings")) {
            var page = collection(name).limit(BATCH_LIMIT.toLong()).get(Source.SERVER).await()
            while (!page.isEmpty) {
                val batch = db.batch()
                page.documents.forEach { document -> batch.delete(document.reference) }
                batch.commit().await()
                page = collection(name).limit(BATCH_LIMIT.toLong()).get(Source.SERVER).await()
            }
        }
    }
}
