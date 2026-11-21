package io.github.dohex.openrose.androidintegration.wire

/** Read-only view of a bridge wire tree: an Android Bundle on the device, a Map in tests. Absent or mistyped values read as null. */
interface WireMap {
    val keys: Set<String>
    fun string(key: String): String?
    fun int(key: String): Int?
    fun long(key: String): Long?
    fun boolean(key: String): Boolean?
    fun child(key: String): WireMap?

    /** An `ArrayList<String>` on the wire; elements of any other type are dropped. */
    fun strings(key: String): List<String>?

    /** An `ArrayList<Int>` on the wire; elements of any other type are dropped. */
    fun ints(key: String): List<Int>?

    /** Untyped value, for primitives whose type is named by a sibling field. */
    fun value(key: String): Any?
}

/**
 * Map-backed view. Nested maps are children and lists are string/int lists. Encoders produce trees whose values are
 * exactly String, Int, Long, Boolean, Float, Double, a nested `Map<String, Any?>`, `List<String>`, `List<Int>`,
 * `List<Map<String, Any?>>` or null, so the Android boundary can pick the matching Bundle put call.
 */
fun Map<String, Any?>.asWireMap(): WireMap = MapWireMap(this)

private class MapWireMap(private val map: Map<String, Any?>) : WireMap {
    override val keys: Set<String> get() = map.keys

    override fun string(key: String): String? = map[key] as? String

    override fun int(key: String): Int? = map[key] as? Int

    override fun long(key: String): Long? = map[key] as? Long

    override fun boolean(key: String): Boolean? = map[key] as? Boolean

    override fun child(key: String): WireMap? {
        val nested = map[key] as? Map<*, *> ?: return null
        return buildMap<String, Any?> { for ((name, item) in nested) if (name is String) put(name, item) }.asWireMap()
    }

    override fun strings(key: String): List<String>? = (map[key] as? List<*>)?.filterIsInstance<String>()

    override fun ints(key: String): List<Int>? = (map[key] as? List<*>)?.filterIsInstance<Int>()

    override fun value(key: String): Any? = map[key]
}
