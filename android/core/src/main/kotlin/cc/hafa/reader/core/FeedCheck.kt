package cc.hafa.reader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Time a fetch gets, in milliseconds, unless a refresh is waiting for it. */
const val FETCH_TIMEOUT_MS = 20_000L

/** Reads an address over the network. */
fun interface FeedFetcher {
    /**
     * Fetch [url], sending [etag] and [lastModified] so an unchanged answer is
     * a 304, giving up after [timeoutMs].
     */
    suspend fun fetch(url: String, etag: String, lastModified: String, timeoutMs: Long): FetchOutcome
}

/** Fetch with the usual time allowed. */
suspend fun FeedFetcher.fetch(url: String, etag: String, lastModified: String): FetchOutcome =
    fetch(url, etag, lastModified, FETCH_TIMEOUT_MS)

/**
 * Sent as the browser the web app's extension fetches through would, since
 * some sites (Reddit, sites behind Cloudflare) refuse unknown clients.
 */
const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

/** Largest answer read, in bytes. */
const val MAX_BODY_BYTES = 10L * 1024 * 1024

/**
 * Send a stored validator, unless the site once sent one a request cannot
 * carry (such as non-ASCII text); then the fetch is simply unconditional.
 */
private fun Request.Builder.validator(name: String, value: String) {
    if (value.isNotEmpty()) {
        try {
            header(name, value)
        } catch (_: IllegalArgumentException) {
            removeHeader(name)
        }
    }
}

/** Most redirects off https followed at their https addresses in one fetch, as many as browsers follow in all. */
private const val MAX_UPGRADED_REDIRECTS = 20

/** [url] at https, its port moved from http's default to https's, as a browser upgrading an insecure request does. */
private fun upgradedUrl(url: HttpUrl): HttpUrl =
    if (url.isHttps) {
        url
    } else {
        url.newBuilder().scheme("https").port(if (url.port == HTTP_PORT) HTTPS_PORT else url.port).build()
    }

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

/**
 * A [FeedFetcher] over OkHttp; a failed request has status zero, and an answer
 * over [MAX_BODY_BYTES] has no text. Plain http addresses are refused unless
 * [httpsOnly] is off, which only tests against a local server do. A redirect
 * from https to plain http is followed at its https address instead, as the
 * web's extension does, so no plain http request is ever sent.
 */
class HttpFeedFetcher(
    client: OkHttpClient,
    private val httpsOnly: Boolean = true,
) : FeedFetcher {
    // A redirect between https and plain http comes back unfollowed, to be followed here at https.
    private val client = client.newBuilder().followSslRedirects(!httpsOnly).build()

    override suspend fun fetch(url: String, etag: String, lastModified: String, timeoutMs: Long): FetchOutcome {
        val address = url.toHttpUrlOrNull()
        return if (address == null) {
            FetchOutcome(status = 0)
        } else if (httpsOnly && !address.isHttps) {
            FetchOutcome(status = 0, insecure = true)
        } else withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var next: HttpUrl = address
            var outcome: FetchOutcome? = null
            var hops = 0
            try {
                while (outcome == null) {
                    val request = Request.Builder().url(next).apply {
                        header("User-Agent", USER_AGENT)
                        header("Accept", "*/*")
                        validator("If-None-Match", etag)
                        validator("If-Modified-Since", lastModified)
                    }.build()
                    val call = client.newCall(request)
                    call.timeout().timeout(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    call.executeAsync().use { response ->
                        val location = response.header("Location")?.let { target -> response.request.url.resolve(target) }
                        if (httpsOnly && response.isRedirect && location != null) {
                            if (hops < MAX_UPGRADED_REDIRECTS) {
                                hops += 1
                                next = upgradedUrl(location)
                            } else {
                                outcome = FetchOutcome(status = 0)
                            }
                        } else {
                            outcome = answerOf(response)
                        }
                    }
                }
            } catch (_: InterruptedIOException) {
                outcome = FetchOutcome(status = 0, timedOut = true)
            } catch (_: IOException) {
                outcome = FetchOutcome(status = 0)
            }
            checkNotNull(outcome)
        }
    }

    /** What an answer says; a body over the limit is left unread, so it counts as no readable feed. */
    private fun answerOf(response: Response): FetchOutcome {
        val text = if (response.code == 200) {
            if (response.body.source().request(MAX_BODY_BYTES + 1)) {
                ""
            } else {
                decodeBody(response.body.bytes(), response.header("Content-Type") ?: "")
            }
        } else {
            ""
        }
        return FetchOutcome(
            status = response.code,
            text = text,
            url = response.request.url.toString(),
            etag = response.header("ETag") ?: "",
            lastModified = response.header("Last-Modified") ?: "",
            hint = hintFromHeaders(response::header),
        )
    }
}

