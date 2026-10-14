package cc.hafa.reader.core

import java.text.Collator

/** Everything the reader holds; the screens show what [deriveView] makes of it. */
data class ReaderState(
    /** Subscribed feeds. */
    val feeds: List<Feed> = emptyList(),
    /** Every stored article. */
    val entries: List<Entry> = emptyList(),
    /** The view the list shows. */
    val selection: Selection = Selection.All,
    /** When each view's settings last changed, by view key. */
    val settingsTimes: Map<String, Long> = emptyMap(),
    /** When each removed feed or setting was removed, by tombstone key (`feed:{id}` or `settings:{viewKey}`). */
    val tombstones: Map<String, Long> = emptyMap(),
    /** Saved settings by view key; see [viewKey]. */
    val settings: Map<String, ViewSettings> = emptyMap(),
    /** Entries read in this view; they stay listed until the view changes. */
    val pinned: Set<String> = emptySet(),
    /**
     * Topics each view filtered on before a topic was toggled from an article, by
     * settings key; the list keeps using them until the next refresh or view change.
     */
    val heldTopics: Map<String, List<String>> = emptyMap(),
    /** Only list articles not yet read, and feeds that have any; one switch for every view. */
    val unreadOnly: Boolean = true,
    /** Id of the open article; empty for none. */
    val currentId: String = "",
    /** Sanitized body of the open article. */
    val body: String = "",
    /** Id of the article [body] belongs to; empty while it loads. */
    val bodyId: String = "",
    /** Sanitized bodies of the open article's neighbors, ready before they are opened, by entry id. */
    val aheadBodies: Map<String, String> = emptyMap(),
    /** Pictures of the open article's neighbors to fetch ahead; empty while saving data. */
    val aheadPictures: List<String> = emptyList(),
    /** Whether stored data has been read. */
    val loaded: Boolean = false,
    /** Refreshes under way. */
    val refreshCount: Int = 0,
    /** Whether any feed is queued, being checked or waiting to be saved, for a refresh or in the background. */
    val checking: Boolean = false,
    /** Whether a feed is being added or imported. */
    val adding: Boolean = false,
    /** Sidebar positions fixed at load and at each refresh; rows added later follow them. */
    val frozenRank: Map<String, Int>? = null,
    /**
     * Ids of the articles the list may show; ones that arrive later wait until
     * the list is next settled, so rows never move under the person. Null
     * lets every article in.
     */
    val heldEntries: Set<String>? = null,
    /** Ids of the feeds the sidebar may list, held like [heldEntries]; null lets every feed in. */
    val heldFeeds: Set<String>? = null,
    /** Imports whose feeds are being checked for the first time. */
    val importing: Int = 0,
) {
    /** Whether feeds are being checked now. */
    val refreshing: Boolean get() = refreshCount > 0
}

/**
 * When a refresh starts to help: once the first feed is overdue (see
 * [overdueAt]), or [Long.MAX_VALUE] while nothing is overdue or feeds are
 * being checked, so the suggestion clears once a refresh has saved them. A
 * plain http feed is never on the schedule, so it is never overdue.
 */
fun refreshSuggestedAt(state: ReaderState): Long =
    if (state.refreshing || state.checking) {
        Long.MAX_VALUE
    } else {
        overdueAt(state.feeds.filter { feed -> !isPlainHttp(feed.url) }.map { feed -> feed.schedule.nextFetch })
    }

/** A feed in the sidebar, with what it is ordered by. */
data class FeedRow(
    /** The feed. */
    val feed: Feed,
    /** Its unread articles. */
    val unread: Int,
    /** Whether its checks keep failing. */
    val failing: Boolean,
)

/** A top-level sidebar item: a group with its feeds, or a feed on its own. */
sealed interface TreeRow {
    /** Unread articles under the row. */
    val unread: Int

    /** Whether the row counts as failing. */
    val failing: Boolean

    /** Name the row is ordered by. */
    val name: String

    /** A group and its feeds. */
    data class Group(
        override val name: String,
        override val unread: Int,
        override val failing: Boolean,
        /** Feeds in the group, in sidebar order. */
        val feeds: List<FeedRow>,
    ) : TreeRow

    /** A feed in no group. */
    data class Loose(
        /** The feed's row. */
        val row: FeedRow,
    ) : TreeRow {
        override val unread: Int get() = row.unread
        override val failing: Boolean get() = row.failing
        override val name: String get() = row.feed.title
    }
}

/** Name of a view: one of the two built-in ones, or a feed's or group's own. */
sealed interface ViewTitle {
    /** "All items". */
    data object AllItems : ViewTitle

    /** "Starred". */
    data object Starred : ViewTitle

    /** A feed or group name. */
    data class Named(
        /** The name. */
        val name: String,
    ) : ViewTitle
}

