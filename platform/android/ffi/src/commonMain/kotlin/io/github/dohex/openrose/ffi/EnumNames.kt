package io.github.dohex.openrose.ffi

internal class EnumNames<T : Enum<T>>(entries: List<T>) {
    private val byKey = entries.associateBy { it.name.withoutSeparators() }

    fun of(source: Enum<*>): T = byKey.getValue(source.name.withoutSeparators())
}

private fun String.withoutSeparators(): String = replace("_", "")
