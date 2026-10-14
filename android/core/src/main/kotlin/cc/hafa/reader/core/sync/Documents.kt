package cc.hafa.reader.core.sync

import cc.hafa.reader.core.DEFAULT_SETTINGS
import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed
import cc.hafa.reader.core.Sort
import cc.hafa.reader.core.ViewSettings
import cc.hafa.reader.core.upgradedAddress
import cc.hafa.reader.core.webAddress

private const val DAY_MS = 24L * 60 * 60 * 1000

/** A view's settings with when they last changed, as they sync. */
data class SavedSettings(
    /** The settings. */
    val settings: ViewSettings,
    /** When they last changed, by the changing device's clock. */
    val updatedAt: Long,
)

/** A feed as stored at `users/{uid}/subscriptions/{feedId}`; a tombstone when deleted. */
data class SubscriptionDocument(
    /** Feed address. */
    val url: String,
    /** Name shown for the feed. */
    val title: String,
    /** Group name; empty for none. */
    val folder: String,
    /** Whether the person chose the name. */
    val renamed: Boolean,
    /** Picture address; empty for none. */
    val icon: String,
    /** Site address; empty when unknown. */
    val siteUrl: String,
    /** When it last changed, by the changing device's clock. */
    override val updatedAt: Long,
    /** The feed was unsubscribed; the other fields are empty. */
    override val deleted: Boolean,
) : Version

/** An article as stored at `users/{uid}/entries/{entryId}`. */
data class EntryDocument(
    /** Id of its feed. */
    val feedId: String,
    /** The feed's id for the article. */
    val guid: String,
    /** Headline. */
    val title: String,
    /** Date, or when first seen if undated. */
    val published: Long,
    /** Whether the feed gave the date. */
    val dated: Boolean,
    /** Link to the original. */
    val url: String,
    /** Writer; empty for none. */
    val author: String,
    /** Lead picture address; empty for none. */
    val imageUrl: String,
    /** Plain-text start of the article. */
    val excerpt: String,
    /** The feed's own short description. */
    val summary: String,
    /** Topics the feed lists. */
    val categories: List<String>,
    /** Hash of the shared body; empty when unknown. */
    val bodyHash: String,
    /** Whether it has been read. */
    val read: Boolean,
    /** Whether it is starred. */
    val starred: Boolean,
    /** When a device first saw it. */
    val addedAt: Long,
    /** When the read or starred mark last changed. */
    val updatedAt: Long,
    /** Milliseconds since the epoch after which Firestore may delete it; only with a finite retention. */
    val expireAt: Long? = null,
)

/** A view's settings as stored at `users/{uid}/settings/{settingsDocumentId(key)}`. */
data class SettingsDocument(
    /** The view key, since document ids cannot hold every character a key can. */
    val key: String,
    /** Newest or oldest first. */
    val sort: Sort,
    /** Words that hide articles. */
    val hideWords: List<String>,
    /** Words an article must have to show. */
    val onlyWords: List<String>,
    /** Topics turned off. */
    val offTopics: List<String>,
    /** When they last changed, by the changing device's clock. */
    override val updatedAt: Long,
    /** The settings were removed; the lists are empty. */
    override val deleted: Boolean,
) : Version

/**
 * The most the Firestore rules accept in a field (`firebase/firestore.rules`),
 * as the web's `FIELD_LIMITS`: characters for text, items for lists. Documents
 * are cut to fit; a feed, entry or view whose identifying text is longer stays
 * on this device.
 */
object FieldLimits {
    /** Feed, site, picture and article addresses. */
    const val ADDRESS = 8192

    /** An article's id within its feed. */
    const val GUID = 8192

    /** Feed names and headlines. */
    const val TITLE = 2000

    /** Group names. */
    const val FOLDER = 1000

    /** Writers. */
    const val AUTHOR = 1000

    /** An article's start, for the list. */
    const val EXCERPT = 1000

