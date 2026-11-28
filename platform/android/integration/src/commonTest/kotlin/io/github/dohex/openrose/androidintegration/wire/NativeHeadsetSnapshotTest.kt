package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.SharedHeadset
import io.github.dohex.openrose.androidintegration.eventually
import io.github.dohex.openrose.androidintegration.hostHeadset
import io.github.dohex.openrose.androidintegration.runtime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.kotlincore.CoreBackedRuntime
import io.github.dohex.openrose.ffi.kotlincore.toControlState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeHeadsetSnapshotTest {
    @Test
    fun aReadyCoreSnapshotProjectsIdentityCapabilitiesAndReadings() = runTest {
        val core = connectedCore()
        val rust = core.coreState.value
        val device = requireNotNull(rust.toControlState().device)

        val native = assertNotNull(NativeHeadsetSnapshot.from(rust, HOST_EPOCH, CAPTURED))

        assertEquals(NativeSessionReference(HOST_EPOCH, ADDRESS, device.sessionId), native.reference)
        assertEquals(setOf(ADDRESS), native.addresses)
        assertEquals(ModelProfile.JASMINE_RS_V2, native.profile)
        assertEquals(device.capabilities.writableSettings.map { it.name }.toSet(), native.writableSettings)
        assertEquals(device.capabilities.readableFields.map { it.name }.toSet(), native.readableFields)
        assertEquals(device.capabilities.noiseControl?.modes.orEmpty().toSet(), native.noiseModes)
        assertEquals(FieldReading.Known(FieldValue.Number(100), ""), native.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldReading.Known(FieldValue.Number(50), ""), native.fields[DeviceField.RIGHT_BATTERY])
        assertFalse(DeviceField.CASE_BATTERY in native.fields)
        assertTrue(native.matchesAddress(" ${ADDRESS.lowercase()} "))
        assertFalse(native.matchesAddress("66:55:44:33:22:11"))
    }

    @Test
    fun aSnapshotWithoutAReadySessionOrWithoutIntegrationMetadataIsNotProjected() = runTest {
        val idle = hostHeadset(SharedHeadset(), earphones()).runtime(backgroundScope)
        assertNull(NativeHeadsetSnapshot.from(idle.coreState.value, HOST_EPOCH, CAPTURED))

        val rust = connectedCore().coreState.value
        assertNull(NativeHeadsetSnapshot.from(rust, " ", CAPTURED))
        assertNull(NativeHeadsetSnapshot.from(rust, HOST_EPOCH, 0L))
    }

    @Test
    fun aSnapshotIsFreshFromCaptureForThreeSecondsAndNeverBeforeIt() = runTest {
        val native = assertNotNull(NativeHeadsetSnapshot.from(connectedCore().coreState.value, HOST_EPOCH, CAPTURED))

        assertFalse(native.isFresh(CAPTURED - 1))
        assertTrue(native.isFresh(CAPTURED))
        assertTrue(native.isFresh(CAPTURED + NativeHeadsetSnapshot.MAX_AGE_MILLIS))
        assertFalse(native.isFresh(CAPTURED + NativeHeadsetSnapshot.MAX_AGE_MILLIS + 1))
    }

    private fun earphones() = DeviceCandidate(DeviceId(ADDRESS), ModelProfile.JASMINE_RS_V2.displayName, null, ModelProfile.JASMINE_RS_V2, "")

    private suspend fun TestScope.connectedCore(): CoreBackedRuntime {
        val core = hostHeadset(SharedHeadset(), earphones()).runtime(backgroundScope)
        core.refreshBondedDevices()
        assertEquals(OperationResult.Success, core.connect(DeviceId(ADDRESS)))
        eventually("the battery to be read") {
            core.state.value.device?.diagnostics?.fields?.get(DeviceField.RIGHT_BATTERY).let { it != null && it !is FieldReading.Loading }
        }
        return core
    }

    private companion object {
        const val ADDRESS = "11:22:33:44:55:66"
        const val HOST_EPOCH = "epoch-1"
        const val CAPTURED = 10_000L
    }
}
