package io.github.dohex.openrose.framework

import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationFailure
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.circulation.HandoffStatus
import io.github.dohex.openrose.circulation.PairingReply
import io.github.dohex.openrose.circulation.PairingTicket
import io.github.dohex.openrose.circulation.PeerDirectory
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.circulation.RemoteSessionReference
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.decodeCirculationCommand
import io.github.dohex.openrose.ffi.decodeCirculationReply
import io.github.dohex.openrose.ffi.encodeToBytes
import uniffi.openrose_circulation.BatterySlots
import uniffi.openrose_circulation.NearbyPeerRecord
import uniffi.openrose_circulation.PairingOffer
import uniffi.openrose_circulation.Peer
import uniffi.openrose_circulation.PullOutcome
import uniffi.openrose_circulation.PullStage
import uniffi.openrose_circulation.RemoteError
import uniffi.openrose_circulation.YieldRefusal
import uniffi.openrose_control.ControlErrorCode
import uniffi.openrose_headset.SettingPrerequisite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import uniffi.openrose_circulation.CirculationCommand as RustCommand
import uniffi.openrose_circulation.CirculationReply as RustReply
import uniffi.openrose_circulation.HandoffStatus as RustHandoff
import uniffi.openrose_circulation.RemoteControlStatus as RustStatus
import uniffi.openrose_circulation.RemoteReading as RustReading
import uniffi.openrose_circulation.RemoteSessionReference as RustSession
import uniffi.openrose_headset.BatteryLayout as RustBatteryLayout
import uniffi.openrose_headset.HeadsetCommand as RustHeadsetCommand
import uniffi.openrose_headset.HeadsetSetting as RustHeadsetSetting
import uniffi.openrose_headset.ModelProfile as RustModelProfile
import uniffi.openrose_headset.NoiseMode as RustNoiseMode

class CirculationMappingTest {
    private val peer = "a".repeat(64)
    private val session = RemoteSessionReference(peer, DeviceId("earphones"), "owner/12")
    private val rustSession = RustSession(peer, "earphones", "owner/12")

    private fun emptyReading(status: RustStatus) = RustReading(
        status = status,
        headsetId = null,
        headsetName = null,
        profile = null,
        session = null,
        battery = null,
        noiseMode = null,
        noiseModes = emptyList(),
        ancLevel = null,
        ancLevels = emptyList(),
        transparencyLevel = null,
        transparencyLevels = emptyList(),
        eqPreset = null,
        eqPresets = emptyList(),
        gameModeSupported = false,
        gameMode = null,
        blockedReasons = emptyMap(),
    )

    @Test
    fun everyUiCommandBecomesItsRustCommandAndSurvivesTheWire() {
        val expected = listOf(
            CirculationCommand.Directory to RustCommand.Directory,
            CirculationCommand.Offer to RustCommand.Offer,
            CirculationCommand.Pair(peer, "001234") to RustCommand.Pair(peer, "001234"),
            CirculationCommand.PairQr("qr") to RustCommand.PairQr("qr"),
            CirculationCommand.Forget(peer) to RustCommand.Forget(peer),
            CirculationCommand.Read(peer) to RustCommand.Read(peer),
            CirculationCommand.Handoff(null) to RustCommand.Handoff(null),
            CirculationCommand.Handoff("AA:BB") to RustCommand.Handoff("AA:BB"),
            CirculationCommand.Pull("AA:BB") to RustCommand.Pull("AA:BB"),
        )
        for ((command, rust) in expected) {
            assertEquals(rust, command.toRust())
            assertEquals(CoreDecodeResult.Ok(rust), decodeCirculationCommand(rust.encodeToBytes()))
        }
    }

    @Test
    fun everyRemoteSettingBecomesItsRustSetting() {
        val expected = listOf(
            HeadsetCommand.NoiseMode(NoiseMode.TRANSPARENCY) to RustHeadsetCommand.NoiseMode(RustNoiseMode.TRANSPARENCY),
            HeadsetCommand.NoiseMode(NoiseMode.WIND) to RustHeadsetCommand.NoiseMode(RustNoiseMode.WIND),
            HeadsetCommand.AncLevel(2) to RustHeadsetCommand.AncLevel(2),
            HeadsetCommand.TransparencyLevel(1) to RustHeadsetCommand.TransparencyLevel(1),
            HeadsetCommand.EqualizerPreset("hifi") to RustHeadsetCommand.EqualizerPreset("hifi"),
            HeadsetCommand.GameMode(true) to RustHeadsetCommand.GameMode(true),
        )
        for ((command, rust) in expected) {
            val rustCommand = RustCommand.Set(rustSession, rust)
            assertEquals(rustCommand, CirculationCommand.SetSetting(session, command).toRust())
            assertEquals(CoreDecodeResult.Ok(rustCommand), decodeCirculationCommand(rustCommand.encodeToBytes()))
        }
    }

