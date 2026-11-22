@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CirculationPanelUiTest {
    @Test
    fun aRemoteNoiseTapUsesTheHeldDeviceWhileItsPreviouslyReportedModeStaysVisible() = runOpenRoseUiTest(420, 600) {
        var selected: NoiseMode? = null
        val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree i5", modes = listOf(NoiseMode.WIND, NoiseMode.TRANSPARENCY), mode = NoiseMode.WIND)
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteHeadsetCard(RemoteHeadset("工作站", reading, false, null) { selected = it })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("通透")
        assertEquals(NoiseMode.TRANSPARENCY, selected)
        assertTrue(panel.hasText("降噪模式 · 抗风噪"))
    }

    @Test
    fun phoneShowsTheSourceAndOnlyTheBatteryValuesReportedByThatSource() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree i5", left = 82, right = 71)
                RemoteHeadsetCard(RemoteHeadset("工作站", reading, false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("音源设备 · 工作站"))
        assertTrue(panel.hasText("EarFree i5"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71% · 充电盒 未知"))
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
    fun aSourceWithoutHeadphonesNeverPrintsNull() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteHeadsetCard(RemoteHeadset("工作站", RemoteReading(RemoteControlStatus.READY), false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("工作站"))
        assertFalse(panel.hasText("工作站 · null"))
    }

    @Test
    fun remoteBatteryFollowsTheLayoutTheSourceReports() = runOpenRoseUiTest(420, 600) {
        val single = RemoteReading(RemoteControlStatus.READY, deviceName = "单耳机", case = 64, batteryLayout = BatteryLayout.SINGLE_UNIT)
        val earbuds = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree", left = 82, right = 71, batteryLayout = BatteryLayout.EARBUDS_ONLY)
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteHeadsetCard(RemoteHeadset("工作站", single, false, null) {})
                RemoteHeadsetCard(RemoteHeadset("笔记本", earbuds, false, null) {})
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
