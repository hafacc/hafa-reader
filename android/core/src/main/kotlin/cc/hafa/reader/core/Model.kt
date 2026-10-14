package cc.hafa.reader.core

/** Where a feed stands in its checking schedule. */
data class FeedSchedule(
    /** `ETag` of the last full answer, sent back as `If-None-Match`. */
    val etag: String = "",
    /** `Last-Modified` of the last full answer, sent back as `If-Modified-Since`. */
    val lastModified: String = "",
    /** How often the feed is believed to post. */
    val model: FeedModel = PRIOR,
    /** Milliseconds since the epoch of the next check; zero means due now. */
    val nextFetch: Long = 0,
    /** Milliseconds since the epoch of the last successful check; zero if never. */
    val lastChecked: Long = 0,
    /** Hash of the last body seen, to tell a changed feed from a repeat. */
    val hash: String = "",
    /** Guids in the last window, to count which items are new. */
    val guids: List<String> = emptyList(),
    /** The feed's title, site link and icon at the last change; null if unknown. */
    val details: String? = null,
    /** Failed checks in a row. */
    val failures: Int = 0,
    /** Why the last check failed, as a [FeedProblem] code; empty when it did not. */
    val problem: String = "",
)

/** A subscribed feed. */
data class Feed(
    /** Hash of the feed address. */
    val id: String,
    /** The feed's address. */
    val url: String,
    /** Name shown for the feed. */
    val title: String,
    /** Address of the site the feed belongs to. */
    val siteUrl: String,
    /** Address of the feed's small picture; empty to use the site's favicon. */
    val icon: String,
    /** The person gave the feed its own name, so updates must not replace it. */
    val renamed: Boolean,
    /** Group the feed is listed under; empty for none. */
    val folder: String,
    /** Why checks keep failing, as a [FeedProblem] code; empty unless two in a row failed. */
    val error: String,
    /** Checking state. */
    val schedule: FeedSchedule = FeedSchedule(),
    /** When a synced field (address, name, group, picture, site) last changed. */
    val updatedAt: Long = 0,
)

/** An article, without its body. */
data class Entry(
    /** Hash of the feed id and the article's guid, the same on every device. */
    val id: String,
    /** Feed the article came from. */
    val feedId: String,
    /** The feed's identifier for the article. */
    val guid: String,
    override val title: String,
    /** Link to the original article. */
    val url: String,
    /** When the feed says it was posted; the first sighting if the feed gives no date. */
    val published: Long,
    /** Whether [published] came from the feed. */
    val dated: Boolean,
    /** Writer's name. */
    val author: String,
    /** Lead image address. */
    val imageUrl: String,
    /** Plain-text start of the article, for the list. */
    val excerpt: String,
    override val summary: String,
    override val categories: List<String>,
    /** Whether the article has been read. */
    val read: Boolean,
    /** Whether the article is starred. */
    val starred: Boolean,
    /** When this device first saw the article. */
    val addedAt: Long,
    /** Place in the feed when first seen, from 0 at the top; orders articles with the same date. */
    val position: Int = 0,
    /** Hex SHA-256 of the stored body; empty when unknown or there is none. */
    val bodyHash: String = "",
    /** When the read or starred mark last changed. */
    val updatedAt: Long = 0,
) : FilterEntry

/** What the article list shows. */
sealed interface Selection {
    /** Every article. */
    data object All : Selection

    /** Starred articles. */
    data object Starred : Selection

    /** The feeds of one group. */
    data class Folder(
        /** Group name. */
        val name: String,
    ) : Selection

    /** One feed. */
    data class FeedView(
        /** Feed id. */
        val id: String,
    ) : Selection
}

/** Key under which a view's settings are saved. */
fun viewKey(selection: Selection): String =
    when (selection) {
        Selection.All -> "all"
        Selection.Starred -> "starred"
        is Selection.Folder -> "folder:${selection.name}"
        is Selection.FeedView -> "feed:${selection.id}"
    }

/** The view a key from [viewKey] names; All items for anything else. */
fun selectionFromKey(key: String): Selection =
    when {
        key == "starred" -> Selection.Starred
        key.startsWith("folder:") -> Selection.Folder(key.removePrefix("folder:"))
        key.startsWith("feed:") -> Selection.FeedView(key.removePrefix("feed:"))
        else -> Selection.All
    }

/** Why a feed could not be read. */
sealed interface FeedProblem {
    /** Stored form, read back by [feedProblemFromCode]. */
    val code: String

    /** No answer at all. */
    data object NoAnswer : FeedProblem {
        override val code = "no-answer"
    }

    /** What was typed is not a web address that can be fetched over https. */
    data object NotWebAddress : FeedProblem {
        override val code = "not-web-address"
    }

    /** The feed's address, or where it redirects, is plain http, which is never fetched. */
    data object NotHttps : FeedProblem {
        override val code = "not-https"
    }

    /** What was typed is a plain http address, which is never added. */
    data object HttpRefused : FeedProblem {
        override val code = "http-refused"
    }

    /** A followed feed's address is plain http, and no https version of it was found. */
    data object HttpFeed : FeedProblem {
        override val code = "http-feed"
    }

    /** An answer that is not a feed, while adding one. */
    data object NotAFeed : FeedProblem {
        override val code = "not-a-feed"
    }

    /** An answer that is not a feed, while checking one already followed. */
    data object Unreadable : FeedProblem {
        override val code = "unreadable"
    }

    /** An HTTP status other than success. */
    data class Status(
        /** HTTP status code. */
        val status: Int,
    ) : FeedProblem {
        override val code = "status:$status"
    }
}

/** The problem a stored code names, or null for none. */
fun feedProblemFromCode(code: String): FeedProblem? =
    when {
        code.isEmpty() -> null
        code == FeedProblem.NotAFeed.code -> FeedProblem.NotAFeed
        code == FeedProblem.NotWebAddress.code -> FeedProblem.NotWebAddress
        code == FeedProblem.NotHttps.code -> FeedProblem.NotHttps
        code == FeedProblem.HttpRefused.code -> FeedProblem.HttpRefused
        code == FeedProblem.HttpFeed.code -> FeedProblem.HttpFeed
        code == FeedProblem.Unreadable.code -> FeedProblem.Unreadable
        code.startsWith("status:") -> code.removePrefix("status:").toIntOrNull()?.let(FeedProblem::Status) ?: FeedProblem.NoAnswer
        else -> FeedProblem.NoAnswer
    }

/** Thrown when a feed cannot be added. */
class FeedProblemException(
    /** What went wrong. */
    val problem: FeedProblem,
) : Exception(problem.code)

/** Outcome of fetching one address. */
data class FetchOutcome(
    /** HTTP status; zero when the request failed. */
    val status: Int,
    /** Body, for a 200 answer. */
    val text: String = "",
    /** The answer's `ETag`. */
    val etag: String = "",
    /** The answer's `Last-Modified`. */
    val lastModified: String = "",
    /** Milliseconds the site asked us to wait before checking again. */
    val hint: Long = 0,
    /** The address the answer came from, after redirects; empty if unknown. */
    val url: String = "",
    /** The address was plain http, or a redirect led to it; status is then zero. */
    val insecure: Boolean = false,
    /** The request ran out of time; status is then zero. */
    val timedOut: Boolean = false,
)