    @Test
    fun aSettingThatCannotBeRemoteControlledHasNoRustCommand() {
        assertNull(CirculationCommand.SetSetting(session, HeadsetCommand.Gain(1)).toRust())
        assertNull(CirculationCommand.SetSetting(session, HeadsetCommand.Multipoint(true)).toRust())
    }

    @Test
    fun directoryOfferAndPairingRepliesBecomeUiReplies() {
        val offer = PairingOffer("001234", "qr", 10_000uL)
        val ticket = PairingTicket("001234", "qr", 10_000L)
        val directory = RustReply.Directory(
            paired = listOf(Peer("phone", "手机")),
            nearby = listOf(NearbyPeerRecord("laptop", "电脑", listOf("192.168.1.2"), 21871u)),
            offer = offer,
        )
        val expected = listOf(
            directory to PeerDirectory(mapOf("phone" to "手机"), mapOf("laptop" to "电脑"), ticket),
            RustReply.Directory(emptyList(), emptyList(), null) to PeerDirectory(),
            RustReply.Offer(offer) to PairingReply(ticket),
            RustReply.Paired(Peer("laptop", "电脑")) to CirculationResult("已配对"),
            RustReply.Forgotten to CirculationResult("已解除配对"),
        )
        for ((reply, ui) in expected) {
            assertEquals(ui, reply.toClient())
            assertEquals(ui, (decodeCirculationReply(reply.encodeToBytes()) as CoreDecodeResult.Ok).value.toClient())
        }
    }

    @Test
    fun handoffAndPullRepliesCarryTheirProgressAndOutcomeText() {
        val stages = mapOf(
            null to null,
            PullStage.CONTACTING_PEERS to "正在联系已配对的音源设备",
            PullStage.ACQUIRING to "正在接入耳机",
            PullStage.ACQUIRING_OFFLINE to "对端均不可达，正在尝试本机接入",
        )
        for ((stage, text) in stages) {
            val handoff = RustReply.Handoff(RustHandoff(available = true, mediaReady = false, offline = true, progress = stage))
            assertEquals(HandoffStatus(available = true, mediaReady = false, offline = true, progress = text), handoff.toClient())
        }
        val outcomes = mapOf(
            PullOutcome.ALREADY_CONNECTED to "本机已接入耳机",
            PullOutcome.ACQUIRED to "本机已接入耳机",
            PullOutcome.ACQUIRED_WITH_PEERS_UNREACHABLE to "对端均不可达，本机已接入耳机",
        )
        for ((outcome, text) in outcomes) assertEquals(CirculationResult(text), RustReply.Pulled(outcome).toClient())
    }

    @Test
    fun aReadingKeepsEveryFieldItsUiCounterpartNeeds() {
        val reading = RustReading(
            status = RustStatus.READY,
            headsetId = "earphones",
            headsetName = "EarFree i5",
            profile = RustModelProfile.I5_ZT,
            session = rustSession,
            battery = BatterySlots(left = 0, right = 100, case = null, layout = RustBatteryLayout.EARBUDS_ONLY),
            noiseMode = RustNoiseMode.WIND,
            noiseModes = listOf(RustNoiseMode.WIND, RustNoiseMode.TRANSPARENCY),
            ancLevel = 2,
            ancLevels = listOf(1, 2, 3),
            transparencyLevel = 1,
            transparencyLevels = listOf(1, 2),
            eqPreset = "pop",
            eqPresets = listOf("pop", "hifi"),
            gameModeSupported = true,
            gameMode = false,
            blockedReasons = mapOf(RustHeadsetSetting.SPATIAL_CONTENT to SettingPrerequisite.SPATIAL_MODE_REQUIRED),
        )
        val expected = RemoteReading(
            status = RemoteControlStatus.READY,
            deviceName = "EarFree i5",
            profile = ModelProfile.I5_ZT,
            session = session,
            mode = NoiseMode.WIND,
            modes = listOf(NoiseMode.WIND, NoiseMode.TRANSPARENCY),
            ancLevel = 2,
            ancLevels = listOf(1, 2, 3),
            transparencyLevel = 1,
            transparencyLevels = listOf(1, 2),
            eqPreset = "pop",
            eqPresets = listOf("pop", "hifi"),
            gameModeSupported = true,
            gameMode = false,
            blockedReasons = mapOf(DeviceSetting.SPATIAL_CONTENT to "请先开启空间模式"),
            left = 0,
            right = 100,
            batteryLayout = BatteryLayout.EARBUDS_ONLY,
        )
        assertEquals(expected, RustReply.Reading(reading).toClient())
    }

