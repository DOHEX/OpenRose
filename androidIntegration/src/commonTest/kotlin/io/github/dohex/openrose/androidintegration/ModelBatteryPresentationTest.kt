package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.asWireMap
import io.github.dohex.openrose.androidintegration.wire.toBridgeSnapshot
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelBatteryPresentationTest {
    @Test
    fun singleUnitBatteryStaysInTheAppAndEarbudsWithoutACaseDoNotPublishOne() = runTest {
        for (profile in listOf(ModelProfile.SWIFT2_RS_V2, ModelProfile.JASMINE_RS_V2)) {
            val id = DeviceId("11:22:33:44:55:66")
            val transport = HeadsetTransport(
                "app",
                SharedHeadset(),
                profile,
                bonded = listOf(DeviceCandidate(id, profile.displayName, null, profile, "")),
            )
            val session = SystemEntryRuntime(
                LocalControlRuntime(
                    PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                    transport = transport,
                    scope = backgroundScope,
                    timing = SessionTiming(writeIntervalMillis = 0),
                ),
                backgroundScope,
            )
            session.refreshBondedDevices()
            assertEquals(OperationResult.Success, session.connect(id))
            val device = requireNotNull(session.state.value.device)
            // OwnerRig notifications are synthetic: the shared V2 parser sees a value in every battery slot.
            assertEquals(FieldValue.Number(80), (device.fields[DeviceField.CASE_BATTERY] as FieldReading.Known).value)
            val native = assertNotNull(NativeHeadsetSnapshot.fromWire(device.toBridgeSnapshot("host", 1, 100).asWireMap()))
            assertFalse(DeviceField.CASE_BATTERY in native.fields)
            assertFalse(DeviceField.CASE_FLAG in native.fields)
            if (profile == ModelProfile.SWIFT2_RS_V2) {
                assertNull(session.presentation.value.battery)
                assertFalse(DeviceField.LEFT_BATTERY in native.fields)
                assertFalse(DeviceField.RIGHT_BATTERY in native.fields)
            } else {
                val battery = assertNotNull(session.presentation.value.battery)
                assertEquals(PresentationReading.Known(100), battery.left)
                assertEquals(PresentationReading.Known(50), battery.right)
                assertEquals(PresentationReading.NotProvided, battery.case)
                assertEquals(FieldReading.Known(FieldValue.Number(100), ""), native.fields[DeviceField.LEFT_BATTERY])
            }
            session.disconnect()
        }
    }
}
