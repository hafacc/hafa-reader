package cc.hafa.reader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val RELATIVE_TOLERANCE = 1e-9
private const val ABSOLUTE_TOLERANCE = 1e-12
private const val INTERVAL_TOLERANCE_MS = 1000.0

private fun numbers(element: JsonElement?): List<Double>? = element?.takeUnless { it is JsonNull }?.jsonArray?.map { item -> item.jsonPrimitive.double }

private fun times(element: JsonElement): List<Long> = element.jsonArray.map { item -> item.jsonPrimitive.double.toLong() }

private fun long(element: JsonElement?): Long = checkNotNull(element).jsonPrimitive.double.toLong()

private fun pattern(element: JsonElement?): PostingPattern? =
    element?.takeUnless { it is JsonNull }?.jsonObject?.let { fields ->
        PostingPattern(
            checkNotNull(numbers(fields["hourOfDay"])),
            checkNotNull(numbers(fields["dayOfWeek"])),
            checkNotNull(numbers(fields["dayOfMonth"])),
        )
    }

private fun model(element: JsonElement?): FeedModel {
    val fields = checkNotNull(element).jsonObject
    return FeedModel(
        alpha = fields.getValue("alpha").jsonPrimitive.double,
        beta = fields.getValue("beta").jsonPrimitive.double,
        capacity = fields.getValue("capacity").jsonPrimitive.int,
        pattern = pattern(fields["pattern"]),
    )
}

private fun json(values: List<Number>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun json(pattern: PostingPattern?): JsonElement =
    if (pattern == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("hourOfDay", json(pattern.hourOfDay))
            put("dayOfWeek", json(pattern.dayOfWeek))
            put("dayOfMonth", json(pattern.dayOfMonth))
        }
    }

private fun json(model: FeedModel): JsonElement =
    buildJsonObject {
        put("alpha", model.alpha)
        put("beta", model.beta)
        put("capacity", model.capacity)
        model.pattern?.let { pattern -> put("pattern", json(pattern)) }
    }

/** One fed in to `planRefresh`, with its id. */
private data class PlanFeed(val id: String, val schedule: FeedSchedule)

/** Every case of `shared/fixtures/schedule.json`, which the TypeScript schedule produced. */
class ScheduleFixtureTest {
    private val sections: Map<String, JsonArray> = run {
        val text = checkNotNull(javaClass.classLoader.getResource("schedule.json")) { "missing fixture schedule.json" }.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        root.getValue("sections").jsonObject.mapValues { (_, cases) -> cases.jsonArray }
    }

