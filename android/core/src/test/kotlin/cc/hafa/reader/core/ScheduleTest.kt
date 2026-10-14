package cc.hafa.reader.core

import okhttp3.Headers.Companion.headersOf
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000

    // Monday 5 January 2026, 00:00 UTC.
    private val monday = utc(2026, 1, 5)

    private fun utc(year: Int, month: Int, date: Int, hours: Int = 0): Long =
        java.time.LocalDateTime.of(year, month, date, hours, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun poissonTail(mean: Double, capacity: Int): Double {
        var term = exp(-mean)
        var covered = term
        for (count in 1..capacity) {
            term *= mean / count
            covered += term
        }
        return 1 - covered
    }

    private fun model(alpha: Double, beta: Double, capacity: Int) = FeedModel(alpha = alpha, beta = beta, capacity = capacity)

    @Test
    fun `a firmly known rate matches the exact Poisson tail`() {
        val model = model(2e6, 1e6, 20)
        for (days in listOf(1.0, 3.0, 5.0)) {
            assertEquals(poissonTail(2 * days, 10), missProbability(model, days, 10), 5e-5)
        }
    }

    @Test
    fun `the chosen wait keeps the chance of a miss under the limit`() {
        val model = model(2e6, 1e6, 20)
        val interval = chooseInterval(model, monday)
        val usable = floor(20 * BURST_MARGIN).toInt()
        assertTrue(missProbability(model, interval / day, usable) <= MISS_PROBABILITY * 1.01)
        assertTrue(missProbability(model, interval * 1.1 / day, usable) > MISS_PROBABILITY)
    }

    @Test
    fun `busier feeds are checked sooner, larger windows later`() {
        val quiet = model(1e4, 1e4, 20)
        val busy = model(1e4, 1e3, 20)
        assertTrue(chooseInterval(busy, monday) < chooseInterval(quiet, monday))
        assertTrue(chooseInterval(quiet.copy(capacity = 100), monday) > chooseInterval(quiet, monday))
    }

    @Test
    fun `waits stay within the bounds`() {
        assertEquals(MIN_INTERVAL_MS.toDouble(), chooseInterval(model(1e6, 1.0, 10), monday))
        assertEquals(MAX_INTERVAL_MS.toDouble(), chooseInterval(model(1.0, 1e6, 500), monday))
    }

    @Test
    fun `the first look uses the dates on the items`() {
        val model = observeWindow(PRIOR, listOf(day, 3 * day, 5 * day, 7 * day))
        assertEquals(PRIOR.alpha + 3, model.alpha)
        assertEquals(PRIOR.beta + 6, model.beta)
        assertEquals(4, model.capacity)
        assertEquals(PRIOR.alpha, observeWindow(PRIOR, listOf(5, 5)).alpha)
    }

    @Test
    fun `the first look skips undated items but counts them in the window`() {
        val dated = listOf(day, 3 * day, 5 * day, 7 * day)
        val withUndated = observeWindow(PRIOR, dated + 0)
        val datedOnly = observeWindow(PRIOR, dated)
        assertEquals(datedOnly.alpha, withUndated.alpha)
        assertEquals(datedOnly.beta, withUndated.beta)
        assertEquals(5, withUndated.capacity)
    }

    @Test
    fun `checks add what they saw and fade old evidence`() {
        val first = observe(PRIOR, 3, monday, monday + 2 * day, 10)
        assertEquals(PRIOR.alpha + 3, first.alpha)
        assertEquals(PRIOR.beta + 2, first.beta, 1e-9)
        val later = observe(model(11.0, 11.0, 10), 0, monday, monday + 60 * day, 10)
        assertEquals(1 + 10 / 2.0, later.alpha, 1e-6)
        assertEquals(1 + 10 / 2.0 + 60, later.beta, 1e-6)
    }

    @Test
    fun `failures back off and reads waits from headers`() {
        assertEquals(MIN_INTERVAL_MS, retryInterval(0))
        assertEquals(MIN_INTERVAL_MS * 4, retryInterval(2))
        assertEquals(MAX_RETRY_MS, retryInterval(50))
        assertEquals(24L * 60 * 60 * 1000, MAX_RETRY_MS)
        assertEquals(600_000L, hintFromHeaders(headersOf("Cache-Control", "public, max-age=600")::get))
        assertEquals(120_000L, hintFromHeaders(headersOf("Retry-After", "120")::get))
        assertEquals(0L, hintFromHeaders(headersOf()::get))
    }

    @Test
    fun `a Retry-After date is read as the wait until then`() {
        val now = utc(2026, 1, 5, 12)
        val format = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        val later = format.format(Instant.ofEpochMilli(now + 3_600_000).atZone(ZoneOffset.UTC))
        assertEquals(3_600_000L, hintFromHeaders(headersOf("Retry-After", later)::get, now))
        val past = format.format(Instant.ofEpochMilli(now - 3_600_000).atZone(ZoneOffset.UTC))
        assertEquals(0L, hintFromHeaders(headersOf("Retry-After", past)::get, now))
        assertEquals(0L, hintFromHeaders(headersOf("Retry-After", "soon")::get, now))
    }

    @Test
    fun `Retry-After is whole seconds or an HTTP date, and nothing else`() {
        val now = utc(2026, 1, 5, 12)
        assertEquals(0L, hintFromHeaders(headersOf("Retry-After", "1.5")::get, now))
        assertEquals(0L, hintFromHeaders(headersOf("Retry-After", "-30")::get, now))
        assertEquals(0L, hintFromHeaders(headersOf("Retry-After", "Monday, 05-Jan-26 13:00:00 GMT")::get, now))
        assertEquals(3_600_000L, hintFromHeaders(headersOf("Retry-After", "Mon, 05 Jan 2026 13:00:00 GMT")::get, now))
    }

    @Test
    fun `the window is the one last seen, and quiet checks keep it`() {
        val model = model(5.0, 5.0, 20)
        assertEquals(8, observe(model, 1, monday, monday + day, 8).capacity)
        assertEquals(20, observe(model, 0, monday, monday + day, 0).capacity)
        assertEquals(3, observeWindow(model, listOf(day, 2 * day, 3 * day)).capacity)
        assertEquals(20, observeWindow(model, emptyList()).capacity)
    }

    @Test
    fun `a date without a time of day teaches the day but not the hour`() {
        val pattern = updatePattern(null, listOf(monday, monday + 9 * hour), 0)
        assertEquals(0.0, pattern.hourOfDay[0])
        assertEquals(1.0, pattern.hourOfDay[9])
        assertEquals(2.0, pattern.dayOfWeek[0])
        assertEquals(2.0, pattern.dayOfMonth[4])
    }

    @Test
    fun `no time means no chance of a miss`() {
        assertEquals(0.0, missProbability(model(1e6, 1.0, 10), 0.0, 1))
        assertEquals(0.0, missProbability(model(1e6, 1.0, 10), -1.0, 1))
    }

    @Test
    fun `a very long requested wait is capped at the longest wait`() {
        assertEquals(MAX_INTERVAL_MS, hintFromHeaders(headersOf("Cache-Control", "max-age=31536000")::get))
    }

    private fun mondayFeed(): FeedModel =
        learnPattern(model(13.0, 91.0, 4), (0 until 104).map { week -> monday - week * 7 * day + 9 * hour })

    @Test
    fun `bins are UTC hours, days of the week from Monday and dates from the 1st`() {
        assertEquals(9, hourOfDay(monday + 9 * hour + 59_000))
        assertEquals(23, hourOfDay(monday - hour))
        assertEquals(0, dayOfWeek(monday))
        assertEquals(6, dayOfWeek(monday - hour))
        assertEquals(3, dayOfWeek(0))
        assertEquals(0, dayOfMonth(utc(2026, 1, 1, 23)))
        assertEquals(DAYS_PER_MONTH - 1, dayOfMonth(utc(2026, 1, 31)))
    }

    @Test
    fun `the factors learn daily, weekly and monthly habits and each average one`() {
        val daily = (0 until 60).map { index -> monday + index * day + 14 * hour }
        val weekly = (0 until 20).map { week -> monday + week * 7 * day + 3 * day }
        val monthly = (1..12).map { month -> utc(2025, month, 1, 6) }
        val dailyFactors = patternFactors(learnPattern(PRIOR, daily).pattern)
        val weeklyFactors = patternFactors(learnPattern(PRIOR, weekly).pattern)
        val monthlyFactors = patternFactors(learnPattern(PRIOR, monthly).pattern)
        assertTrue(dailyFactors.hourOfDay[14] > 20)
        assertTrue(dailyFactors.hourOfDay[3] < 0.05)
        assertTrue(dailyFactors.dayOfWeek.max() < 1.2)
        assertTrue(weeklyFactors.dayOfWeek[3] > 6)
        assertTrue(weeklyFactors.dayOfWeek[0] < 0.05)
        assertTrue(monthlyFactors.dayOfMonth[0] > 8)
        for (factors in listOf(dailyFactors, weeklyFactors, monthlyFactors)) {
            assertEquals(1.0, factors.hourOfDay.average(), 1e-9)
            assertEquals(1.0, factors.dayOfWeek.average(), 1e-9)
            assertEquals(1.0, factors.dayOfMonth.average(), 1e-9)
        }
    }

    @Test
    fun `the day-of-month factor stays near flat with few posts`() {
        val weekly = (0 until 8).map { week -> monday - week * 7 * day + 9 * hour }
        val factors = patternFactors(learnPattern(PRIOR, weekly).pattern)
        assertTrue(factors.dayOfMonth.min() > 0.75)
        assertTrue(factors.dayOfMonth.max() < 1.6)
    }

    @Test
    fun `the pattern fades and skips undated posts`() {
        val pattern = updatePattern(null, listOf(monday + hour, 0, monday + hour), 0)
        assertEquals(2.0, pattern.hourOfDay[1])
        assertEquals(2.0, pattern.dayOfWeek[0])
        assertEquals(2.0, pattern.dayOfMonth[4])
        val faded = updatePattern(pattern, listOf(monday + 2 * hour), 60 * day)
        assertEquals(1.0, faded.hourOfDay[1], 1e-9)
        assertEquals(1.0, faded.hourOfDay[2])
    }

    @Test
    fun `a flat model's activity is elapsed days, also past the stepped span`() {
        assertEquals(3.5, expectedActivity(PRIOR, monday, monday + (3.5 * day).toLong()), 1e-9)
        assertEquals(50 - 0.3 / 24, expectedActivity(PRIOR, monday + (0.3 * hour).toLong(), monday + 50 * day), 1e-9)
        assertEquals(0.0, expectedActivity(PRIOR, monday, monday - day))
    }

    @Test
    fun `a flat model chooses the same wait as the plain rate`() {
        val model = model(40.0, 20.0, 20)
        val flat = learnPattern(model, emptyList())
        val interval = chooseInterval(model, monday + 5 * hour)
        assertEquals(interval, chooseInterval(flat, monday + 5 * hour), 500.0)
        val usable = floor(20 * BURST_MARGIN).toInt()
        assertTrue(missProbability(model, interval / day, usable) <= MISS_PROBABILITY * 1.01)
        assertEquals(interval, chooseInterval(model, monday + 13 * day), 500.0)
    }

    @Test
    fun `a Monday feed sleeps after Monday and is checked soon before the next`() {
        val model = mondayFeed()
        val afterMonday = chooseInterval(model, monday + 12 * hour)
        val beforeMonday = chooseInterval(model, monday + 6 * day + 12 * hour)
        val flatAfter = chooseInterval(model.copy(pattern = null), monday + 12 * hour)
        assertTrue(afterMonday > 5 * day)
        assertTrue(beforeMonday < day)
        assertTrue(afterMonday > flatAfter)
    }

    @Test
    fun `a Monday feed is unlikely on Tuesday and likely the next Monday`() {
        val model = mondayFeed()
        val lastChecked = monday + 12 * hour
        val tuesday = newProbability(model, lastChecked, lastChecked + day)
        val nextMonday = newProbability(model, lastChecked, monday + 7 * day + 12 * hour)
        val flatTuesday = newProbability(model.copy(pattern = null), lastChecked, lastChecked + day)
        assertTrue(tuesday < 0.1, "tuesday $tuesday")
        assertTrue(tuesday < flatTuesday / 2)
        assertTrue(nextMonday > 0.5, "next Monday $nextMonday")
    }

    @Test
    fun `the chance of news grows with time and starts at one for new feeds`() {
        val model = model(3.0, 6.0, 10)
        var previous = 0.0
        for (days in listOf(0.1, 0.5, 1.0, 3.0, 10.0)) {
            val probability = newProbability(model, monday, monday + (days * day).toLong())
            assertTrue(probability > previous)
            assertTrue(probability < 1)
            previous = probability
        }
        assertEquals(0.0, newProbability(model, monday, monday))
        assertEquals(1.0, newProbability(model, 0, monday))
    }

    private data class TestFeed(val id: String, val schedule: FeedSchedule)

    private fun feed(id: String, model: FeedModel, lastChecked: Long, failures: Int = 0, nextFetch: Long = 0) =
        TestFeed(id, FeedSchedule(model = model, lastChecked = lastChecked, failures = failures, nextFetch = nextFetch))

    @Test
    fun `a refresh waits for likely feeds and leaves unlikely ones`() {
        val now = monday + 30 * day
        val daily = model(30.0, 30.0, 10)
        val rare = model(1.0, 200.0, 10)
        val plan = planRefresh(
            (0 until 20).map { index -> feed("rare$index", rare, now - hour) } + listOf(
                feed("daily", daily, now - 2 * day),
                feed("new", daily, 0),
                feed("failing due", daily, now - day, 2, now - 1),
                feed("failing later", daily, now - day, 2, now + hour),
                feed("fresh daily", daily, now - hour),
            ),
            now,
        ) { item -> item.schedule }
        assertEquals(listOf("new", "daily", "failing due"), plan.ordered.take(3).map { item -> item.id })
        assertEquals("failing later", plan.ordered.last().id)
        plan.probabilities.zipWithNext { first, second -> assertTrue(second <= first) }
        val allowance = maxOf(0.5, 0.02 * plan.probabilities.sum())
        assertTrue(plan.probabilities.drop(plan.foreground).sum() <= allowance)
        assertTrue(plan.foreground >= 3)
        assertTrue(plan.foreground < plan.ordered.size)
        assertTrue(plan.foreground == 3 || plan.probabilities.drop(plan.foreground - 1).sum() > allowance)
    }

    @Test
    fun `many middling feeds are all waited for`() {
        val model = model(1.0, 5.0, 10)
        val plan = planRefresh((0 until 40).map { index -> feed("$index", model, monday - day) }, monday) { item -> item.schedule }
        assertTrue(plan.foreground > 30)
    }

    @Test
    fun `ties keep their given order`() {
        val model = model(1.0, 5.0, 10)
        val plan = planRefresh((0 until 6).map { index -> feed("$index", model, 0) }, monday) { item -> item.schedule }
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), plan.ordered.map { item -> item.id })
        assertEquals(6, plan.foreground)
    }

    @Test
    fun `every check fades the pattern`() {
        val model = learnPattern(PRIOR, listOf(monday + hour))
        val faded = observe(model, 0, monday, monday + 60 * day, 10)
        assertEquals(0.5, faded.pattern!!.hourOfDay[1], 1e-9)
        assertEquals(0.5, faded.pattern.dayOfWeek[0], 1e-9)
        assertNull(observe(PRIOR, 0, monday, monday + day, 10).pattern)
    }

    @Test
    fun `a refresh ranks a Monday feed by its pattern`() {
        val mondayModel = mondayFeed()
        val flat = model(13.0, 91.0, 4)
        val lastChecked = monday + 7 * day - 2 * day
        fun ids(nowMs: Long) =
            planRefresh(listOf(feed("monday", mondayModel, lastChecked), feed("flat", flat, lastChecked)), nowMs) { item -> item.schedule }
                .ordered.map { item -> item.id }
        assertEquals(listOf("flat", "monday"), ids(monday + 7 * day - hour))
        assertEquals(listOf("monday", "flat"), ids(monday + 7 * day + 11 * hour))
    }

    @Test
    fun `feeds become overdue a while after their earliest next check`() {
        assertEquals(monday + OVERDUE_MS, overdueAt(listOf(monday + day, monday, monday + 2 * day)))
        assertEquals(OVERDUE_MS, overdueAt(listOf(0L)))
        assertEquals(Long.MAX_VALUE, overdueAt(emptyList()))
        assertEquals(2 * MIN_INTERVAL_MS, OVERDUE_MS)
    }

    @Test
    fun `a refresh is suggested only while nothing is checking feeds`() {
        val feeds = listOf(
            Feed("late", "https://late.example/feed", "Late", "", "", renamed = false, folder = "", error = "", schedule = FeedSchedule(nextFetch = monday)),
        )
        assertEquals(monday + OVERDUE_MS, refreshSuggestedAt(ReaderState(feeds = feeds)))
        assertEquals(Long.MAX_VALUE, refreshSuggestedAt(ReaderState(feeds = feeds, checking = true)))
        assertEquals(Long.MAX_VALUE, refreshSuggestedAt(ReaderState(feeds = feeds, refreshCount = 1)))
        assertEquals(Long.MAX_VALUE, refreshSuggestedAt(ReaderState()))
    }

    @Test
    fun `a plain http feed, never checked, is never overdue`() {
        val plain = Feed("plain", "http://plain.example/feed", "Plain", "", "", renamed = false, folder = "", error = "", schedule = FeedSchedule(nextFetch = 0))
        assertEquals(Long.MAX_VALUE, refreshSuggestedAt(ReaderState(feeds = listOf(plain))))
    }
}
