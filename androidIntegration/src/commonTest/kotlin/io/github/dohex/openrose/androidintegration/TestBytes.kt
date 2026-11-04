package io.github.dohex.openrose.androidintegration

/** Hex helpers for frames in tests; the core module keeps its own copies internal. */
internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