/** What the screens show, worked out from a [ReaderState]. */
data class ReaderView(
    /** Settings of the current view. */
    val viewSettings: ViewSettings,
    /** Entries that belong to the current view, before any rules. */
    val baseEntries: List<Entry>,
    /** Entries in the current view, in the chosen order, as listed. */
    val visible: List<Entry>,
    /** The topics in the current view, commonest first, kept as the same object while they stay the same. */
    val topicOrder: TopicOrder,
    /** Name of the current view. */
    val viewTitle: ViewTitle,
    /** Feeds in the current view; empty means every feed. */
    val viewFeedIds: List<String>,
    /** The open entry, if any. */
    val current: Entry?,
    /** Unread entries per feed id that the feed's own words and topics keep. */
    val unreadByFeed: Map<String, Int>,
    /** The sidebar's groups, then its loose feeds. */
    val tree: List<TreeRow>,
    /** Total unread entries. */
    val unreadTotal: Int,
    /** Starred entries. */
    val starredCount: Int,
    /** Group names in use, sorted. */
    val folders: List<String>,
)

/** Most unread first, then working before failing, then by name. */
private fun byUnread(collator: Collator): Comparator<TreeRow> =
    Comparator { first, second ->
        compareRows(collator, first.unread, first.failing, first.name, second.unread, second.failing, second.name)
    }

// A Collator is not safe to share between threads, so each sort takes its own.
private fun compareRows(
    collator: Collator,
    firstUnread: Int,
    firstFailing: Boolean,
    firstName: String,
    secondUnread: Int,
    secondFailing: Boolean,
    secondName: String,
): Int {
    val byCount = secondUnread - firstUnread
    val byFailing = firstFailing.compareTo(secondFailing)
    return if (byCount != 0) {
        byCount
    } else if (byFailing != 0) {
        byFailing
    } else {
        collator.compare(firstName, secondName)
    }
}

private fun sortRows(rows: List<FeedRow>): List<FeedRow> {
    val collator = Collator.getInstance()
    return rows.sortedWith { first, second ->
        compareRows(collator, first.unread, first.failing, first.feed.title, second.unread, second.failing, second.feed.title)
    }
}

/** Order items by a saved position, keeping the given order for unsaved ones, which go last. */
private fun <Item> holdOrder(items: List<Item>, keyOf: (Item) -> String, rank: Map<String, Int>?): List<Item> =
    if (rank == null) {
        items
    } else {
        items.sortedBy { item -> rank[keyOf(item)] ?: Int.MAX_VALUE }
    }

/** Unread articles the filters keep, per feed, per group and in all. */
data class UnreadCounts(
    /** Per feed id: unread articles its own words and topics keep. */
    val byFeed: Map<String, Int>,
    /** Per group: those the group's settings keep as well. */
    val byFolder: Map<String, Int>,
    /** Those All items' settings keep as well. */
    val total: Int,
)

private fun settingsKeep(settings: ViewSettings?, entry: Entry): Boolean = passes(settings ?: DEFAULT_SETTINGS, entry)

/** The inputs and result of the last count, so counts are worked out again only when one changes. */
private data class CountedFrom(
    val entries: List<Entry>,
    val feeds: List<Feed>,
    val settings: Map<String, ViewSettings>,
    val counts: UnreadCounts,
)

@Volatile
private var lastCounts: CountedFrom? = null

/**
 * Unread articles a feed's own words and topics keep, per feed; per group and
 * in All items, those that view's settings keep as well. Worked out again only
 * when the entries, feeds or settings change.
 */
fun unreadCounts(entries: List<Entry>, feeds: List<Feed>, settings: Map<String, ViewSettings>): UnreadCounts {
    val cached = lastCounts
    return if (cached != null && cached.entries === entries && cached.feeds === feeds && cached.settings === settings) {
        cached.counts
    } else {
        val byFeed = mutableMapOf<String, Int>()
        val byFolder = mutableMapOf<String, Int>()
        var total = 0
        val folderOf = feeds.associate { feed -> feed.id to feed.folder }
        for (entry in entries) {
            if (!entry.read && settingsKeep(settings["feed:${entry.feedId}"], entry)) {
                byFeed[entry.feedId] = (byFeed[entry.feedId] ?: 0) + 1
                val folder = folderOf[entry.feedId] ?: ""
                if (folder.isNotEmpty() && settingsKeep(settings["folder:$folder"], entry)) {
                    byFolder[folder] = (byFolder[folder] ?: 0) + 1
                }
                if (settingsKeep(settings["all"], entry)) {
                    total += 1
                }
            }
        }
        UnreadCounts(byFeed, byFolder, total).also { counts -> lastCounts = CountedFrom(entries, feeds, settings, counts) }
    }
}

