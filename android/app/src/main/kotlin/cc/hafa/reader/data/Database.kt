package cc.hafa.reader.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import cc.hafa.reader.core.PostingPattern
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A subscribed feed with its checking schedule. */
@Entity(tableName = "feeds")
data class FeedRecord(
    /** Hash of the feed address. */
    @PrimaryKey val id: String,
    /** The feed's address. */
    val url: String,
    /** Name shown for the feed. */
    val title: String,
    /** Address of the site the feed belongs to. */
    val siteUrl: String,
    /** Address of the feed's small picture. */
    val icon: String,
    /** Whether the person gave the feed its own name. */
    val renamed: Boolean,
    /** Group the feed is listed under; empty for none. */
    val folder: String,
    /** Problem code shown after repeated failures; empty for none. */
    val error: String,
    /** When a synced field last changed. */
    val updatedAt: Long,
    /** `ETag` of the last full answer. */
    val etag: String,
    /** `Last-Modified` of the last full answer. */
    val lastModified: String,
    /** Gamma shape of the posting model. */
    val alpha: Double,
    /** Days of observation behind the posting model. */
    val beta: Double,
    /** Most items the feed has shown at once. */
    val capacity: Int,
    /** Posting pattern by hour of day, day of week and day of month, as JSON; null means flat. */
    val pattern: PostingPattern?,
    /** Milliseconds since the epoch of the next check. */
    val nextFetch: Long,
    /** Milliseconds since the epoch of the last successful check. */
    val lastChecked: Long,
    /** Hash of the last body seen. */
    val hash: String,
    /** Guids in the last window, as JSON. */
    val guids: List<String>,
    /** Title, site link and icon at the last change; null if unknown. */
    val details: String?,
    /** Failed checks in a row. */
    val failures: Int,
    /** Code of the last failure; empty when it did not fail. */
    val problem: String,
)

/** An article, without its body. */
@Entity(tableName = "entries", indices = [Index("feedId")])
data class EntryRecord(
    /** Hash of the feed id and guid. */
    @PrimaryKey val id: String,
    /** Feed the article came from. */
    val feedId: String,
    /** The feed's identifier for the article. */
    val guid: String,
    /** Headline. */
    val title: String,
    /** Link to the original article. */
    val url: String,
    /** When it was posted, or first seen. */
    val published: Long,
    /** Whether the feed gave the date. */
    val dated: Boolean,
    /** Writer's name. */
    val author: String,
    /** Lead image address. */
    val imageUrl: String,
    /** Plain-text start for the list. */
    val excerpt: String,
    /** The feed's own short description. */
    val summary: String,
    /** Topics the feed lists, as JSON. */
    val categories: List<String>,
    /** Whether it has been read. */
    val read: Boolean,
    /** Whether it is starred. */
    val starred: Boolean,
    /** When this device first saw it. */
    val addedAt: Long,
    /** Place in the feed when first seen. */
    @ColumnInfo(defaultValue = "0")
    val position: Int,
    /** Hex SHA-256 of the body; empty when none. */
    val bodyHash: String,
    /** When the read or starred mark last changed. */
    val updatedAt: Long,
)

/** An article's body, kept apart so lists load without it. */
@Entity(tableName = "bodies")
data class BodyRecord(
    /** The article. */
    @PrimaryKey val entryId: String,
    /** Its body as HTML, not yet sanitized. */
    val html: String,
)

/** One view's settings, by view key. */
@Entity(tableName = "settings")
data class SettingsRecord(
    /** View key. */
    @PrimaryKey val key: String,
    /** `newest` or `oldest`. */
    val sort: String,
    /** Words that hide articles, as JSON. */
    val hideWords: List<String>,
    /** Words articles must have, as JSON. */
    val onlyWords: List<String>,
    /** Topics turned off, as JSON. */
    val offTopics: List<String>,
    /** When the settings last changed. */
    val updatedAt: Long,
)

/** When a feed or view's settings were removed, so the removal can sync. */
@Entity(tableName = "tombstones")
data class TombstoneRecord(
    /** `feed:{id}` or `settings:{viewKey}`. */
    @PrimaryKey val key: String,
    /** When it was removed. */
    val removedAt: Long,
)

/** Lists and posting patterns stored as JSON text. */
class Converters {
    private val serializer = ListSerializer(String.serializer())
    private val patternSerializer = MapSerializer(String.serializer(), ListSerializer(Double.serializer()))

    /** Text form of a list. */
    @TypeConverter
    fun fromList(values: List<String>): String = Json.encodeToString(serializer, values)

    /** List from its text form. */
    @TypeConverter
    fun toList(text: String): List<String> = Json.decodeFromString(serializer, text)

    /** Text form of a posting pattern; null stays null. */
    @TypeConverter
    fun fromPattern(pattern: PostingPattern?): String? =
        pattern?.let { counts ->
            Json.encodeToString(
                patternSerializer,
                mapOf("hourOfDay" to counts.hourOfDay, "dayOfWeek" to counts.dayOfWeek, "dayOfMonth" to counts.dayOfMonth),
            )
        }

