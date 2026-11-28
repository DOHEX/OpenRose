package io.github.dohex.openrose.module

import android.app.Application
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import io.github.dohex.openrose.ffi.kotlincore.toControlState
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class NativeSnapshotFrameTest {
    @Test
    fun hostPublishedCoreBytesDecodeToTheHostsHeadsetState() = runBlocking<Unit> {
        val ready = readyHeadset()
        val frame = ready.frame
        val host = requireNotNull(ready.hostState.toControlState().device)

        val native = assertIs<NativeSnapshotDecode.Decoded>(decodeNativeSnapshot(frame)).native

        assertEquals(frame.getString(BridgeWire.HOST_EPOCH), native.hostEpoch)
        assertEquals(frame.getLong(BridgeWire.SESSION_ID), native.sessionId)
        assertEquals(frame.getLong(BridgeWire.CAPTURED_ELAPSED_MILLIS), native.capturedElapsedMillis)
        assertEquals("AA:BB:CC:DD:EE:FF", native.deviceId)
        assertEquals(setOf("AA:BB:CC:DD:EE:FF"), native.addresses)
        assertEquals(ModelProfile.I5_ZT, native.profile)
        for (field in listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.NOISE_MODE)) {
            val hostReading = assertIs<FieldReading.Known>(host.diagnostics.fields[field])
            assertEquals(FieldReading.Known(hostReading.value, ""), native.fields[field], field.name)
        }
        assertIs<FieldValue.Number>(assertIs<FieldReading.Known>(native.fields[DeviceField.LEFT_BATTERY]).value)
        assertTrue(native.noiseModes.isNotEmpty())
        assertEquals(host.capabilities.noiseControl?.modes.orEmpty().toSet(), native.noiseModes)
        assertEquals(host.capabilities.writableSettings.map { it.name }.toSet(), native.writableSettings)
        assertTrue("NOISE_MODE" in native.writableSettings)
    }

    @Test
    fun corruptOrForeignVersionCoreBytesAreRejectedWithoutThrowing() = runBlocking<Unit> {
        val frame = readyHeadsetFrame()
        val bytes = requireNotNull(frame.getByteArray(BridgeWire.CORE_SNAPSHOT))

        fun withBytes(replacement: ByteArray?): Bundle = Bundle(frame).apply {
            if (replacement == null) remove(BridgeWire.CORE_SNAPSHOT) else putByteArray(BridgeWire.CORE_SNAPSHOT, replacement)
        }

        assertEquals(NativeSnapshotDecode.Rejected("snapshot_core_missing"), decodeNativeSnapshot(withBytes(null)))
        assertEquals(NativeSnapshotDecode.Rejected("snapshot_core_malformed"), decodeNativeSnapshot(withBytes("corrupt".encodeToByteArray())))
        val foreign = bytes.copyOf().apply { this[3] = (coreWireFormatVersion().toInt() + 1).toByte() }
        assertEquals(NativeSnapshotDecode.Rejected("snapshot_core_unsupported_version"), decodeNativeSnapshot(withBytes(foreign)))
    }
}