/** What one check of a feed found. */
data class CheckResult(
    /** The schedule after the check. */
    val schedule: FeedSchedule,
    /** The feed's contents, when they changed since the last check. */
    val pulled: ParsedFeed?,
)

/** Where the time since the last check starts; a first look starts now. */
private fun lastCheckOr(schedule: FeedSchedule, now: Long): Long =
    if (schedule.lastChecked == 0L) now else schedule.lastChecked

private fun learn(schedule: FeedSchedule, parsed: ParsedFeed, now: Long): FeedModel =
    if (schedule.lastChecked == 0L) {
        val published = parsed.entries.map { entry -> entry.published }
        learnPattern(observeWindow(schedule.model, published), published)
    } else {
        val known = schedule.guids.toSet()
        val added = parsed.entries.filter { entry -> entry.guid !in known }
        val observed = observe(schedule.model, added.size, lastCheckOr(schedule, now), now, parsed.entries.size)
        learnPattern(observed, added.map { entry -> entry.published })
    }

private fun succeeded(schedule: FeedSchedule, now: Long, model: FeedModel, hint: Long): FeedSchedule =
    schedule.copy(
        model = model,
        lastChecked = now,
        nextFetch = now + max(chooseInterval(model, now).toLong(), hint),
        failures = 0,
        problem = "",
    )

private fun failed(schedule: FeedSchedule, now: Long, problem: FeedProblem, hint: Long): FeedSchedule =
    schedule.copy(
        failures = schedule.failures + 1,
        nextFetch = now + max(retryInterval(schedule.failures), hint),
        problem = problem.code,
    )

private fun quiet(schedule: FeedSchedule, now: Long, outcome: FetchOutcome): FeedSchedule =
    succeeded(schedule, now, observe(schedule.model, 0, lastCheckOr(schedule, now), now, 0), outcome.hint)

private fun feedDetails(parsed: ParsedFeed): String = listOf(parsed.title, parsed.siteUrl, parsed.iconUrl).joinToString("\n")

private fun changed(schedule: FeedSchedule, outcome: FetchOutcome, parsed: ParsedFeed, now: Long, hash: String): CheckResult {
    val known = schedule.guids.toSet()
    val details = feedDetails(parsed)
    val added = parsed.entries.count { entry -> entry.guid !in known }
    val news = schedule.hash.isEmpty() || details != schedule.details || added > 0
    // Every item new means more may have been missed, so look again soon to measure the rate.
    val saturated = known.isNotEmpty() && added > 0 && added == parsed.entries.size
    val scheduled = succeeded(schedule, now, learn(schedule, parsed, now), outcome.hint)
    val updated = scheduled.copy(
        nextFetch = if (saturated) now + max(MIN_INTERVAL_MS, outcome.hint) else scheduled.nextFetch,
        hash = hash,
        etag = outcome.etag,
        lastModified = outcome.lastModified,
        guids = parsed.entries.map { entry -> entry.guid },
        details = details,
    )
    return CheckResult(updated, if (news) parsed else null)
}

/**
 * Fold one fetch of a feed into its schedule.
 *
 * A 304 or an unchanged body adds a quiet check and keeps the answer's
 * validators. A changed body is parsed against the address it came from, its
 * new items are counted, and it is handed on only when it has a new item, new
 * details, or the feed was reset. Anything else is a failure, retried after the
 * longer of the doubling backoff and the site's requested wait.
 */
fun checkOutcome(schedule: FeedSchedule, url: String, outcome: FetchOutcome, now: Long): CheckResult =
    if (outcome.status == 304) {
        CheckResult(quiet(schedule, now, outcome), null)
    } else if (outcome.status == 200) {
        val hash = sha256Hex(outcome.text)
        if (hash == schedule.hash) {
            CheckResult(quiet(schedule, now, outcome).copy(etag = outcome.etag, lastModified = outcome.lastModified), null)
        } else {
            val parsed = try {
                parseFeed(outcome.text, outcome.url.ifEmpty { url })
            } catch (_: NotAFeedException) {
                null
            }
            if (parsed == null) {
                CheckResult(failed(schedule, now, FeedProblem.Unreadable, outcome.hint), null)
            } else {
                changed(schedule, outcome, parsed, now, hash)
            }
        }
    } else {
        val problem = if (outcome.insecure) {
            FeedProblem.NotHttps
        } else if (outcome.status == 0) {
            FeedProblem.NoAnswer
        } else {
            FeedProblem.Status(outcome.status)
        }
        CheckResult(failed(schedule, now, problem, outcome.hint), null)
    }

/** Failures in a row before a feed shows as failing. */
const val FAILURES_SHOWN = 2

/** The problem shown for a feed: only after [FAILURES_SHOWN] failures in a row. */
fun shownProblem(schedule: FeedSchedule): String =
    if (schedule.failures >= FAILURES_SHOWN) schedule.problem else ""
