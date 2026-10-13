package cc.hafa.reader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** The parse and id fixtures in `shared/fixtures`, which every client's tests read. */
class SharedFixtureTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)) { "missing fixture $name" }.readText()

    private fun json(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    @Test
    fun `feeds parse as the fixtures say`() {
        val cases = json("parse.json").getValue("cases").jsonArray
        for (case in cases.map { item -> item.jsonObject }) {
            val input = case.getValue("input").jsonObject
            val output = case.getValue("output").jsonObject
            val file = input.text("file")
            val parsed = parseFeed(fixture(file), input.text("feedUrl"))
            val expected = ParsedFeed(
                title = output.text("title"),
                siteUrl = output.text("siteUrl"),
                iconUrl = output.text("iconUrl"),
                entries = output.getValue("entries").jsonArray.map { item ->
                    val entry = item.jsonObject
                    FeedEntry(
                        guid = entry.text("guid"),
                        title = entry.text("title"),
                        url = entry.text("url"),
                        published = entry.getValue("published").jsonPrimitive.long,
                        author = entry.text("author"),
                        imageUrl = entry.text("imageUrl"),
                        summary = entry.text("summary"),
                        categories = entry.getValue("categories").jsonArray.map { category -> category.jsonPrimitive.content },
                        html = entry.text("html"),
                    )
                },
            )
            assertEquals(expected, parsed, file)
        }
    }

    @Test
    fun `feed and entry ids match the fixtures`() {
        val ids = json("ids.json")
        for (case in ids.getValue("feeds").jsonArray.map { item -> item.jsonObject }) {
            val url = case.getValue("input").jsonObject.text("url")
            assertEquals(case.text("output"), feedId(url), url)
        }
        for (case in ids.getValue("entries").jsonArray.map { item -> item.jsonObject }) {
            val input = case.getValue("input").jsonObject
            assertEquals(case.text("output"), entryId(input.text("feedId"), input.text("guid")), input.text("guid"))
        }
    }
}
