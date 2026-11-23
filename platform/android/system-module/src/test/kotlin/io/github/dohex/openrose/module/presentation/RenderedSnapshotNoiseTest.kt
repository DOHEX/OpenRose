package io.github.dohex.openrose.module.presentation

import android.app.Application
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class RenderedSnapshotNoiseTest {
    @Test
    fun aConfirmedDeepModeIsShownAndTakesPartInCycling() {
        val snapshot = snapshot(confirmed = "deep", modes = listOf("deep", "anc", "normal", "dynamic"))

        assertEquals(listOf("normal", "anc", "dynamic", "deep"), snapshot.availableNoiseModes())
        assertEquals("deep", snapshot.knownNoiseMode())
        assertEquals("深度降噪", modeLabel("deep"))
        assertEquals("normal", snapshot.nextNoiseMode("deep"))
        assertEquals("deep", snapshot.nextNoiseMode("dynamic"))
    }

    private fun snapshot(confirmed: String, modes: List<String>): RenderedSnapshot {
        val noiseField = Bundle().apply {
            putString(BridgeWire.STATUS, "KNOWN")
            putBundle(
                "value",
                Bundle().apply {
                    putString("type", "TEXT")
                    putString("value", confirmed)
                },
            )
        }
        return RenderedSnapshot(
            wire = Bundle(),
            identity = SessionIdentity("headset", 1, "host"),
            readSequence = 1,
            fields = Bundle().apply { putBundle("NOISE_MODE", noiseField) },
            capabilities = Bundle().apply {
                putBundle("noise", Bundle().apply { putStringArrayList("modes", ArrayList(modes)) })
                putStringArrayList("writable_settings", arrayListOf("NOISE_MODE"))
            },
            presentation = Bundle(),
            mode = BridgePresentationWire.MODULE,
            focusEnabled = true,
            statusIsland = false,
            statusTarget = BridgePresentationWire.TARGET_OPENROSE,
            dynamicColor = false,
            address = null,
        )
    }
}
