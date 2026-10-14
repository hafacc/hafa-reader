package cc.hafa.reader.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.reader.R
import cc.hafa.reader.core.GatePhase
import cc.hafa.reader.core.viewKey
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The phone layout: the article list, with the feed list in a drawer opened
 * from its bottom bar, and the article, view settings, add-feed or account screen over it.
 * System Back closes the drawer, then leaves the detail screen for the list,
 * then leaves the app.
 */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val colors = ReaderTheme.colors
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val current = snapshot
    val platformLinks = LocalUriHandler.current
    val safeLinks = remember(platformLinks, viewModel) {
        SafeUriHandler(platformLinks) { viewModel.notify(Notice.Text(R.string.open_failed)) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.notices.collect { notice ->
            val message = when (notice) {
                is Notice.Text -> resources.getString(notice.text)
                is Notice.Count -> resources.getQuantityString(notice.text, notice.count, NumberFormat.getInstance().format(notice.count))
                is Notice.Problem -> problemText(resources, notice.problem)
            }
            snackbarHostState.showSnackbar(message, withDismissAction = true)
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safeLinks, LocalSnackbarHostState provides snackbarHostState) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.page),
        ) {
            if (current != null && current.state.loaded) {
                val state = current.state
                val listKey = "${viewKey(state.selection)}|${state.unreadOnly}|${current.view.viewSettings.sort}"
                LaunchedEffect(listKey) { listState.scrollToItem(0) }
                // On the list Back is left to the system, which leaves the app; the open drawer's sheet closes it on Back.
                BackHandler(enabled = drawerState.isClosed && viewModel.screen == Screen.Detail) { viewModel.back() }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // Swiping right on the list marks an article read, so the drawer opens from its menu button and only swipes closed.
                    gesturesEnabled = drawerState.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerState = drawerState,
                            drawerContainerColor = colors.side,
                            drawerContentColor = colors.text,
                        ) {
                            Sidebar(current, viewModel) { scope.launch { drawerState.close() } }
                        }
                    },
                ) {
                    ScreenContent(current, viewModel, listState) { scope.launch { drawerState.open() } }
                }
            }
            if (gate.blocks) {
                LoadingCover(showLogo = gate.phase == GatePhase.Shown)
                // Back still leaves the app from the list, but does not move between screens behind the cover.
                BackHandler(enabled = drawerState.isOpen || viewModel.screen == Screen.Detail) {}
            }
        }
    }
}

/** The loading screen: an opaque cover that takes every touch, so nothing half loaded shows or can be tapped, with the turning logo once [showLogo]. */
@Composable
private fun LoadingCover(showLogo: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ReaderTheme.colors.page)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change -> change.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showLogo) {
            Logo(Modifier.size(48.dp), loading = true)
        }
    }
}

/** The list, or the detail screen on show. */
@Composable
private fun ScreenContent(snapshot: Snapshot, viewModel: ReaderViewModel, listState: LazyListState, onMenu: () -> Unit) {
    if (viewModel.screen == Screen.List) {
        if (snapshot.state.feeds.isEmpty()) {
            FirstFeedScreen(snapshot, viewModel, onMenu)
        } else {
            EntryList(snapshot, viewModel, listState, onMenu)
        }
    } else {
        when (viewModel.detail) {
            Detail.Article -> ArticleScreen(snapshot, viewModel)
            Detail.Settings -> SettingsScreen(snapshot, viewModel)
            Detail.Add -> AddFeedScreen(snapshot, viewModel)
            Detail.Account -> AccountScreen(viewModel)
        }
    }
}

/** Opens links, calling [onFail] when no installed app can open one (such as a mail link with no mail app). */
private class SafeUriHandler(private val platform: UriHandler, private val onFail: () -> Unit) : UriHandler {
    override fun openUri(uri: String) {
        try {
            platform.openUri(uri)
        } catch (_: IllegalArgumentException) {
            onFail()
        } catch (_: ActivityNotFoundException) {
            onFail()
        }
    }
}