    /** A feed's own description of an article. */
    const val SUMMARY = 20_000

    /** Topics per article. */
    const val CATEGORIES = 100

    /** View keys. */
    const val KEY = 1100

    /** Words or topics per list. */
    const val WORDS = 500
}

/** Text cut to at most [limit] UTF-16 units, never in the middle of a character. */
fun cutText(source: String, limit: Int): String {
    var end = minOf(source.length, limit)
    if (end in 1 until source.length && Character.isHighSurrogate(source[end - 1])) {
        end -= 1
    }
    return source.substring(0, end)
}

/** An address that fits the rules; empty when it is too long to keep. */
private fun fitAddress(address: String): String = if (address.length <= FieldLimits.ADDRESS) address else ""

/** Whether a feed's address is short enough for the account. */
fun storableFeed(feed: Feed): Boolean = feed.url.length <= FieldLimits.ADDRESS

/** Whether an entry's guid is short enough for the account. */
fun storableEntry(entry: Entry): Boolean = entry.guid.length <= FieldLimits.GUID

// Longest document id Firestore accepts, in bytes; an encoded id is plain ASCII, so one byte a character.
private const val DOCUMENT_ID_BYTES = 1500

/** Whether a view key is short enough for the account, both as itself and as its encoded document id. */
fun storableKey(key: String): Boolean = key.length <= FieldLimits.KEY && settingsDocumentId(key).length <= DOCUMENT_ID_BYTES

/** How long entries are kept in the account: a number of days, or null for forever. */
typealias RetentionDays = Int?

/** The retention choices, shortest first; forever, the default, last. */
val RETENTION_CHOICES: List<RetentionDays> = listOf(30, 90, 365, null)

// How long a body outlives the longest finite retention, so an entry never outlives its text.
private const val BODY_GRACE_DAYS = 7

/** Document id, in `users/{uid}/settings`, of the account's own settings; no view key encodes to it. */
const val ACCOUNT_SETTINGS_ID = "account"

/** The account's own settings, as stored at `users/{uid}/settings/account`. */
data class AccountDocument(
    /** Days entries are kept in the account; 0 for forever. */
    val retentionDays: Int,
    /** When the setting last changed, on the changing device's clock. */
    val updatedAt: Long,
    /** The retention, in days, the account's entries were last all stamped with; 0 for forever. */
    val stampedDays: Int = 0,
)

/** Whether a stored number of days is a retention the rules accept: 0 or an offered choice. */
private fun offeredDays(days: Int?): Boolean = days != null && (days == 0 || days in RETENTION_CHOICES)

