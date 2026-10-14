package cc.hafa.reader.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24.0 * 60 * 60 * 1000

/** Bins of the hour-of-day factor: one per UTC hour. */
const val HOURS_PER_DAY = 24

/** Bins of the day-of-week factor: one per UTC day, Monday first. */
const val DAYS_PER_WEEK = 7

/** Bins of the day-of-month factor: one per UTC date, the 1st first. */
const val DAYS_PER_MONTH = 31

/**
 * Pseudo-posts added to every bin when reading a pattern, so an unseen feed is
 * flat and a sparse one stays close to flat.
 */
const val PATTERN_SMOOTHING = 0.1

/**
 * Pseudo-posts added to every bin of the day-of-month factor instead, so that
 * a few posts spread over the dates do not make it lumpy.
 */
const val DAY_OF_MONTH_SMOOTHING = 1.0

/** Longest span stepped hour by hour; beyond it each day counts as one. */
const val ACTIVITY_STEP_LIMIT_MS = 35L * 24 * 60 * 60 * 1000

/** Refreshes of at most this many feeds fetch them all before returning. */
const val SMALL_REFRESH = 5

/** Feeds at least this likely to hold news are always waited for. */
const val LIKELY_PROBABILITY = 0.5

/** Chance of news given to a failing feed that is due again. */
const val FAILING_PROBABILITY = 0.5

/** Expected feeds with news a refresh may leave to the background, at least. */
const val LEFT_OVER_FEEDS = 0.5

/** Share of all expected feeds with news a refresh may leave to the background. */
const val LEFT_OVER_SHARE = 0.02

// The epoch fell on a Thursday, three days into a week that starts on Monday.
private const val EPOCH_DAY_OF_WEEK = 3L
private const val DAY_MS_LONG = 24L * 60 * 60 * 1000

/** Shortest wait between checks of one feed, in milliseconds. */
const val MIN_INTERVAL_MS = 15L * 60 * 1000

/** Longest wait between checks of one feed, in milliseconds. */
const val MAX_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/** Chance, per wait, that more items appear than the feed can show. */
const val MISS_PROBABILITY = 1e-4

/**
 * Share of a feed's window counted as usable, since real posts come in clumps
 * rather than evenly.
 */
const val BURST_MARGIN = 0.5

/** Window size assumed before any feed has been seen. */
private const val UNKNOWN_CAPACITY = 10

/** Days after which old observations count half as much. */
private const val HALF_LIFE_DAYS = 60.0

private const val MAX_CAPACITY = 500
private const val SEARCH_STEPS = 40
private val LANCZOS = doubleArrayOf(
    676.5203681218851, -1259.1392167224028, 771.3234287776531,
    -176.6150291621406, 12.507343278686905, -0.13857109526572012,
    9.984369578019572e-6, 1.5056327351493116e-7,
)

/**
 * What is known about how often one feed posts.
 *
 * Posts are modeled as a Poisson process whose rate at time `t`, per day, is
 * `rate * hourOfDay(t) * dayOfWeek(t) * dayOfMonth(t)`: `rate` has a Gamma
 * distribution with shape [alpha] and rate [beta], and each factor is learned
 * from when posts appear and averages one over its period. Each check adds the
 * new items seen and the expected activity of the time they took to appear.
 */
data class FeedModel(
    /** Gamma shape: posts seen, plus the prior's. */
    val alpha: Double,
    /** Days of observation behind [alpha], in expected activity. */
    val beta: Double,
    /** Most items the feed has been seen to show at once; zero if unknown. */
    val capacity: Int,
    /** Faded post counts by hour of day, day of week and day of month; null means flat. */
    val pattern: PostingPattern? = null,
)

/** Faded counts of posts in each bin of the three periodic factors, all in UTC. */
data class PostingPattern(
    /** [HOURS_PER_DAY] counts, hour 0 first. */
    val hourOfDay: List<Double>,
    /** [DAYS_PER_WEEK] counts, Monday first. */
    val dayOfWeek: List<Double>,
    /** [DAYS_PER_MONTH] counts, the 1st first. */
    val dayOfMonth: List<Double>,
)

/** Belief before a feed has been seen: about one post a day, weakly held. */
val PRIOR = FeedModel(alpha = 1.0, beta = 1.0, capacity = 0)

private fun logGamma(value: Double): Double =
    if (value < 0.5) {
        ln(PI / sin(PI * value)) - logGamma(1 - value)
    } else {
        val shifted = value - 1
        var sum = 0.99999999999980993
        for ((index, coefficient) in LANCZOS.withIndex()) {
            sum += coefficient / (shifted + index + 1)
        }
        val base = shifted + LANCZOS.size - 0.5
        0.5 * ln(2 * PI) + (shifted + 0.5) * ln(base) - base + ln(sum)
    }

