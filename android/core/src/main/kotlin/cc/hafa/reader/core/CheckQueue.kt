package cc.hafa.reader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Time a fetch gets, in milliseconds, while a refresh waits for it. */
const val FOREGROUND_TIMEOUT_MS = 8_000L

/** Fetches running at once. */
const val PULL_CONCURRENCY = 8

/** Fetches running at once to one host. */
const val HOST_CONCURRENCY = 2

/** Results kept before they are saved together. */
const val SAVE_BATCH = 16

/** Longest time, in milliseconds, results wait to be saved while others are still being fetched. */
const val SAVE_INTERVAL_MS = 5_000L

/** How hard the queue works. */
data class QueueLimits(
    /** Most checks running at once. */
    val concurrency: Int = PULL_CONCURRENCY,
    /** Most checks running at once against one host. */
    val perHost: Int = HOST_CONCURRENCY,
    /** Time a check gets once it has timed out as a waited-for check. */
    val fullTimeoutMs: Long = FETCH_TIMEOUT_MS,
    /** Finished checks that trigger a save. */
    val saveBatch: Int = SAVE_BATCH,
    /** Longest time a finished check waits to be saved, in milliseconds. */
    val saveIntervalMs: Long = SAVE_INTERVAL_MS,
)

/** A feed to check, as a refresh or the timer asks for it. */
data class CheckRequest(
    /** The feed. */
    val feedId: String,
    /** Chance the feed holds news; higher goes first. */
    val probability: Double,
    /** Whether a refresh waits for it. */
    val foreground: Boolean,
    /** Time the fetch gets, in milliseconds. */
    val timeoutMs: Long,
)

/** One finished check, ready to save. */
data class FinishedCheck(
    /** The feed as it was when it was fetched. */
    val feed: Feed,
    /** What the check found. */
    val result: CheckResult,
    /** When it was checked. */
    val checkedAt: Long,
)

/** One feed waiting to be checked, being checked, or waiting to be saved. */
class QueuedCheck internal constructor(
    /** The feed. */
    val feedId: String,
    internal val host: String,
    internal var probability: Double,
    internal var foreground: Boolean,
    internal var timeoutMs: Long,
) {
    /** It timed out once, so it now gets the full time. */
    internal var slow = false

    /** Completes when the result is saved or the check stops being waited for. */
    val settled = CompletableDeferred<Unit>()

    /** Completes when the result is saved, or the check ended without one. */
    val stored = CompletableDeferred<Unit>()
}

/**
 * The one queue every check of a feed goes through, whether a refresh or the
 * timer asks for it; the same rules as `shared/src/queue.ts`.
 *
 * Waited-for checks go first, then likelier ones, then the earlier queued; at
 * most [QueueLimits.concurrency] run at once and [QueueLimits.perHost] against
 * one host. A feed already queued, running or unsaved is joined rather than
 * checked twice: the join keeps the higher probability, becomes waited-for if
 * either was, and takes the shorter timeout unless the check already timed out
 * once; a waited-for join of a running check stops being waited for after its
 * own timeout. A check that runs out of a time shorter than
 * [QueueLimits.fullTimeoutMs] ([check] gives null) stops being waited for and
 * is queued again in the background with the full time. A check that throws
 * releases its feed without saving. Finished checks are saved through [save]
 * in batches: once no waited-for check is left unfinished and one is done, at
 * [QueueLimits.saveBatch], [QueueLimits.saveIntervalMs] after the last save
 * (with a timer when nothing else comes), or when nothing is left to run.
 * [onBusy] hears whether anything is queued, running or unsaved each time
 * that changes.
 */
