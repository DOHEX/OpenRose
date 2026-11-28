package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSnapshotTest {
    @Test
    fun currentI5AndI7SessionCanBeReadWithoutChangingProtocolState() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val id = earphonesId
            val transport = snapshotHeadset(profile, id)
            val session = SystemEntryRuntime(transport.runtime(backgroundScope), backgroundScope)
            session.refreshBondedDevices()
            assertEquals(OperationResult.Success, session.connect(id))

            val device = requireNotNull(session.state.value.device)
            val reference = SessionReference(device.deviceId, device.sessionId)
            val writesBeforeRead = transport.links.single().writes.toList()
            val available = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
            val snapshot = available.device

            assertEquals(device, snapshot)
            assertEquals(session.presentation.value, available.presentation)
            assertEquals(reference, available.presentation.reference)
            assertEquals(PresentationReading.Known(100), available.presentation.battery?.left)
            assertEquals(PresentationReading.Known(if (profile == ModelProfile.I5_ZT) 98 else 50), available.presentation.battery?.right)
            assertEquals(if (profile == ModelProfile.I5_ZT) PresentationReading.NotProvided else PresentationReading.Known(80), available.presentation.battery?.case)
            assertEquals(PresentationReading.Known(if (profile == ModelProfile.I5_ZT) "wind" else "anc"), available.presentation.noise?.mode)
            assertEquals(device.capabilities.writableSettings, available.presentation.actions)
            assertEquals(writesBeforeRead, transport.links.single().writes)
            if (profile == ModelProfile.I5_ZT) {
                assertEquals(FieldReading.NotProvided("ff"), snapshot.diagnostics.fields[DeviceField.CASE_BATTERY])
            }
            session.disconnect()
        }
    }

    @Test
    fun aReferenceMustMatchTheReadyDeviceAndCurrentSession() = runTest {
        val profile = ModelProfile.I5_ZT
        val id = earphonesId
        val transport = snapshotHeadset(profile, id)
        val session = newSession(transport)
        val initialReference = SessionReference(id, 1)

        assertFailure(ControlErrorCode.NOT_READY, session.readSession(initialReference))
        connect(session, id)
        val firstDevice = requireNotNull(session.state.value.device)
        val firstReference = SessionReference(firstDevice.deviceId, firstDevice.sessionId)

        assertFailure(ControlErrorCode.DISCONNECTED, session.readSession(SessionReference(DeviceId("other"), firstDevice.sessionId)))
        assertIs<SessionSnapshotResult.Available>(session.readSession(firstReference))
        session.disconnect()
        assertFailure(ControlErrorCode.NOT_READY, session.readSession(firstReference))

        connect(session, id)
        val nextDevice = requireNotNull(session.state.value.device)
        assertFalse(nextDevice.sessionId == firstDevice.sessionId)
        assertFailure(ControlErrorCode.DISCONNECTED, session.readSession(firstReference))
        assertIs<SessionSnapshotResult.Available>(session.readSession(SessionReference(id, nextDevice.sessionId)))
        session.disconnect()
    }

    @Test
    fun aConnectedSessionStillInitializingIsNotReady() = runTest {
        val profile = ModelProfile.I5_ZT
        val id = earphonesId
        val device = DeviceState(id, 1, profile, profile.capabilities(), persistentMapOf())
        val initializing = MutableStateFlow(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                connection = ConnectionState.Connected(id, 1, profile, ConnectionPhase.INITIALIZING),
                device = device,
            ),
        )
        val real = newSession(snapshotHeadset(profile, id))
        val session = SystemEntryRuntime(
            object : ControlRuntime by real {
                override val state: StateFlow<ControlState> = initializing
            },
            backgroundScope,
        )

        val failure = assertIs<SessionSnapshotResult.Failure>(session.readSession(SessionReference(id, 1)))
        assertEquals(ControlErrorCode.NOT_READY, failure.error.code)
        assertEquals("设备会话尚未就绪", failure.error.message)
    }

    @Test
    fun readDuringPendingSettingAndSettingsChangeKeepsTheConfirmedStateAndSession() = runTest {
        val profile = ModelProfile.I5_ZT
        val id = earphonesId
        val transport = snapshotHeadset(profile, id)
        val session = newSession(transport)
        connect(session, id)
        val device = requireNotNull(session.state.value.device)
        val reference = SessionReference(device.deviceId, device.sessionId)
        val link = transport.links.single()
        val initialWrites = link.writes.size
        link.initializing = false

        val pending = async { session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL)) }
        runCurrent()
        eventually("the setting to be written") { session.state.value.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
        assertFalse(pending.isCompleted)

        val beforeConfirmation = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(FieldValue.Text("wind"), (beforeConfirmation.diagnostics.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        eventually("the setting and its read-back query to reach the earphones") { link.writes.size >= initialWrites + 2 }
        assertEquals(initialWrites + 2, link.writes.size)

        session.updateSettings(session.state.value.settings.copy(forceBle = true))
        val afterSettingsChange = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(device.sessionId, afterSettingsChange.sessionId)
        assertEquals(FieldValue.Text("wind"), (afterSettingsChange.diagnostics.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        session.updateIntegrationSettings(session.settings.copy(islandMode = IslandMode.OFF, statusNotificationEnabled = false))
        val hidden = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertFalse(hidden.presentation.showStatusNotification)
        assertEquals(null, hidden.presentation.prompt)
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.last().stage)
        assertEquals(beforeConfirmation, hidden.device)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        session.updateIntegrationSettings(session.settings.copy(islandMode = IslandMode.OPENROSE_STYLE, statusNotificationEnabled = true))
        val switched = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertEquals(null, switched.presentation.prompt, "Mode changes must not replay the connection event")
        assertEquals(beforeConfirmation, switched.device)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        link.deviceReply("09ff00000106020e000001000020")
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        val confirmed = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(FieldValue.Text("normal"), (confirmed.diagnostics.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(initialWrites + 2, link.writes.size)
        session.disconnect()
    }

    private fun kotlinx.coroutines.test.TestScope.newSession(transport: HostHeadset) = SystemEntryRuntime(transport.runtime(backgroundScope), backgroundScope)

    private suspend fun kotlinx.coroutines.test.TestScope.connect(session: SystemEntryRuntime, id: DeviceId) {
        session.refreshBondedDevices()
        assertEquals(OperationResult.Success, session.connect(id))
        eventually("the session to be ready") { session.state.value.readySession != null }
    }

    private fun assertFailure(code: ControlErrorCode, result: SessionSnapshotResult) {
        assertEquals(code, assertIs<SessionSnapshotResult.Failure>(result).error.code)
    }

    private fun kotlinx.coroutines.test.TestScope.snapshotHeadset(profile: ModelProfile, id: DeviceId) = hostHeadset(SharedHeadset(), rosePair(id.value, profile))

    private val earphonesId = DeviceId(APP_HEADSET_ADDRESS)
}
