package cc.hafa.reader.core

import cc.hafa.reader.core.sync.SyncReport
import cc.hafa.reader.core.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadGateTest {
    private val timing = GateTiming()

    /** The gate after each of [steps], a busy flag at a time, from the app opening at 0. */
    private fun run(vararg steps: Pair<Boolean, Long>): LoadGate =
        steps.fold(LoadGate.started(0)) { gate, (busy, now) -> gate.step(busy, now) }

    @Test
    fun holdsThePersonFromTheStartWithoutTheLogo() {
        val gate = LoadGate.started(0)
        assertEquals(GatePhase.Waiting, gate.phase)
        assertTrue(gate.blocks)
        assertEquals(timing.delayMs, gate.wakeAt())
    }

    @Test
    fun anInstantLoadNeverShowsTheLogo() {
        assertEquals(GatePhase.Waiting, run(true to 50L).phase)
        assertEquals(GatePhase.Open, run(true to 50L, false to 120L).phase)
    }

    @Test
    fun aSlowLoadShowsTheLogoThenLetsThePersonIn() {
        val shown = run(true to timing.delayMs)
        assertEquals(GatePhase.Shown, shown.phase)
        assertEquals(timing.delayMs, shown.shownSince)
        val done = shown.step(false, timing.delayMs + timing.minShownMs + 1)
        assertEquals(GatePhase.Open, done.phase)
        assertNull(done.wakeAt())
    }

    @Test
    fun theLogoStaysItsShortestTime() {
        val early = run(true to timing.delayMs, false to timing.delayMs + 100)
        assertEquals(GatePhase.Shown, early.phase)
        assertEquals(timing.delayMs + timing.minShownMs, early.wakeAt())
        assertEquals(GatePhase.Open, early.step(false, timing.delayMs + timing.minShownMs).phase)
    }

    @Test
    fun aLoadThatRunsTooLongLetsThePersonIn() {
        val late = run(true to timing.delayMs, true to timing.timeoutMs)
        assertEquals(GatePhase.Open, late.phase)
        assertTrue(late.timedOut)
        assertNull(late.wakeAt())
        assertFalse(late.step(true, timing.timeoutMs * 2).blocks)
    }

    @Test
    fun aNewLoadStartsOver() {
        val later = run(false to 10L, true to 1000L)
        assertEquals(GatePhase.Waiting, later.phase)
        assertEquals(1000L, later.busySince)
        assertEquals(GatePhase.Shown, later.step(true, 1000 + timing.delayMs).phase)
    }

    @Test
    fun aLoadAfterOneThatTimedOutHoldsAgain() {
        val next = run(true to timing.timeoutMs, false to timing.timeoutMs + 1, true to timing.timeoutMs + 2)
        assertEquals(GatePhase.Waiting, next.phase)
        assertFalse(next.timedOut)
    }

    @Test
    fun wakesForTheLogoThenForTheTimeLimit() {
        assertEquals(timing.delayMs, run(true to 10L).wakeAt())
        assertEquals(timing.timeoutMs, run(true to timing.delayMs).wakeAt())
    }

    @Test
    fun theAccountHoldsThePersonWhileItArrivesButNotOfflineOrFailing() {
        val loaded = ReaderState(loaded = true)
        assertTrue(loadingBusy(ReaderState(), SyncReport(SyncStatus.Off), awaited = false))
        assertFalse(loadingBusy(loaded, SyncReport(SyncStatus.Off), awaited = true))
        assertTrue(loadingBusy(loaded, SyncReport(SyncStatus.Connecting), awaited = true))
        assertTrue(loadingBusy(loaded, SyncReport(SyncStatus.Syncing, "reader@example.com"), awaited = true))
        assertFalse(loadingBusy(loaded, SyncReport(SyncStatus.Offline, "reader@example.com"), awaited = true))
        assertFalse(loadingBusy(loaded, SyncReport(SyncStatus.Error, "reader@example.com"), awaited = true))
        assertFalse(loadingBusy(loaded, SyncReport(SyncStatus.Synced, "reader@example.com", caughtUp = true), awaited = true))
        assertFalse(loadingBusy(loaded, SyncReport(SyncStatus.Syncing, "reader@example.com"), awaited = false))
        assertTrue(loadingBusy(loaded.copy(importing = 1), SyncReport(SyncStatus.Off), awaited = false))
    }
}