/** Whether the current view's unread count includes an entry, read or not. */
fun countedInView(state: ReaderState, entry: Entry): Boolean {
    val own = settingsKeep(state.settings["feed:${entry.feedId}"], entry)
    val selection = state.selection
    return if (selection == Selection.Starred) {
        true
    } else if (selection is Selection.FeedView) {
        own
    } else {
        own && settingsKeep(state.settings[viewKey(selection)], entry)
    }
}

/** Group names in use, sorted. */
fun foldersOf(feeds: List<Feed>): List<String> =
    feeds.map { feed -> feed.folder }.filter { folder -> folder.isNotEmpty() }.distinct().sorted()

/**
 * The sidebar's groups, then its loose feeds, each most unread first, then
 * working before failing, then by name; with a [rank], rows keep their frozen
 * places and new ones go after.
 */
fun buildTree(feeds: List<Feed>, counts: UnreadCounts, rank: Map<String, Int>?): List<TreeRow> {
    fun rowOf(feed: Feed) = FeedRow(feed, counts.byFeed[feed.id] ?: 0, feed.error.isNotEmpty())
    val groups = foldersOf(feeds).map { name ->
        val rows = holdOrder(
            sortRows(feeds.filter { feed -> feed.folder == name }.map(::rowOf)),
            { row -> "feed:${row.feed.id}" },
            rank,
        )
        TreeRow.Group(
            name = name,
            unread = counts.byFolder[name] ?: 0,
            failing = rows.all { row -> row.failing },
            feeds = rows,
        )
    }.sortedWith(byUnread(Collator.getInstance()))
    val loose = holdOrder(
        sortRows(feeds.filter { feed -> feed.folder.isEmpty() }.map(::rowOf)),
        { row -> "feed:${row.feed.id}" },
        rank,
    ).map(TreeRow::Loose)
    return holdOrder(groups, { group -> "folder:${group.name}" }, rank) + loose
}

/** Sidebar positions of every group and feed as the counts stand now. */
fun frozenRankOf(feeds: List<Feed>, entries: List<Entry>, settings: Map<String, ViewSettings>): Map<String, Int> {
    val rank = mutableMapOf<String, Int>()
    for (item in buildTree(feeds, unreadCounts(entries, feeds, settings), null)) {
        when (item) {
            is TreeRow.Group -> {
                rank["folder:${item.name}"] = rank.size
                for (row in item.feeds) {
                    rank["feed:${row.feed.id}"] = rank.size
                }
            }
            is TreeRow.Loose -> rank["feed:${item.row.feed.id}"] = rank.size
        }
    }
    return rank
}

private fun inView(entry: Entry, selection: Selection, feedFolders: Map<String, String>): Boolean =
    when (selection) {
        Selection.All -> true
        Selection.Starred -> entry.starred
        is Selection.Folder -> feedFolders[entry.feedId] == selection.name
        is Selection.FeedView -> entry.feedId == selection.id
    }

private fun holding(state: ReaderState, key: String, settings: ViewSettings): ViewSettings {
    val held = state.heldTopics[key]
    return if (held == null) settings else settings.copy(offTopics = held)
}

/** Whether the view's words and topics, and the article's own feed's, keep an article; held topics count instead of current ones. */
private fun keeps(state: ReaderState, viewSettings: ViewSettings, entry: Entry): Boolean =
    if (state.selection == Selection.Starred) {
        true
    } else {
        val ownKey = "feed:${entry.feedId}"
        val own = holding(state, ownKey, state.settings[ownKey] ?: DEFAULT_SETTINGS)
        val view = if (state.selection is Selection.FeedView) {
            DEFAULT_SETTINGS
        } else {
            holding(state, viewKey(state.selection), viewSettings)
        }
        passes(own, entry) && passes(view, entry)
    }

/** Entries of the current view before any rules. */
fun baseEntriesOf(state: ReaderState): List<Entry> {
    val feedFolders = state.feeds.associate { feed -> feed.id to feed.folder }
    return state.entries.filter { entry -> inView(entry, state.selection, feedFolders) }
}

/**
 * Newest first: by date, then the later sighting, then higher in its feed,
 * then by guid and address (the same on every device, unlike the place in
 * the feed), then by id, so equal dates always come out in the same order.
 */
val NEWEST_FIRST: Comparator<Entry> =
    compareByDescending<Entry> { entry -> entry.published }
        .thenByDescending { entry -> entry.addedAt }
        .thenBy { entry -> entry.position }
        .thenBy { entry -> entry.guid }
        .thenBy { entry -> entry.url }
        .thenBy { entry -> entry.id }

@Volatile
private var lastTopicOrder: TopicOrder? = null

/** The topics of [entries] in order, reusing the last order when the counts have not changed, so screens can skip. */
private fun topicOrderOf(entries: List<Entry>): TopicOrder {
    val counts = topicCounts(entries)
    val cached = lastTopicOrder
    return if (cached != null && cached.counts == counts) {
        cached
    } else {
        TopicOrder(counts).also { order -> lastTopicOrder = order }
    }
}

