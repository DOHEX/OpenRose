package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ConnectionBlockReason
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.ReconnectGuidance
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableCode
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.RustControlRuntime
import io.github.dohex.openrose.ffi.currentHeadset
import io.github.dohex.openrose.ffi.hasSystemAudio
import io.github.dohex.openrose.ffi.readySession
import io.github.dohex.openrose.ffi.sessionFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.modelDisplayName
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.dohex.openrose.core.transport.RawAudioDevice as KotlinRawAudioDevice
import uniffi.openrose_control.ConnectionBlockReason as RustBlockReason
import uniffi.openrose_control.HostPlatform as RustHostPlatform
import uniffi.openrose_control.PermissionState as RustPermissionState
import uniffi.openrose_control.PlatformApplicability as RustPlatformApplicability
import uniffi.openrose_control.PlatformState as RustPlatformState
import uniffi.openrose_control.TransportAvailability as RustTransportAvailability
import uniffi.openrose_control.TransportUnavailableCode as RustUnavailableCode
import uniffi.openrose_headset.HeadsetField as RustHeadsetField
import uniffi.openrose_headset.HeadsetSetting as RustHeadsetSetting
import uniffi.openrose_headset.ModelProfile as RustModelProfile
import uniffi.openrose_headset.NoiseMode as RustNoiseMode
import uniffi.openrose_headset.ReconnectGuidance as RustReconnectGuidance

class CoreControlRuntimeTest {
    private fun kotlinPlatform(
        permission: PermissionState = PermissionState.GRANTED,
        transport: TransportAvailability = TransportAvailability.Available,
    ) = PlatformState(
        permission = permission,
        transport = transport,
        hostPlatform = HostPlatform.ANDROID,
        scanPermission = PermissionState.GRANTED,
        userUnlocked = true,
    )

    private fun rustPlatform(
        permission: RustPermissionState = RustPermissionState.GRANTED,
        transport: RustTransportAvailability = RustTransportAvailability.Available,
        host: RustHostPlatform = RustHostPlatform.ANDROID,
        applicability: RustPlatformApplicability = RustPlatformApplicability.UNKNOWN,
    ) = RustPlatformState(permission, transport, host, true, RustPermissionState.NOT_REQUIRED, applicability)

