package io.github.dohex.openrose.androidintegration.wire

fun parseWire(text: String): Wire {
    val parser = WireParser(text)
    val result = parser.value(0)
    parser.skipWhitespace()
    if (!parser.atEnd) throw WireFormatException("多余内容")
    return result
}

private class WireParser(text: String) {
    private val cursor = WireCursor(text)
    val atEnd get() = cursor.atEnd
    fun skipWhitespace() = cursor.skipWhitespace()

    fun value(depth: Int): Wire {
        if (depth > MAX_DEPTH) throw WireFormatException("嵌套过深")
        skipWhitespace()
        if (atEnd) throw WireFormatException("意外结束")
        return when (cursor.peek()) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> Wire.Str(cursor.string())
            't' -> cursor.literal("true", Wire.Bool(true))
            'f' -> cursor.literal("false", Wire.Bool(false))
            'n' -> cursor.literal("null", Wire.Null)
            else -> cursor.number()
        }
    }

    private fun obj(depth: Int): Wire {
        cursor.next()
        val fields = linkedMapOf<String, Wire>()
        skipWhitespace()
        if (cursor.peek() == '}') {
            cursor.next()
            return Wire.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = cursor.fieldName()
            skipWhitespace()
            cursor.expect(':')
            if (fields.put(key, value(depth + 1)) != null) throw WireFormatException("重复字段 $key")
            skipWhitespace()
            when (cursor.next()) {
                ',' -> continue
                '}' -> return Wire.Obj(fields)
                else -> throw WireFormatException("需要 , 或 }")
            }
        }
    }

    private fun arr(depth: Int): Wire {
        cursor.next()
        val items = mutableListOf<Wire>()
        skipWhitespace()
        if (cursor.peek() == ']') {
            cursor.next()
            return Wire.Arr(items)
        }
        while (true) {
            items += value(depth + 1)
            skipWhitespace()
            when (cursor.next()) {
                ',' -> continue
                ']' -> return Wire.Arr(items)
                else -> throw WireFormatException("需要 , 或 ]")
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}

private class WireCursor(private val text: String) {
    private var index = 0
    val atEnd get() = index >= text.length

    fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (atEnd) throw WireFormatException("字符串未结束")
            when (val char = text[index++]) {
                '"' -> return out.toString()
                '\\' -> out.append(escaped())
                else -> out.append(char)
            }
        }
    }

    private fun escaped(): Char {
        if (atEnd) throw WireFormatException("转义未结束")
        return when (val escaped = next()) {
            '"', '\\', '/' -> escaped
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicode()
            else -> throw WireFormatException("未知转义 $escaped")
        }
    }

    private fun unicode(): Char {
        if (index + 4 > text.length) throw WireFormatException("Unicode 转义不完整")
        val char = text.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: throw WireFormatException("Unicode 转义无效")
        index += 4
        return char
    }

    fun fieldName(): String {
        if (peek() != '"') throw WireFormatException("需要字段名")
        return string()
    }

    fun number(): Wire {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index].isDigit()) index++
        val token = text.substring(start, index)
        return Wire.Num(token.toLongOrNull() ?: throw WireFormatException("无效数字"))
    }

    fun literal(word: String, value: Wire): Wire {
        if (!text.startsWith(word, index)) throw WireFormatException("无效字面量")
        index += word.length
        return value
    }

    fun peek(): Char? = text.getOrNull(index)
    fun next(): Char = text.getOrNull(index++) ?: throw WireFormatException("意外结束")
    fun expect(char: Char) {
        if (next() != char) throw WireFormatException("需要 $char")
    }
}
