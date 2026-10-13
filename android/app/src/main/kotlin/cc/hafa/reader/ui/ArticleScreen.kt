package cc.hafa.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.reader.R
import cc.hafa.reader.core.Entry
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.decodeHtmlEntities
import cc.hafa.reader.core.ViewSettings
import cc.hafa.reader.core.showsLeadImage
import cc.hafa.reader.core.topicOff
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

private val ARTICLE_WIDTH = 680.dp
private val ARTICLE_MARGIN = 20.dp
// room between articles while one slides over the next
private val PAGE_GAP = 16.dp
// How far past the first or last article a drag must go to close it, as on the web (90 CSS pixels).
private val CLOSE_DRAG = 90.dp

private fun isWebLink(link: String): Boolean = link.startsWith("https://")

/** Opens the share sheet for [entry]'s link; false when nothing on the device can share it. */
private fun share(context: Context, entry: Entry): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, entry.title)
        putExtra(Intent.EXTRA_TEXT, entry.url)
    }
    return try {
        context.startActivity(Intent.createChooser(send, null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** An article's date and time as the web shows it, such as "Oct 12, 2026, 08:06", with the locale's month names. */
fun articleDate(published: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm", locale).format(Instant.ofEpochMilli(published).atZone(zone))

/**
 * The open article: its details, actions and body. Swiping sideways slides to
 * the next or previous article in the list's order, which then counts as opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(snapshot: Snapshot, viewModel: ReaderViewModel) {
    val (state, view) = snapshot
    val entry = view.current
    val colors = ReaderTheme.colors
    // The screen can open before the list knows its article, so only an article that was shown and then went (removed by sync) leaves it.
    var shown by remember { mutableStateOf(false) }
    if (entry != null) {
        shown = true
    }
    LaunchedEffect(entry == null && shown) {
        if (entry == null && shown) {
            viewModel.back()
        }
    }
    val context = LocalContext.current
    LaunchedEffect(state.aheadPictures) {
        val loader = SingletonImageLoader.get(context)
        val requests = state.aheadPictures.map { address -> loader.enqueue(ImageRequest.Builder(context).data(address).build()) }
        try {
            awaitCancellation()
        } finally {
            requests.forEach { request -> request.dispose() }
        }
    }
    val scrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()
    ReaderScaffold(
        bottomBar = {
            ReaderBottomBar(scrollBehavior) {
                BackButton(viewModel::back)
                Spacer(Modifier.weight(1f))
                if (entry != null) {
                    ArticleBarActions(entry, viewModel)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    ) { padding ->
        if (entry != null) {
            val rows = view.visible
            val index = rows.indexOfFirst { row -> row.id == entry.id }
            val pageModifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
            if (index == -1) {
                // An article the list no longer holds, such as one a filter just hid, stays open on its own.
                ArticlePage(entry, snapshot, viewModel, padding, scrollBehavior, pageModifier)
            } else {
                ArticlePager(rows, index, snapshot, viewModel, padding, scrollBehavior, pageModifier)
            }
        }
    }
}

/**
 * The list's articles side by side, on the one at [index]; settling on another
 * opens it. Dragging on past the first or last article moves it with the
 * finger, and far enough closes it, as stepping past either end does. The
 * bottom bar comes back whenever the page changes, so an article too short to
 * scroll never leaves it hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticlePager(
    rows: List<Entry>,
    index: Int,
    snapshot: Snapshot,
    viewModel: ReaderViewModel,
    padding: PaddingValues,
    scrollBehavior: BottomAppBarScrollBehavior,
    modifier: Modifier,
) {
    val pagerState = rememberPagerState(initialPage = index) { rows.size }
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(pagerState) {
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val settled = latestRows.getOrNull(page)
            if (settled != null && settled.id != viewModel.model.state.value.currentId) {
                viewModel.openEntry(settled.id, heading = if (page < previous) -1 else 1)
            }
            previous = page
        }
    }
    val threshold = with(LocalDensity.current) { CLOSE_DRAG.toPx() }
    val edgeDrag = remember(viewModel, threshold) { EdgeDrag(threshold, viewModel::back) }
    LaunchedEffect(pagerState, scrollBehavior) {
        snapshotFlow { pagerState.currentPage }.drop(1).collectLatest { scrollBehavior.showBar() }
    }
    // The list can change under the open article (sync, a refresh); keep showing that article.
    LaunchedEffect(index) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != index) {
            pagerState.scrollToPage(index)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .onSizeChanged { size -> edgeDrag.width = size.width.toFloat() }
            .nestedScroll(edgeDrag)
            .graphicsLayer { translationX = edgeDrag.offset },
        pageSpacing = PAGE_GAP,
        key = { page -> rows[page].id },
    ) { page ->
        ArticlePage(rows[page], snapshot, viewModel, padding, scrollBehavior.takeIf { page == pagerState.currentPage }, Modifier.fillMaxSize())
    }
}

/**
 * One article's details, actions and body; the body shows once it is the open
 * article and has loaded. On the page in view, [scrollBehavior] is the bottom
 * bar's, kept shown while the article is too short to scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticlePage(
    entry: Entry,
    snapshot: Snapshot,
    viewModel: ReaderViewModel,
    padding: PaddingValues,
    scrollBehavior: BottomAppBarScrollBehavior?,
    modifier: Modifier,
) {
    val (state, view) = snapshot
    val listState = rememberLazyListState()
    // A neighbor's body is ready before it is opened, so swiping onto it shows it at once.
    val shownBody = if (state.bodyId == entry.id) state.body else state.aheadBodies[entry.id]
    if (scrollBehavior != null) {
        LaunchedEffect(listState, scrollBehavior) {
            snapshotFlow { !(listState.canScrollForward || listState.canScrollBackward) && scrollBehavior.state.heightOffset != 0f }
                .collect { stuck ->
                    if (stuck) {
                        scrollBehavior.state.heightOffset = 0f
                    }
                }
        }
    }
    val colors = ReaderTheme.colors
    // Null until the body is split, so the lead image does not appear and then jump away.
    val split by produceState<Pair<List<HtmlBlock>, Boolean>?>(null, shownBody, entry.imageUrl) {
        value = if (shownBody != null) {
            withContext(Dispatchers.Default) {
                htmlBlocks(shownBody) to showsLeadImage(entry.imageUrl, shownBody)
            }
        } else {
            null
        }
    }
    val blocks = split?.first ?: emptyList()
    val showImage = split?.second == true
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        // Wide screens keep lines to a readable length, centered, with the whole width still scrolling.
        val side = maxOf(ARTICLE_MARGIN, (maxWidth - ARTICLE_WIDTH) / 2)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + side,
                end = padding.calculateEndPadding(layoutDirection) + side,
                top = 24.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dateText = if (entry.dated) articleDate(entry.published) else ""
                    val feedTitle = state.feeds.firstOrNull { feed -> feed.id == entry.feedId }?.title ?: ""
                    val meta = listOf(feedTitle, entry.author, dateText).filter { part -> part.isNotEmpty() }.joinToString(" · ")
                    Text(
                        meta,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 16.sp,
                        color = colors.muted,
                    )
                    val title = remember(entry.title, entry.url, colors) {
                        if (entry.url.isEmpty()) {
                            AnnotatedString(entry.title)
                        } else {
                            // A link annotation, so screen readers announce the title as a link.
                            buildAnnotatedString {
                                withLink(LinkAnnotation.Url(entry.url, TextLinkStyles(SpanStyle(color = colors.text)))) {
                                    append(entry.title)
                                }
                            }
                        }
                    }
                    Text(
                        title,
                        fontFamily = Fraunces,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading),
                    )
                }
            }
            if (showImage) {
                item(key = "image") {
                    AsyncImage(
                        model = decodeHtmlEntities(entry.imageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 384.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            items(blocks) { block -> BodyBlock(block) }
            // The actions and topics wait for the body, so they do not show under the title and then jump down.
            if (split != null && entry.categories.isNotEmpty()) {
                item(key = "categories") {
                    // Starred has no topic filter, so there the topics are plain labels.
                    if (state.selection == Selection.Starred) {
                        Text(entry.categories.joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, color = colors.muted)
                    } else {
                        // The chips read the view's settings, which change at once; only the list waits for a refresh.
                        CategoryChips(entry.categories, view.viewSettings) { topic ->
                            viewModel.model.toggleTopic(topic, fromArticle = true)
                        }
                    }
                }
            }
        }
    }
}

/** The article's topics as filter chips; turning one off hides its articles from the view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(categories: List<String>, settings: ViewSettings, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (category in categories) {
            TopicChip(category, off = topicOff(settings, category), onToggle = { onToggle(category) })
        }
    }
}

/**
 * The drag past the first or last page, which the pager leaves unused: the
 * pages follow it, and on release one of at least [threshold] pixels slides
 * them off and calls [onClose], while a shorter one springs back.
 */
private class EdgeDrag(private val threshold: Float, private val onClose: () -> Unit) : NestedScrollConnection {
    /** How far the pages are moved past the edge, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The pager's width, which a closing slide covers. */
    var width = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Dragging back toward the pages first undoes the distance past the edge.
        return if (source == NestedScrollSource.UserInput && offset != 0f && sign(available.x) != sign(offset)) {
            val taken = if (abs(available.x) >= abs(offset)) -offset else available.x
            offset += taken
            Offset(taken, 0f)
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        return if (source == NestedScrollSource.UserInput && available.x != 0f) {
            offset += available.x
            Offset(available.x, 0f)
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        return if (offset == 0f) {
            Velocity.Zero
        } else if (abs(offset) >= threshold) {
            animate(offset, sign(offset) * maxOf(width, abs(offset))) { value, _ -> offset = value }
            onClose()
            available
        } else {
            animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
            available
        }
    }
}

/** Star, read, share and open the original, for the bottom bar. */
@Composable
private fun RowScope.ArticleBarActions(entry: Entry, viewModel: ReaderViewModel) {
    val colors = ReaderTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    IconAction(
        if (entry.starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
        stringResource(R.string.star),
        viewModel.model::toggleStar,
        tint = if (entry.starred) colors.star else colors.muted,
        pressed = entry.starred,
    )
    IconAction(
        if (entry.read) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.Check,
        stringResource(if (entry.read) R.string.mark_unread else R.string.mark_read),
        viewModel.model::toggleRead,
    )
    if (entry.url.isNotEmpty()) {
        IconAction(Icons.Outlined.Share, stringResource(R.string.share), {
            if (!share(context, entry)) {
                viewModel.notify(Notice.Text(R.string.share_failed))
            }
        })
        IconAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_original), { uriHandler.openUri(entry.url) })
    }
}

@Composable
private fun BodyBlock(block: HtmlBlock) {
    val colors = ReaderTheme.colors
    val uriHandler = LocalUriHandler.current
    when (block) {
        is HtmlBlock.Text -> {
            val links = TextLinkStyles(SpanStyle(color = colors.accent))
            val text = remember(block.html, links) { AnnotatedString.fromHtml(block.html, linkStyles = links) }
            val quoted = block.quoteDepth > 0 || block.kind == TextKind.Caption
            val style = bodyStyle(block)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                if (quoted) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(colors.line),
                    )
                }
                Text(
                    if (block.prefix.isEmpty()) text else AnnotatedString(block.prefix) + text,
                    modifier = Modifier.padding(start = if (quoted) 12.dp else 0.dp),
                    style = style,
                    color = if (block.kind == TextKind.Body && block.quoteDepth == 0 || block.kind == TextKind.Heading) {
                        colors.text
                    } else {
                        colors.muted
                    },
                )
            }
        }
        is HtmlBlock.Table -> BodyTable(block)
        is HtmlBlock.Code -> Text(
            block.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.side)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            softWrap = false,
        )
        is HtmlBlock.Image -> AsyncImage(
            model = block.source,
            contentDescription = block.alt.ifEmpty { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(if (isWebLink(block.link)) Modifier.clickable { uriHandler.openUri(block.link) } else Modifier),
        )
        HtmlBlock.Rule -> HorizontalDivider(color = colors.line)
    }
}

// Sizes and weights of Tailwind's prose styles, which the web article uses.
private val HEADING_STYLES = listOf(
    TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
    TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    TextStyle(fontSize = 20.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

private fun bodyStyle(block: HtmlBlock.Text): TextStyle {
    val base = TextStyle(fontFamily = PublicSans)
    return when (block.kind) {
        TextKind.Heading -> base.merge(HEADING_STYLES[(block.level - 1).coerceIn(0, HEADING_STYLES.size - 1)])
        TextKind.Caption -> base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic)
        TextKind.FigureCaption -> base.copy(fontSize = 14.sp, lineHeight = 20.sp)
        TextKind.Body -> base.copy(fontSize = 16.sp, lineHeight = 28.sp)
    }
}

/** A table drawn cell by cell, scrolling sideways when it is wider than the screen. */
@Composable
private fun BodyTable(table: HtmlBlock.Table) {
    val colors = ReaderTheme.colors
    val links = TextLinkStyles(SpanStyle(color = colors.accent))
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        for (row in table.rows) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (cell in row) {
                    val text = remember(cell.html, links) { AnnotatedString.fromHtml(cell.html, linkStyles = links) }
                    Text(
                        text,
                        modifier = Modifier
                            .widthIn(min = 64.dp, max = 280.dp)
                            .fillMaxHeight()
                            .border(0.5.dp, colors.line)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.text,
                    )
                }
            }
        }
    }
}