/**
 * Fold in a check that found [newItems] between [fromMs] and [toMs], in a
 * window of [windowSize] (zero for a check that saw no new body, which keeps
 * the window known). Old evidence fades by the elapsed days, `beta` grows
 * by the expected activity in between, and the pattern fades too; its new
 * posts are added with [learnPattern].
 */
fun observe(model: FeedModel, newItems: Int, fromMs: Long, toMs: Long, windowSize: Int): FeedModel {
    val elapsedMs = max(0L, toMs - fromMs)
    val keep = 0.5.pow(elapsedMs / DAY_MS / HALF_LIFE_DAYS)
    return FeedModel(
        alpha = PRIOR.alpha + (model.alpha - PRIOR.alpha) * keep + newItems,
        beta = PRIOR.beta + (model.beta - PRIOR.beta) * keep + expectedActivity(model, fromMs, toMs),
        capacity = if (windowSize > 0) windowSize else model.capacity,
        pattern = model.pattern?.let { pattern -> updatePattern(pattern, emptyList(), elapsedMs) },
    )
}

/**
 * Fold in the first look at a feed, using the dates of the items it shows.
 * [published] are millisecond timestamps.
 */
fun observeWindow(model: FeedModel, published: List<Long>): FeedModel {
    val sorted = published.filter { time -> time > 0 }.sorted()
    val span = if (sorted.isEmpty()) 0L else sorted.last() - sorted.first()
    val capacity = if (published.isNotEmpty()) published.size else model.capacity
    return if (span > 0) {
        model.copy(alpha = model.alpha + sorted.size - 1, beta = model.beta + span / DAY_MS, capacity = capacity)
    } else {
        model.copy(capacity = capacity)
    }
}

/**
 * Chance that more new items appear within [days] than a window of
 * [capacity] can show, so that some are never seen.
 */
fun missProbability(model: FeedModel, days: Double, capacity: Int): Double =
    if (days > 0) missProbabilityOver(model, days, capacity) else 0.0

private fun missProbabilityOver(model: FeedModel, days: Double, capacity: Int): Double {
    val stay = model.beta / (model.beta + days)
    val logStay = ln(stay)
    val logGo = ln(1 - stay)
    val base = logGamma(model.alpha)
    var covered = 0.0
    for (count in 0..capacity) {
        covered += exp(
            logGamma(count + model.alpha) - base - logGamma(count + 1.0) +
                model.alpha * logStay + count * logGo,
        )
    }
    return max(0.0, 1 - covered)
}

/**
 * Longest wait, in milliseconds, from [nowMs] within the minimum and maximum
 * waits for which the chance of missing an item, over the expected activity of
 * that wait, stays under [MISS_PROBABILITY].
 */
fun chooseInterval(model: FeedModel, nowMs: Long): Double {
    val known = if (model.capacity > 0) model.capacity else UNKNOWN_CAPACITY
    val usable = max(1, floor(min(known, MAX_CAPACITY) * BURST_MARGIN).toInt())
    val factors = patternFactors(model.pattern)
    fun isSafe(waitMs: Double): Boolean =
        missProbability(model, activityBetween(factors, nowMs.toDouble(), nowMs + waitMs), usable) <= MISS_PROBABILITY
    return if (!isSafe(MIN_INTERVAL_MS.toDouble())) {
        MIN_INTERVAL_MS.toDouble()
    } else if (isSafe(MAX_INTERVAL_MS.toDouble())) {
        MAX_INTERVAL_MS.toDouble()
    } else {
        var low = MIN_INTERVAL_MS.toDouble()
        var high = MAX_INTERVAL_MS.toDouble()
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2
            if (isSafe(middle)) {
                low = middle
            } else {
                high = middle
            }
        }
        low
    }
}

/**
 * How late a feed's scheduled check may run before a refresh is suggested, in
 * milliseconds. The background checks (about every 15 minutes here, every 5 in
 * the browser extension) normally reach a due feed well within it, so only a
 * feed they missed, as when the phone slept, counts.
 */
const val OVERDUE_MS = 2 * MIN_INTERVAL_MS

/**
 * When the first of these feeds becomes overdue: the earliest next check plus
 * [OVERDUE_MS]; [Long.MAX_VALUE] when there are none. One pass over the next
 * checks, so the caller can work it out on every change and wait once for it.
 */
fun overdueAt(nextChecks: Iterable<Long>): Long {
    val earliest = nextChecks.minOrNull()
    return if (earliest == null) Long.MAX_VALUE else earliest + OVERDUE_MS
}

/** Wait after the given number of failures in a row, doubling each time. */
fun retryInterval(failures: Int): Long =
    min(MAX_RETRY_MS.toDouble(), MIN_INTERVAL_MS * 2.0.pow(failures)).toLong()