    @Test
    fun aReadingWithoutBatteryLeavesTheLayoutUnspecified() {
        val ui = assertIs<RemoteReading>(RustReply.Reading(emptyReading(RustStatus.IDLE)).toClient())
        assertEquals(RemoteControlStatus.IDLE, ui.status)
        assertNull(ui.batteryLayout)
        assertNull(ui.left)
        assertNull(ui.session)
    }

    @Test
    fun everyRemoteErrorCodeHasItsText() {
        val expected = listOf(
            RemoteError.UnsupportedVersion to "对端流转协议版本不兼容",
            RemoteError.NotPaired to "对端尚未完成流转配对",
            RemoteError.PeerOffline to "无法联系对端",
            RemoteError.ServiceClosed to "流转服务已关闭",
            RemoteError.OwnerInactive to "控制所有者已结束",
            RemoteError.PairingNotOpen to "请先在电脑显示配对码",
            RemoteError.PairingExpired to "配对码已失效",
            RemoteError.PairingAttemptsExceeded to "请重新显示配对码",
            RemoteError.PairingRejected to "配对校验中断，请刷新配对码",
            RemoteError.InvalidPairingCode to "请输入六位配对码",
            RemoteError.InvalidQrPayload to "无法识别流转二维码",
            RemoteError.SessionExpired to "耳机会话已结束，请重新读取",
            RemoteError.SessionNotReady to "控制会话尚未就绪，暂不能设置",
            RemoteError.UnsupportedOperation to "该平台暂不支持流转",
            RemoteError.UnsupportedSetting to "耳机不支持该设置",
            RemoteError.InvalidArgument to "参数无效",
            RemoteError.Protocol to "流转报文无效",
            RemoteError.Timeout to "连接超时",
            RemoteError.IdentityError to "流转身份无效",
            RemoteError.StorageError to "流转配对存储不可用",
            RemoteError.DiscoveryError to "局域网发现不可用",
            RemoteError.PullInProgress to "另一项耳机流转正在进行",
        )
        for ((error, text) in expected) {
            assertEquals(CirculationFailure(text), RustReply.Failed(error).toClient())
            assertEquals(CoreDecodeResult.Ok(RustReply.Failed(error)), decodeCirculationReply(RustReply.Failed(error).encodeToBytes()))
        }
    }

    @Test
    fun nestedErrorsExplainTheirCause() {
        assertEquals("原音源未让出耳机 · 对方正在通话", RemoteError.YieldRefused(YieldRefusal.InCall).text())
        assertEquals("本机未能接入耳机 · 麦克风正在使用", RemoteError.AcquireFailed(YieldRefusal.MicInUse).text())
        assertEquals("原音源未让出耳机 · 对方读不到耳机的音频状态", RemoteError.YieldRefused(YieldRefusal.AudioStateUnreadable("proxy timeout")).text())
        assertEquals("本机未能接入耳机 · 系统蓝牙暂时不可用", RemoteError.AcquireFailed(YieldRefusal.PlatformUnavailable("a2dp")).text())
        assertEquals("本机未能接入耳机 · 耳机已连接但没有接入音频", RemoteError.AcquireFailed(YieldRefusal.MediaNotConnected).text())
        val control = ControlErrorCode.entries.map { RemoteError.ControlFailed(it).text() }
        assertEquals(ControlErrorCode.entries.size, control.toSet().size)
        assertEquals("耳机未确认 · 耳机响应超时", RemoteError.ControlFailed(ControlErrorCode.TIMEOUT).text())
        assertEquals(SettingPrerequisite.entries.size, SettingPrerequisite.entries.map { it.text() }.toSet().size)
    }

    @Test
    fun everyRustEnumReachesTheUiWithoutLoss() {
        for (profile in RustModelProfile.entries) {
            val reading = emptyReading(RustStatus.IDLE).copy(profile = profile)
            assertEquals(profile.name.replace("_", ""), assertIs<RemoteReading>(RustReply.Reading(reading).toClient()).profile?.name?.replace("_", ""))
        }
        for (status in RustStatus.entries) {
            val reading = emptyReading(status)
            assertEquals(status.name, assertIs<RemoteReading>(RustReply.Reading(reading).toClient()).status.name)
        }
        for (mode in RustNoiseMode.entries) {
            val reading = emptyReading(RustStatus.IDLE).copy(noiseMode = mode)
            assertEquals(mode.name, assertIs<RemoteReading>(RustReply.Reading(reading).toClient()).mode?.name)
        }
    }
}
