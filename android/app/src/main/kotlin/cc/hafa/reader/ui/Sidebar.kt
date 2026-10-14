package cc.hafa.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.reader.R
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.sidebarRows
import cc.hafa.reader.core.viewKey

private val PINNED_FOOTER_HEIGHT = 480.dp

/** The feed list in the left drawer: the brand, All items, Starred, groups and feeds, sync, and Add feed. */
@Composable
fun Sidebar(snapshot: Snapshot, viewModel: ReaderViewModel, onPicked: () -> Unit) {
    val (state, view) = snapshot
    val colors = ReaderTheme.colors
    val selectedKey = viewKey(state.selection)
    val sync by viewModel.syncUi.collectAsStateWithLifecycle()
    val footer: @Composable () -> Unit = {
        HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = colors.line)
        SidebarItem(
            label = syncStatusText(sync.report.status),
            count = 0,
            selected = false,
            icon = { tint ->
                if (sync.report.email.isNotEmpty()) {
                    AccountAvatar(sync.report.email, sync.report.photoUrl, sync.report.status)
                } else {
                    SyncStatusIcon(sync.report.status, tint, 24.dp)
                }
            },
            onClick = {
                viewModel.syncClicked()
                onPicked()
            },
        )
        SidebarItem(
            label = stringResource(R.string.add_feed),
            count = 0,
            selected = false,
            icon = { tint -> Icon(Icons.Outlined.Add, null, tint = tint) },
            onClick = {
                viewModel.startAdding()
                onPicked()
            },
        )
    }
    // On a short screen, such as a phone on its side, sync and Add feed scroll with the list rather than squeezing it.
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        val footerPinned = maxHeight >= PINNED_FOOTER_HEIGHT
        Column(
            Modifier
                .fillMaxHeight()
                .padding(bottom = 12.dp),
        ) {
            val suggested = refreshSuggested(state)
            val suggestion = if (suggested) stringResource(R.string.refresh_suggested) else ""
            Row(
                Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .clickable {
                        viewModel.goHome()
                        onPicked()
                    }
                    .semantics {
                        if (suggested) {
                            stateDescription = suggestion
                        }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WithDueDot(suggested) {
                    Logo(Modifier.size(32.dp), loading = !state.loaded || state.refreshing || state.adding, labeled = false)
                }
                Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = colors.text, maxLines = 1)
            }
            LazyColumn(Modifier.weight(1f)) {
                item(key = "all") {
                    SidebarItem(
                        label = stringResource(R.string.all_items),
                        count = view.unreadTotal,
                        selected = selectedKey == viewKey(Selection.All),
                        icon = { tint -> Icon(Icons.Outlined.Inbox, null, tint = tint) },
                        onClick = {
                            viewModel.pick(Selection.All)
                            onPicked()
                        },
                    )
                }
                item(key = "starred") {
                    SidebarItem(
                        label = stringResource(R.string.starred),
                        count = view.starredCount,
                        selected = selectedKey == viewKey(Selection.Starred),
                        icon = { tint -> Icon(Icons.Outlined.StarOutline, null, tint = tint) },
                        onClick = {
                            viewModel.pick(Selection.Starred)
                            onPicked()
                        },
                    )
                }
                item(key = "gap") {
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = colors.line)
                }
                items(sidebarRows(state, view), key = { row -> viewKey(row.selection) }) { row ->
                    val feed = row.feed
                    SidebarItem(
                        label = row.label,
                        count = row.count,
                        selected = selectedKey == viewKey(row.selection),
                        nested = row.nested,
                        problem = feed?.error ?: "",
                        icon = { tint ->
                            if (feed == null) {
                                Icon(Icons.Outlined.Folder, null, tint = tint)
                            } else {
                                FeedIcon(feed, onFail = { viewModel.refreshIcon(feed.id) })
                            }
                        },
                        onClick = {
                            viewModel.pick(row.selection)
                            onPicked()
                        },
                    )
                }
                if (!footerPinned) {
                    item(key = "footer") { Column { footer() } }
                }
            }
            if (footerPinned) {
                footer()
            }
        }
    }
}

/** One row of the sidebar, a navigation drawer item with the unread count and any feed problem at its end. */
@Composable
private fun SidebarItem(
    label: String,
    count: Int,
    selected: Boolean,
    icon: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
    nested: Boolean = false,
    problem: String = "",
) {
    val colors = ReaderTheme.colors
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(start = if (nested) 16.dp else 0.dp),
        icon = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                icon(if (selected) colors.accent else colors.muted)
            }
        },
        badge = if (problem.isEmpty() && count == 0) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (problem.isNotEmpty()) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = problemText(problem),
                            tint = colors.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (count > 0) {
                        Text(count.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.accentSoft,
            unselectedContainerColor = Color.Transparent,
            selectedTextColor = colors.text,
            unselectedTextColor = colors.text,
            selectedBadgeColor = colors.text,
            unselectedBadgeColor = colors.muted,
        ),
    )
}
