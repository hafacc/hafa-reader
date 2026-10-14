package cc.hafa.reader.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Who is signed in, as the sign-in provider reports it. */
data class AuthState(
    /** Whether the provider has said yet; until then nobody is known. */
    val known: Boolean = false,
    /** The account's id; null when signed out. */
    val uid: String? = null,
    /** The account's email; empty when signed out. */
    val email: String = "",
    /** Address of the account's photo; empty when it has none or is signed out. */
    val photoUrl: String = "",
)

/** How a sign-in attempt ended. */
enum class SignInResult {
    /** Signed in. */
    SignedIn,

    /** The person closed the account chooser. */
    Closed,
}

/** Google sign-in through Firebase Auth; the app implements it, tests fake it. */
interface AccountAuth {
    /** Who is signed in. */
    val state: StateFlow<AuthState>

    /** Ask the person to choose a Google account and sign in with it. */
    suspend fun signIn(): SignInResult

    /** Sign out here; other devices stay signed in. */
    suspend fun signOut()

    /**
     * Sign in again when the last sign-in is too old for Firebase to delete
     * the account; throws when it cannot, as when the person closes the chooser.
     */
    suspend fun ensureRecentSignIn()

    /** Delete the signed-in Firebase user, signing in again first when Firebase asks for a recent sign-in. */
    suspend fun deleteUser()
}

/** Each account's Firestore documents, and whether the network is up. */
interface SyncBackend {
    /** Whether the device is online. */
    val online: StateFlow<Boolean>

    /** The documents of one account. */
    fun storeFor(uid: String): AccountStore
}

/** What the sync section of the app shows. */
data class SyncUi(
    /** How sync is doing. */
    val report: SyncReport = SyncReport(),
    /** Whether the last sign-in attempt failed. */
    val signInFailed: Boolean = false,
    /** Whether the account is being deleted. */
    val deleting: Boolean = false,
    /** Whether the last attempt to delete the account failed. */
    val deleteFailed: Boolean = false,
) {
    /** Whether an account is signed in, or signing in. */
    val signedIn: Boolean get() = report.email.isNotEmpty() || report.status == SyncStatus.Connecting
}

private val logger: Logger = Logger.getLogger("cc.hafa.reader.sync")

// Longest wait at sign-out for written changes to reach the server, which takes them only while signed in.
private const val SIGN_OUT_WAIT_MS = 5_000L

/**
 * Optional Google sign-in and Firestore sync, following the signed-in
 * account; the same rules as the web's `sync.svelte.ts` and `startFirebase`.
 *
 * Started [inBackground], as in a process woken only to check feeds, a
 * session just sends this device's changes and uploads the entries it
 * found; [enterForeground] adds the listeners and the pass over the account.
 *
 * A different account than the one this device last synced with, while the
 * device holds data, waits for the person to choose: replace this device's
 * data with that account's, or cancel the sign-in. One account's data is
 * never sent to another. Changes made before anyone is known wait and go to
 * the account that signs in.
 */