/** Work out what the screens show from the reader's state. */
fun deriveView(state: ReaderState): ReaderView {
    val selection = state.selection
    val viewSettings = state.settings[viewKey(selection)] ?: DEFAULT_SETTINGS
    val baseEntries = baseEntriesOf(state)
    val ordered = if (viewSettings.sort == Sort.Newest) {
        baseEntries.sortedWith(NEWEST_FIRST)
    } else {
        baseEntries.sortedWith(NEWEST_FIRST.reversed())
    }
    val kept = baseEntries.filter { entry -> keeps(state, viewSettings, entry) }.map { entry -> entry.id }.toSet()
    val unreadOnly = state.unreadOnly && selection != Selection.Starred
    val held = state.heldEntries
    val visible = ordered.filter { entry ->
        entry.id == state.currentId ||
            ((held == null || entry.id in held) && entry.id in kept && !(unreadOnly && entry.read && entry.id !in state.pinned))
    }
    val counts = unreadCounts(state.entries, state.feeds, state.settings)
    val unread = counts.byFeed
    val tree = buildTree(state.feeds, counts, state.frozenRank)
    val viewTitle = when (selection) {
        Selection.All -> ViewTitle.AllItems
        Selection.Starred -> ViewTitle.Starred
        is Selection.Folder -> ViewTitle.Named(selection.name)
        is Selection.FeedView ->
            state.feeds.firstOrNull { feed -> feed.id == selection.id }?.let { feed -> ViewTitle.Named(feed.title) }
                ?: ViewTitle.AllItems
    }
    val viewFeedIds = when (selection) {
        is Selection.Folder -> state.feeds.filter { feed -> feed.folder == selection.name }.map { feed -> feed.id }
        is Selection.FeedView -> listOf(selection.id)
        else -> emptyList()
    }
    return ReaderView(
        viewSettings = viewSettings,
        baseEntries = baseEntries,
        visible = visible,
        topicOrder = topicOrderOf(baseEntries),
        viewTitle = viewTitle,
        viewFeedIds = viewFeedIds,
        current = state.entries.firstOrNull { entry -> entry.id == state.currentId },
        unreadByFeed = unread,
        tree = tree,
        unreadTotal = counts.total,
        starredCount = state.entries.count { entry -> entry.starred },
        folders = foldersOf(state.feeds),
    )
}

/** One row of the sidebar's feed list. */
data class SidebarRow(
    /** The view the row opens. */
    val selection: Selection,
    /** Name shown. */
    val label: String,
    /** Unread count shown; zero shows none. */
    val count: Int,
    /** The feed, for a feed row. */
    val feed: Feed?,
    /** Whether the row sits under a group. */
    val nested: Boolean,
)

/**
 * The group and feed rows the sidebar lists, in order. With unread only on,
 * a feed is listed only when it has something unread or is the one open, and
 * a group only when it lists a feed or is the one open.
 */
fun sidebarRows(state: ReaderState, view: ReaderView): List<SidebarRow> = listedRows(state, view.tree, view.unreadByFeed)

/**
 * Whether the sidebar lists a feed, given each feed's [unread] count: it is
 * the one open, or it was there when the sidebar was last settled and has
 * something unread or unread only is off.
 */
fun feedListed(state: ReaderState, unread: Map<String, Int>, feed: Feed): Boolean {
    val selection = state.selection
    val held = state.heldFeeds
    return (selection is Selection.FeedView && selection.id == feed.id) ||
        ((held == null || feed.id in held) && (!state.unreadOnly || (unread[feed.id] ?: 0) > 0))
}

private fun listedRows(state: ReaderState, tree: List<TreeRow>, unread: Map<String, Int>): List<SidebarRow> {
    val selection = state.selection
    fun listed(feed: Feed): Boolean = feedListed(state, unread, feed)
    val rows = mutableListOf<SidebarRow>()
    for (item in tree) {
        when (item) {
            is TreeRow.Group -> {
                val feeds = item.feeds.filter { row -> listed(row.feed) }
                if (feeds.isNotEmpty() || selection == Selection.Folder(item.name)) {
                    rows.add(SidebarRow(Selection.Folder(item.name), item.name, item.unread, null, nested = false))
                    feeds.forEach { row ->
                        rows.add(SidebarRow(Selection.FeedView(row.feed.id), row.feed.title, row.unread, row.feed, nested = true))
                    }
                }
            }
            is TreeRow.Loose ->
                if (listed(item.row.feed)) {
                    val row = item.row
                    rows.add(SidebarRow(Selection.FeedView(row.feed.id), row.feed.title, row.unread, row.feed, nested = false))
                }
        }
    }
    return rows
}
