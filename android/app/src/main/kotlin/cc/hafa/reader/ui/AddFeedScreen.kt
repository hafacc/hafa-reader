package cc.hafa.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.reader.R
import cc.hafa.reader.core.FeedHit
import cc.hafa.reader.core.FeedProblem
import cc.hafa.reader.core.MAX_BODY_BYTES
import cc.hafa.reader.core.buildOpml
import cc.hafa.reader.core.faviconFor
import cc.hafa.reader.core.hasScheme
import cc.hafa.reader.core.opmlFeedsOf
import cc.hafa.reader.core.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.NumberFormat

private val SPACE_PATTERN = Regex("\\s")
private const val OPML_TYPE = "text/x-opml"
private const val EXPORT_NAME = "h-reader.opml"

private fun looksLikeAddress(text: String): Boolean =
    hasScheme(text) || ("." in text && !SPACE_PATTERN.containsMatchIn(text))

/** A file's text as UTF-8, or null when it is larger than a feed may be. */
private fun readCapped(stream: InputStream): String? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    var count = stream.read(buffer)
    while (count >= 0 && total <= MAX_BODY_BYTES) {
        output.write(buffer, 0, count)
        total += count
        count = stream.read(buffer)
    }
    return if (total > MAX_BODY_BYTES) null else output.toString(Charsets.UTF_8.name())
}

/** What the address field says under itself: the hint, a reason the feed cannot be added, or that a search found nothing. */
private sealed interface FieldMessage {
    data object Hint : FieldMessage

    data class Problem(val problem: FeedProblem) : FieldMessage

    data object NoResults : FieldMessage
}

/** The add-feed screen, with Back and its title in the bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(snapshot: Snapshot, viewModel: ReaderViewModel) {
    ReaderScaffold(
        bottomBar = { scrollBehavior -> BackBottomBar(stringResource(R.string.add_feed), scrollBehavior, viewModel::back) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AddFeedForm(snapshot, viewModel, first = false)
        }
    }
}

/** The screen shown before any feed is followed, with the menu button that opens the drawer (and sign-in) in its bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstFeedScreen(snapshot: Snapshot, viewModel: ReaderViewModel, onMenu: () -> Unit) {
    ReaderScaffold(
        bottomBar = { scrollBehavior ->
            ReaderBottomBar(scrollBehavior) {
                IconAction(Icons.Filled.Menu, stringResource(R.string.feeds), onMenu, tint = ReaderTheme.colors.text)
                BarTitle(stringResource(R.string.app_name))
            }
        },
    ) { padding ->
        val sync by viewModel.syncUi.collectAsStateWithLifecycle()
        val report = sync.report
        // signed in with the account's feeds still on their way: they arrive here rather than a prompt to add one
        val arriving = report.email.isNotEmpty() && !report.feedsKnown &&
            report.status in setOf(SyncStatus.Connecting, SyncStatus.Syncing, SyncStatus.Synced)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (arriving) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Logo(Modifier.size(48.dp), loading = true)
                    Text(stringResource(R.string.sync_syncing), fontSize = 14.sp, lineHeight = 20.sp, color = ReaderTheme.colors.muted)
                }
            } else {
                AddFeedForm(snapshot, viewModel, first = true, offerSignIn = !sync.signedIn)
            }
        }
    }
}

/**
 * Subscribe by address (finding the feed a page offers), or import an OPML
 * file; with [offerSignIn], signing in to bring an account's feeds is offered
 * under the address field.
 */
