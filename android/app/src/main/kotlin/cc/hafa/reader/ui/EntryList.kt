package cc.hafa.reader.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.reader.R
import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.ViewTitle
import cc.hafa.reader.core.articleTopics
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Name of a view in the reader's words. */
@Composable
fun viewTitleText(title: ViewTitle): String =
    when (title) {
        ViewTitle.AllItems -> stringResource(R.string.all_items)
        ViewTitle.Starred -> stringResource(R.string.starred)
        is ViewTitle.Named -> title.name
    }

/** The article list: its bottom bar, the cards, and pulling down to check the view's feeds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryList(snapshot: Snapshot, viewModel: ReaderViewModel, listState: LazyListState, onMenu: () -> Unit) {
    val (state, view) = snapshot
    val colors = ReaderTheme.colors
    val model = viewModel.model
    val feedTitles = remember(state.feeds) { state.feeds.associate { feed -> feed.id to feed.title } }
    val shownFeed = (state.selection as? Selection.FeedView)?.let { chosen -> state.feeds.firstOrNull { feed -> feed.id == chosen.id } }
    ReaderScaffold(
        bottomBar = { scrollBehavior ->
            ReaderBottomBar(scrollBehavior) {
                val suggested = refreshSuggested(state)
                IconAction(Icons.Filled.Menu, stringResource(R.string.feeds), onMenu, tint = colors.text, dotted = suggested)
                BarTitle(viewTitleText(view.viewTitle), onClick = onMenu, clickLabel = stringResource(R.string.show_feeds))
                IconAction(
                    if (state.unreadOnly) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(R.string.unread_only),
                    { model.setUnreadOnly(!state.unreadOnly) },
                    tint = if (state.unreadOnly) colors.accent else colors.muted,
                    pressed = state.unreadOnly,
                )
                IconAction(Icons.Outlined.Tune, stringResource(R.string.settings), viewModel::toggleSettings)
                IconAction(Icons.Outlined.DoneAll, stringResource(R.string.mark_all_read), model::markAllRead)
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refreshView,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = scrollEndPadding(),
                ),
            ) {
                val problem = shownFeed?.error ?: ""
                if (problem.isNotEmpty()) {
                    item(key = "problem") { FeedProblemBanner(problemText(problem)) }
                }
                items(view.visible, key = { entry -> entry.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        source = feedTitles[entry.feedId] ?: "",
                        topics = remember(entry.categories, view.topicOrder) { articleTopics(entry.categories, view.topicOrder) },
                        active = entry.id == state.currentId,
                        onOpen = { viewModel.openEntry(entry.id) },
                        onToggleRead = { model.toggleReadFor(entry.id) },
                        onStar = { model.toggleStarFor(entry.id) },
                    )
                }
                if (view.visible.isNotEmpty()) {
                    item(key = "end") {
                        ListEnd(refreshing = state.refreshing, onRefresh = viewModel::refreshView)
                    }
                } else {
                    item(key = "empty") {
                        Column(
                            Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(stringResource(R.string.no_entries), fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

// One turn of the refresh icon while feeds are checked.
private const val FULL_TURN = 360f
private const val TURN_MS = 1000

/** The end of the list: a full-width row that checks the view's feeds as pulling down does; its icon turns while they are checked. */
@Composable
private fun ListEnd(refreshing: Boolean, onRefresh: () -> Unit) {
    val colors = ReaderTheme.colors
    val turning = refreshing && animationsOn()
    val angle = if (turning) {
        rememberInfiniteTransition(label = "refresh").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)),
            label = "refresh angle",
        ).value
    } else {
        0f
    }
    val content = if (refreshing) colors.muted else colors.text
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = !refreshing, role = Role.Button, onClick = onRefresh)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = content, modifier = Modifier.size(18.dp).rotate(angle))
            Text(stringResource(R.string.refresh), fontSize = 14.sp, lineHeight = 20.sp, color = content)
        }
        HorizontalDivider(color = colors.line)
    }
}

/** Why the feed on show cannot be checked, at the top of its list. */
@Composable
private fun FeedProblemBanner(text: String) {
    val colors = ReaderTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        HorizontalDivider(color = colors.line)
    }
}

/** When an article was posted: the time today, else the month and day. */
private fun age(published: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(published).atZone(zone)
    val locale = Locale.getDefault()
    val pattern = if (moment.toLocalDate() == LocalDate.now(zone)) {
        DateFormat.getBestDateTimePattern(locale, "HHmm")
    } else {
        DateFormat.getBestDateTimePattern(locale, "MMMd")
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(moment)
}

/** One article in the list; swiping it sideways marks it read or unread. */
@Composable
private fun EntryCard(
    entry: Entry,
    source: String,
    topics: List<String>,
    active: Boolean,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onStar: () -> Unit,
) {
    val colors = ReaderTheme.colors
    val readIcon = if (entry.read) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.Check
    val hasImage = entry.imageUrl.isNotEmpty()
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val latestToggleRead by rememberUpdatedState(onToggleRead)
    // SwipeToDismissBox calls this again whenever it is a new lambda while the card is still out; toggling recomposes
    // the card, so a fresh lambda on each pass would flip the mark back and forth until the card is back in place.
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember(swipe, scope) {
        { _ ->
            latestToggleRead()
            scope.launch { swipe.reset() }
        }
    }
    Column {
        SwipeToDismissBox(
            state = swipe,
            onDismiss = onDismiss,
            backgroundContent = {
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(colors.accentSoft)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(readIcon, null, tint = colors.accent)
                }
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (active) colors.accentSoft else colors.page)
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
            ) {
                Row(
                    Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (entry.dated) "$source · ${age(entry.published)}" else source,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp, lineHeight = 16.sp,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.title,
                            fontWeight = FontWeight.SemiBold,
                            color = if (entry.read && !active) colors.read else colors.text,
                            lineHeight = 21.sp,
                        )
                        if (entry.excerpt.isNotEmpty()) {
                            Text(
                                entry.excerpt,
                                fontSize = 14.sp, lineHeight = 20.sp,
                                color = colors.muted,
                                maxLines = if (hasImage) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (hasImage) {
                        AsyncImage(
                            model = entry.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topics.joinToString(" · "),
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp, lineHeight = 16.sp,
                        color = colors.read,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconAction(
                        if (entry.starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        stringResource(R.string.star),
                        onStar,
                        tint = if (entry.starred) colors.star else colors.muted,
                        iconSize = 20.dp,
                        pressed = entry.starred,
                    )
                    IconAction(
                        readIcon,
                        stringResource(if (entry.read) R.string.mark_unread else R.string.mark_read),
                        onToggleRead,
                        iconSize = 20.dp,
                    )
                }
            }
        }
        HorizontalDivider(color = colors.line)
    }
}