/** Longest wait after failures, in milliseconds. */
const val MAX_RETRY_MS = 24L * 60 * 60 * 1000

private val CACHE_SECONDS_PATTERN = Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

private val DELAY_SECONDS_PATTERN = Regex("^\\d+$")
private val HTTP_DATE_PATTERN = Regex(
    "^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} \\d{2}:\\d{2}:\\d{2} GMT$",
)

/**
 * Seconds a `Retry-After` value asks for: whole decimal seconds, or an
 * HTTP-date (`Sun, 06 Nov 1994 08:49:37 GMT`) from now; zero for anything else.
 */
private fun retryAfterSeconds(value: String, nowMs: Long): Double =
    if (DELAY_SECONDS_PATTERN.matches(value)) {
        value.toDoubleOrNull() ?: 0.0
    } else if (HTTP_DATE_PATTERN.matches(value)) {
        val date = parseFeedDate(value, ZoneOffset.UTC)
        if (date == 0L) 0.0 else (date - nowMs) / 1000.0
    } else {
        0.0
    }

/**
 * Milliseconds a site asked us to wait, from `Retry-After` (seconds or a
 * date) or `Cache-Control`, given a header lookup; never below zero.
 */
fun hintFromHeaders(header: (String) -> String?, nowMs: Long = System.currentTimeMillis()): Long {
    val retry = retryAfterSeconds(header("Retry-After")?.trim() ?: "", nowMs)
    val cache = CACHE_SECONDS_PATTERN.find(header("Cache-Control") ?: "")?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val seconds = maxOf(0.0, retry, cache)
    return ceil(min(MAX_INTERVAL_MS.toDouble(), seconds * 1000)).toLong()
}

/** UTC hour of day, 0 to 23, of a millisecond timestamp. */
fun hourOfDay(timeMs: Long): Int = Math.floorMod(Math.floorDiv(timeMs, HOUR_MS), HOURS_PER_DAY.toLong()).toInt()

/** UTC day of the week, Monday being zero, of a millisecond timestamp. */
fun dayOfWeek(timeMs: Long): Int =
    Math.floorMod(Math.floorDiv(timeMs, DAY_MS_LONG) + EPOCH_DAY_OF_WEEK, DAYS_PER_WEEK.toLong()).toInt()

/** UTC day of the month, the 1st being zero, of a millisecond timestamp. */
fun dayOfMonth(timeMs: Long): Int = Instant.ofEpochMilli(timeMs).atOffset(ZoneOffset.UTC).dayOfMonth - 1

private fun counts(values: List<Double>?, length: Int): List<Double> =
    if (values?.size == length) values else List(length) { 0.0 }

/**
 * Fade a pattern over [elapsedMs] with the model's half-life and add one post
 * to each factor's bin for each [published] timestamp; unknown dates (zero)
 * are skipped. A missing pattern starts empty.
 */
fun updatePattern(pattern: PostingPattern?, published: List<Long>, elapsedMs: Long): PostingPattern {
    val keep = 0.5.pow(max(0.0, elapsedMs / DAY_MS) / HALF_LIFE_DAYS)
    val hours = counts(pattern?.hourOfDay, HOURS_PER_DAY).map { count -> count * keep }.toMutableList()
    val days = counts(pattern?.dayOfWeek, DAYS_PER_WEEK).map { count -> count * keep }.toMutableList()
    val dates = counts(pattern?.dayOfMonth, DAYS_PER_MONTH).map { count -> count * keep }.toMutableList()
    for (time in published) {
        if (time > 0) {
            // A time of exactly midnight is most likely a date with no time, so it says nothing about the hour.
            if (time % DAY_MS_LONG != 0L) {
                hours[hourOfDay(time)] += 1.0
            }
            days[dayOfWeek(time)] += 1.0
            dates[dayOfMonth(time)] += 1.0
        }
    }
    return PostingPattern(hours, days, dates)
}

/** The model with the posts at [published] added to its pattern, without fading. */
fun learnPattern(model: FeedModel, published: List<Long>): FeedModel =
    model.copy(pattern = updatePattern(model.pattern, published, 0))

/** Relative intensity of each bin of each factor, averaging one per factor. */
data class PatternFactors(
    /** Per UTC hour. */
    val hourOfDay: List<Double>,
    /** Per UTC day of the week, Monday first. */
    val dayOfWeek: List<Double>,
    /** Per UTC date, the 1st first. */
    val dayOfMonth: List<Double>,
)

private fun normalized(values: List<Double>?, length: Int, smoothing: Double): List<Double> {
    val smoothed = counts(values, length).map { count -> max(0.0, count) + smoothing }
    val mean = smoothed.sum() / length
    return smoothed.map { count -> count / mean }
}

