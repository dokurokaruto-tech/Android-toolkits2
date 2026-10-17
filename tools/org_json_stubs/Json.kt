// Standalone harness only; replaces org.json (gradle uses org.json:json:20240303).
package org.json

class JSONObject {
    internal val values = LinkedHashMap<String, Any?>()

    fun put(name: String, value: Any?): JSONObject {
        values[name] = value
        return this
    }

    fun keys(): Iterator<String> = values.keys.iterator()

    fun optInt(key: String, fallback: Int): Int = (values[key] as? Number)?.toInt() ?: fallback

    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject

    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray

    override fun toString(): String =
        values.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
            "${JsonText.quote(name)}:${JsonText.render(value)}"
        }
}

class JSONArray {
    internal val items = mutableListOf<Any?>()

    constructor()

    constructor(raw: String) {
        val parsed = JsonParser(raw).parse()
        if (parsed !is JSONArray) {
            throw IllegalArgumentException("not a JSON array")
        }
        items.addAll(parsed.items)
    }

    fun put(value: Any?): JSONArray {
        items.add(value)
        return this
    }

    fun length(): Int = items.size

    fun optString(index: Int): String = items.getOrNull(index)?.toString() ?: ""

    fun optJSONObject(index: Int): JSONObject? = items.getOrNull(index) as? JSONObject

    fun optJSONArray(index: Int): JSONArray? = items.getOrNull(index) as? JSONArray

    override fun toString(): String =
        items.joinToString(separator = ",", prefix = "[", postfix = "]") { JsonText.render(it) }
}

internal object JsonText {
    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    fun render(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject, is JSONArray, is Boolean, is Number -> value.toString()
        else -> quote(value.toString())
    }
}

internal class JsonParser(private val raw: String) {
    private var pos = 0

    fun parse(): Any? {
        val value = parseValue()
        skipSpaces()
        if (pos != raw.length) {
            throw IllegalArgumentException("trailing input at $pos")
        }
        return value
    }

    private fun parseValue(): Any? {
        skipSpaces()
        val c = peek() ?: throw IllegalArgumentException("unexpected end of input")
        return when (c) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            else -> parseLiteral()
        }
    }

    private fun parseObject(): JSONObject {
        val result = JSONObject()
        pos++
        skipSpaces()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            val name = parseString()
            skipSpaces()
            expect(':')
            result.put(name, parseValue())
            skipSpaces()
            when (val c = next()) {
                ',' -> Unit
                '}' -> return result
                else -> throw IllegalArgumentException("unexpected '$c' in object")
            }
        }
    }

    private fun parseArray(): JSONArray {
        val result = JSONArray()
        pos++
        skipSpaces()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result.put(parseValue())
            skipSpaces()
            when (val c = next()) {
                ',' -> Unit
                ']' -> return result
                else -> throw IllegalArgumentException("unexpected '$c' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val text = StringBuilder()
        while (true) {
            val c = next()
            if (c == '"') {
                return text.toString()
            }
            if (c != '\\') {
                text.append(c)
                continue
            }
            when (val escaped = next()) {
                '"', '\\', '/' -> text.append(escaped)
                'n' -> text.append('\n')
                'r' -> text.append('\r')
                't' -> text.append('\t')
                'b' -> text.append('\b')
                'f' -> text.append('\u000C')
                'u' -> {
                    val hex = raw.substring(pos, pos + HEX_LENGTH)
                    pos += HEX_LENGTH
                    text.append(hex.toInt(HEX_RADIX).toChar())
                }
                else -> throw IllegalArgumentException("bad escape \\$escaped")
            }
        }
    }

    private fun parseLiteral(): Any? {
        val start = pos
        while (pos < raw.length && !raw[pos].isWhitespace() && raw[pos] !in ",:]}") {
            pos++
        }
        return when (val word = raw.substring(start, pos)) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> word.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad literal '$word'")
        }
    }

    private fun skipSpaces() {
        while (pos < raw.length && raw[pos].isWhitespace()) {
            pos++
        }
    }

    private fun peek(): Char? = raw.getOrNull(pos)

    private fun next(): Char =
        raw.getOrElse(pos++) { throw IllegalArgumentException("unexpected end of input") }

    private fun expect(c: Char) {
        if (next() != c) {
            throw IllegalArgumentException("expected '$c'")
        }
    }

    private companion object {
        const val HEX_LENGTH = 4
        const val HEX_RADIX = 16
    }
}
