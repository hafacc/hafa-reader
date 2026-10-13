package cc.hafa.reader.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val LIMITS = QueueLimits(concurrency = 3, perHost = 2, fullTimeoutMs = 20_000, saveBatch = 4, saveIntervalMs = 5000)

private fun feed(id: String, host: String = "$id.example") =
    Feed(id, "https://$host/feed", id, "", "", renamed = false, folder = "", error = "")

private fun done(feed: Feed) = FinishedCheck(feed, CheckResult(FeedSchedule(lastChecked = 1), null), 1)

/** A queue over fake fetches, saves and timers, with a clock moved by hand; mirrors the harness in queue.test.ts. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(private val test: TestScope, feeds: List<Feed>, limits: QueueLimits = LIMITS) {
    class Call(val feedId: String, val timeoutMs: Long, val answer: CompletableDeferred<FinishedCheck?>)

    val calls = mutableListOf<Call>()
    val saves = mutableListOf<List<String>>()
    private var now = 1_000_000L
    private var timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val byId = feeds.associateBy { item -> item.id }

    val queue = CheckQueue(
        scope = test.backgroundScope,
        clock = { now },
        feedOf = { id -> byId[id] },
        check = { item, timeoutMs ->
            val pending = CompletableDeferred<FinishedCheck?>()
            calls.add(Call(item.id, timeoutMs, pending))
            pending.await()
        },
        save = { batch -> saves.add(batch.map { finished -> finished.feed.id }) },
        limits = limits,
        setTimer = { delayMs, callback -> timers.add(now + delayMs to callback) },
    )

    fun settle() = test.runCurrent()

    fun advance(milliseconds: Long) {
        now += milliseconds
        val due = timers.filter { (at, _) -> at <= now }
        timers = timers.filter { (at, _) -> at > now }.toMutableList()
        due.forEach { (_, callback) -> callback() }
        settle()
    }

    fun answer(feedId: String, result: FinishedCheck?) {
        calls.last { call -> call.feedId == feedId }.answer.complete(result)
        settle()
    }

    fun request(item: Feed, probability: Double = 0.5, foreground: Boolean = false, timeoutMs: Long = LIMITS.fullTimeoutMs) =
        CheckRequest(item.id, probability, foreground, timeoutMs)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckQueueTest {
    @Test
    fun `a check that throws an error still frees its place`() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, failure -> errors.add(failure) },
        )
        val broken = feed("broken")
        val calls = mutableListOf<String>()
        val queue = CheckQueue(
            scope = scope,
            clock = { 0L },
            feedOf = { id -> if (id == broken.id) broken else null },
            check = { item, _ ->
                calls.add(item.id)
                throw StackOverflowError("deep")
            },
            save = {},
            limits = LIMITS.copy(concurrency = 1),
        )
        val checks = queue.enqueue(listOf(CheckRequest(broken.id, 0.5, foreground = true, timeoutMs = 1000)))
        runCurrent()
        assertTrue(checks.single().first.stored.isCompleted)
        assertFalse(queue.busy())
        assertTrue(errors.single() is StackOverflowError)
        queue.enqueue(listOf(CheckRequest(broken.id, 0.5, foreground = true, timeoutMs = 1000)))
        runCurrent()
        assertEquals(listOf("broken", "broken"), calls)
    }

    @Test
    fun `at most the limit run at once, and at most two to one host`() = runTest {
        val feeds = listOf(feed("a", "same.example"), feed("b", "same.example"), feed("c", "same.example"), feed("d"), feed("e"))
        val harness = Harness(this, feeds)
        harness.queue.enqueue(feeds.map { item -> harness.request(item) })
        harness.settle()
        assertEquals(listOf("a", "b", "d"), harness.calls.map { call -> call.feedId })
    }

    @Test
    fun `waited-for checks and likelier ones go first, and a joined check moves up`() = runTest {
        val names = listOf("blocker", "low", "high", "waited", "joined")
        val feeds = names.associateWith { name -> feed(name) }
        val harness = Harness(this, feeds.values.toList(), LIMITS.copy(concurrency = 1))
        harness.queue.enqueue(listOf(harness.request(feeds.getValue("blocker"))))
        harness.queue.enqueue(
            listOf(
                harness.request(feeds.getValue("low"), probability = 0.1),
                harness.request(feeds.getValue("high"), probability = 0.9),
                harness.request(feeds.getValue("waited"), probability = 0.01, foreground = true),
                harness.request(feeds.getValue("joined"), probability = 0.2),
            ),
        )
        harness.queue.enqueue(listOf(harness.request(feeds.getValue("joined"), probability = 0.95)))
        harness.settle()
        for (name in listOf("blocker", "waited", "joined", "high")) {
            harness.answer(name, done(feeds.getValue(name)))
        }
        assertEquals(listOf("blocker", "waited", "joined", "high", "low"), harness.calls.map { call -> call.feedId })
    }

    @Test
    fun `a feed asked for twice is checked once and both callers hear back`() = runTest {
        val item = feed("a")
        val harness = Harness(this, listOf(item))
        val (first, _) = harness.queue.enqueue(listOf(harness.request(item))).single()
        val (second, _) = harness.queue.enqueue(listOf(harness.request(item, foreground = true))).single()
        harness.settle()
        harness.answer("a", done(item))
        first.stored.await()
        second.stored.await()
        second.settled.await()
        assertEquals(1, harness.calls.size)
    }

    @Test
    fun `a waited-for check that runs out of time stops being waited for and is saved later`() = runTest {
        val item = feed("slow")
        val harness = Harness(this, listOf(item))
        val (handle, _) = harness.queue.enqueue(listOf(harness.request(item, foreground = true, timeoutMs = 8000))).single()
        harness.settle()
        harness.answer("slow", null)
        handle.settled.await()
        assertFalse(handle.stored.isCompleted)
        assertEquals(listOf(8000L, 20_000L), harness.calls.map { call -> call.timeoutMs })
        harness.answer("slow", done(item))
        assertEquals(1, harness.saves.size)
        assertTrue(handle.stored.isCompleted)
    }

    @Test
    fun `waited-for results are saved as soon as they are all done, even in a small batch`() = runTest {
        val fast = feed("fast")
        val background = feed("background")
        val harness = Harness(this, listOf(fast, background))
        harness.queue.enqueue(listOf(harness.request(fast, foreground = true), harness.request(background)))
        harness.settle()
        harness.answer("fast", done(fast))
        assertEquals(listOf(listOf("fast")), harness.saves)
    }

    @Test
    fun `background results are saved in batches, or on time while a slow check runs`() = runTest {
        val feeds = listOf("a", "b", "hung", "c", "d").associateWith { name -> feed(name) }
        val harness = Harness(this, feeds.values.toList())
        harness.advance(LIMITS.saveIntervalMs)
        harness.queue.enqueue(listOf("a", "b", "hung").map { name -> harness.request(feeds.getValue(name)) })
        harness.advance(1000)
        harness.answer("a", done(feeds.getValue("a")))
        harness.answer("b", done(feeds.getValue("b")))
        assertEquals(1, harness.saves.size)
        harness.queue.enqueue(listOf("c", "d").map { name -> harness.request(feeds.getValue(name)) })
        harness.settle()
        harness.answer("c", done(feeds.getValue("c")))
        assertEquals(1, harness.saves.size)
        harness.advance(LIMITS.saveIntervalMs)
        assertEquals(listOf(listOf("a"), listOf("b", "c")), harness.saves)
    }

    @Test
    fun `a refresh that joins a running check stops waiting after its own time`() = runTest {
        val item = feed("running")
        val harness = Harness(this, listOf(item))
        harness.queue.enqueue(listOf(harness.request(item)))
        harness.settle()
        val (joined, _) = harness.queue.enqueue(listOf(harness.request(item, foreground = true, timeoutMs = 8000))).single()
        harness.advance(7999)
        assertFalse(joined.settled.isCompleted)
        harness.advance(1)
        assertTrue(joined.settled.isCompleted)
    }

    @Test
    fun `the queue says when it is busy and when it is done`() = runTest {
        val item = feed("a")
        val harness = Harness(this, listOf(item))
        harness.queue.enqueue(listOf(harness.request(item)))
        assertTrue(harness.queue.busy())
        harness.settle()
        harness.answer("a", done(item))
        assertFalse(harness.queue.busy())
    }

    @Test
    fun `a check that throws releases its feed without saving`() = runTest {
        val item = feed("broken")
        val harness = Harness(this, listOf(item))
        val (handle, _) = harness.queue.enqueue(listOf(harness.request(item))).single()
        harness.settle()
        harness.calls.single().answer.completeExceptionally(IllegalStateException("broken"))
        harness.settle()
        assertTrue(handle.stored.isCompleted)
        assertTrue(handle.settled.isCompleted)
        assertEquals(emptyList(), harness.saves)
        assertFalse(harness.queue.busy())
    }
}
