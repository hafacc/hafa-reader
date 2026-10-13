package cc.hafa.reader.data

import android.content.Context
import androidx.core.content.edit
import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Feed
import cc.hafa.reader.core.FeedModel
import cc.hafa.reader.core.FeedSchedule
import cc.hafa.reader.core.ReaderPreferences
import cc.hafa.reader.core.ReaderStore
import cc.hafa.reader.core.Sort
import cc.hafa.reader.core.ViewSettings
import cc.hafa.reader.core.sync.SavedSettings

private fun Feed.toRecord() = FeedRecord(
    id = id,
    url = url,
    title = title,
    siteUrl = siteUrl,
    icon = icon,
    renamed = renamed,
    folder = folder,
    error = error,
    updatedAt = updatedAt,
    etag = schedule.etag,
    lastModified = schedule.lastModified,
    alpha = schedule.model.alpha,
    beta = schedule.model.beta,
    capacity = schedule.model.capacity,
    pattern = schedule.model.pattern,
    nextFetch = schedule.nextFetch,
    lastChecked = schedule.lastChecked,
    hash = schedule.hash,
    guids = schedule.guids,
    details = schedule.details,
    failures = schedule.failures,
    problem = schedule.problem,
)

private fun FeedRecord.toFeed() = Feed(
    id = id,
    url = url,
    title = title,
    siteUrl = siteUrl,
    icon = icon,
    renamed = renamed,
    folder = folder,
    error = error,
    updatedAt = updatedAt,
    schedule = FeedSchedule(
        etag = etag,
        lastModified = lastModified,
        model = FeedModel(alpha = alpha, beta = beta, capacity = capacity, pattern = pattern),
        nextFetch = nextFetch,
        lastChecked = lastChecked,
        hash = hash,
        guids = guids,
        details = details,
        failures = failures,
        problem = problem,
    ),
)

private fun Entry.toRecord() = EntryRecord(
    id = id,
    feedId = feedId,
    guid = guid,
    title = title,
    url = url,
    published = published,
    dated = dated,
    author = author,
    imageUrl = imageUrl,
    excerpt = excerpt,
    summary = summary,
    categories = categories,
    read = read,
    starred = starred,
    addedAt = addedAt,
    position = position,
    bodyHash = bodyHash,
    updatedAt = updatedAt,
)

private fun EntryRecord.toEntry() = Entry(
    id = id,
    feedId = feedId,
    guid = guid,
    title = title,
    url = url,
    published = published,
    dated = dated,
    author = author,
    imageUrl = imageUrl,
    excerpt = excerpt,
    summary = summary,
    categories = categories,
    read = read,
    starred = starred,
    addedAt = addedAt,
    position = position,
    bodyHash = bodyHash,
    updatedAt = updatedAt,
)

/** The reader's storage in its Room database. */
class RoomReaderStore(private val dao: ReaderDao) : ReaderStore {
    override suspend fun loadFeeds(): List<Feed> = dao.feeds().map { record -> record.toFeed() }

    override suspend fun loadEntries(): List<Entry> = dao.entries().map { record -> record.toEntry() }

    override suspend fun loadSettings(): Map<String, SavedSettings> =
        dao.settings().associate { record ->
            record.key to SavedSettings(
                ViewSettings(
                    sort = if (record.sort == "oldest") Sort.Oldest else Sort.Newest,
                    hideWords = record.hideWords,
                    onlyWords = record.onlyWords,
                    offTopics = record.offTopics,
                ),
                record.updatedAt,
            )
        }

    override suspend fun loadTombstones(): Map<String, Long> = dao.tombstones().associate { record -> record.key to record.removedAt }

    override suspend fun putTombstones(tombstones: Map<String, Long>) =
        dao.upsertTombstones(tombstones.map { (key, removedAt) -> TombstoneRecord(key, removedAt) })

    override suspend fun deleteTombstones(keys: Collection<String>) = dao.deleteTombstones(keys.toList())

    override suspend fun putBody(entryId: String, html: String) = dao.upsertBodies(listOf(BodyRecord(entryId, html)))

    override suspend fun clear() = dao.clearAll()

    override suspend fun putSettings(key: String, settings: ViewSettings, updatedAt: Long) {
        dao.upsertSettings(
            SettingsRecord(
                key = key,
                sort = if (settings.sort == Sort.Oldest) "oldest" else "newest",
                hideWords = settings.hideWords,
                onlyWords = settings.onlyWords,
                offTopics = settings.offTopics,
                updatedAt = updatedAt,
            ),
        )
    }

    override suspend fun deleteSettings(key: String) = dao.deleteSettings(key)

    override suspend fun putFeeds(feeds: List<Feed>) = dao.upsertFeeds(feeds.map { feed -> feed.toRecord() })

    override suspend fun deleteFeed(feedId: String) = dao.deleteFeed(feedId)

    override suspend fun putEntries(entries: List<Entry>, bodies: Map<String, String>) =
        dao.insertEntries(
            entries.map { entry -> entry.toRecord() },
            entries.map { entry -> BodyRecord(entry.id, bodies[entry.id] ?: "") },
        )

    override suspend fun updateEntries(entries: List<Entry>) = dao.upsertEntries(entries.map { entry -> entry.toRecord() })

    override suspend fun getBody(entryId: String): String = dao.body(entryId) ?: ""

    override suspend fun putChecks(feeds: List<Feed>, entries: List<Entry>, bodies: Map<String, String>) =
        dao.saveChecks(
            feeds.map { feed -> feed.toRecord() },
            entries.map { entry -> entry.toRecord() },
            entries.map { entry -> BodyRecord(entry.id, bodies[entry.id] ?: "") },
        )
}

private const val UNREAD_KEY = "unread-only"
private const val VIEW_KEY = "view"

/** The reader's small choices in shared preferences. */
class SharedReaderPreferences(context: Context) : ReaderPreferences {
    private val shared = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    override var unreadOnly: Boolean
        get() = shared.getBoolean(UNREAD_KEY, true)
        set(value) = shared.edit { putBoolean(UNREAD_KEY, value) }

    override var lastView: String
        get() = shared.getString(VIEW_KEY, null) ?: "all"
        set(value) = shared.edit { putString(VIEW_KEY, value) }
}
