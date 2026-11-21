package io.github.dohex.openrose.androidintegration.wire

fun Wire.render(): String = buildString { render(this@render) }

private fun StringBuilder.render(node: Wire) {
    when (node) {
        Wire.Null -> append("null")

        is Wire.Bool -> append(node.value)

        is Wire.Num -> append(node.value)

        is Wire.Str -> renderString(node.value)

        is Wire.Arr -> {
            append('[')
            node.items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                render(item)
            }
            append(']')
        }

        is Wire.Obj -> {
            append('{')
            node.fields.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                renderString(key)
                append(':')
                render(value)
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
