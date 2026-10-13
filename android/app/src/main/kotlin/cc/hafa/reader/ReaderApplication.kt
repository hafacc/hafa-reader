package cc.hafa.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import cc.hafa.reader.core.HttpFeedFetcher
import cc.hafa.reader.core.ReaderModel
import cc.hafa.reader.core.sync.SyncEngine
import cc.hafa.reader.data.ReaderDatabase
import cc.hafa.reader.data.RoomReaderStore
import cc.hafa.reader.data.SharedReaderPreferences
import cc.hafa.reader.sync.AndroidSyncBackend
import cc.hafa.reader.sync.FirebaseAccountAuth
import cc.hafa.reader.sync.SharedSyncPreferences
import cc.hafa.reader.sync.SyncConfig
import cc.hafa.reader.work.RefreshWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

// A failure in background reader work is logged rather than ending the app.
private val logFailures = CoroutineExceptionHandler { _, failure ->
    Log.w("Reader", "background work failed", failure)
}

/** The app: one reader shared by the screens and the background worker, and sync. */
class ReaderApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)

    private var shownActivity = WeakReference<Activity>(null)

    private val mutableSync = MutableStateFlow<SyncEngine?>(null)

    private val mutableSyncBroken = MutableStateFlow(false)

    // Set once a screen shows, so sync listens to the account; a process started only to check feeds just uploads.
    private val onScreen = AtomicBoolean(false)

    /** Everything the reader holds, for the whole process. */
    val model: ReaderModel by lazy {
        ReaderModel(
            store = RoomReaderStore(ReaderDatabase.open(this).dao()),
            preferences = SharedReaderPreferences(this),
            fetcher = HttpFeedFetcher(OkHttpClient()),
            scope = scope,
            onBackgroundWork = { RefreshWorker.finishInBackground(this) },
            // Data Saver, as the web's saveData: nothing is fetched ahead while it is on.
            savingData = {
                getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus ==
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            },
        )
    }

    /** Sign-in and sync, once the reader has loaded; null until then. */
    val sync: StateFlow<SyncEngine?> = mutableSync.asStateFlow()

    /** Whether sync could not start, so signing in cannot work. */
    val syncBroken: StateFlow<Boolean> = mutableSyncBroken.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this)
        registerActivityLifecycleCallbacks(ShownActivityTracker())
        scope.launch {
            var started = false
            try {
                // Sync compares against what the device holds, so it starts once that is loaded;
                // the model keeps changes made until then.
                model.load()
                val firebase = FirebaseApp.initializeApp(this@ReaderApplication, SyncConfig.firebaseOptions())
                val engine = SyncEngine(
                    auth = FirebaseAccountAuth(FirebaseAuth.getInstance(firebase), this@ReaderApplication, SyncConfig.WEB_CLIENT_ID) {
                        shownActivity.get()
                    },
                    backend = AndroidSyncBackend(this@ReaderApplication, FirebaseFirestore.getInstance(firebase)),
                    host = model.syncHost,
                    preferences = SharedSyncPreferences(this@ReaderApplication),
                    scope = scope,
                    inBackground = true,
                )
                model.sync = engine
                mutableSync.value = engine
                if (onScreen.get()) {
                    engine.enterForeground()
                }
                started = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("Reader", "sync could not start", failure)
            } finally {
                // Whatever stopped it, empty Firebase settings included, signing in then fails at once.
                if (!started) {
                    // drops the changes kept for sync
                    model.sync = null
                    mutableSyncBroken.value = true
                }
            }
        }
    }

    /** Keeps the activity on show, which the Google account chooser opens over. */
    private inner class ShownActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            shownActivity = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            onScreen.set(true)
            mutableSync.value?.enterForeground()
        }

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (shownActivity.get() === activity) {
                shownActivity = WeakReference(null)
            }
        }
    }
}

/** The process's app object. */
val Context.readerApplication: ReaderApplication
    get() = applicationContext as ReaderApplication