class CheckQueue(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val feedOf: (String) -> Feed?,
    private val check: suspend (Feed, Long) -> FinishedCheck?,
    private val save: suspend (List<FinishedCheck>) -> Unit,
    private val limits: QueueLimits = QueueLimits(),
    private val setTimer: (Long, () -> Unit) -> Unit = { delayMs, callback ->
        scope.launch {
            delay(delayMs)
            callback()
        }
    },
    private val onBusy: (Boolean) -> Unit = {},
) {
    private val lock = Mutex()
    private val saveLock = Mutex()
    private val queued = mutableListOf<QueuedCheck>()
    private val inFlight = mutableSetOf<QueuedCheck>()
    private val checking = mutableMapOf<String, QueuedCheck>()
    private val activeByHost = mutableMapOf<String, Int>()
    private var unsaved = mutableListOf<Pair<QueuedCheck, FinishedCheck>>()
    private var lastSave = 0L
    private var saveTimerArmed = false
    private var reportedBusy = false

    /** Queue checks, joining any already under way, and return each request's check. */
    suspend fun enqueue(requests: List<CheckRequest>): List<Pair<QueuedCheck, CheckRequest>> =
        lock.withLock {
            val checks = requests.mapNotNull { request ->
                val feed = feedOf(request.feedId)
                if (feed == null) {
                    null
                } else {
                    val known = checking[request.feedId]
                    val check = if (known != null) {
                        join(known, request)
                        known
                    } else {
                        val host = feed.url.toHttpUrlOrNull()?.host ?: ""
                        val created = QueuedCheck(request.feedId, host, request.probability, request.foreground, request.timeoutMs)
                        checking[request.feedId] = created
                        queued.add(created)
                        created
                    }
                    check to request
                }
            }
            saveIfReady()
            pump()
            reportBusy()
            checks
        }

    /** Whether any check is queued, running or unsaved. */
    suspend fun busy(): Boolean = lock.withLock { checking.isNotEmpty() }

    /** Wait until every check queued so far, and any queued meanwhile, is saved. */
    suspend fun awaitIdle() {
        var pending = lock.withLock { checking.values.toList() }
        while (pending.isNotEmpty()) {
            pending.forEach { check -> check.stored.await() }
            pending = lock.withLock { checking.values.toList() }
        }
    }

    private fun join(known: QueuedCheck, request: CheckRequest) {
        known.probability = maxOf(known.probability, request.probability)
        known.foreground = known.foreground || request.foreground
        if (!known.slow) {
            known.timeoutMs = minOf(known.timeoutMs, request.timeoutMs)
        }
        if (request.foreground && known in inFlight) {
            // The fetch already running keeps its own time limit, so the refresh stops waiting on its own.
            setTimer(request.timeoutMs) { known.settled.complete(Unit) }
        }
    }

    private fun ranksBefore(candidate: QueuedCheck, best: QueuedCheck): Boolean =
        if (candidate.foreground == best.foreground) {
            candidate.probability > best.probability
        } else {
            candidate.foreground
        }

    private fun takeNext(): QueuedCheck? {
        var best: QueuedCheck? = null
        for (candidate in queued) {
            val current = best
            if ((activeByHost[candidate.host] ?: 0) < limits.perHost && (current == null || ranksBefore(candidate, current))) {
                best = candidate
            }
        }
        if (best != null) {
            queued.remove(best)
        }
        return best
    }

    private fun pump() {
        while (inFlight.size < limits.concurrency) {
            val next = takeNext() ?: return
            start(next)
        }
    }

    private fun release(check: QueuedCheck) {
        checking.remove(check.feedId)
        check.settled.complete(Unit)
        check.stored.complete(Unit)
    }

    private fun start(queuedCheck: QueuedCheck) {
        inFlight.add(queuedCheck)
        activeByHost[queuedCheck.host] = (activeByHost[queuedCheck.host] ?: 0) + 1
        val timeoutMs = queuedCheck.timeoutMs
        scope.launch {
            var attempt: Attempt = Attempt.Gone
            try {
                val feed = feedOf(queuedCheck.feedId)
                attempt = if (feed == null) {
                    Attempt.Gone
                } else {
                    check(feed, timeoutMs)?.let(Attempt::Done) ?: Attempt.Retry
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                attempt = Attempt.Gone
            } finally {
                // Runs even after an Error or cancellation, so the slot and its waiters are always let go.
                withContext(NonCancellable) { finish(queuedCheck, attempt) }
            }
        }
    }

    private suspend fun finish(queuedCheck: QueuedCheck, attempt: Attempt) {
        lock.withLock {
            inFlight.remove(queuedCheck)
            activeByHost[queuedCheck.host] = (activeByHost[queuedCheck.host] ?: 1) - 1
            when (attempt) {
                Attempt.Gone -> release(queuedCheck)
                Attempt.Retry -> {
                    queuedCheck.slow = true
                    queuedCheck.foreground = false
                    queuedCheck.timeoutMs = limits.fullTimeoutMs
                    queued.add(queuedCheck)
                    queuedCheck.settled.complete(Unit)
                }
                is Attempt.Done -> unsaved.add(queuedCheck to attempt.finished)
            }
            saveIfReady()
            pump()
            reportBusy()
        }
    }

    // Called with the lock held.
    private fun reportBusy() {
        val busy = checking.isNotEmpty()
        if (busy != reportedBusy) {
            reportedBusy = busy
            onBusy(busy)
        }
    }

    private fun saveIfReady() {
        val foregroundLeft = queued.any { candidate -> candidate.foreground } || inFlight.any { candidate -> candidate.foreground }
        val ready = (!foregroundLeft && unsaved.any { (candidate, _) -> candidate.foreground }) ||
            unsaved.size >= limits.saveBatch ||
            clock() - lastSave >= limits.saveIntervalMs ||
            (queued.isEmpty() && inFlight.isEmpty())
        if (unsaved.isNotEmpty() && ready) {
            val batch = unsaved
            unsaved = mutableListOf()
            lastSave = clock()
            scope.launch {
                try {
                    saveLock.withLock { save(batch.map { (_, finished) -> finished }) }
                } finally {
                    lock.withLock {
                        batch.forEach { (finishedCheck, _) -> release(finishedCheck) }
                        reportBusy()
                    }
                }
            }
        } else if (unsaved.isNotEmpty() && !saveTimerArmed) {
            // Results finished early are saved on time even while a slow check holds the rest.
            saveTimerArmed = true
            setTimer(maxOf(0L, lastSave + limits.saveIntervalMs - clock())) {
                scope.launch {
                    lock.withLock {
                        saveTimerArmed = false
                        saveIfReady()
                    }
                }
            }
        }
    }
}

/** How one fetch of a queued check ended. */
private sealed interface Attempt {
    /** Checked; the result waits to be saved. */
    data class Done(val finished: FinishedCheck) : Attempt

    /** It ran out of a shorter time; it goes back in the queue with the full time. */
    data object Retry : Attempt

    /** The feed is gone, or the check broke; nothing to save. */
    data object Gone : Attempt
}
