package io.github.dohex.openrose.androidintegration.wire

/** Raised for any malformed or unexpected wire content; callers treat it as a rejected message. */
class WireFormatException(message: String) : Exception(message)

/**
 * A minimal JSON-shaped tree for the authenticated IPC payloads. Platform bindings carry the
 * rendered text, so the same codec is exercised by tests and by the Android Binder boundary.
 */
sealed interface Wire {
    data class Obj(val fields: Map<String, Wire>) : Wire {
        fun field(key: String): Wire = fields[key] ?: throw WireFormatException("缺少字段 $key")
        fun optional(key: String): Wire? = fields[key]?.takeUnless { it == Null }
        fun str(key: String): String = field(key).asStr()
        fun int(key: String): Int = field(key).asInt()
        fun long(key: String): Long = field(key).asLong()
        fun bool(key: String): Boolean = field(key).asBool()
        fun arr(key: String): List<Wire> = field(key).asArr()
    }

    data class Arr(val items: List<Wire>) : Wire
    data class Str(val value: String) : Wire
    data class Num(val value: Long) : Wire
    data class Bool(val value: Boolean) : Wire
    data object Null : Wire

    fun asObj(): Obj = this as? Obj ?: throw WireFormatException("需要对象")
    fun asArr(): List<Wire> = (this as? Arr)?.items ?: throw WireFormatException("需要数组")
    fun asStr(): String = (this as? Str)?.value ?: throw WireFormatException("需要字符串")
    fun asLong(): Long = (this as? Num)?.value ?: throw WireFormatException("需要数字")
    fun asInt(): Int = asLong().also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw WireFormatException("数字越界") }.toInt()
    fun asBool(): Boolean = (this as? Bool)?.value ?: throw WireFormatException("需要布尔值")
}

fun obj(vararg fields: Pair<String, Wire?>): Wire.Obj = Wire.Obj(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

fun str(value: String): Wire = Wire.Str(value)
fun num(value: Int): Wire = Wire.Num(value.toLong())
fun num(value: Long): Wire = Wire.Num(value)
fun bool(value: Boolean): Wire = Wire.Bool(value)
fun arr(items: Iterable<Wire>): Wire = Wire.Arr(items.toList())

inline fun <reified E : Enum<E>> Wire.asEnum(): E {
    val name = asStr()
    return enumValues<E>().firstOrNull { it.name == name } ?: throw WireFormatException("未知枚举值 $name")
}
