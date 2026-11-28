package io.github.dohex.openrose.module.presentation

import android.app.Application
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
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
        val snapshot = snapshot(confirmed = "deep", modes = setOf("deep", "anc", "normal", "dynamic"))

        assertEquals(listOf("normal", "anc", "dynamic", "deep"), snapshot.availableNoiseModes())
        assertEquals("deep", snapshot.knownNoiseMode())
        assertEquals("深度降噪", modeLabel("deep"))
        assertEquals("normal", snapshot.nextNoiseMode("deep"))
        assertEquals("deep", snapshot.nextNoiseMode("dynamic"))
    }

    private fun snapshot(confirmed: String, modes: Set<String>): RenderedSnapshot = RenderedSnapshot(
        wire = Bundle(),
        identity = SessionIdentity("headset", 1, "host"),
        readSequence = 1,
        native = NativeHeadsetSnapshot(
            hostEpoch = "host",
            deviceId = "headset",
            sessionId = 1,
            addresses = emptySet(),
            profile = ModelProfile.I5_ZT,
            capturedElapsedMillis = 1,
            writableSettings = setOf(DeviceSetting.NOISE_MODE),
            readableFields = emptySet(),
            noiseModes = modes,
            ancLevel = null,
            transparencyLevel = null,
            fields = mapOf(DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text(confirmed), "")),
        ),
        presentation = Bundle(),
        mode = BridgePresentationWire.OPENROSE_STYLE,
        statusNotificationEnabled = true,
        statusIsland = false,
        statusTarget = BridgePresentationWire.TARGET_OPENROSE,
        dynamicColor = false,
        address = null,
    )
}
