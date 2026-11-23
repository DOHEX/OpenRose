package io.github.dohex.openrose.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NoiseModeOrderTest {
    @Test
    fun everyKnownModeHasAPositionAndUnknownIdsSortLast() {
        val ids = listOf("mystery", "deep", "dynamic", "auto", "wind", "transparency", "anc", "normal")

        assertEquals(
            listOf("normal", "anc", "transparency", "wind", "auto", "dynamic", "deep", "mystery"),
            ids.sortedBy { NoiseMode.displayOrder(it) },
        )
    }
}