class SyncEngine(
    private val auth: AccountAuth,
    private val backend: SyncBackend,
    private val host: SyncHost,
    private val preferences: SyncPreferences,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timing: SyncTiming = SyncTiming(),
    inBackground: Boolean = false,
) : SyncSink {
    @Volatile
    private var foreground = !inBackground

    private val mutableUi = MutableStateFlow(SyncUi(SyncReport(SyncStatus.Connecting)))

    /** What the sync section shows. */
    val ui: StateFlow<SyncUi> = mutableUi.asStateFlow()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var uid: String? = null

    @Volatile
    private var waiting = false

    private val replacing = AtomicBoolean(false)

    // Guards early, authHeard and heldForDelete, and keeps queued changes in the order they were made.
    private val changesLock = Any()

    // Changes made before the first answer about who is signed in.
    private val early = mutableListOf<LocalChange>()

    // Whether that first answer has been handled.
    private var authHeard = false

    // Changes made while the account is being deleted, sent if that fails.
    private var heldForDelete: MutableList<LocalChange>? = null

    init {
        scope.launch { auth.state.collect(::onAuth) }
        scope.launch {
            backend.online.collect { available ->
                if (available) {
                    session?.retryCreates()
                }
                report()
            }
        }
    }

    private fun onAuth(next: AuthState) {
        if (next.known && next.uid != uid) {
            session?.stop()
            session = null
            uid = next.uid
            val signedIn = next.uid
            waiting = signedIn != null && needsChoice(preferences.get(ACCOUNT_KEY) ?: "", signedIn, hasLocalData(host.local()))
            if (signedIn != null && !waiting) {
                synchronized(changesLock) { begin(signedIn, emptyList()) }
            }
        }
        if (next.known) {
            synchronized(changesLock) {
                // signed out, or waiting for a choice: early changes are dropped, as later ones would be
                session?.let { current -> early.forEach(current::queue) }
                early.clear()
                authHeard = true
            }
        }
        report()
    }

    private fun begin(account: String, changes: List<LocalChange>) {
        preferences.set(ACCOUNT_KEY, account)
        val started = Session(backend.storeFor(account), account, host, preferences, scope, backend.online, clock, ::report, timing)
        session = started
        if (foreground) {
            started.start()
        } else {
            started.startUploads()
        }
        changes.forEach(started::queue)
    }

    /** The app is on screen: sync fully from now on, listening to the account. */
    fun enterForeground() {
        foreground = true
        session?.startListening()
    }

    private fun status(): SyncStatus {
        val known = auth.state.value
        val current = session
        return if (!known.known) {
            SyncStatus.Connecting
        } else if (known.uid == null) {
            SyncStatus.Off
        } else if (waiting) {
            SyncStatus.Waiting
        } else if (!backend.online.value) {
            SyncStatus.Offline
        } else if (current?.failed == true) {
            SyncStatus.Error
        } else if (current?.busy == true) {
            SyncStatus.Syncing
        } else {
            SyncStatus.Synced
        }
    }

    private fun report() {
        val known = auth.state.value
        mutableUi.update { current ->
            current.copy(
                report = SyncReport(
                    status(),
                    if (known.uid == null) "" else known.email,
                    session?.loadingOlder ?: false,
                    session?.retention,
                    if (known.uid == null) "" else known.photoUrl,
                    session?.feedsKnown ?: false,
                    session?.caughtUp ?: false,
                ),
            )
        }
    }

    /** Sign in with Google and start syncing. */
    fun signIn() {
        mutableUi.update { current -> current.copy(signInFailed = false) }
        scope.launch {
            try {
                auth.signIn()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "sign-in failed", failure)
                mutableUi.update { current -> current.copy(signInFailed = true) }
            }
        }
    }

    /** With a different account waiting, replace this device's data with that account's, and sync it. */
    fun replaceLocal() {
        val account = uid
        // taken at once, so a second tap does nothing
        if (waiting && account != null && replacing.compareAndSet(false, true)) {
            scope.launch {
                try {
                    host.clear()
                    waiting = false
                    // this account's saved progress belonged to data that is gone now; a retention pass still has to finish
                    forgetProgress(preferences, account)
                    // the account may have signed out meanwhile
                    if (uid == account && session == null) {
                        synchronized(changesLock) { begin(account, emptyList()) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // still waiting, so the person can try again
                    logger.log(Level.WARNING, "could not replace this device's data", failure)
                } finally {
                    replacing.set(false)
                    report()
                }
            }
        }
    }

    /**
     * Sign out after sending what is queued, waiting a few seconds when online
     * for it to reach the server, which takes it only while signed in;
     * everything stays on this device.
     */
    fun signOut() {
        waiting = false
        val account = uid
        session?.flushNow()
        session?.stop()
        session = null
        scope.launch {
            try {
                if (account != null && backend.online.value) {
                    awaitPendingWrites(account)
                }
                auth.signOut()
            } finally {
                report()
            }
        }
    }

    private suspend fun awaitPendingWrites(account: String) {
        try {
            withTimeoutOrNull(SIGN_OUT_WAIT_MS) { backend.storeFor(account).waitForPendingWrites() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.FINE, "pending writes did not finish before signing out", failure)
        }
    }

    /**
     * Delete the signed-in account: its feeds, entries and settings in
     * Firestore, this device's sync progress for it, and the Google sign-in
     * itself, then sign out. Shared article text stays, and so does
     * everything on this device.
     */
    fun deleteAccount() {
        val account = uid
        if (account != null) {
            mutableUi.update { current -> current.copy(deleting = true, deleteFailed = false) }
            scope.launch {
                try {
                    // before anything stops, so a closed chooser leaves sync as it was
                    auth.ensureRecentSignIn()
                    emptyAccount(account)
                    if (preferences.get(ACCOUNT_KEY) == account) {
                        preferences.set(ACCOUNT_KEY, null)
                    }
                    auth.signOut()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.log(Level.WARNING, "could not delete the account", failure)
                    mutableUi.update { current -> current.copy(deleteFailed = true) }
                } finally {
                    mutableUi.update { current -> current.copy(deleting = false) }
                    report()
                }
            }
        }
    }

    /**
     * Stop syncing, delete the account's documents and its sign-in. On a
     * failure before the documents are gone sync carries on for the account;
     * after, it signs out instead, so the next full pass does not upload this
     * device's data back into the emptied account.
     */
    private suspend fun emptyAccount(account: String) {
        var emptied = false
        val wasWaiting = waiting
        synchronized(changesLock) {
            waiting = false
            // nothing may be written back while the account is emptied
            session?.stop()
            session = null
            heldForDelete = mutableListOf()
        }
        try {
            backend.storeFor(account).deleteAccountData()
            emptied = true
            preferences.set(stateKey(account), null)
            auth.deleteUser()
            synchronized(changesLock) { heldForDelete = null }
        } catch (failure: Exception) {
            synchronized(changesLock) {
                val held = heldForDelete.orEmpty()
                heldForDelete = null
                // still signed in with the account intact: carry on syncing, so the status stays true
                if (!emptied && uid == account && session == null) {
                    waiting = wasWaiting
                    if (!waiting) {
                        begin(account, held)
                    }
                }
            }
            if (emptied) {
                signOutAfterFailedDelete()
            }
            throw failure
        }
    }

    private suspend fun signOutAfterFailedDelete() {
        try {
            auth.signOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "could not sign out after a failed delete", failure)
        }
    }

    /** Choose how long the signed-in account keeps entries; ignored when signed out. */
    fun setRetention(retention: RetentionDays) {
        session?.setRetention(retention)
    }

    /** Write queued changes now, as when the app goes to the background. */
    fun flushNow() {
        session?.flushNow()
    }

    override fun queue(change: LocalChange) {
        synchronized(changesLock) {
            val held = heldForDelete
            if (!authHeard) {
                early.add(change)
            } else if (held != null) {
                held.add(change)
            } else {
                session?.queue(change)
            }
        }
    }

    override fun restoreBody(hash: String, text: String) {
        session?.restoreBody(hash, text)
    }

    override suspend fun fetchBody(hash: String): String {
        val account = uid
        val text = if (account != null && hash.isNotEmpty() && !waiting) {
            try {
                backend.storeFor(account).readBody(hash)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.FINE, "body download failed", failure)
                ""
            }
        } else {
            ""
        }
        val found = matches(hash, text)
        if (found) {
            session?.bodyPresent(hash)
        }
        return if (found) text else ""
    }
}
