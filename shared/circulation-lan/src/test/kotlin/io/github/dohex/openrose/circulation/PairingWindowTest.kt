package io.github.dohex.openrose.circulation

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PairingWindowTest {
    @Test
    fun onlyAnUnusedUnexpiredOfferCanBeShownOrAccepted() {
        var now = 0L
        val window = PairingWindow("desktop") { now }
        val first = window.open()
        now += TimeUnit.MINUTES.toNanos(1)
        assertEquals(60_000L, window.current()?.validForMillis)
        now += TimeUnit.MINUTES.toNanos(1)
        assertNull(window.current())
        assertFailsWith<IllegalStateException> { window.finish(first.code) }

        val renewed = window.open()
        assertEquals(renewed.code, window.tryPairing())
        window.finish(renewed.code)
        assertNull(window.current())
    }
}
