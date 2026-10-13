package cc.hafa.reader.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.hafa.reader.R
import cc.hafa.reader.core.FeedHit
import cc.hafa.reader.core.FeedProblem
import cc.hafa.reader.core.FeedProblemException
import cc.hafa.reader.core.ReaderModel
import cc.hafa.reader.core.ReaderState
import cc.hafa.reader.core.ReaderView
import cc.hafa.reader.core.Selection
import cc.hafa.reader.core.sync.RetentionDays
import cc.hafa.reader.core.sync.SyncEngine
import cc.hafa.reader.core.sync.SyncReport
import cc.hafa.reader.core.sync.SyncStatus
import cc.hafa.reader.core.sync.SyncUi
import cc.hafa.reader.core.viewKey
import cc.hafa.reader.readerApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which screen a phone shows: the article list, or what the detail column holds. */
enum class Screen {
    /** The article list. */
    List,

    /** The detail column, full screen. */
    Detail,
}

/** What the detail column shows. */
enum class Detail {
    /** The open article. */
    Article,

    /** The current view's settings. */
    Settings,

    /** Adding a feed. */
    Add,

    /** Sign-in and sync. */
    Account,
}

/** A short message for the snackbar. */
sealed interface Notice {
    /** A fixed message. */
    data class Text(
        /** String resource of the message. */
        @param:StringRes val text: Int,
    ) : Notice

    /** Why a feed cannot be added. */
    data class Problem(
        /** What went wrong. */
        val problem: FeedProblem,
    ) : Notice
}

/** The reader's state together with what the screens show of it. */
data class Snapshot(
    /** Everything the reader holds. */
    val state: ReaderState,
    /** What the screens show. */
    val view: ReaderView,
)

// A failure in reader work started from a screen is logged rather than ending the app.
private val logFailures = CoroutineExceptionHandler { _, failure ->
    Log.w("Reader", "reader work failed", failure)
}

