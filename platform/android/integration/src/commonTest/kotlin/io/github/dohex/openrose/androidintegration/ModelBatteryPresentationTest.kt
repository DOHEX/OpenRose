package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelBatteryPresentationTest {
    @Test
    fun singleUnitBatteryStaysInTheAppAndEarbudsWithoutACaseDoNotPublishOne() = runTest {
        for (profile in listOf(ModelProfile.SWIFT2_RS_V2, ModelProfile.JASMINE_RS_V2)) {
            val id = DeviceId("11:22:33:44:55:66")
            val earphones = DeviceCandidate(id, profile.displayName, null, profile, "")
            val core = hostHeadset(SharedHeadset(), earphones).runtime(backgroundScope)
            val session = SystemEntryRuntime(core, backgroundScope)
            session.refreshBondedDevices()
            assertEquals(OperationResult.Success, session.connect(id))
            eventually("the battery to be read") { session.state.value.device?.diagnostics?.fields?.get(DeviceField.LEFT_BATTERY).let { it != null && it !is FieldReading.Loading } }
            val device = requireNotNull(session.state.value.device)
            val caseBattery = device.diagnostics.fields[DeviceField.CASE_BATTERY]
            if (profile == ModelProfile.SWIFT2_RS_V2) {
                assertEquals(FieldValue.Number(80), (caseBattery as FieldReading.Known).value)
            } else {
                assertIs<FieldReading.NotProvided>(caseBattery)
            }
            val native = assertNotNull(NativeHeadsetSnapshot.from(core.coreState.value, "host", 100))
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