    /** Posting pattern from its text form. */
    @TypeConverter
    fun toPattern(text: String?): PostingPattern? =
        text?.let { stored ->
            val bins = Json.decodeFromString(patternSerializer, stored)
            PostingPattern(
                hourOfDay = bins["hourOfDay"] ?: emptyList(),
                dayOfWeek = bins["dayOfWeek"] ?: emptyList(),
                dayOfMonth = bins["dayOfMonth"] ?: emptyList(),
            )
        }
}

/** Reads and writes of the reader's tables. */
@Dao
interface ReaderDao {
    /** Every feed. */
    @Query("SELECT * FROM feeds")
    suspend fun feeds(): List<FeedRecord>

    /** Every entry, in the order they were added. */
    @Query("SELECT * FROM entries ORDER BY addedAt, rowid")
    suspend fun entries(): List<EntryRecord>

    /** Every view's settings. */
    @Query("SELECT * FROM settings")
    suspend fun settings(): List<SettingsRecord>

    /** One body. */
    @Query("SELECT html FROM bodies WHERE entryId = :entryId")
    suspend fun body(entryId: String): String?

    /** Every removal kept for sync. */
    @Query("SELECT * FROM tombstones")
    suspend fun tombstones(): List<TombstoneRecord>

    /** Save removals. */
    @Upsert
    suspend fun upsertTombstones(tombstones: List<TombstoneRecord>)

    /** Forget removals. */
    @Query("DELETE FROM tombstones WHERE `key` IN (:keys)")
    suspend fun deleteTombstones(keys: List<String>)

    /** Remove every feed. */
    @Query("DELETE FROM feeds")
    suspend fun clearFeeds()

    /** Remove every entry. */
    @Query("DELETE FROM entries")
    suspend fun clearEntries()

    /** Remove every body. */
    @Query("DELETE FROM bodies")
    suspend fun clearBodies()

    /** Remove every view's settings. */
    @Query("DELETE FROM settings")
    suspend fun clearSettings()

    /** Remove every removal. */
    @Query("DELETE FROM tombstones")
    suspend fun clearTombstones()

    /** Remove everything, before another account's data comes in. */
    @Transaction
    suspend fun clearAll() {
        clearBodies()
        clearEntries()
        clearFeeds()
        clearSettings()
        clearTombstones()
    }

    /** Save feeds. */
    @Upsert
    suspend fun upsertFeeds(feeds: List<FeedRecord>)

    /** Save entries. */
    @Upsert
    suspend fun upsertEntries(entries: List<EntryRecord>)

    /** Save bodies. */
    @Upsert
    suspend fun upsertBodies(bodies: List<BodyRecord>)

    /** Save one view's settings. */
    @Upsert
    suspend fun upsertSettings(settings: SettingsRecord)

    /** Remove one view's settings. */
    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSettings(key: String)

    /** Remove a feed. */
    @Query("DELETE FROM feeds WHERE id = :feedId")
    suspend fun deleteFeedRow(feedId: String)

    /** Remove a feed's entries. */
    @Query("DELETE FROM entries WHERE feedId = :feedId")
    suspend fun deleteEntriesOf(feedId: String)

    /** Remove the bodies of a feed's entries. */
    @Query("DELETE FROM bodies WHERE entryId IN (SELECT id FROM entries WHERE feedId = :feedId)")
    suspend fun deleteBodiesOf(feedId: String)

    /** Save new entries together with their bodies. */
    @Transaction
    suspend fun insertEntries(entries: List<EntryRecord>, bodies: List<BodyRecord>) {
        upsertEntries(entries)
        upsertBodies(bodies)
    }

    /** Save checked feeds and their new entries with bodies in one transaction. */
    @Transaction
    suspend fun saveChecks(feeds: List<FeedRecord>, entries: List<EntryRecord>, bodies: List<BodyRecord>) {
        upsertFeeds(feeds)
        upsertEntries(entries)
        upsertBodies(bodies)
    }

    /** Remove a feed with its entries, bodies and settings. */
    @Transaction
    suspend fun deleteFeed(feedId: String) {
        deleteBodiesOf(feedId)
        deleteEntriesOf(feedId)
        deleteSettings("feed:$feedId")
        deleteFeedRow(feedId)
    }
}

/** The reader's local database. */
@Database(
    entities = [FeedRecord::class, EntryRecord::class, BodyRecord::class, SettingsRecord::class, TombstoneRecord::class],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ReaderDatabase : RoomDatabase() {
    /** The reader's tables. */
    abstract fun dao(): ReaderDao

    companion object {
        /** Open the database, creating it on first use. */
        fun open(context: Context): ReaderDatabase =
            Room.databaseBuilder(context, ReaderDatabase::class.java, "reader.db")
                .addMigrations(*MIGRATIONS)
                // versions from before the first release are wiped, not migrated
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
                .build()

        /**
         * Steps from one database version to the next; none yet. To change the
         * tables, raise `version`, build to export the new schema into
         * `app/schemas`, and add a [Migration] from the old version here.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
