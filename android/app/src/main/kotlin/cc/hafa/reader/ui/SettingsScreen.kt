package cc.hafa.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.reader.R
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.Sort
import cc.hafa.reader.core.hasWord
import cc.hafa.reader.core.topicOff

/** Which list of words the field adds to. */
private enum class WordMode { Hide, Only }

/** The current view's settings: name, sort, words, topics, and the feed's group or the group's removal. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snapshot: Snapshot, viewModel: ReaderViewModel) {
    val (state, view) = snapshot
    val colors = ReaderTheme.colors
    val model = viewModel.model
    val settings = view.viewSettings
    val selection = state.selection
    val feed = (selection as? Selection.FeedView)?.let { chosen -> state.feeds.firstOrNull { item -> item.id == chosen.id } }
    val folder = (selection as? Selection.Folder)?.name ?: ""
    val title = viewTitleText(view.viewTitle)
    var mode by remember { mutableStateOf(WordMode.Hide) }
    var draft by remember { mutableStateOf("") }
    var confirming by rememberConfirming()
    val words = if (mode == WordMode.Hide) settings.hideWords else settings.onlyWords
    val wordCounts = remember(view.baseEntries, words) {
        words.associateWith { word -> view.baseEntries.count { entry -> hasWord(listOf(word), entry) } }
    }

    fun changeWords(next: List<String>) {
        model.changeSettings { current ->
            if (mode == WordMode.Hide) current.copy(hideWords = next) else current.copy(onlyWords = next)
        }
    }

    fun addWord() {
        val word = draft.trim()
        if (word.isNotEmpty() && words.none { known -> known.equals(word, ignoreCase = true) }) {
            changeWords(words + word)
        }
        draft = ""
    }

    ReaderScaffold(
        bottomBar = { scrollBehavior -> BackBottomBar(stringResource(R.string.settings), scrollBehavior, viewModel::back) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp)
                if (feed != null && feed.error.isNotEmpty()) {
                    Text(problemText(feed.error), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            if (feed != null || folder.isNotEmpty()) {
                CommitField(
                    label = stringResource(R.string.name_label),
                    value = title,
                    onCommit = { name ->
                        if (feed != null) {
                            model.renameFeed(feed.id, name)
                        } else {
                            model.renameFolder(folder, name)
                        }
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sort_label), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Segmented(
                    listOf(Sort.Newest to stringResource(R.string.newest), Sort.Oldest to stringResource(R.string.oldest)),
                    settings.sort,
                    { sort -> model.changeSettings { current -> current.copy(sort = sort) } },
                    Modifier.fillMaxWidth(),
                )
            }

            // Starred lists what was starred by hand, so it offers only the sort.
            if (selection != Selection.Starred) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rules_label), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Segmented(
                    listOf(WordMode.Hide to stringResource(R.string.action_hide), WordMode.Only to stringResource(R.string.action_only)),
                    mode,
                    { next -> mode = next },
                    Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { text -> draft = text },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.add_phrase), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingText = { Text(stringResource(R.string.filter_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addWord() }),
                    colors = readerFieldColors(),
                )
                if (words.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (word in words) {
                            val removeLabel = stringResource(R.string.remove_rule)
                            InputChip(
                                selected = false,
                                onClick = { changeWords(words.filter { known -> known != word }) },
                                label = { Text(word) },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text((wordCounts[word] ?: 0).toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.muted)
                                        Icon(Icons.Outlined.Close, contentDescription = removeLabel, modifier = Modifier.size(InputChipDefaults.IconSize))
                                    }
                                },
                                border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = colors.line),
                            )
                        }
                    }
                }
            }

            if (selection != Selection.Starred && view.topics.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.topics), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in view.topics) {
                            TopicChip(
                                item.topic,
                                off = topicOff(settings, item.topic),
                                onToggle = { model.toggleTopic(item.topic, fromArticle = false) },
                                count = item.count,
                            )
                        }
                    }
                    Text(stringResource(R.string.topics_hint), fontSize = 12.sp, lineHeight = 16.sp, color = colors.muted)
                }
            }

            if (feed != null) {
                DividedSection {
                    GroupField(feed.folder, view.folders) { name -> model.setFolder(feed.id, name) }
                    ConfirmButton(
                        label = stringResource(R.string.unsubscribe),
                        confirmLabel = stringResource(R.string.confirm_unsubscribe),
                        confirming = confirming,
                        onClick = {
                            if (confirming) {
                                model.removeFeed(feed.id)
                                viewModel.back()
                            } else {
                                confirming = true
                            }
                        },
                    )
                }
            } else if (folder.isNotEmpty()) {
                DividedSection {
                    Text(stringResource(R.string.delete_group_hint), fontSize = 12.sp, lineHeight = 16.sp, color = colors.muted)
                    ConfirmButton(
                        label = stringResource(R.string.delete_group),
                        confirmLabel = stringResource(R.string.confirm_delete_group),
                        confirming = confirming,
                        onClick = {
                            if (confirming) {
                                model.deleteFolder(folder)
                                viewModel.back()
                            } else {
                                confirming = true
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The colors of the reader's outlined text fields. */
@Composable
fun readerFieldColors(): TextFieldColors {
    val colors = ReaderTheme.colors
    return OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = colors.line,
        focusedContainerColor = colors.panel,
        unfocusedContainerColor = colors.panel,
        unfocusedLabelColor = colors.muted,
        unfocusedSupportingTextColor = colors.muted,
        focusedSupportingTextColor = colors.muted,
    )
}

/** A text field whose value is used when Done is pressed or the field is left; a blank one goes back to [value]. */
@Composable
private fun CommitField(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commit() {
        if (text.isBlank()) {
            text = value
        } else if (text != value) {
            onCommit(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next -> text = next },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (focused && !focus.isFocused) {
                    commit()
                }
                focused = focus.isFocused
            },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = readerFieldColors(),
    )
}

/** The feed's group: type a name or pick one in use; empty takes the feed out of its group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(current: String, folders: List<String>, onCommit: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val suggestions = folders.filter { name -> name != text && name.contains(text.trim(), ignoreCase = true) }
    ExposedDropdownMenuBox(
        expanded = focused && suggestions.isNotEmpty(),
        onExpandedChange = {},
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { next -> text = next },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (focused && !focus.isFocused && text.trim() != current) {
                        onCommit(text)
                    }
                    focused = focus.isFocused
                },
            label = { Text(stringResource(R.string.group_label)) },
            placeholder = { Text(stringResource(R.string.no_group)) },
            supportingText = { Text(stringResource(R.string.group_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            colors = readerFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = focused && suggestions.isNotEmpty(),
            onDismissRequest = {},
        ) {
            for (name in suggestions) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        text = name
                        focusManager.clearFocus()
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** The closing part of the screen, set off by a line above it. */
@Composable
private fun DividedSection(content: @Composable ColumnScope.() -> Unit) {
    val line = ReaderTheme.colors.line
    Column(
        Modifier
            .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
