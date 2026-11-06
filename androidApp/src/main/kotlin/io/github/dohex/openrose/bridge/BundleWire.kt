package io.github.dohex.openrose.bridge

import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.WireMap

/** Reads a [Bundle] in place; an absent or mistyped value reads as null instead of the Bundle getters' defaults. */
internal fun Bundle.asWireMap(): WireMap = BundleWireMap(this)

/**
 * Builds the Bundle for an encoded wire tree. The Bundle put call follows the Kotlin type of each value, so the parcel is
 * identical to one assembled by hand. An empty list has no element type and is written as a string list, which reads back
 * as an empty list for every list reader.
 */
internal fun Map<String, Any?>.toBundle(): Bundle = Bundle().also { bundle ->
    for ((key, value) in this) {
        when (value) {
            null -> bundle.putString(key, null)
            is String -> bundle.putString(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            is Map<*, *> -> bundle.putBundle(key, value.toStringKeyed().toBundle())
            is List<*> -> bundle.putList(key, value)
            else -> throw IllegalArgumentException("Unsupported wire value for $key: ${value::class}")
        }
    }
}

private fun Map<*, *>.toStringKeyed(): Map<String, Any?> = entries.associate { (name, item) ->
    require(name is String) { "Wire keys must be strings" }
    name to item
}

private fun Bundle.putList(key: String, list: List<*>) {
    when (list.firstOrNull()) {
        null -> putStringArrayList(key, ArrayList())
        is String -> putStringArrayList(key, ArrayList(list.map { it as String }))
        is Int -> putIntegerArrayList(key, ArrayList(list.map { it as Int }))
        is Map<*, *> -> putParcelableArrayList(key, ArrayList(list.map { (it as Map<*, *>).toStringKeyed().toBundle() }))
        else -> throw IllegalArgumentException("Unsupported wire list for $key")
    }
}

private class BundleWireMap(private val bundle: Bundle) : WireMap {
    override val keys: Set<String> get() = bundle.keySet()

    override fun string(key: String): String? = value(key) as? String

    override fun int(key: String): Int? = value(key) as? Int

    override fun long(key: String): Long? = value(key) as? Long

    override fun boolean(key: String): Boolean? = value(key) as? Boolean

    override fun child(key: String): WireMap? = (value(key) as? Bundle)?.let(::BundleWireMap)

    override fun strings(key: String): List<String>? = (value(key) as? ArrayList<*>)?.filterIsInstance<String>()

    override fun ints(key: String): List<Int>? = (value(key) as? ArrayList<*>)?.filterIsInstance<Int>()

    // The typed getters cannot tell a mistyped value from their default, and the typed Bundle.get(String, Class) needs API 33.
    @Suppress("DEPRECATION")
    override fun value(key: String): Any? = if (bundle.containsKey(key)) bundle.get(key) else null
}
