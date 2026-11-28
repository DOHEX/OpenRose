@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CirculationPanelUiTest {
    @Test
    fun aRemoteNoiseTapUsesTheHeldDeviceWhileItsPreviouslyReportedModeStaysVisible() = runOpenRoseUiTest(420, 600) {
        var selected: HeadsetCommand? = null
        val reading = RemoteReading(
            status = RemoteControlStatus.READY,
            deviceName = "EarFree i5",
            session = RemoteSessionReference("pc", DeviceId("i5"), "1"),
            modes = listOf(NoiseMode.WIND, NoiseMode.TRANSPARENCY),
            mode = NoiseMode.WIND,
        )
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteCards(RemoteHeadset("工作站", reading, false, null) { selected = it })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("通透")
        assertEquals(HeadsetCommand.NoiseMode(NoiseMode.TRANSPARENCY), selected)
        assertTrue(panel.hasSelectedText("抗风噪"))
    }

    @Test
    fun remoteHeadsetRendersHighFrequencyControlsAndDispatchesCommands() = runOpenRoseUiTest(420, 900) {
        val sent = mutableListOf<HeadsetCommand>()
        val reading = RemoteReading(
            status = RemoteControlStatus.READY,
            deviceName = "ROSE EARFREE i5",
            profile = ModelProfile.I5_ZT,
            session = RemoteSessionReference("pc", DeviceId("mk2"), "1"),
            mode = NoiseMode.ANC,
            modes = listOf(NoiseMode.ANC, NoiseMode.NORMAL, NoiseMode.TRANSPARENCY),
            ancLevel = 1,
            ancLevels = listOf(1, 2, 3),
            transparencyLevel = 2,
            transparencyLevels = listOf(1, 2),
            eqPreset = "pop",
            eqPresets = listOf("pop", "hifi", "rock"),
            gameModeSupported = true,
            gameMode = false,
        )
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteCards(RemoteHeadset("工作站", reading, false, null) { sent += it })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("降噪档位"))
        assertTrue(panel.hasText("通透档位"))
        assertTrue(panel.hasText("均衡器"))
        assertTrue(panel.hasText("游戏模式"))
        panel.clickText("3 档")
        panel.render()
        panel.clickText("均衡器")
        panel.render()
        panel.clickText("乐器大师 · ROCK")
        panel.render()
        panel.clickText("游戏模式")
        panel.render()
        assertEquals(
            listOf(
                HeadsetCommand.AncLevel(3),
                HeadsetCommand.EqualizerPreset("rock"),
                HeadsetCommand.GameMode(true),
            ),
            sent,
        )
    }

    @Test
    fun phoneShowsTheSourceAndOnlyTheBatteryValuesReportedByThatSource() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree i5", left = 82, right = 71)
                RemoteCards(RemoteHeadset("工作站", reading, false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("音源设备 · 工作站"))
        assertTrue(panel.hasText("EarFree i5"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71% · 充电盒 未知"))
    }

    @Test
    fun aTransferredSecondaryHeadsetRetainsItsAudioAndRecoveryActionAfterTheResultExpires() {
        val first = trayCandidate("first", ModelProfile.I5_ZT)
        val second = trayCandidate("second", ModelProfile.I5_ZT)
        val base = disconnectedAndroidState()
        val state = base.copy(platform = base.platform.copy(hostPlatform = HostPlatform.LINUX), bondedDevices = persistentListOf(first, second))
        val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "i5", session = RemoteSessionReference("phone", second.deviceId, "1"))
        val peers = mutableStateOf(listOf(RemoteHeadset("手机", reading, false, null) {}))
        var connectedAddress: String? = null
        val client = CirculationClient { command ->
            when (command) {
                is CirculationCommand.Handoff -> HandoffStatus(available = true, mediaReady = command.address == connectedAddress)

                is CirculationCommand.Pull -> {
                    connectedAddress = command.address
                    peers.value = emptyList()
                    CirculationResult("本机已接入耳机")
                }

                else -> error("Unexpected request $command")
            }
        }
        withTrayPanel(state, InterfaceStyle.MIUIX, circulation = client, remoteHeadsets = peers) { panel ->
            panel.render()
            panel.clickText("改连本机")
            panel.render()
            panel.advanceTimeBy(12_050)
            assertFalse(panel.hasText("本机已接入耳机"))
            assertTrue(panel.hasText("音源设备 · 这台电脑"))
            panel.clickText("恢复耳机控制")
            assertEquals(listOf(second.deviceId), panel.model.connectRequests)
            connectedAddress = null
            panel.advanceTimeBy(2_050)
            assertFalse(panel.hasText("音源设备 · 这台电脑"))
            assertFalse(panel.hasText("恢复耳机控制"))
        }
    }

    @Test
    fun hiddenUiStopsPeerRequestsAndResumesWithAFreshReading() = runOpenRoseUiTest(420, 600) {
        val visible = mutableStateOf(true)
        val requests = mutableListOf<CirculationCommand>()
        val client = CirculationClient { command ->
            requests += command
            if (command == CirculationCommand.Directory) PeerDirectory(mapOf("phone" to "手机"), mapOf("phone" to "手机")) else RemoteReading(RemoteControlStatus.IDLE)
        }
        setContent {
            CompositionLocalProvider(LocalCirculationVisible provides visible.value) {
                rememberRemoteHeadsets(client)
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertEquals(listOf(CirculationCommand.Directory, CirculationCommand.Read("phone")), requests)
        visible.value = false
        panel.render()
        panel.advanceTimeBy(5_000)
        assertEquals(listOf(CirculationCommand.Directory, CirculationCommand.Read("phone")), requests)
        visible.value = true
        panel.render()
        assertEquals(2, requests.count { it == CirculationCommand.Read("phone") })
        assertEquals(2, requests.count { it == CirculationCommand.Directory })
    }

    @Test
    fun aConsumedOrExpiredPairingOfferDisappearsWhileThePageIsOpen() = runOpenRoseUiTest(420, 700) {
        var offer: PairingTicket? = null
        val client = CirculationClient { command ->
            when (command) {
                CirculationCommand.Offer -> PairingReply(PairingTicket("123456", "QR", 3_000L).also { offer = it })
                CirculationCommand.Directory -> PeerDirectory(offer = offer)
                else -> error("Unexpected request $command")
            }
        }
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                CirculationSettings(client, showQr = { AppText(it) })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("显示配对码")
        panel.render()
        assertTrue(panel.hasText("123456"))
        offer = null
        panel.advanceTimeBy(2_050)
        assertFalse(panel.hasText("QR"))
        panel.clickText("刷新配对码")
        panel.render()
        assertTrue(panel.hasText("QR"))
        panel.advanceTimeBy(3_050)
        assertFalse(panel.hasText("123456"))
    }

    @Test
    fun aSourceWithoutHeadphonesHasNoHeadsetCard() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteCards(RemoteHeadset("工作站", RemoteReading(RemoteControlStatus.READY), false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertFalse(panel.hasText("音源设备 · 工作站"))
    }

    @Test
    fun remoteBatteryFollowsTheLayoutTheSourceReports() = runOpenRoseUiTest(420, 600) {
        val single = RemoteReading(RemoteControlStatus.READY, deviceName = "单耳机", case = 64, batteryLayout = BatteryLayout.SINGLE_UNIT)
        val earbuds = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree", left = 82, right = 71, batteryLayout = BatteryLayout.EARBUDS_ONLY)
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteCards(RemoteHeadset("工作站", single, false, null) {}, RemoteHeadset("笔记本", earbuds, false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("电量 64%"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71%"))
    }

    @Test
    fun unpairingNeedsAConfirmationThatNamesTheDevice() = runOpenRoseUiTest(420, 700) {
        val commands = mutableListOf<CirculationCommand>()
        val client = CirculationClient { command ->
            commands += command
            if (command == CirculationCommand.Directory) PeerDirectory(paired = mapOf("phone" to "小米 14")) else CirculationResult("已解除配对")
        }
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) { CirculationSettings(client) }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("解除配对")
        panel.render()
        assertTrue(panel.hasText("解除与「小米 14」的配对？"))
        assertFalse(commands.any { it is CirculationCommand.Forget })
        panel.clickText("取消")
        panel.render()
        assertFalse(panel.hasText("解除与「小米 14」的配对？"))
        assertFalse(commands.any { it is CirculationCommand.Forget })
        panel.clickText("解除配对")
        panel.render()
        panel.clickText("确认解除")
        panel.render()
        assertEquals(listOf<CirculationCommand>(CirculationCommand.Forget("phone")), commands.filterIsInstance<CirculationCommand.Forget>())
    }

    @Test
    fun theOpenPairingOfferCountsDownItsRemainingSeconds() = runOpenRoseUiTest(420, 700) {
        val ticket = PairingTicket("123456", "QR", 5_000L)
        var offered = false
        val client = CirculationClient { command ->
            when (command) {
                CirculationCommand.Offer -> PairingReply(ticket).also { offered = true }
                CirculationCommand.Directory -> PeerDirectory(offer = ticket.takeIf { offered })
                else -> error("Unexpected request $command")
            }
        }
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                CirculationSettings(client, showQr = { AppText(it) })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("显示配对码")
        panel.render()
        assertTrue(panel.hasText("约 5 秒后失效"))
        panel.advanceTimeBy(2_050)
        panel.render()
        assertTrue(panel.hasText("约 3 秒后失效"))
    }
}

@Composable
private fun RemoteCards(vararg headsets: RemoteHeadset) {
    val cards = rememberHeadsetCards(disconnectedAndroidState(), client = null, remoteHeadsets = headsets.toList())
    cards.others.forEach { AdditionalHeadsetCard(it) }
}
