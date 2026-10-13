package cc.hafa.reader.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What sync remembers on this device for one account. */
data class SyncState(
    /** Newest server write time the entry listener has seen, in milliseconds. */
    val cursor: Long = 0,
    /** Whether the first pass over the account's entries has finished. */
    val merged: Boolean = false,
    /** Where that pass stopped; null before its first page. */
    val page: PageCursor? = null,
    /** Ids of entries found here that still have to be uploaded. */
    val pending: List<String> = emptyList(),
    /** The account's retention as last known here. */
    val account: AccountDocument = AccountDocument(0, 0),
    /** An unfinished pass applying a retention; null when none. */
    val restamp: Restamp? = null,
)

/**
 * What stays saved when sync stops for an account, as at sign-out: nothing,
 * except an unfinished retention pass, which must still finish or entries
 * keep an expiry the account no longer wants.
 */
fun stateAfterStop(state: SyncState): SyncState? = state.restamp?.let { pass -> SyncState(account = state.account, restamp = pass) }

/** Forget an account's sync progress on this device, all but an unfinished retention pass; see [stateAfterStop]. */
fun forgetProgress(preferences: SyncPreferences, uid: String) {
    preferences.set(stateKey(uid), stateAfterStop(parseState(preferences.get(stateKey(uid))))?.toText())
}

/** A pass setting the expiry of the account's entries to a retention, in document id order. */
data class Restamp(
    /** The retention being applied, in days; 0 for forever. */
    val retentionDays: Int,
    /** Id of the last entry done; empty before the first page. */
    val after: String,
)

/** Storage key of an account's sync state. */
fun stateKey(uid: String): String = "h-reader:sync:$uid"

/** Storage key of the account this device last synced with. */
const val ACCOUNT_KEY = "h-reader:sync-account"

/** Saved state as text. */
fun SyncState.toText(): String =
    buildJsonObject {
        put("cursor", cursor)
        put("merged", merged)
        val saved = page
        if (saved != null) {
            putJsonObject("page") {
                put("published", saved.published)
                put("id", saved.id)
            }
        }
        putJsonArray("pending") { pending.forEach { id -> add(JsonPrimitive(id)) } }
        putJsonObject("account") {
            put("retentionDays", account.retentionDays)
            put("updatedAt", account.updatedAt)
            put("stampedDays", account.stampedDays)
        }
        val pass = restamp
        if (pass != null) {
            putJsonObject("restamp") {
                put("retentionDays", pass.retentionDays)
                put("after", pass.after)
            }
        }
    }.toString()

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { value -> !value.isString }?.doubleOrNull?.toLong()

/** Read saved state; anything unreadable starts over. */
fun parseState(raw: String?): SyncState {
    val value = try {
        raw?.let(Json::parseToJsonElement) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
    return if (value == null) {
        SyncState()
    } else {
        val page = (value["page"] as? JsonObject)?.let { saved ->
            val published = saved.long("published")
            val id = (saved["id"] as? JsonPrimitive)?.takeIf { item -> item.isString }?.content
            if (published != null && id != null) PageCursor(published, id) else null
        }
        SyncState(
            cursor = value.long("cursor") ?: 0,
            merged = (value["merged"] as? JsonPrimitive)?.takeIf { item -> !item.isString }?.booleanOrNull ?: false,
            page = page,
            pending = (value["pending"] as? JsonArray)
                ?.mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                ?: emptyList(),
            account = (value["account"] as? JsonObject)?.let { saved ->
                val days = saved.long("retentionDays")
                val updatedAt = saved.long("updatedAt")
                if (days != null && updatedAt != null) AccountDocument(days.toInt(), updatedAt, saved.long("stampedDays")?.toInt() ?: 0) else null
            } ?: AccountDocument(0, 0),
            restamp = (value["restamp"] as? JsonObject)?.let { saved ->
                val days = saved.long("retentionDays")
                val after = saved.string("after")
                if (days != null && after != null) Restamp(days.toInt(), after) else null
            },
        )
    }
}

/** When the entry listener starts: the cursor less an overlap for clock skew; 0 for no cursor. */
fun listenFrom(cursor: Long, overlap: Long): Long = if (cursor > 0) maxOf(0, cursor - overlap) else 0

/**
 * Whether signing in must wait for the person to choose: a different
 * account than the one this device last synced with, while the device still
 * holds data. One account's data is never uploaded into another.
 */
fun needsChoice(lastUid: String, uid: String, hasData: Boolean): Boolean = lastUid.isNotEmpty() && lastUid != uid && hasData