    private inline fun withRuntime(rig: SimulatedDrivers, block: (CoreControlRuntime, RustControlRuntime) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = KotlinCoreTransport(rig)
        val rust = RustControlRuntime(transport, kotlinPlatform().toRust(), clock = rig.clock(), timing = rig.timing())
        try {
            block(CoreControlRuntime(rust, scope) { transport.close() }, rust)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun StateFlow<ControlState>.eventually(condition: (ControlState) -> Boolean): ControlState = withTimeout(3_000) { first(condition) }

    @Test
    fun theKotlinStateFollowsTheSnapshotsTheCorePushes() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, _ ->
                assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
                val state = runtime.state.eventually { it.connection is ConnectionState.Connected }
                val device = assertNotNull(state.device)
                assertEquals(ModelProfile.I5_ZT, device.profile)
                assertEquals(DeviceId(ADDRESS), device.deviceId)
                assertEquals("wind", device.confirmedNoiseMode?.id)
                assertTrue(DeviceSetting.NOISE_MODE in device.capabilities.writableSettings)
                assertTrue(state.deviceHasSystemAudio)
                assertEquals(device.sessionId, state.readySession?.sessionId)
                assertEquals(DeviceId(ADDRESS), state.audioDeviceId)
                assertTrue(runtime.disconnect())
                runtime.state.eventually { it.connection is ConnectionState.Disconnected }
            }
        }
    }

    @Test
    fun aSettingIsConfirmedOnlyAfterTheHeadsetReadsBackTheValue() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, _ ->
                assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
                val connected = runtime.state.eventually { it.connection is ConnectionState.Connected }
                val session = checkNotNull(connected.readySession).sessionId
                val gatt = rig.gatts.single()
                gatt.muted = true
                val written = gatt.writes.size
                val setting = async(Dispatchers.Default) { runtime.set(HeadsetCommand.NoiseMode(NoiseMode.ANC), session) }
                withTimeout(3_000) { while (gatt.writes.drop(written).none { it == NOISE_READBACK_QUERY }) delay(5) }

                assertFalse(setting.isCompleted)
                assertEquals("wind", runtime.state.value.device?.confirmedNoiseMode?.id)
                assertEquals(SettingStage.WRITTEN, runtime.state.value.settingOperations.last().stage)

                gatt.inject(NOISE_REPLY_ANC)
                assertEquals(OperationResult.Success, withTimeout(3_000) { setting.await() })
                val confirmed = runtime.state.eventually { it.settingOperations.lastOrNull()?.stage == SettingStage.CONFIRMED }
                assertEquals("anc", confirmed.device?.confirmedNoiseMode?.id)
                assertEquals(FieldValue.Text("anc"), confirmed.settingOperations.last().requestedValue)
                assertTrue(DeviceField.NOISE_MODE in confirmed.settingOperations.last().confirmedFields)
            }
        }
    }

    @Test
    fun aSettingForAnEndedSessionFailsWithTheCoreError() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, _ ->
                assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
                val connected = runtime.state.eventually { it.connection is ConnectionState.Connected }
                val session = checkNotNull(connected.readySession).sessionId
                val failure = assertIs<OperationResult.Failure>(runtime.set(HeadsetCommand.NoiseMode(NoiseMode.ANC), session + 1))
                assertEquals(ControlErrorCode.DISCONNECTED, failure.error.code)
            }
        }
    }

    @Test
    fun platformAndSettingChangesReachTheCoreAndTheStateReflectsThem() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, _ ->
                runtime.updatePlatformState(kotlinPlatform(permission = PermissionState.DENIED))
                val denied = runtime.state.eventually { it.platform.permission == PermissionState.DENIED }
                assertEquals(ConnectionBlockReason.BluetoothPermissionRequired, denied.connectionBlockReason)

                runtime.updatePlatformState(kotlinPlatform(transport = TransportAvailability.Unavailable(TransportUnavailableCode.NO_ADAPTER, "none")))
                val unavailable = runtime.state.eventually { it.platform.transport is TransportAvailability.Unavailable }
                assertEquals(ConnectionBlockReason.TransportUnavailable(TransportUnavailableCode.NO_ADAPTER, "none"), unavailable.connectionBlockReason)

                runtime.updateSettings(UserSettings(forceBle = true))
                assertTrue(runtime.state.eventually { it.settings.forceBle }.settings.forceBle)
            }
        }
    }

    @Test
    fun scanStateIsServedFromTheCore() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, _ ->
                assertEquals(OperationResult.Success, runtime.startScan())
                runtime.state.eventually { it.scan == ScanState.Scanning }
                runtime.stopScan()
                runtime.state.eventually { it.scan == ScanState.Idle }
            }
        }
    }

    @Test
    fun theCurrentHeadsetTheKotlinStateShowsIsTheOneTheCoreNames() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.audioConnected = false
            rig.pairedDevices.add(KotlinRawAudioDevice(modelDisplayName(RustModelProfile.I7_RS_V2), I7_ADDRESS, alias = null))
            withRuntime(rig) { runtime, rust ->
                runtime.refreshBondedDevices()
                var state = runtime.state.eventually { it.bondedHeadsets.size == 2 }
                assertEquals(rust.snapshot().currentHeadset()?.id, state.currentHeadset?.deviceId?.value)
                assertEquals(DeviceId(ADDRESS), state.currentHeadset?.deviceId)

                runtime.selectCurrentHeadset(DeviceId(I7_ADDRESS))
                state = runtime.state.eventually { it.selectedHeadsetId == DeviceId(I7_ADDRESS) }
                assertEquals(rust.snapshot().currentHeadset()?.id, state.currentHeadset?.deviceId?.value)
                assertEquals(DeviceId(I7_ADDRESS), state.currentHeadset?.deviceId)

                assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
                state = runtime.state.eventually { it.connection is ConnectionState.Connected }
                assertEquals(rust.snapshot().currentHeadset()?.id, state.currentHeadset?.deviceId?.value)
                assertEquals(DeviceId(ADDRESS), state.currentHeadset?.deviceId)
            }
        }
    }

    @Test
    fun theReadySessionAndSystemAudioFactsMatchTheCoreRules() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, rust ->
                assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
                val state = runtime.state.eventually { it.connection is ConnectionState.Connected }
                val snapshot = rust.snapshot()
                assertEquals(snapshot.readySession()?.sessionId?.toLong(), state.readySession?.sessionId)
                assertEquals(snapshot.sessionFor(RustHeadsetSetting.NOISE_MODE)?.sessionId?.toLong(), state.noiseModeSession?.sessionId)
                assertEquals(snapshot.hasSystemAudio(), state.deviceHasSystemAudio)
            }
        }
    }

    @Test
    fun theConnectionBlockReasonAndReconnectGuidanceMatchTheCore() = runBlocking<Unit> {
        Rig().use { rig ->
            withRuntime(rig) { runtime, rust ->
                runtime.refreshBondedDevices()
                var state = runtime.state.eventually { it.bondedHeadsets.isNotEmpty() }
                var snapshot = rust.snapshot()
                assertNull(snapshot.connectionBlockReason)
                assertNull(state.connectionBlockReason)
                assertEquals(snapshot.reconnectGuidance?.name?.replace("_", ""), state.reconnectGuidance?.name?.replace("_", ""))
                assertEquals(ReconnectGuidance.OPEN_CASE_AUTO_CONNECT, state.reconnectGuidance)

                runtime.updatePlatformState(kotlinPlatform(transport = TransportAvailability.Disabled))
                state = runtime.state.eventually { it.platform.transport == TransportAvailability.Disabled }
                snapshot = rust.snapshot()
                assertEquals(RustBlockReason.BluetoothDisabled, snapshot.connectionBlockReason)
                assertEquals(ConnectionBlockReason.BluetoothDisabled, state.connectionBlockReason)
            }
        }
    }

    @Test
    fun everyModelCoreNameHasAKotlinProfileWithTheSameDisplayName() {
        for (profile in RustModelProfile.entries) {
            assertEquals(modelDisplayName(profile), profile.toKotlin().displayName, profile.name)
        }
        assertEquals(RustModelProfile.entries.size, ModelProfile.entries.size)
    }

    @Test
    fun everyCoreFieldSettingAndPlatformVariantHasAKotlinCounterpart() {
        assertEquals(RustHeadsetField.entries.size, RustHeadsetField.entries.map { it.toKotlin() }.toSet().size)
        assertEquals(RustHeadsetSetting.entries.size, RustHeadsetSetting.entries.map { it.toKotlin() }.toSet().size)
        assertEquals(DeviceField.entries.size, RustHeadsetField.entries.size)
        assertEquals(DeviceSetting.entries.size, RustHeadsetSetting.entries.size)
        for (code in RustUnavailableCode.entries) {
            val platform = rustPlatform(transport = RustTransportAvailability.Unavailable(code, null)).toKotlin()
            assertIs<TransportAvailability.Unavailable>(platform.transport)
        }
        for (permission in RustPermissionState.entries) {
            assertEquals(permission.name, rustPlatform(permission = permission).toKotlin().permission.name)
        }
        for (applicability in RustPlatformApplicability.entries) {
            assertEquals(applicability.name, rustPlatform(applicability = applicability).toKotlin().controlTransportApplicability.name)
        }
        for (host in RustHostPlatform.entries.filter { it != RustHostPlatform.MAC_OS }) {
            assertEquals(host.name, rustPlatform(host = host).toKotlin().hostPlatform.name)
        }
        for (mode in RustNoiseMode.entries) assertEquals(mode.name, NoiseMode.entries.first { it.name == mode.name }.toRust().name)
        for (guidance in RustReconnectGuidance.entries) {
            assertTrue(ReconnectGuidance.entries.any { it.name == guidance.name }, guidance.name)
        }
    }

    private class ClosableTransport(private val delegate: KotlinCoreTransport) :
        ControlTransport by delegate,
        AutoCloseable {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
            delegate.close()
        }
    }

    @Test
    fun theRuntimeAndItsTransportCloseOnceTheLinkIsReleasedAndTheOwnerScopeEnded() = runBlocking<Unit> {
        Rig().use { rig ->
            val transport = ClosableTransport(KotlinCoreTransport(rig))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = CoreControlRuntime(transport, kotlinPlatform(), scope = scope, timing = rig.timing(), clock = rig.clock())
            assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
            assertTrue(runtime.release())
            assertEquals(1, rig.gatts.single().closes.size)
            assertFalse(transport.closed.get())
            scope.cancel()
            withTimeout(3_000) { while (!transport.closed.get()) delay(5) }
        }
    }

    @Test
    fun anOwnerScopeThatEndedFirstStillLetsTheRuntimeReleaseItsLinkBeforeClosing() = runBlocking<Unit> {
        Rig().use { rig ->
            val transport = ClosableTransport(KotlinCoreTransport(rig))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = CoreControlRuntime(transport, kotlinPlatform(), scope = scope, timing = rig.timing(), clock = rig.clock())
            assertEquals(OperationResult.Success, runtime.connect(DeviceId(ADDRESS)))
            scope.cancel()
            delay(100)
            assertFalse(transport.closed.get())
            assertTrue(rig.gatts.single().closes.isEmpty())

            assertTrue(runtime.release())
            assertEquals(1, rig.gatts.single().closes.size)
            withTimeout(3_000) { while (!transport.closed.get()) delay(5) }
        }
    }

    private companion object {
        const val I7_ADDRESS = "A1:B2:C3:D4:E5:F6"
        const val NOISE_READBACK_QUERY = "08ee00000006020a0008"
        const val NOISE_REPLY_ANC = "09ff00000106020e000100000020"
    }
}
