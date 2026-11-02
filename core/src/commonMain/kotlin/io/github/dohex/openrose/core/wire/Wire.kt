package io.github.dohex.openrose.core.wire

/** Raised for any malformed or unexpected wire content; callers treat it as a rejected message. */
class WireFormatException(message: String) : Exception(message)

/**
 * A minimal JSON-shaped tree for the authenticated IPC payloads. Platform bindings carry the
 * rendered text, so the same codec is exercised by tests and by the Android Binder boundary.
 */
sealed interface Wire {
    data class Obj(val fields: Map<String, Wire>) : Wire
    data class Arr(val items: List<Wire>) : Wire
    data class Str(val value: String) : Wire
    data class Num(val value: Long) : Wire
    data class Bool(val value: Boolean) : Wire
    data object Null : Wire
}

fun obj(vararg fields: Pair<String, Wire?>): Wire.Obj =
    Wire.Obj(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

fun str(value: String): Wire = Wire.Str(value)
fun num(value: Int): Wire = Wire.Num(value.toLong())
fun num(value: Long): Wire = Wire.Num(value)
fun bool(value: Boolean): Wire = Wire.Bool(value)
fun arr(items: Iterable<Wire>): Wire = Wire.Arr(items.toList())

fun Wire.asObj(): Wire.Obj = this as? Wire.Obj ?: throw WireFormatException("需要对象")
fun Wire.asArr(): List<Wire> = (this as? Wire.Arr)?.items ?: throw WireFormatException("需要数组")
fun Wire.asStr(): String = (this as? Wire.Str)?.value ?: throw WireFormatException("需要字符串")
fun Wire.asLong(): Long = (this as? Wire.Num)?.value ?: throw WireFormatException("需要数字")
fun Wire.asInt(): Int = asLong().also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw WireFormatException("数字越界") }.toInt()
fun Wire.asBool(): Boolean = (this as? Wire.Bool)?.value ?: throw WireFormatException("需要布尔值")

fun Wire.Obj.field(key: String): Wire = fields[key] ?: throw WireFormatException("缺少字段 $key")
fun Wire.Obj.optional(key: String): Wire? = fields[key]?.takeUnless { it == Wire.Null }
fun Wire.Obj.str(key: String): String = field(key).asStr()
fun Wire.Obj.int(key: String): Int = field(key).asInt()
fun Wire.Obj.long(key: String): Long = field(key).asLong()
fun Wire.Obj.bool(key: String): Boolean = field(key).asBool()
fun Wire.Obj.arr(key: String): List<Wire> = field(key).asArr()

inline fun <reified E : Enum<E>> Wire.asEnum(): E {
    val name = asStr()
    return enumValues<E>().firstOrNull { it.name == name } ?: throw WireFormatException("未知枚举值 $name")
}

fun Wire.render(): String = buildString { render(this@render) }

private fun StringBuilder.render(node: Wire) {
    when (node) {
        Wire.Null -> append("null")
        is Wire.Bool -> append(node.value)
        is Wire.Num -> append(node.value)
        is Wire.Str -> renderString(node.value)
        is Wire.Arr -> {
            append('[')
            node.items.forEachIndexed { index, item -> if (index > 0) append(','); render(item) }
            append(']')
        }
        is Wire.Obj -> {
            append('{')
            node.fields.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                renderString(key); append(':'); render(value)
            }
            append('}')
        }
    }
}

private fun StringBuilder.renderString(value: String) {
    append('"')
    for (char in value) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    append('"')
}

fun parseWire(text: String): Wire {
    val parser = WireParser(text)
    val result = parser.value(0)
    parser.skipWhitespace()
    if (!parser.atEnd) throw WireFormatException("多余内容")
    return result
}

private class WireParser(private val text: String) {
    private var index = 0
    val atEnd get() = index >= text.length

    fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun value(depth: Int): Wire {
        if (depth > MAX_DEPTH) throw WireFormatException("嵌套过深")
        skipWhitespace()
        if (atEnd) throw WireFormatException("意外结束")
        return when (text[index]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> Wire.Str(string())
            't' -> literal("true", Wire.Bool(true))
            'f' -> literal("false", Wire.Bool(false))
            'n' -> literal("null", Wire.Null)
            else -> number()
        }
    }

    private fun obj(depth: Int): Wire {
        index++
        val fields = linkedMapOf<String, Wire>()
        skipWhitespace()
        if (peek() == '}') { index++; return Wire.Obj(fields) }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw WireFormatException("需要字段名")
            val key = string()
            skipWhitespace()
            expect(':')
            if (fields.put(key, value(depth + 1)) != null) throw WireFormatException("重复字段 $key")
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return Wire.Obj(fields)
                else -> throw WireFormatException("需要 , 或 }")
            }
        }
    }

    private fun arr(depth: Int): Wire {
        index++
        val items = mutableListOf<Wire>()
        skipWhitespace()
        if (peek() == ']') { index++; return Wire.Arr(items) }
        while (true) {
            items += value(depth + 1)
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return Wire.Arr(items)
                else -> throw WireFormatException("需要 , 或 ]")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (atEnd) throw WireFormatException("字符串未结束")
            when (val char = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (atEnd) throw WireFormatException("转义未结束")
                    when (val escaped = text[index++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) throw WireFormatException("Unicode 转义不完整")
                            out.append(text.substring(index, index + 4).toIntOrNull(16)?.toChar()
                                ?: throw WireFormatException("Unicode 转义无效"))
                            index += 4
                        }
                        else -> throw WireFormatException("未知转义 $escaped")
                    }
                }
                else -> out.append(char)
            }
        }
    }

    private fun number(): Wire {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index].isDigit()) index++
        val token = text.substring(start, index)
        return Wire.Num(token.toLongOrNull() ?: throw WireFormatException("无效数字"))
    }

    private fun literal(word: String, value: Wire): Wire {
        if (!text.startsWith(word, index)) throw WireFormatException("无效字面量")
        index += word.length
        return value
    }

    private fun peek(): Char? = text.getOrNull(index)
    private fun next(): Char = text.getOrNull(index++) ?: throw WireFormatException("意外结束")
    private fun expect(char: Char) { if (next() != char) throw WireFormatException("需要 $char") }

    private companion object { const val MAX_DEPTH = 64 }
}
