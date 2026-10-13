package cc.hafa.reader.ui

import android.content.res.Resources
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.reader.R
import cc.hafa.reader.core.Feed
import cc.hafa.reader.core.FeedProblem
import cc.hafa.reader.core.faviconFor
import cc.hafa.reader.core.feedProblemFromCode
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private const val FIRST_SERVER_ERROR = 500
private const val LAST_SERVER_ERROR = 599
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val GONE = 410
private const val TOO_MANY_REQUESTS = 429

/** An icon button labeled for screen readers; a toggle that reads as on or off when [pressed] is given. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = ReaderTheme.colors.muted,
    iconSize: Dp = 24.dp,
    pressed: Boolean? = null,
) {
    if (pressed == null) {
        IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors(contentColor = tint)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    } else {
        IconToggleButton(
            checked = pressed,
            onCheckedChange = { onClick() },
            colors = IconButtonDefaults.iconToggleButtonColors(contentColor = tint, checkedContentColor = tint),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    }
}

/** The snackbar state every screen shows its messages in. */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/** The snackbar host a screen's scaffold places above its bottom bar and the keyboard. */
@Composable
fun ReaderSnackbarHost() {
    SnackbarHost(LocalSnackbarHostState.current)
}

/**
 * The bottom app bar every screen has, where a thumb reaches its controls; it slides away while the content scrolls down and back on the way up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBottomBar(scrollBehavior: BottomAppBarScrollBehavior, content: @Composable RowScope.() -> Unit) {
    val colors = ReaderTheme.colors
    BottomAppBar(
        containerColor = colors.side,
        contentColor = colors.text,
        contentPadding = PaddingValues(horizontal = 4.dp),
        // Above the keyboard too, so a form's Back stays in reach while typing.
        windowInsets = BottomAppBarDefaults.windowInsets.union(WindowInsets.ime),
        scrollBehavior = scrollBehavior,
        content = content,
    )
}

/**
 * A screen with the reader's bottom bar, from [bottomBar] given the bar's
 * scroll behavior: the page color, content kept clear of the system bars, the
 * page color behind the status bar, and the snackbar host.
 *
 * The bar slides away while the content scrolls down and back on the way up.
 * Each screen has its own [scrollBehavior], so a screen opens with its bar
 * shown; pass one in to bring the bar back from the screen (see [showBar]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScaffold(
    bottomBar: @Composable (BottomAppBarScrollBehavior) -> Unit,
    scrollBehavior: BottomAppBarScrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior(),
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = ReaderTheme.colors.page,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { StatusBarBackdrop() },
        bottomBar = { bottomBar(scrollBehavior) },
        snackbarHost = { ReaderSnackbarHost() },
        content = content,
    )
}

/** Slide a hidden bottom bar back into view. */
@OptIn(ExperimentalMaterial3Api::class)
suspend fun BottomAppBarScrollBehavior.showBar() {
    animate(state.heightOffset, 0f) { value, _ -> state.heightOffset = value }
}

/** The bottom bar of a form screen: Back and the screen's name, above the keyboard while typing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBottomBar(title: String, scrollBehavior: BottomAppBarScrollBehavior, onBack: () -> Unit) {
    ReaderBottomBar(scrollBehavior) {
        BackButton(onBack)
        BarTitle(title)
    }
}

/** A screen's name in its bottom bar, taking the room the buttons leave. */
@Composable
fun RowScope.BarTitle(title: String) {
    Text(
        title,
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp)
            .semantics { heading() },
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The page color behind the status bar, for screens whose bar is at the bottom, so content scrolls under it unseen. */
@Composable
fun StatusBarBackdrop() {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(ReaderTheme.colors.page),
    )
}

/** The Back arrow of a bottom bar. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack, tint = ReaderTheme.colors.text)
}

/** A feed's picture: its own icon, else the site's favicon, else its first letter on a tile. */
@Composable
fun FeedIcon(feed: Feed, onFail: () -> Unit) {
    SiteIcon(feed.icon.ifEmpty { faviconFor(feed.siteUrl.ifEmpty { feed.url }) }, feed.title, onFail = onFail)
}