@Composable
private fun AddFeedForm(snapshot: Snapshot, viewModel: ReaderViewModel, first: Boolean, offerSignIn: Boolean = false) {
    val colors = ReaderTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var fieldMessage by remember { mutableStateOf<FieldMessage>(FieldMessage.Hint) }
    var hits by remember { mutableStateOf<List<FeedHit>>(emptyList()) }
    val adding = snapshot.state.adding
    // a topic search, as opposed to adding an address or a file
    var searching by remember { mutableStateOf(false) }

    // When an address could not be added and was searched for instead, finding nothing gives the address's problem.
    fun search(text: String, problem: FeedProblem? = null) {
        searching = true
        viewModel.searchFeeds(text) { found ->
            searching = false
            hits = found
            fieldMessage = if (found.isNotEmpty()) {
                FieldMessage.Hint
            } else if (problem != null) {
                FieldMessage.Problem(problem)
            } else {
                FieldMessage.NoResults
            }
        }
    }

    fun subscribe(address: String, fromField: Boolean, searchIfItFails: Boolean) {
        fieldMessage = FieldMessage.Hint
        searching = false
        viewModel.addFeed(address) { problem ->
            if (searchIfItFails) {
                search(address, problem)
            } else if (fromField) {
                fieldMessage = FieldMessage.Problem(problem)
            } else {
                viewModel.notify(Notice.Problem(problem))
            }
        }
    }

    fun submit() {
        val text = query.trim()
        fieldMessage = FieldMessage.Hint
        if (looksLikeAddress(text)) {
            subscribe(text, fromField = true, searchIfItFails = !hasScheme(text))
        } else if (text.isNotEmpty()) {
            search(text)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val source = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(::readCapped)
                    }
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
                if (source == null) {
                    viewModel.notify(Notice.Text(R.string.import_failed))
                } else {
                    viewModel.importOpml(source)
                }
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OPML_TYPE)) { uri ->
        if (uri != null) {
            val text = buildOpml(opmlFeedsOf(snapshot.state.feeds))
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("no file to write")
                        stream.use { output -> output.write(text.toByteArray()) }
                    }
                } catch (_: IOException) {
                    viewModel.notify(Notice.Text(R.string.export_failed))
                } catch (_: SecurityException) {
                    viewModel.notify(Notice.Text(R.string.export_failed))
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (first) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.empty_title), fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text(stringResource(R.string.empty_body), fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val problem = (fieldMessage as? FieldMessage.Problem)?.problem
            val supporting = when (val current = fieldMessage) {
                FieldMessage.Hint -> stringResource(R.string.find_hint)
                FieldMessage.NoResults -> stringResource(R.string.no_results)
                is FieldMessage.Problem -> problemText(current.problem)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { text ->
                    query = text
                    fieldMessage = FieldMessage.Hint
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.find_feed)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (problem != null) {
                    { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) }
                } else {
                    null
                },
                isError = problem != null,
                supportingText = {
                    Text(supporting, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (!adding) submit() }),
                colors = readerFieldColors(),
            )
            Button(
                onClick = ::submit,
                enabled = !adding,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    stringResource(
                        if (!adding) {
                            R.string.add_feed
                        } else if (searching) {
                            R.string.searching
                        } else {
                            R.string.adding
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (offerSignIn) {
            SignInPrompt(viewModel::syncClicked)
        }
        if (hits.isNotEmpty()) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, colors.line, shape),
            ) {
                hits.forEachIndexed { index, hit ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.line)
                    }
                    HitRow(hit, enabled = !adding) { subscribe(hit.url, fromField = false, searchIfItFails = false) }
                }
            }
        }
        HorizontalDivider(color = colors.line)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.import_title), fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(stringResource(R.string.import_body), fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted)
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .dashedBorder(colors.line, 12.dp)
                    .clickable(enabled = !adding, role = Role.Button) {
                        picker.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "application/octet-stream", "*/*"))
                    }
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.FileUpload, null, tint = colors.muted, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.import_choose), fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted, textAlign = TextAlign.Center)
            }
        }
        if (snapshot.state.feeds.isNotEmpty()) {
            OutlineButton(
                stringResource(R.string.export_opml),
                onClick = { exporter.launch(EXPORT_NAME) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.FileDownload,
            )
        }
    }
}

/** For someone who already uses the reader elsewhere: signing in brings their feeds. */
@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.first_sign_in_line), modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = ReaderTheme.colors.muted)
        OutlineButton(
            stringResource(R.string.first_sign_in),
            onClick = onSignIn,
            modifier = Modifier.heightIn(min = 48.dp),
            icon = Icons.AutoMirrored.Outlined.Login,
        )
    }
}

/** One search result: picture, name, description and how many follow it. */
@Composable
private fun HitRow(hit: FeedHit, enabled: Boolean, onClick: () -> Unit) {
    val colors = ReaderTheme.colors
    ListItem(
        headlineContent = { Text(hit.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hit.description.isNotEmpty()) {
                    Text(hit.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.subscribers, NumberFormat.getInstance().format(hit.subscribers)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        },
        leadingContent = { SiteIcon(hit.icon.ifEmpty { faviconFor(hit.url) }, hit.title, size = 24.dp) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = colors.page, supportingColor = colors.muted),
    )
}

/** A 2dp dashed border, as the web's drop area has. */
private fun Modifier.dashedBorder(color: Color, corner: Dp): Modifier =
    drawBehind {
        val width = 2.dp.toPx()
        val radius = corner.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