    private fun compute(section: String, input: JsonObject): JsonElement =
        when (section) {
            "bins" -> {
                val time = long(input["timeMs"])
                buildJsonObject {
                    put("hourOfDay", hourOfDay(time))
                    put("dayOfWeek", dayOfWeek(time))
                    put("dayOfMonth", dayOfMonth(time))
                }
            }
            "expectedActivity" -> JsonPrimitive(expectedActivity(model(input["model"]), long(input["fromMs"]), long(input["toMs"])))
            "newProbability" -> JsonPrimitive(newProbability(model(input["model"]), long(input["lastCheckedMs"]), long(input["nowMs"])))
            "missProbability" ->
                JsonPrimitive(
                    missProbability(model(input["model"]), input.getValue("days").jsonPrimitive.double, input.getValue("capacity").jsonPrimitive.int),
                )
            "chooseInterval" -> JsonPrimitive(chooseInterval(model(input["model"]), long(input["nowMs"])))
            "planRefresh" -> {
                val feeds = input.getValue("feeds").jsonArray.map { item ->
                    val fields = item.jsonObject
                    PlanFeed(
                        fields.getValue("id").jsonPrimitive.content,
                        FeedSchedule(
                            model = model(fields["model"]),
                            lastChecked = long(fields["lastChecked"]),
                            nextFetch = long(fields["nextFetch"]),
                            failures = fields.getValue("failures").jsonPrimitive.int,
                        ),
                    )
                }
                val plan = planRefresh(feeds, long(input["nowMs"])) { feed -> feed.schedule }
                buildJsonObject {
                    put("ordered", JsonArray(plan.ordered.map { feed -> JsonPrimitive(feed.id) }))
                    put("probabilities", json(plan.probabilities))
                    put("foreground", plan.foreground)
                }
            }
            "hintFromHeaders" -> {
                val headers = input.getValue("headers").jsonObject.entries.associate { (name, value) -> name.lowercase() to value.jsonPrimitive.content }
                JsonPrimitive(hintFromHeaders({ name -> headers[name.lowercase()] }, long(input["nowMs"])))
            }
            "retryInterval" -> JsonPrimitive(retryInterval(input.getValue("failures").jsonPrimitive.int))
            "observe" ->
                json(
                    observe(
                        model(input["model"]),
                        input.getValue("newItems").jsonPrimitive.int,
                        long(input["fromMs"]),
                        long(input["toMs"]),
                        input.getValue("windowSize").jsonPrimitive.int,
                    ),
                )
            "observeWindow" -> json(observeWindow(model(input["model"]), times(input.getValue("published"))))
            "updatePattern" -> json(updatePattern(pattern(input["pattern"]), times(input.getValue("published")), long(input["elapsedMs"])))
            "learnPattern" -> json(learnPattern(model(input["model"]), times(input.getValue("published"))))
            "patternFactors" ->
                patternFactors(pattern(input["pattern"])).let { factors ->
                    buildJsonObject {
                        put("hourOfDay", json(factors.hourOfDay))
                        put("dayOfWeek", json(factors.dayOfWeek))
                        put("dayOfMonth", json(factors.dayOfMonth))
                    }
                }
            else -> fail("unknown section $section")
        }

    private fun numbersMatch(expected: Double, actual: Double, absolute: Double): Boolean =
        abs(expected - actual) <= max(absolute, RELATIVE_TOLERANCE * max(abs(expected), abs(actual)))

    /** Where [actual] differs from [expected], or null when they match. */
    private fun difference(expected: JsonElement, actual: JsonElement, path: String, absolute: Double): String? =
        if (expected is JsonObject && actual is JsonObject) {
            if (expected.keys != actual.keys) {
                "$path: keys ${expected.keys} != ${actual.keys}"
            } else {
                expected.keys.firstNotNullOfOrNull { key -> difference(expected.getValue(key), actual.getValue(key), "$path.$key", absolute) }
            }
        } else if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) {
                "$path: length ${expected.size} != ${actual.size}"
            } else {
                expected.indices.firstNotNullOfOrNull { index -> difference(expected[index], actual[index], "$path[$index]", absolute) }
            }
        } else if (expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && expected !is JsonNull && actual !is JsonNull) {
            val wanted = expected.content.toDouble()
            val got = actual.content.toDouble()
            if (numbersMatch(wanted, got, absolute)) null else "$path: $wanted != $got"
        } else {
            if (expected == actual) null else "$path: $expected != $actual"
        }

    @Test
    fun everyCaseMatchesTheTypeScript() {
        assertTrue(sections.isNotEmpty())
        val failures = mutableListOf<String>()
        var count = 0
        for ((section, cases) in sections) {
            for ((index, case) in cases.withIndex()) {
                count += 1
                val fields = case.jsonObject
                val expected = fields.getValue("output")
                val actual = compute(section, fields.getValue("input").jsonObject)
                val absolute = if (section == "chooseInterval") INTERVAL_TOLERANCE_MS else ABSOLUTE_TOLERANCE
                difference(expected, actual, "$section[$index]", absolute)?.let(failures::add)
            }
        }
        assertTrue(count > 0)
        assertEquals(emptyList(), failures, "${failures.size} of $count cases differ")
    }
}
