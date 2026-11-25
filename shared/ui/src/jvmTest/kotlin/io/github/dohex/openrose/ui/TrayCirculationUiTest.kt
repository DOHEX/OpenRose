package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayCirculationUiTest {
    private val first = trayCandidate("rose-1", ModelProfile.I5_ZT)
    private val second = trayCandidate("rose-2", ModelProfile.I7_RS_V2)

    @Test
    fun eachRemoteHeadsetControlsItsOwnSessionWhileTheDesktopRadioIsDisabled() {
        for (platform in listOf(HostPlatform.LINUX, HostPlatform.WINDOWS)) {
            val base = disconnectedAndroidState()
            val state = base.copy(platform = base.platform.copy(hostPlatform = platform, transport = TransportAvailability.Disabled))
            val sessions = listOf(
                RemoteSessionReference("xiaomi", DeviceId("i5-a"), "xiaomi/12"),
                RemoteSessionReference("redmi", DeviceId("i5-b"), "redmi/3"),
            )
            val readings = sessions.associate { session ->
                val available = if (session == sessions.first()) NoiseMode.TRANSPARENCY else NoiseMode.ANC
                session.peer to i5Reading(session, listOf(NoiseMode.NORMAL, available))
            }.toMutableMap()
            val commands = mutableListOf<CirculationCommand.SetSetting>()
            val nearby = mapOf("xiaomi" to "Xiaomi 14", "redmi" to "Redmi K70")
            val client = CirculationClient { request ->
                when (request) {
                    CirculationCommand.Directory -> PeerDirectory(nearby + ("offline" to "旧手机"), nearby)

                    is CirculationCommand.Read -> readings.getValue(request.peer)

                    is CirculationCommand.SetSetting -> {
                        commands.add(request)
                        val mode = (request.command as HeadsetCommand.NoiseMode).mode
                        readings.getValue(request.session.peer).copy(mode = mode).also { readings[request.session.peer] = it }
                    }

                    else -> error("Unexpected request $request")
                }
            }
            for (style in InterfaceStyle.entries) {
                commands.clear()
                readings.replaceAll { _, reading -> reading.copy(mode = NoiseMode.NORMAL) }
                withTrayPanel(state, style, circulation = client) { panel ->
                    panel.render()
                    assertEquals(2, panel.countText("ROSE EARFREE i5"))
                    assertTrue(panel.hasText("音源 · Xiaomi 14"))
                    assertTrue(listOf("左 85%", "右 80%", "盒 60%").all(panel::hasText))
                    assertTrue(panel.hasText("音源 · Redmi K70 · L 85% · R 80% · C 60%"))
                    assertTrue(panel.textBounds("音源 · Xiaomi 14").bottom <= panel.clickableBounds("通透").top)
                    assertTrue(panel.clickableBounds("通透").bottom <= panel.textBounds("音源 · Redmi K70 · L 85% · R 80% · C 60%").top)
                    panel.clickText("通透")
                    panel.render()
                    panel.clickText("展开远端控制")
                    panel.render()
                    panel.clickText("降噪")
                    panel.render()
                    assertTrue(panel.hasSelectedText("通透"))
                    assertTrue(panel.hasSelectedText("降噪"))
                    assertEquals(
                        listOf(
                            CirculationCommand.SetSetting(sessions[0], HeadsetCommand.NoiseMode(NoiseMode.TRANSPARENCY)),
                            CirculationCommand.SetSetting(sessions[1], HeadsetCommand.NoiseMode(NoiseMode.ANC)),
                        ),
                        commands,
                    )
                    assertTrue(panel.model.settingWrites.isEmpty())
                    assertTrue(panel.model.connectRequests.isEmpty())
                    if (platform == HostPlatform.LINUX) panel.capturePanel("remote-headsets", style)
                }
            }
        }
    }

    @Test
    fun handoffTargetsThePairedRemoteHeadsetByAddressWhenNamesMatch() {
        val target = first.copy(name = "ROSE EARFREE i5", classicAddress = "00:10:20:30:40:50")
        val base = disconnectedAndroidState()
        val state = base.copy(
            platform = base.platform.copy(hostPlatform = HostPlatform.LINUX),
            bondedDevices = persistentListOf(second, target),
        )
        val phones = mapOf("other" to "Redmi K70", "matching" to "Xiaomi 14")
        val pulls = mutableListOf<String>()
        val client = CirculationClient { command ->
            when (command) {
                CirculationCommand.Directory -> PeerDirectory(phones, phones)

                is CirculationCommand.Read -> {
                    val id = if (command.peer == "matching") requireNotNull(target.classicAddress) else "00:10:20:30:40:51"
                    RemoteReading(
                        RemoteControlStatus.READY,
                        deviceName = target.name,
                        session = RemoteSessionReference(command.peer, DeviceId(id), "session"),
                    )
                }

                is CirculationCommand.Handoff -> HandoffStatus(available = true)

                is CirculationCommand.Pull -> {
                    pulls.add(command.address)
                    CirculationResult()
                }

                else -> error("Unexpected request $command")
            }
        }
        withTrayPanel(state.copy(selectedHeadsetId = second.deviceId), InterfaceStyle.MIUIX, circulation = client) { panel ->
            panel.render()
            assertEquals(2, panel.countText("ROSE EARFREE i5"))
            assertEquals(1, panel.countText("改连本机"))
            panel.clickText("改连本机")
            panel.render()
            assertEquals(listOf(target.classicAddress), pulls)
        }
    }

    @Test
    fun unavailablePhoneOffersOnlyTheExplicitAudioConnection() {
        val headset = first.copy(name = "ROSE EARFREE i5", classicAddress = "00:10:20:30:40:50")
        val base = disconnectedAndroidState()
        val state = base.copy(platform = base.platform.copy(hostPlatform = HostPlatform.LINUX), bondedDevices = persistentListOf(headset))
        for (style in InterfaceStyle.entries) {
            val pulls = mutableListOf<String>()
            val client = CirculationClient { request ->
                when (request) {
                    CirculationCommand.Directory -> PeerDirectory(paired = mapOf("phone" to "Xiaomi 14"))

                    is CirculationCommand.Handoff -> HandoffStatus(available = true, offline = true)

                    is CirculationCommand.Pull -> {
                        pulls += request.address
                        CirculationResult(message = "正在尝试本机接入")
                    }

                    else -> error("Unexpected request $request")
                }
            }
            withTrayPanel(state, style, circulation = client) { panel ->
                panel.render()
                assertEquals(1, panel.countText("ROSE EARFREE i5"))
                assertTrue(panel.hasText("这台电脑尚未连接"))
                assertFalse(panel.hasText("重新连接"))
                assertTrue(panel.hasTappableText("连接本机"))
                assertFalse(panel.hasTappableText("恢复耳机控制"))
                panel.capturePanel("disconnected", style)
                panel.clickText("连接本机")
                panel.render()
                assertEquals(listOf(headset.classicAddress), pulls)
                assertTrue(panel.model.connectRequests.isEmpty())
            }
        }
    }

    @Test
    fun handoffFailureSurvivesThePeerReleasingTheHeadsetBeforeTheReply() {
        val address = "00:10:20:30:40:50"
        val headset = first.copy(name = "ROSE EARFREE i5", classicAddress = address)
        val secondAddress = "00:10:20:30:40:51"
        val otherHeadset = headset.copy(deviceId = DeviceId("i5-b"), classicAddress = secondAddress)
        val base = disconnectedAndroidState()
        val state = base.copy(platform = base.platform.copy(hostPlatform = HostPlatform.LINUX), bondedDevices = persistentListOf(headset, otherHeadset))
        val reading = i5Reading(RemoteSessionReference("phone", DeviceId(address), "session")).copy(mode = NoiseMode.ANC)
        val peers = mutableStateOf(listOf(RemoteHeadset("Xiaomi 14", reading, busy = false, feedback = null) {}))
        val pulls = mutableListOf<String>()
        val reply = CompletableDeferred<CirculationResult>()
        val client = CirculationClient { command ->
            when (command) {
                is CirculationCommand.Handoff -> HandoffStatus(available = true, progress = "正在接入本机音频".takeIf { pulls.isNotEmpty() })

                is CirculationCommand.Pull -> {
                    pulls.add(command.address)
                    reply.await()
                }

                else -> error("Unexpected request $command")
            }
        }
        withTrayPanel(state, InterfaceStyle.MIUIX, circulation = client, remoteHeadsets = peers) { panel ->
            panel.render()
            assertTrue(panel.hasText("音源 · Xiaomi 14"))
            panel.capturePanel("phone-headset", InterfaceStyle.MIUIX)
            panel.clickText("改连本机")
            panel.render()
            assertEquals(listOf(address), pulls)

            val secondReading = reading.copy(session = RemoteSessionReference("second", DeviceId(secondAddress), "second/session"))
            val otherPeer = RemoteHeadset("Redmi K70", secondReading, busy = false, feedback = null) {}
            peers.value = peers.value + otherPeer
            panel.render()
            assertEquals(2, panel.countText("改连本机"))
            assertEquals(1, panel.countText("正在接入本机音频"))

            peers.value = listOf(RemoteHeadset("Xiaomi 14", RemoteReading(RemoteControlStatus.IDLE), busy = false, feedback = null) {}, otherPeer)
            panel.render()
            assertFalse(panel.hasText("音源 · Xiaomi 14"))
            assertFalse(panel.hasText("音源设备 · 这台电脑"))
            assertFalse(panel.hasTappableText("改连本机"))
            reply.complete(CirculationResult(error = "耳机未接受本机音频连接"))
            panel.render()
            assertEquals(1, panel.countText("耳机未接受本机音频连接"))
            assertTrue(panel.textBounds("耳机未接受本机音频连接").bottom <= panel.textBounds("音源 · Redmi K70 · L 85% · R 80% · C 60%").top)
            assertTrue(panel.hasTappableText("改连本机"))
            assertEquals(listOf(address), pulls)
            assertTrue(panel.model.connectRequests.isEmpty())
        }
    }

    private fun i5Reading(
        session: RemoteSessionReference,
        modes: List<NoiseMode> = listOf(NoiseMode.NORMAL, NoiseMode.ANC, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
    ) = RemoteReading(
        RemoteControlStatus.READY,
        deviceName = "ROSE EARFREE i5",
        session = session,
        mode = NoiseMode.NORMAL,
        modes = modes,
        left = 85,
        right = 80,
        case = 60,
        batteryLayout = ModelProfile.I5_ZT.batteryLayout,
    )
}