/** Holds the reader for the screens and moves between them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    /** The process's reader. */
    val model: ReaderModel = application.readerApplication.model

    private val syncEngine: StateFlow<SyncEngine?> = application.readerApplication.sync

    private val syncBroken: StateFlow<Boolean> = application.readerApplication.syncBroken

    // tapped Sign in while sync could not start
    private val brokenSignIn = MutableStateFlow(false)

    /** What the sync section shows; connecting until sync has started, and signed out if it could not. */
    val syncUi: StateFlow<SyncUi> = combine(syncEngine, syncBroken, brokenSignIn) { engine, broken, tapped -> Triple(engine, broken, tapped) }
        .flatMapLatest { (engine, broken, tapped) ->
            engine?.ui ?: flowOf(if (broken) SyncUi(SyncReport(SyncStatus.Off), signInFailed = tapped) else SyncUi(SyncReport(SyncStatus.Connecting)))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncUi(SyncReport(SyncStatus.Connecting)))

    /** The latest state and view; null until first worked out. */
    val snapshot: StateFlow<Snapshot?> = model.state
        .mapLatest { state -> Snapshot(state, model.viewOf(state)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)

    /** Messages for the snackbar, each shown once. */
    val notices: Flow<Notice> = noticeChannel.receiveAsFlow()

    /** The screen on show. */
    var screen by mutableStateOf(Screen.List)
        private set

    /** What the detail screen holds. */
    var detail by mutableStateOf(Detail.Article)
        private set

    init {
        viewModelScope.launch(logFailures) {
            // the drawer has no room for why sign-in failed; the account screen says it
            syncUi.map { sync -> sync.signInFailed }.distinctUntilChanged().filter { failed -> failed }.collect { showAccount() }
        }
        viewModelScope.launch(logFailures) {
            syncUi.map { sync -> sync.report.status }
                .distinctUntilChanged()
                .filter { status -> status == SyncStatus.Error }
                .collect { notify(Notice.Text(R.string.sync_error)) }
        }
        viewModelScope.launch(logFailures) {
            model.load()
            model.refresh()
        }
    }

    /** Show [notice] in the snackbar. */
    fun notify(notice: Notice) {
        noticeChannel.trySend(notice)
    }

    /** Go to the article list. */
    fun showList() {
        screen = Screen.List
        if (detail == Detail.Add || detail == Detail.Account) {
            detail = Detail.Article
        }
    }

    /** Open an article from the list, or from the pager moving down ([heading] 1) or up (-1) the list. */
    fun openEntry(id: String, heading: Int = 1) {
        model.open(id, heading)
        detail = Detail.Article
        screen = Screen.Detail
    }

    /** Open the view settings, or close them when they are open. */
    fun toggleSettings() {
        if (detail == Detail.Settings && screen == Screen.Detail) {
            back()
        } else {
            detail = Detail.Settings
            screen = Screen.Detail
        }
    }

    /** Open the add-feed screen. */
    fun startAdding() {
        closeArticle()
        detail = Detail.Add
        screen = Screen.Detail
    }

    /** Show the sign-in and sync section. */
    fun showAccount() {
        closeArticle()
        detail = Detail.Account
        screen = Screen.Detail
    }

    // An article left for another screen counts as closed, as Back from it does.
    private fun closeArticle() {
        if (model.state.value.currentId.isNotEmpty()) {
            model.close()
        }
    }

    /** The sidebar's sync row: sign in, or show the account when signed in. */
    fun syncClicked() {
        if (syncUi.value.signedIn) {
            showAccount()
        } else {
            signIn()
        }
    }

    /** Sign in with Google and start syncing; fails at once when sync could not start. */
    fun signIn() {
        val engine = syncEngine.value
        if (engine != null) {
            engine.signIn()
        } else if (syncBroken.value) {
            brokenSignIn.value = true
            showAccount()
        }
    }

    /** Choose how long the signed-in account keeps synced articles; null keeps them forever. */
    fun setRetention(retention: RetentionDays) {
        syncEngine.value?.setRetention(retention)
    }

    /** Sign out after sending what is queued. */
    fun signOut() {
        syncEngine.value?.signOut()
    }

    /** Replace this device's data with the waiting account's. */
    fun replaceLocal() {
        syncEngine.value?.replaceLocal()
    }

    /** Delete the signed-in account's data and sign-in. */
    fun deleteAccount() {
        syncEngine.value?.deleteAccount()
    }

    /** Leave the add-feed screen for the list; nothing when the person has already gone elsewhere, such as to an article. */
    fun finishAdding() {
        if (detail == Detail.Add) {
            closeArticle()
            detail = Detail.Article
            screen = Screen.List
        }
    }

    /** Go back from the detail screen to the list, closing the open article. */
    fun back() {
        if (screen == Screen.Detail) {
            if (detail == Detail.Article) {
                model.close()
            }
            screen = Screen.List
            detail = Detail.Article
        }
    }

    /** Pick a view in the sidebar; picking the one on show checks its feeds instead. */
    fun pick(selection: Selection) {
        if (viewKey(selection) == viewKey(model.state.value.selection)) {
            refreshView()
        } else {
            model.select(selection)
        }
        showList()
    }

    /** Go to All items and check every feed. */
    fun goHome() {
        model.select(Selection.All)
        viewModelScope.launch(logFailures) { model.refresh() }
        showList()
    }

    /** Check the feeds of the view on show. */
    fun refreshView() {
        viewModelScope.launch(logFailures) { model.refreshView() }
    }

    /**
     * Subscribe to what was typed, then show the list; [onFailure] gets the
     * reason when no feed is found. Runs past the add screen leaving.
     */
    fun addFeed(input: String, onFailure: (FeedProblem) -> Unit) {
        viewModelScope.launch(logFailures) {
            try {
                model.addFeed(input)
                finishAdding()
            } catch (failure: FeedProblemException) {
                onFailure(failure.problem)
            }
        }
    }

    /** Search for feeds about [query]; [onResult] gets the hits, empty when nothing is found. */
    fun searchFeeds(query: String, onResult: (List<FeedHit>) -> Unit) {
        viewModelScope.launch(logFailures) { onResult(model.searchFeeds(query)) }
    }

    /**
     * Import OPML text, then show the list when anything was new; when nothing
     * was, or the file cannot be read, the snackbar says why.
     */
    fun importOpml(source: String) {
        viewModelScope.launch(logFailures) {
            val result = try {
                model.importOpml(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("Reader", "import failed", failure)
                null
            }
            if (result == null) {
                notify(Notice.Text(R.string.import_failed))
            } else if (result.found == 0) {
                notify(Notice.Text(R.string.import_none))
            } else if (result.added == 0) {
                notify(Notice.Text(R.string.import_nothing_new))
            } else {
                finishAdding()
            }
        }
    }

    /** Ask for a feed's own picture once its usual one fails to load. */
    fun refreshIcon(id: String) {
        viewModelScope.launch(logFailures) { model.refreshIcon(id) }
    }
}
