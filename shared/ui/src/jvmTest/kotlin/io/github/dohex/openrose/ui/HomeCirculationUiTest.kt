package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCirculationUiTest {
    private val headset = trayCandidate("i5-le", ModelProfile.I5_ZT).copy(name = "ROSE EARFREE i5", classicAddress = "00:10:20:30:40:50")
    private val linux = disconnectedAndroidState().let {
        it.copy(platform = it.platform.copy(hostPlatform = HostPlatform.LINUX), bondedDevices = persistentListOf(headset))
    }

    @Test
    fun knownSourceOffersRemoteNoiseControlAndExplicitTransferInsideOneHeadsetCard() {
        val session = RemoteSessionReference("phone", DeviceId(requireNotNull(headset.classicAddress)), "session")
        for (style in InterfaceStyle.entries) {
            val writes = mutableListOf<CirculationCommand>()
            val client = knownSourceClient(session, writes)
            val model = FakeOpenRoseModel(mutableStateOf(linux), mutableStateOf(UiSettings(interfaceStyle = style)))
            runOpenRoseUiTest(900, 800) {
                setContent { OpenRoseApp(model, HomePlatform(client)) }
                val home = OpenRoseUiTestHarness(this)
                home.render()
                assertEquals(1, home.countText("ROSE EARFREE i5"))
                assertTrue(home.hasText("ZT"))
                assertTrue(home.hasText("音源设备 · 小米 14"))
                assertTrue(home.hasText("左耳 87% · 右耳 89% · 充电盒 未知"))
                assertTrue(home.hasTappableText("改连这台电脑"))
                assertFalse(home.hasTappableText("连接"))
                assertFalse(home.hasText("未连接 · 耳机开盖后将自动连接"))
                System.getenv("OPENROSE_SURFACE_DIR")?.let { directory ->
                    val file = File(File(directory).apply { mkdirs() }, "home-source-${style.name.lowercase()}.png")
                    ImageIO.write(home.snapshot(), "png", file)
                }
                home.clickText("通透")
                home.render()
                assertEquals(listOf<CirculationCommand>(CirculationCommand.SetSetting(session, HeadsetCommand.NoiseMode(NoiseMode.TRANSPARENCY))), writes)
                assertTrue(home.hasSelectedText("通透"))
                home.clickText("改连这台电脑")
                home.render()
                assertEquals(CirculationCommand.Pull(requireNotNull(headset.classicAddress)), writes.last())
                assertTrue(home.hasText("手机正在通话，无法让出耳机"))
                assertTrue(home.hasText("音源设备 · 小米 14"))
                assertTrue(model.connectRequests.isEmpty())
            }
        }
    }

    private fun knownSourceClient(
        session: RemoteSessionReference,
        writes: MutableList<CirculationCommand>,
    ): CirculationClient {
        val peer = mapOf("phone" to "小米 14")
        var reading = RemoteReading(
            status = RemoteControlStatus.READY,
            deviceName = headset.name,
            session = session,
            mode = NoiseMode.ANC,
            modes = listOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY),
            left = 87,
            right = 89,
            batteryLayout = ModelProfile.I5_ZT.batteryLayout,
        )
        return CirculationClient { command ->
            when (command) {
                CirculationCommand.Directory -> PeerDirectory(peer, peer)

                is CirculationCommand.Read -> reading

                is CirculationCommand.Handoff -> HandoffStatus(available = true)

                is CirculationCommand.SetSetting -> {
                    writes += command
                    val mode = (command.command as HeadsetCommand.NoiseMode).mode
                    reading.copy(mode = mode).also { reading = it }
                }

                is CirculationCommand.Pull -> {
                    writes += command
                    CirculationResult(error = "手机正在通话，无法让出耳机")
                }

                else -> error("Unexpected request $command")
            }
        }
    }

    @Test
    fun unknownSourceBecomesLocalAudioAndControlRecoveryPreservesTheOutput() {
        val model = FakeOpenRoseModel(mutableStateOf(linux))
        val pulls = mutableListOf<String>()
        val client = CirculationClient { command ->
            when (command) {
                CirculationCommand.Directory -> PeerDirectory(paired = mapOf("phone" to "小米 14"))

                is CirculationCommand.Handoff -> HandoffStatus(available = true, offline = true)

                is CirculationCommand.Pull -> {
                    pulls += command.address
                    model.mutableState.value = linux.copy(audioDeviceId = DeviceId(command.address))
                    CirculationResult(message = "本机已接入耳机")
                }

                else -> error("Unexpected request $command")
            }
        }
        var openedSoundSettings = 0
        val sound = DesktopSoundActions(currentOutput = { "内置扬声器" }, openSoundSettings = { openedSoundSettings++ })
        runOpenRoseUiTest(700, 750) {
            setContent { OpenRoseApp(model, HomePlatform(client, sound)) }
            val home = OpenRoseUiTestHarness(this)
            home.render()
            assertTrue(home.hasText("这台电脑尚未连接"))
            assertFalse(home.hasText("音源设备 · 小米 14"))
            assertFalse(home.hasTappableText("连接"))
            home.clickText("将耳机连接到这台电脑")
            home.render()
            assertEquals(listOf(headset.classicAddress), pulls)
            assertTrue(home.hasText("音源设备 · 这台电脑"))
            assertTrue(home.hasText("系统默认输出 · 内置扬声器"))
            assertFalse(home.hasTappableText("将耳机连接到这台电脑"))
            home.clickText("恢复耳机控制")
            home.clickText("打开系统声音设置")
            home.render()
            assertEquals(1, openedSoundSettings)
            assertEquals(listOf(headset.deviceId), model.connectRequests)
            assertEquals(1, pulls.size)
        }
    }

    @Test
    fun aSecondHeadsetWaitsForItsTransferToFinishBeforeOfferingControlRecovery() {
        val target = headset.copy(deviceId = DeviceId("second-le"), name = "工作耳机", classicAddress = "00:10:20:30:40:51")
        val address = requireNotNull(target.classicAddress)
        val model = FakeOpenRoseModel(mutableStateOf(linux.copy(bondedDevices = persistentListOf(headset, target))))
        val response = CompletableDeferred<CirculationResult>()
        val peer = mapOf("phone" to "工作手机")
        val session = RemoteSessionReference("phone", DeviceId(address), "work-session")
        val client = CirculationClient { command ->
            when (command) {
                CirculationCommand.Directory -> PeerDirectory(peer, peer)

                is CirculationCommand.Read -> RemoteReading(RemoteControlStatus.READY, target.name, session = session)

                is CirculationCommand.Handoff -> HandoffStatus(available = true)

                is CirculationCommand.Pull -> {
                    assertEquals(address, command.address)
                    model.mutableState.value = model.mutableState.value.copy(audioDeviceId = DeviceId(address))
                    response.await()
                }

                else -> error("Unexpected request $command")
            }
        }
        val sound = DesktopSoundActions(currentOutput = { "桌面音箱" }, openSoundSettings = {})
        runOpenRoseUiTest(900, 850) {
            setContent { OpenRoseApp(model, HomePlatform(client, sound)) }
            val home = OpenRoseUiTestHarness(this)
            home.render()
            assertEquals(1, home.countText("工作耳机"))
            home.clickText("改连这台电脑")
            home.render()
            assertEquals(1, home.countText("音源设备 · 这台电脑"))
            assertTrue(home.hasText("系统默认输出 · 桌面音箱"))
            assertFalse(home.hasTappableText("恢复耳机控制"))
            response.complete(CirculationResult(message = "本机已接入耳机"))
            home.render()
            assertEquals(1, home.countText("工作耳机"))
            home.clickText("恢复耳机控制")
            assertEquals(listOf(target.deviceId), model.connectRequests)
        }
    }

    private class HomePlatform(val client: CirculationClient, override val desktopSound: DesktopSoundActions? = null) : PlatformUi {
        override val platformName = "Linux"
        override val headsetCirculation: CirculationClient
            @Composable get() = client
    }
}
