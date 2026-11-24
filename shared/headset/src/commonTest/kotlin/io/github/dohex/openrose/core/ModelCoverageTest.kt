package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCoverageTest {
    @Test
    fun catalogAdvertisementsReachAReadyControlSessionWithTheDocumentedBatteryLayout() = runTest {
        for (profile in ModelProfile.entries) {
            val id = DeviceId(profile.name)
            val transport = CatalogTransport(profile, advertisements = flowOf(catalogAdvertisement(profile, id)))
            val runtime = LocalControlRuntime(
                PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                transport = transport,
                scope = backgroundScope,
                timing = SessionTiming(queryTimeoutMillis = 25, writeIntervalMillis = 0),
            )
            runtime.startScan()
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectForAddDevice(id), profile.name)
            val device = assertNotNull(runtime.state.value.device)
            assertEquals(profile, device.profile)
            val battery = if (profile.batteryLayout == BatteryLayout.SINGLE_UNIT) DeviceField.CASE_BATTERY else DeviceField.LEFT_BATTERY
            assertIs<FieldReading.Known>(device.diagnostics.fields[battery], profile.name)
            if (profile.family == ProtocolFamily.RS_COMMON_V2) {
                assertEquals(OperationResult.Success, runtime.set(HeadsetCommand.GameMode(false)), profile.name)
                val commands = transport.links.single().writes.map { it.substring(6, 10) }
                val expected = if (profile in setOf(ModelProfile.FIRST_ANGEL_RS_V2, ModelProfile.MOKA_RS_V2)) {
                    listOf("0e00", "fa0e", "fa2a")
                } else {
                    listOf("0e00", "fa0e")
                }
                assertEquals(expected, commands.takeLast(expected.size), profile.name)
            }
            assertTrue(runtime.release(), profile.name)
        }
    }
}