/** The factors a pattern gives, each bin smoothed and each factor averaging one. */
fun patternFactors(pattern: PostingPattern?): PatternFactors =
    PatternFactors(
        hourOfDay = normalized(pattern?.hourOfDay, HOURS_PER_DAY, PATTERN_SMOOTHING),
        dayOfWeek = normalized(pattern?.dayOfWeek, DAYS_PER_WEEK, PATTERN_SMOOTHING),
        dayOfMonth = normalized(pattern?.dayOfMonth, DAYS_PER_MONTH, DAY_OF_MONTH_SMOOTHING),
    )

private fun intensityAt(factors: PatternFactors, timeMs: Long): Double =
    factors.hourOfDay[hourOfDay(timeMs)] * factors.dayOfWeek[dayOfWeek(timeMs)] * factors.dayOfMonth[dayOfMonth(timeMs)]

private fun activityBetween(factors: PatternFactors, fromMs: Double, toMs: Double): Double {
    val stepEnd = min(toMs, fromMs + ACTIVITY_STEP_LIMIT_MS)
    var days = 0.0
    var cursor = fromMs
    while (cursor < stepEnd) {
        val hourEnd = (floor(cursor / HOUR_MS) + 1) * HOUR_MS
        val end = min(hourEnd, stepEnd)
        days += (end - cursor) / DAY_MS * intensityAt(factors, floor(cursor).toLong())
        cursor = end
    }
    return days + max(0.0, toMs - stepEnd) / DAY_MS
}

/**
 * Expected activity from [fromMs] to [toMs], in days: the integral of the
 * feed's relative posting intensity, stepped hour by hour along UTC hours for
 * up to [ACTIVITY_STEP_LIMIT_MS], each later day counting as one. A flat model
 * gives elapsed days; a backward span gives zero.
 */
fun expectedActivity(model: FeedModel, fromMs: Long, toMs: Long): Double =
    activityBetween(patternFactors(model.pattern), fromMs.toDouble(), toMs.toDouble())

/**
 * Chance that a feed has posted at least once since it was last checked:
 * `1 - (beta / (beta + activity))^alpha` over the expected activity in
 * between. A feed never checked ([lastCheckedMs] zero) gets one.
 */
fun newProbability(model: FeedModel, lastCheckedMs: Long, nowMs: Long): Double =
    if (lastCheckedMs <= 0) {
        1.0
    } else {
        1 - (model.beta / (model.beta + expectedActivity(model, lastCheckedMs, nowMs))).pow(model.alpha)
    }

/** Chance a feed holds news, as a refresh counts it. */
fun refreshProbability(schedule: FeedSchedule, nowMs: Long): Double =
    if (schedule.failures > 0) {
        if (schedule.nextFetch <= nowMs) FAILING_PROBABILITY else 0.0
    } else {
        newProbability(schedule.model, schedule.lastChecked, nowMs)
    }

/** Feeds in the order a refresh fetches them. */
data class RefreshPlan<Item>(
    /** Feeds, most likely to hold news first. */
    val ordered: List<Item>,
    /** Chance each feed in [ordered] holds news. */
    val probabilities: List<Double>,
    /** How many of the first [ordered] feeds the refresh waits for. */
    val foreground: Int,
)

/**
 * Order feeds for a refresh, most likely to hold news first, and choose how
 * many to wait for.
 *
 * The refresh waits for the shortest prefix after which the feeds left hold,
 * in expectation, at most [LEFT_OVER_FEEDS] or [LEFT_OVER_SHARE] of all the
 * expected feeds with news, whichever is more; it always waits for every
 * feed at least [LIKELY_PROBABILITY] likely. A failing feed counts as
 * [FAILING_PROBABILITY] when due and zero otherwise. The rest are fetched
 * afterwards.
 */
fun <Item> planRefresh(feeds: List<Item>, nowMs: Long, scheduleOf: (Item) -> FeedSchedule): RefreshPlan<Item> {
    val ranked = feeds
        .map { feed -> feed to refreshProbability(scheduleOf(feed), nowMs) }
        .sortedByDescending { (_, probability) -> probability }
    val probabilities = ranked.map { (_, probability) -> probability }
    val allowance = max(LEFT_OVER_FEEDS, LEFT_OVER_SHARE * probabilities.sum())
    var foreground = probabilities.count { probability -> probability >= LIKELY_PROBABILITY }
    var leftOver = probabilities.drop(foreground).sum()
    while (leftOver > allowance && foreground < probabilities.size) {
        leftOver -= probabilities[foreground]
        foreground += 1
    }
    return RefreshPlan(ranked.map { (feed, _) -> feed }, probabilities, foreground)
}
