package cc.hafa.reader.core

import cc.hafa.reader.core.sync.SyncReport
import cc.hafa.reader.core.sync.SyncStatus

/**
 * Whether something that can still change the lists is loading: the stored
 * data, the first check of imported feeds, or, while the person waits for it
 * ([awaited]), the signed-in account's data: sync is starting, or it has not
 * caught up and is neither offline nor failing.
 */
fun loadingBusy(state: ReaderState, sync: SyncReport, awaited: Boolean): Boolean {
    val arriving = sync.status == SyncStatus.Connecting ||
        (!sync.caughtUp && (sync.status == SyncStatus.Syncing || sync.status == SyncStatus.Synced))
    return !state.loaded || state.importing > 0 || (awaited && arriving)
}

/** What the loading screen shows. */
enum class GatePhase {
    /** Nothing: the person is let in. */
    Open,

    /** A blank screen for a moment, so a quick load shows no logo. */
    Waiting,

    /** The turning logo. */
    Shown,
}

/** How long the loading screen waits, stays and holds out. */
data class GateTiming(
    /** How long a load runs before the logo shows. */
    val delayMs: Long = 300,
    /** Shortest time the logo stays once shown, so it never flickers. */
    val minShownMs: Long = 700,
    /** Longest time a load holds the person; after it they are let in and the load goes on behind. */
    val timeoutMs: Long = 15_000,
)

/**
 * The loading screen, which holds the person while what can still change the
 * lists loads; [step] moves it on. It matches the web app's `load-gate.ts`.
 */
data class LoadGate(
    /** What the screen shows now. */
    val phase: GatePhase = GatePhase.Open,
    /** When the current load began; null when nothing is loading. */
    val busySince: Long? = null,
    /** When the logo appeared; null when it is not shown. */
    val shownSince: Long? = null,
    /** Whether the current load ran out of time, so it goes on without holding anyone. */
    val timedOut: Boolean = false,
) {
    /** Whether the screen holds the person: nothing behind it takes taps or keys. */
    val blocks: Boolean get() = phase != GatePhase.Open

    /**
     * The loading screen at [now], with [busy] saying whether something that
     * can still change the lists is loading.
     *
     * A load holds the person behind a blank screen at first, and behind the
     * turning logo once it has run [GateTiming.delayMs]; an instant load shows
     * no logo. Once shown, the logo stays at least [GateTiming.minShownMs]. A
     * load still running after [GateTiming.timeoutMs] lets the person in and
     * holds no one until it ends; the next one starts over.
     */
    fun step(busy: Boolean, now: Long, timing: GateTiming = GateTiming()): LoadGate {
        val since = busySince ?: now
        return if (!busy) {
            val shown = shownSince
            if (phase == GatePhase.Shown && shown != null && now < shown + timing.minShownMs) {
                copy(busySince = null, timedOut = false)
            } else {
                LoadGate()
            }
        } else if (timedOut) {
            copy(phase = GatePhase.Open, shownSince = null)
        } else if (now - since >= timing.timeoutMs) {
            LoadGate(GatePhase.Open, since, null, timedOut = true)
        } else if (phase == GatePhase.Shown) {
            copy(busySince = since)
        } else if (now - since >= timing.delayMs) {
            LoadGate(GatePhase.Shown, since, now)
        } else {
            LoadGate(GatePhase.Waiting, since)
        }
    }

    /** When the screen next changes on its own, for a timer; null when it will not. */
    fun wakeAt(timing: GateTiming = GateTiming()): Long? {
        val times = mutableListOf<Long>()
        val since = busySince
        val shown = shownSince
        if (since != null && !timedOut) {
            times.add(since + timing.timeoutMs)
            if (phase == GatePhase.Waiting) {
                times.add(since + timing.delayMs)
            }
        }
        // A load that ended while the logo was up lets the person in once it has stayed long enough.
        if (phase == GatePhase.Shown && since == null && shown != null) {
            times.add(shown + timing.minShownMs)
        }
        return times.minOrNull()
    }

    companion object {
        /** A loading screen whose load begins at [now], as when the app opens. */
        fun started(now: Long): LoadGate = LoadGate(GatePhase.Waiting, now)
    }
}