/** A small picture from [source], or the first letter of [title] on a tile when there is none or it fails. */
@Composable
fun SiteIcon(source: String, title: String, modifier: Modifier = Modifier, size: Dp = 20.dp, onFail: () -> Unit = {}) {
    var failed by remember(source) { mutableStateOf(false) }
    val tile = Modifier
        .size(size)
        .clip(RoundedCornerShape(6.dp))
        .background(ReaderTheme.colors.line)
    if (source.isEmpty() || failed) {
        Box(modifier.then(tile), contentAlignment = Alignment.Center) {
            Text(
                title.take(1).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = ReaderTheme.colors.text,
            )
        }
    } else {
        AsyncImage(
            model = source,
            contentDescription = null,
            modifier = modifier.then(tile),
            onError = {
                failed = true
                onFail()
            },
        )
    }
}

/** A bordered button in the text color, with an optional leading [icon], drawn with [iconModifier]. */
@Composable
fun OutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
) {
    val colors = ReaderTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
        contentPadding = if (icon == null) ButtonDefaults.ContentPadding else ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = iconModifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }
}

// How long a confirm button waits for its second tap, as on the web.
private const val CONFIRM_MS = 4000L

/** Whether a [ConfirmButton] is waiting for its second tap; it stops waiting on its own after four seconds. */
@Composable
fun rememberConfirming(): MutableState<Boolean> {
    val confirming = remember { mutableStateOf(false) }
    LaunchedEffect(confirming.value) {
        if (confirming.value) {
            delay(CONFIRM_MS)
            confirming.value = false
        }
    }
    return confirming
}

/** A button that asks to be tapped again before it acts, turning to the error color while it waits. */
@Composable
fun ConfirmButton(label: String, confirmLabel: String, confirming: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = ReaderTheme.colors
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (confirming) error else colors.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (confirming) error else colors.text),
    ) {
        Text(if (confirming) confirmLabel else label)
    }
}

/** A topic as a filter chip, struck through while [off]; [count] shows after it when given. */
@Composable
fun TopicChip(topic: String, off: Boolean, onToggle: () -> Unit, count: Int? = null) {
    val colors = ReaderTheme.colors
    FilterChip(
        selected = !off,
        onClick = onToggle,
        label = {
            Text(
                topic,
                color = if (off) colors.read else colors.text,
                textDecoration = if (off) TextDecoration.LineThrough else TextDecoration.None,
            )
        },
        trailingIcon = count?.let { shown ->
            { Text(shown.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.muted) }
        },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.page),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = !off,
            borderColor = colors.line,
            selectedBorderColor = colors.line,
        ),
    )
}

/** A row of choices of which exactly one is on. */
@Composable
fun <Value> Segmented(options: List<Pair<Value, String>>, value: Value, onChange: (Value) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReaderTheme.colors
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = option == value,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accentSoft,
                    activeContentColor = colors.text,
                    inactiveContainerColor = colors.page,
                    inactiveContentColor = colors.muted,
                    activeBorderColor = colors.line,
                    inactiveBorderColor = colors.line,
                ),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Words for why a feed cannot be read, in the web app's wording. */
fun problemText(resources: Resources, problem: FeedProblem): String =
    when (problem) {
        FeedProblem.NoAnswer -> resources.getString(R.string.error_no_answer)
        FeedProblem.NotAFeed -> resources.getString(R.string.error_not_a_feed)
        FeedProblem.NotWebAddress -> resources.getString(R.string.error_not_https)
        FeedProblem.NotHttps -> resources.getString(R.string.error_not_https_feed)
        FeedProblem.Unreadable -> resources.getString(R.string.error_unreadable)
        is FeedProblem.Status -> when (problem.status) {
            UNAUTHORIZED, FORBIDDEN -> resources.getString(R.string.error_refused)
            NOT_FOUND, GONE -> resources.getString(R.string.error_missing)
            TOO_MANY_REQUESTS -> resources.getString(R.string.error_too_many)
            in FIRST_SERVER_ERROR..LAST_SERVER_ERROR -> resources.getString(R.string.error_site_trouble)
            else -> resources.getString(R.string.error_status, problem.status)
        }
    }

/** Words for why a feed cannot be read. */
@Composable
fun problemText(problem: FeedProblem): String = problemText(LocalResources.current, problem)

/** Words for a stored problem code; empty for none. */
@Composable
fun problemText(code: String): String = feedProblemFromCode(code)?.let { problem -> problemText(problem) } ?: ""
