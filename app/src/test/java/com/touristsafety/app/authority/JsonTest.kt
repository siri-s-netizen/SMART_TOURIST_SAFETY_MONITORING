package com.touristsafety.app.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON reader/writer.
 *
 * It stands in for `org.json`, which is an Android stub that returns nulls on the plain JVM, so it has
 * to be correct enough to trust with responses from a server: escaped quotes in a tourist's name, a
 * `null` where a number was expected, and trailing garbage after a valid document.
 */
class JsonTest {

    @Test
    fun `round trips nested objects arrays and primitives`() {
        val value = Json.obj(
            "name" to Json.str("Ada"),
            "count" to Json.int(7L),
            "ratio" to Json.real(0.5),
            "ok" to Json.bool(true),
            "missing" to Json.str(null),
            "list" to Json.arr(listOf(Json.int(1), Json.str("two"))),
            "nested" to Json.obj("inner" to Json.str("x")),
        )

        val text = Json.write(value)
        val parsed = Json.parse(text)

        assertEquals("Ada", parsed.string("name"))
        assertEquals(7L, parsed.long("count"))
        assertEquals(0.5, parsed.double("ratio")!!, 1e-9)
        assertEquals(true, parsed.boolean("ok"))
        assertNull(parsed.string("missing"))
        assertEquals(2, parsed.array("list").size)
        assertEquals("x", parsed.objectOrNull("nested")?.string("inner"))
    }

    @Test
    fun `writes a whole-valued number without a decimal point`() {
        // The backend's _integer() rejects 5.0 for a field declared whole.
        assertTrue(Json.write(Json.int(5L)).contains("5"))
        assertEquals("5", Json.write(Json.int(5L)))
        assertEquals("5.5", Json.write(Json.real(5.5)))
    }

    @Test
    fun `escapes quotes backslashes and control characters`() {
        val text = Json.write(Json.str("a\"b\\c\nd\te"))

        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", text)
        assertEquals("a\"b\\c\nd\te", Json.parse(text).asStringOrNull())
    }

    @Test
    fun `reads escaped unicode`() {
        assertEquals("é", Json.parse("\"\\u00e9\"").asStringOrNull())
    }

    @Test
    fun `rejects trailing content`() {
        assertThrows(JsonParseException::class.java) { Json.parse("{\"a\":1} oops") }
    }

    @Test
    fun `rejects a truncated document rather than guessing`() {
        assertThrows(JsonParseException::class.java) { Json.parse("{\"a\":") }
        assertThrows(JsonParseException::class.java) { Json.parse("{\"a\"") }
    }

    @Test
    fun `parseOrNull degrades instead of throwing`() {
        assertNull(Json.parseOrNull("<html>not json</html>"))
        assertNull(Json.parseOrNull(null))
        assertNull(Json.parseOrNull("   "))
        assertEquals(1L, Json.parseOrNull("1")?.asLongOrNull())
    }

    @Test
    fun `a field of the wrong type reads as null rather than throwing`() {
        val parsed = Json.parse("{\"latitude\":\"north\",\"count\":3.5}")

        assertNull(parsed.double("latitude"))
        // 3.5 is not a whole number, so it is not an Int either.
        assertNull(parsed.int("count"))
        assertEquals(3.5, parsed.double("count")!!, 1e-9)
    }

    @Test
    fun `reads a realistic sync response`() {
        val text = """
            {"results":[{"dedupeKey":"sos:1","status":"DELIVERED","serverEventId":"evt_ab","acknowledgementId":"evt_ab"},
                        {"dedupeKey":"sos:2","status":"REJECTED","reason":"latitude must be between -90 and 90"}],
             "serverTimeMillis":1730000000000}
        """.trimIndent()

        val results = Json.parse(text).array("results")

        assertEquals(2, results.size)
        assertEquals("DELIVERED", results[0].string("status"))
        assertEquals("evt_ab", results[0].string("serverEventId"))
        assertEquals("latitude must be between -90 and 90", results[1].string("reason"))
        assertEquals(1730000000000L, Json.parse(text).long("serverTimeMillis"))
    }
}