private fun wholeNumber(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf { number -> number == Math.floor(number) }?.toInt()

/** Whether the account's entries still have to be stamped with its retention. */
fun needsRestamp(account: AccountDocument): Boolean = account.retentionDays != account.stampedDays

/** A retention as its stored number of days; 0 for forever. */
fun retentionDays(retention: RetentionDays): Int = retention ?: 0

/** A retention from its stored number of days; anything not offered is forever. */
fun retentionFromDays(days: Int): RetentionDays = RETENTION_CHOICES.firstOrNull { choice -> choice == days }

/** Read the account settings document, or null when it is not one the rules allow. */
fun parseAccount(fields: Map<String, Any?>): AccountDocument? {
    val days = wholeNumber(fields["retentionDays"])
    val updatedAt = fields["updatedAt"] as? Number
    val stamped = wholeNumber(fields["stampedDays"])
    return if (days != null && offeredDays(days) && updatedAt != null) {
        // a document from before stamps were recorded: nothing was stamped yet
        AccountDocument(days, updatedAt.toLong(), if (stamped != null && offeredDays(stamped)) stamped else 0)
    } else {
        null
    }
}

/** Which copy of the account settings wins. */
enum class AccountWinner {
    /** The account's, which is newer. */
    Remote,

    /** This device's, which is newer and set here. */
    Local,

    /** Neither; they agree. */
    Same,
}

/** Which copy of the account settings wins, last write wins; a missing account copy loses to any set here. */
fun accountWinner(local: AccountDocument, remote: AccountDocument?): AccountWinner {
    val remoteAt = remote?.updatedAt ?: 0
    return if (remote != null && remoteAt > local.updatedAt) {
        AccountWinner.Remote
    } else if (local.updatedAt > remoteAt) {
        AccountWinner.Local
    } else {
        AccountWinner.Same
    }
}

/** When an entry leaves the account: [addedAt] plus the retention; never for a starred entry or under forever. */
fun entryExpiry(addedAt: Long, starred: Boolean, retention: RetentionDays): Long? =
    if (retention == null || starred) null else addedAt + retention * DAY_MS

/**
 * When a body uploaded at [now] leaves the account: after the longest finite
 * retention offered plus a week; never when this account keeps entries forever.
 */
fun bodyExpiry(retention: RetentionDays, now: Long): Long? {
    val longest = RETENTION_CHOICES.maxOf(::retentionDays)
    return if (retention == null) null else now + (longest + BODY_GRACE_DAYS) * DAY_MS
}

/**
 * Whether an article's body may have expired from the account: it was added
 * at least as long ago as a body lasts there (the longest retention plus a week).
 */
fun bodyMayBeGone(addedAt: Long, now: Long): Boolean {
    val lifeDays = RETENTION_CHOICES.maxOf(::retentionDays) + BODY_GRACE_DAYS
    return now - addedAt >= lifeDays * DAY_MS
}

/** A change to an account entry's expiry. */
sealed interface ExpiryChange {
    /** Expire at this time, in milliseconds since the epoch. */
    data class At(
        /** The new expiry. */
        val time: Long,
    ) : ExpiryChange

    /** Never expire: remove the field. */
    data object Clear : ExpiryChange
}

/** The change an account entry's expiry, now [current], needs under a retention; null when it is right already. */
fun restampedExpiry(document: EntryDocument, current: Long?, retention: RetentionDays): ExpiryChange? {
    val wanted = entryExpiry(document.addedAt, document.starred, retention)
    return if (wanted == current) {
        null
    } else if (wanted == null) {
        ExpiryChange.Clear
    } else {
        ExpiryChange.At(wanted)
    }
}

/** The fields of a feed that sync between devices. */
fun subscriptionDocument(feed: Feed): SubscriptionDocument =
    SubscriptionDocument(
        url = feed.url,
        title = cutText(feed.title, FieldLimits.TITLE),
        folder = cutText(feed.folder, FieldLimits.FOLDER),
        renamed = feed.renamed,
        icon = fitAddress(feed.icon),
        siteUrl = fitAddress(feed.siteUrl),
        updatedAt = feed.updatedAt,
        deleted = false,
    )

/** The document that records an unsubscribe. */
fun subscriptionTombstone(updatedAt: Long): SubscriptionDocument =
    SubscriptionDocument("", "", "", renamed = false, icon = "", siteUrl = "", updatedAt = updatedAt, deleted = true)

/**
 * A feed from its document, keeping this device's own fields from [local] when
 * given. Each device reads the feed's own name, site and picture itself, so a
 * change from another device (such as a new group) does not replace them with
 * that device's older copy: the name is taken only when someone named the feed
 * by hand, and the site and picture only when this device has none.
 */
fun feedFromDocument(id: String, stored: SubscriptionDocument, local: Feed? = null): Feed {
    val keepName = local != null && !stored.renamed
    val base = Feed(
        id = id,
        url = stored.url,
        title = if (keepName) local.title else stored.title,
        siteUrl = local?.siteUrl?.ifEmpty { null } ?: stored.siteUrl,
        icon = local?.icon?.ifEmpty { null } ?: stored.icon,
        renamed = if (keepName) local.renamed else stored.renamed,
        folder = stored.folder,
        error = local?.error ?: "",
        updatedAt = stored.updatedAt,
    )
    return if (local == null) base else base.copy(schedule = local.schedule)
}

/** An entry's document; `expireAt` is set only when [retention] is finite and the entry is not starred. */
fun entryDocument(entry: Entry, retention: RetentionDays = null): EntryDocument =
    EntryDocument(
        feedId = entry.feedId,
        guid = entry.guid,
        title = cutText(entry.title, FieldLimits.TITLE),
        published = entry.published,
        dated = entry.dated,
        url = fitAddress(entry.url),
        author = cutText(entry.author, FieldLimits.AUTHOR),
        imageUrl = fitAddress(entry.imageUrl),
        excerpt = cutText(entry.excerpt, FieldLimits.EXCERPT),
        summary = cutText(entry.summary, FieldLimits.SUMMARY),
        categories = entry.categories.take(FieldLimits.CATEGORIES),
        bodyHash = entry.bodyHash,
        read = entry.read,
        starred = entry.starred,
        addedAt = entry.addedAt,
        updatedAt = entry.updatedAt,
        expireAt = entryExpiry(entry.addedAt, entry.starred, retention),
    )

/** An entry from its document. */
fun entryFromDocument(id: String, stored: EntryDocument): Entry =
    Entry(
        id = id,
        feedId = stored.feedId,
        guid = stored.guid,
        title = stored.title,
        url = stored.url,
        published = stored.published,
        dated = stored.dated,
        author = stored.author,
        imageUrl = stored.imageUrl,
        excerpt = stored.excerpt,
        summary = stored.summary,
        categories = stored.categories.toList(),
        read = stored.read,
        starred = stored.starred,
        addedAt = stored.addedAt,
        position = 0,
        bodyHash = stored.bodyHash,
        updatedAt = stored.updatedAt,
    )

/** A settings document; [saved] null makes a tombstone. */
fun settingsDocument(key: String, saved: SavedSettings?, updatedAt: Long): SettingsDocument =
    SettingsDocument(
        key = key,
        sort = saved?.settings?.sort ?: DEFAULT_SETTINGS.sort,
        hideWords = saved?.settings?.hideWords?.take(FieldLimits.WORDS) ?: emptyList(),
        onlyWords = saved?.settings?.onlyWords?.take(FieldLimits.WORDS) ?: emptyList(),
        offTopics = saved?.settings?.offTopics?.take(FieldLimits.WORDS) ?: emptyList(),
        updatedAt = updatedAt,
        deleted = saved == null,
    )

/** Saved settings from their document. */
fun settingsFromDocument(stored: SettingsDocument): SavedSettings =
    SavedSettings(ViewSettings(stored.sort, stored.hideWords.toList(), stored.onlyWords.toList(), stored.offTopics.toList()), stored.updatedAt)

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** A Firestore document id for a view key, encoded as a browser's `encodeURIComponent` does; ids may not contain a slash. */
fun settingsDocumentId(key: String): String {
    val encoded = StringBuilder()
    for (byte in key.toByteArray(Charsets.UTF_8)) {
        val character = byte.toInt().toChar()
        if (byte >= 0 && character in UNRESERVED) {
            encoded.append(character)
        } else {
            encoded.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
    return encoded.toString()
}

private fun text(fields: Map<String, Any?>, name: String): String = fields[name] as? String ?: ""

private fun number(fields: Map<String, Any?>, name: String): Long {
    val value = (fields[name] as? Number)?.toDouble()
    return if (value != null && value.isFinite()) value.toLong() else 0
}

private fun flag(fields: Map<String, Any?>, name: String): Boolean = fields[name] as? Boolean ?: false

private fun texts(fields: Map<String, Any?>, name: String): List<String> =
    (fields[name] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

private fun hasTime(fields: Map<String, Any?>): Boolean = fields["updatedAt"] is Number

/** Read a subscription document, or null when it lacks its timestamp or, live, its address. */
fun parseSubscription(fields: Map<String, Any?>): SubscriptionDocument? {
    val stored = if (hasTime(fields)) {
        SubscriptionDocument(
            // a plain http feed is kept as http, to be tried over https before any fetch; other addresses are dropped
            url = webAddress(text(fields, "url")),
            title = text(fields, "title"),
            folder = text(fields, "folder"),
            renamed = flag(fields, "renamed"),
            icon = upgradedAddress(text(fields, "icon")),
            siteUrl = upgradedAddress(text(fields, "siteUrl")),
            updatedAt = number(fields, "updatedAt"),
            deleted = flag(fields, "deleted"),
        )
    } else {
        null
    }
    return if (stored != null && !stored.deleted && stored.url.isEmpty()) null else stored
}

/** Read an entry document, or null when it lacks its feed or timestamp. */
fun parseEntry(fields: Map<String, Any?>): EntryDocument? =
    if (hasTime(fields) && text(fields, "feedId").isNotEmpty()) {
        EntryDocument(
            feedId = text(fields, "feedId"),
            guid = text(fields, "guid"),
            title = text(fields, "title"),
            published = number(fields, "published"),
            dated = flag(fields, "dated"),
            url = upgradedAddress(text(fields, "url")),
            author = text(fields, "author"),
            imageUrl = upgradedAddress(text(fields, "imageUrl")),
            excerpt = text(fields, "excerpt"),
            summary = text(fields, "summary"),
            categories = texts(fields, "categories"),
            bodyHash = text(fields, "bodyHash"),
            read = flag(fields, "read"),
            starred = flag(fields, "starred"),
            addedAt = number(fields, "addedAt"),
            updatedAt = number(fields, "updatedAt"),
        )
    } else {
        null
    }

/** Read a settings document, or null when it lacks its key or timestamp. */
fun parseSettings(fields: Map<String, Any?>): SettingsDocument? =
    if (hasTime(fields) && text(fields, "key").isNotEmpty()) {
        SettingsDocument(
            key = text(fields, "key"),
            sort = if (fields["sort"] == "oldest") Sort.Oldest else Sort.Newest,
            hideWords = texts(fields, "hideWords"),
            onlyWords = texts(fields, "onlyWords"),
            offTopics = texts(fields, "offTopics"),
            updatedAt = number(fields, "updatedAt"),
            deleted = flag(fields, "deleted"),
        )
    } else {
        null
    }

/** A subscription document's fields as stored. */
fun SubscriptionDocument.toFields(): Map<String, Any?> =
    mapOf(
        "url" to url, "title" to title, "folder" to folder, "renamed" to renamed, "icon" to icon,
        "siteUrl" to siteUrl, "updatedAt" to updatedAt, "deleted" to deleted,
    )

/** A settings document's fields as stored. */
fun SettingsDocument.toFields(): Map<String, Any?> =
    mapOf(
        "key" to key, "sort" to if (sort == Sort.Oldest) "oldest" else "newest", "hideWords" to hideWords,
        "onlyWords" to onlyWords, "offTopics" to offTopics, "updatedAt" to updatedAt, "deleted" to deleted,
    )

/**
 * An entry document's fields as stored: no `bodyHash` when it is empty (a
 * merge then keeps the account's), and `expireAt` only when set; the caller
 * turns `expireAt` into a timestamp.
 */
fun EntryDocument.toFields(): Map<String, Any?> {
    val fields = mutableMapOf<String, Any?>(
        "feedId" to feedId, "guid" to guid, "title" to title, "published" to published, "dated" to dated,
        "url" to url, "author" to author, "imageUrl" to imageUrl, "excerpt" to excerpt, "summary" to summary,
        "categories" to categories, "read" to read, "starred" to starred, "addedAt" to addedAt, "updatedAt" to updatedAt,
    )
    if (bodyHash.isNotEmpty()) {
        fields["bodyHash"] = bodyHash
    }
    if (expireAt != null) {
        fields["expireAt"] = expireAt
    }
    return fields
}
