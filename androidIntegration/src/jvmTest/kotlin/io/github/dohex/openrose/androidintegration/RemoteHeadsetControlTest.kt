package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.circulation.CirculationNode
import io.github.dohex.openrose.circulation.HeadsetEndpoint
import io.github.dohex.openrose.circulation.LoopbackDiscoveryNetwork
import io.github.dohex.openrose.circulation.localCommand
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
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
            val read = phone.command("read")
            assertEquals("wind", read["mode"])
            assertEquals("100", read["left"])
            val pending = async(Dispatchers.IO) { phone.noise(read) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            assertFalse(pending.isCompleted)
            assertEquals("Linux", device.headset.openOwner)
            device.link.deviceReply(TRANSPARENCY)
            val confirmed = pending.await()
            assertEquals("transparency", confirmed["mode"])
            assertEquals("transparency", phone.command("read")["mode"])
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
            assertEquals(listOf("Linux:open"), device.headset.events)
        }
    }

    @Test
    fun pairedPhoneCanAdjustConfirmedNoiseDuringDesktopInitialization() = runBlocking {
        withPair { holder, phone, device ->
            val previous = phone.command("read")
            device.transport.audioDevice.emit(null)
            holder.state.first { it.connection == ConnectionState.Disconnected }
            device.transport.autoReplyOnOpen = false
            device.transport.audioDevice.emit(device.candidate.deviceId)
            holder.state.first { (it.connection as? ConnectionState.Connecting)?.phase == ConnectionPhase.INITIALIZING }
            val recovering = phone.command("read")
            assertEquals("recovering", recovering["status"])
            assertFalse("session" in recovering)
            assertTrue("error" in phone.noise(previous))

            device.link.replyToWrite("08ee00000001010a0002", i5Replies.getValue("0101"))
            device.link.replyToWrite("08ee00000006020a0008", i5Replies.getValue("0602"))
            holder.state.first { it.noiseModeSession != null }
            val reading = phone.command("read")
            assertEquals("initializing", reading["status"])
            assertEquals("wind", reading["mode"])
            assertTrue("transparency" in reading.getValue("modes").split(','))
            assertNotEquals(previous["session"], reading["session"])

            val pending = async(Dispatchers.IO) { phone.noise(reading) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            assertFalse(pending.isCompleted)
            device.link.replyToWrite("08ee00000006020a0008", TRANSPARENCY)
            val confirmed = pending.await()
            assertEquals("transparency", confirmed["mode"])
            assertEquals("initializing", confirmed["status"])
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
        }
    }

    @Test
    fun missingReadbackMarksTheModeUnknownAndReturnsAnUnconfirmedResult() = runBlocking {
        withPair { holder, phone, device ->
            val result = phone.noise(phone.command("read"))
            assertTrue("耳机未确认" in result.getValue("error"))
            val reading = phone.command("read")
            assertEquals(null, reading["mode"])
            assertEquals("recovering", reading["status"])
            assertEquals(SettingStage.TIMED_OUT, holder.state.value.settingOperations.last().stage)
            assertEquals(device.candidate.deviceId, holder.state.value.audioDeviceId)
        }
    }

    @Test
    fun disconnectRetiresThePendingWriteAndTheOldCardCannotWriteIntoTheReplacementSession() = runBlocking {
        withPair { holder, phone, device ->
            val previous = phone.command("read")
            val oldLink = device.link
            val pending = async(Dispatchers.IO) { phone.noise(previous) }
            holder.state.first { it.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN }
            device.transport.audioDevice.emit(null)
            holder.state.first { it.connection == ConnectionState.Disconnected }
            assertTrue("error" in pending.await())
            assertEquals("idle", phone.command("read")["status"])

            device.transport.audioDevice.emit(device.candidate.deviceId)
            holder.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY }
            device.link.initializing = false
            val current = phone.command("read")
            assertNotEquals(previous.getValue("session"), current.getValue("session"))
            val writes = device.link.writes.toList()
            assertTrue("error" in phone.noise(previous))
            runCatching { oldLink.deviceReply(TRANSPARENCY) }
            assertEquals(writes, device.link.writes)
            assertEquals("wind", phone.command("read")["mode"])
        }
    }

    private suspend fun withPair(test: suspend (ControlRuntime, CirculationNode, Headphones) -> Unit) = withTimeout(8_000) {
        val device = Headphones()
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
            CirculationNode(files.newFolder(), "Linux", network.discovery(), endpoint::handle).use { desktop ->
                CirculationNode(files.newFolder(), "Phone", network.discovery()) { error("Phone holds no earphones") }.use { phone ->
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

    private class Headphones {
        val headset = SharedHeadset()
        val candidate = rosePair("11:22:33:44:55:66")
        val transport = HeadsetTransport("Linux", headset, bonded = listOf(candidate))
        val link get() = transport.links.last()
    }

    private fun CirculationNode.command(op: String, fields: Map<String, String> = emptyMap()) = localCommand(fields + mapOf("op" to op, "peer" to peers.value.single().id))

    private fun CirculationNode.noise(read: Map<String, String>) = command("noise", mapOf("mode" to "transparency", "device" to read.getValue("device"), "session" to read.getValue("session")))

    private companion object {
        const val TRANSPARENCY = "09ff00000106020e000000000120"
    }
}
