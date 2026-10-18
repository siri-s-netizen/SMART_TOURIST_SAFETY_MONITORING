package com.touristsafety.app.authority

import kotlin.math.floor

/**
 * A tiny, dependency-free JSON reader and writer.
 *
 * Hand-rolled for the same reason [com.touristsafety.app.outbox.OutboxPayloads] is: `org.json` is an
 * Android-stub class, so anything that touches it cannot be tested on the plain JVM. The whole
 * authority client — request bodies, response parsing, error extraction — therefore has to be pure
 * Kotlin for its tests to mean anything, and this is that foundation.
 *
 * It is deliberately a *complete* parser rather than a substring hunt: the responses come from a
 * server, so a reordered field, an escaped quote in a tourist's name, or a `null` where a number was
 * expected must all be handled without either crashing or silently inventing a value.
 */
sealed interface JsonValue {

    object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

/** Thrown when a response body is not valid JSON. Carries the offset so a failure is diagnosable. */
class JsonParseException(message: String) : IllegalArgumentException(message)

// ---------------------------------------------------------------------------- accessors

fun JsonValue.asObjectOrNull(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue.asArrayOrNull(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.Str)?.value

fun JsonValue.asDoubleOrNull(): Double? = (this as? JsonValue.Num)?.value

fun JsonValue.asLongOrNull(): Long? {
    val number = asDoubleOrNull() ?: return null
    if (number.isNaN() || number.isInfinite() || floor(number) != number) return null
    if (number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()) return null
    return number.toLong()
}

fun JsonValue.asIntOrNull(): Int? = asLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

fun JsonValue.asBooleanOrNull(): Boolean? = (this as? JsonValue.Bool)?.value

/** A field lookup that never throws: a missing key, a wrong type and an explicit null all give null. */
fun JsonValue.field(name: String): JsonValue =
    (this as? JsonValue.Obj)?.fields?.get(name) ?: JsonValue.Null

fun JsonValue.string(name: String): String? = field(name).asStringOrNull()

fun JsonValue.long(name: String): Long? = field(name).asLongOrNull()

fun JsonValue.int(name: String): Int? = field(name).asIntOrNull()

fun JsonValue.double(name: String): Double? = field(name).asDoubleOrNull()

fun JsonValue.boolean(name: String): Boolean? = field(name).asBooleanOrNull()

fun JsonValue.array(name: String): List<JsonValue> = field(name).asArrayOrNull()?.items ?: emptyList()

fun JsonValue.objectOrNull(name: String): JsonValue.Obj? = field(name).asObjectOrNull()

// ---------------------------------------------------------------------------- reader / writer

object Json {

    // ---------------------------------------------------------------- factories

    fun str(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun int(value: Long?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toDouble())

    fun int(value: Int?): JsonValue = int(value?.toLong())

    fun real(value: Double?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value)

    fun bool(value: Boolean?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Bool(value)

    fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf(*fields))

    fun obj(fields: Map<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(fields))

    fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

    // ---------------------------------------------------------------- writing

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(numberText(value.value))
            is JsonValue.Str -> writeString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeTo(out, item)
                }
                out.append(']')
            }

            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, field) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeTo(out, field)
                }
                out.append('}')
            }
        }
    }

    /**
     * JSON has no integer type, but the backend's validators call `_integer()` which rejects a value
     * like `5.0` for a field declared whole (`"zoneId must be a whole number"`). So a whole-valued
     * number is emitted without a decimal point, which is what keeps a duration in millis acceptable
     * to both `_number()` and `_integer()`.
     */
    private fun numberText(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "null"
        floor(value) == value && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
        else -> value.toString()
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }

    // ---------------------------------------------------------------- reading

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw JsonParseException("trailing content at offset ${reader.offset}")
        return value
    }

    /** Parses and returns null instead of throwing; for callers that can degrade gracefully. */
    fun parseOrNull(text: String?): JsonValue? =
        if (text.isNullOrBlank()) null else runCatching { parse(text) }.getOrNull()

    private class Reader(private val text: String) {
        var offset = 0

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isJsonWhitespace()) offset++
        }

        fun readValue(): JsonValue {
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val character = text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (character == '-' || character.isDigit()) readNumber() else {
                    throw JsonParseException("unexpected '$character' at offset $offset")
                }
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return JsonValue.Obj(fields)
                    }

                    else -> throw JsonParseException("expected ',' or '}' at offset $offset")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return JsonValue.Arr(items)
                    }

                    else -> throw JsonParseException("expected ',' or ']' at offset $offset")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                when (val character = text[offset++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        when (val escaped = text[offset++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> out.append(readUnicodeEscape())
                            else -> throw JsonParseException("bad escape '\\$escaped' at offset $offset")
                        }
                    }

                    else -> out.append(character)
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            if (offset + 4 > text.length) throw JsonParseException("truncated \\u escape")
            val hex = text.substring(offset, offset + 4)
            offset += 4
            return hex.toIntOrNull(16)?.toChar()
                ?: throw JsonParseException("bad \\u escape '$hex' at offset ${offset - 4}")
        }

        private fun readNumber(): JsonValue.Num {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd() && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            val raw = text.substring(start, offset)
            val parsed = raw.toDoubleOrNull()
                ?: throw JsonParseException("bad number '$raw' at offset $start")
            return JsonValue.Num(parsed)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, offset)) {
                throw JsonParseException("expected '$literal' at offset $offset")
            }
            offset += literal.length
            return value
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[offset]

        private fun expect(character: Char) {
            if (atEnd() || text[offset] != character) {
                throw JsonParseException("expected '$character' at offset $offset")
            }
            offset++
        }
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
