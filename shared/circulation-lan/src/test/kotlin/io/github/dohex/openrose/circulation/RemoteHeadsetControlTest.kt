package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemoteHeadsetControlTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun phoneModeRequestKeepsLinuxAudioAndWaitsForTheActualI5Report() = runBlocking {
        withPair { holder, phone, device ->
            val read = phone.read()
            assertEquals(NoiseMode.WIND, read.mode)
            assertEquals(100, read.left)
            assertEquals(BatteryLayout.EARBUDS_AND_CASE, read.batteryLayout)
            val pending = async(Dispatchers.IO) { phone.noise(read) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            assertFalse(pending.isCompleted)
            assertEquals("Linux", device.openOwner)
            device.link.deviceReply(TRANSPARENCY)
            val confirmed = pending.await()
            assertEquals(NoiseMode.TRANSPARENCY, confirmed.mode)
            assertEquals(NoiseMode.TRANSPARENCY, phone.read().mode)
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
            assertEquals(listOf("Linux:open"), device.events)
        }
    }

    @Test
    fun unsupportedRemoteModeLeavesTheHolderAndItsHeadsetUntouched() = runBlocking {
        withPair { holder, phone, device ->
            val read = phone.read()
            assertFalse(NoiseMode.DEEP in read.modes)
            val before = holder.state.value
            val writes = device.link.writes.toList()
            val result = phone.noise(read, NoiseMode.DEEP)
            assertEquals("该型号不支持 deep 模式", result.error)
            assertEquals(writes, device.link.writes)
            assertEquals(before.lastError, holder.state.value.lastError)
            assertEquals(before.settingOperations, holder.state.value.settingOperations)
            assertEquals(read, phone.read())
        }
    }

    @Test
    fun pairedPhoneCanAdjustConfirmedNoiseDuringDesktopInitialization() = runBlocking {
        withPair { holder, phone, device ->
            val previous = phone.read()
            device.transport.audioDevice.emit(null)
            holder.state.first { it.connection == ConnectionState.Disconnected }
            device.transport.autoReplyOnOpen = false
            device.transport.audioDevice.emit(device.candidate.deviceId)
            holder.state.first { (it.connection as? ConnectionState.Connecting)?.phase == ConnectionPhase.INITIALIZING }
            val recovering = phone.read()
            assertEquals(RemoteControlStatus.RECOVERING, recovering.status)
            assertEquals(null, recovering.session)
            assertTrue(phone.noise(previous).error != null)

            device.link.replyToWrite("08ee00000001010a0002", i5Replies.getValue("0101"))
            device.link.replyToWrite("08ee00000006020a0008", i5Replies.getValue("0602"))
            holder.state.first { it.noiseModeSession != null }
            val reading = phone.read()
            assertEquals(RemoteControlStatus.INITIALIZING, reading.status)
            assertEquals(NoiseMode.WIND, reading.mode)
            assertTrue(NoiseMode.TRANSPARENCY in reading.modes)
            assertTrue(reading.eqPresets.isEmpty())
            assertFalse(reading.gameModeSupported)
            assertEquals("控制会话尚未就绪，暂不能设置", phone.set(reading, HeadsetCommand.GameMode(false)).error)
            assertNotEquals(previous.session, reading.session)

            val pending = async(Dispatchers.IO) { phone.noise(reading) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            assertFalse(pending.isCompleted)
            device.link.replyToWrite("08ee00000006020a0008", TRANSPARENCY)
            val confirmed = pending.await()
            assertEquals(NoiseMode.TRANSPARENCY, confirmed.mode)
            assertEquals(RemoteControlStatus.INITIALIZING, confirmed.status)
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
        }
    }

    @Test
    fun missingReadbackMarksTheModeUnknownAndReturnsAnUnconfirmedResult() = runBlocking {
        withPair { holder, phone, device ->
            val result = phone.noise(phone.read())
            assertTrue("耳机未确认" in result.error.orEmpty())
            val reading = phone.read()
            assertEquals(null, reading.mode)
            assertEquals(RemoteControlStatus.RECOVERING, reading.status)
            assertEquals(SettingStage.TIMED_OUT, holder.state.value.settingOperations.last().stage)
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
        }
    }

    @Test
    fun disconnectRetiresThePendingWriteAndTheOldCardCannotWriteIntoTheReplacementSession() = runBlocking {
        withPair { holder, phone, device ->
            val previous = phone.read()
            val oldLink = device.link
            val pending = async(Dispatchers.IO) { phone.noise(previous) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            device.transport.audioDevice.emit(null)
            holder.state.first { it.connection == ConnectionState.Disconnected }
            assertTrue(pending.await().error != null)
            assertEquals(RemoteControlStatus.IDLE, phone.read().status)

            device.transport.audioDevice.emit(device.candidate.deviceId)
            holder.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY }
            device.link.initializing = false
            val current = phone.read()
            assertNotEquals(previous.session, current.session)
            val writes = device.link.writes.toList()
            assertTrue(phone.noise(previous).error != null)
            runCatching { oldLink.deviceReply(TRANSPARENCY) }
            assertEquals(writes, device.link.writes)
            assertEquals(NoiseMode.WIND, phone.read().mode)
        }
    }

    @Test
    fun remoteCanAdjustEqPresetAndGameModeAfterReady() = runBlocking {
        withPair { holder, phone, device ->
            val initial = phone.read()
            assertEquals(ModelProfile.I5_ZT, initial.profile)
            assertTrue(initial.eqPresets.containsAll(listOf("pop", "hifi", "rock")))
            assertTrue(initial.gameModeSupported)
            assertEquals(false, initial.gameMode)
            assertTrue(initial.ancLevels.isEmpty())
            assertTrue(initial.transparencyLevels.isEmpty())

            val eqPending = async(Dispatchers.IO) { phone.set(initial, HeadsetCommand.EqualizerPreset("rock")) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            device.link.deviceReply(EQ_ROCK)
            val eqConfirmed = eqPending.await()
            assertEquals("rock", eqConfirmed.eqPreset)

            val gamePending = async(Dispatchers.IO) { phone.set(eqConfirmed, HeadsetCommand.GameMode(true)) }
            holder.state.first {
                val last = it.settingOperations.lastOrNull()
                last?.stage == SettingStage.WRITTEN && last.setting == io.github.dohex.openrose.core.DeviceSetting.GAME_MODE
            }
            device.link.deviceReply(GAME_MODE_ON)
            val gameConfirmed = gamePending.await()
            assertEquals(true, gameConfirmed.gameMode)
        }
    }

    @Test
    fun supportsCirculationCoversAllRecognizedModels() {
        assertTrue(ModelProfile.entries.all(::supportsCirculation))
        assertFalse(supportsCirculation(null))
    }

    private suspend fun withPair(test: suspend (ControlRuntime, CirculationNode, I5Headphones) -> Unit) = withTimeout(8_000) {
        val device = I5Headphones()
        val owner = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = LocalControlRuntime(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available, hostPlatform = HostPlatform.LINUX),
            transport = device.transport,
            scope = owner,
            timing = SessionTiming(queryTimeoutMillis = 700, writeIntervalMillis = 0),
        )
        try {
            device.transport.audioDevice.emit(device.candidate.deviceId)
            holder.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY }
            device.link.initializing = false
            val network = LoopbackDiscoveryNetwork()
            val endpoint = HeadsetEndpoint(holder)
            CirculationNode(files.newFolder(), "Linux", network.discovery(), headset = endpoint).use { desktop ->
                CirculationNode(files.newFolder(), "Phone", network.discovery()).use { phone ->
                    desktop.start()
                    phone.start()
                    phone.pairQr(desktop.offerPairing().qr)
                    test(holder, phone, device)
                }
            }
        } finally {
            holder.release()
            owner.cancel()
        }
    }

    private fun CirculationNode.read() = localCommand(CirculationCommand.Read(peers.value.single().id)) as RemoteReading

    private fun CirculationNode.noise(read: RemoteReading, mode: NoiseMode = NoiseMode.TRANSPARENCY) = set(read, HeadsetCommand.NoiseMode(mode))

    private fun CirculationNode.set(read: RemoteReading, command: HeadsetCommand) = localCommand(
        CirculationCommand.SetSetting(requireNotNull(read.session), command),
    ) as RemoteReading

    private companion object {
        const val TRANSPARENCY = "09ff00000106020e000000000120"
        const val EQ_ROCK = "09ff00000102010b000219"
        const val GAME_MODE_ON = "09ff00000106030b00001d"
    }
}
