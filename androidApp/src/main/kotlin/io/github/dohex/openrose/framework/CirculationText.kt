package io.github.dohex.openrose.framework

import uniffi.openrose_circulation.PullOutcome
import uniffi.openrose_circulation.PullStage
import uniffi.openrose_circulation.RemoteError
import uniffi.openrose_circulation.YieldRefusal
import uniffi.openrose_control.ControlErrorCode
import uniffi.openrose_headset.SettingPrerequisite

private val remoteErrors: Map<RemoteError, String> = mapOf(
    RemoteError.UnsupportedVersion to "对端互联协议版本不兼容",
    RemoteError.NotPaired to "对端尚未完成流转配对",
    RemoteError.PeerOffline to "无法联系对端",
    RemoteError.ServiceClosed to "互联服务已关闭",
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
    RemoteError.Protocol to "互联报文无效",
    RemoteError.Timeout to "连接超时",
    RemoteError.IdentityError to "流转身份无效",
    RemoteError.StorageError to "流转配对存储不可用",
    RemoteError.DiscoveryError to "局域网发现不可用",
    RemoteError.PullInProgress to "另一项耳机流转正在进行",
)

private val controlErrors: Map<ControlErrorCode, String> = mapOf(
    ControlErrorCode.PERMISSION_REQUIRED to "缺少蓝牙权限",
    ControlErrorCode.TRANSPORT_UNAVAILABLE to "蓝牙通道不可用",
    ControlErrorCode.NOT_READY to "耳机尚未就绪",
    ControlErrorCode.UNSUPPORTED to "耳机不支持该操作",
    ControlErrorCode.INVALID_ARGUMENT to "参数无效",
    ControlErrorCode.NOT_APPLIED to "耳机未应用该设置",
    ControlErrorCode.TIMEOUT to "耳机响应超时",
    ControlErrorCode.DISCONNECTED to "耳机已断开",
    ControlErrorCode.FAILED to "操作失败",
)

private val yieldRefusals: Map<YieldRefusal, String> = mapOf(
    YieldRefusal.InCall to "手机通话中，无法让出耳机",
    YieldRefusal.MicInUse to "麦克风正在使用中，无法让出耳机",
)

private val pullOutcomes: Map<PullOutcome, String> = mapOf(
    PullOutcome.ALREADY_CONNECTED to "本机已接入耳机",
    PullOutcome.ACQUIRED to "本机已接入耳机",
    PullOutcome.ACQUIRED_WITH_PEERS_UNREACHABLE to "对端均不可达，本机已接入耳机",
)

private val pullStages: Map<PullStage, String> = mapOf(
    PullStage.CONTACTING_PEERS to "正在联系已配对的音源设备",
    PullStage.ACQUIRING to "正在接入耳机",
    PullStage.ACQUIRING_OFFLINE to "对端均不可达，正在尝试本机接入",
)

private val prerequisites: Map<SettingPrerequisite, String> = mapOf(
    SettingPrerequisite.SPATIAL_MODE_REQUIRED to "请先开启空间模式",
    SettingPrerequisite.INDEPENDENT_MIC_ACTIVATION_REQUIRED to "请先激活独立麦克风",
)

internal const val UNSUPPORTED_REMOTE_SETTING = "不支持远程设置该项"
internal const val PAIRED_MESSAGE = "已配对"
internal const val FORGOTTEN_MESSAGE = "已解除配对"

internal fun RemoteError.text(): String = when (this) {
    is RemoteError.ControlFailed -> "耳机未确认 · ${controlErrors.getValue(v1)}"
    is RemoteError.YieldRefused -> "原音源未让出耳机 · ${v1.text()}"
    is RemoteError.AcquireFailed -> "本机未能接入耳机 · ${v1.text()}"
    else -> remoteErrors.getValue(this)
}

internal fun YieldRefusal.text(): String = (this as? YieldRefusal.Unavailable)?.v1 ?: yieldRefusals.getValue(this)

internal fun PullOutcome.text(): String = pullOutcomes.getValue(this)

internal fun PullStage.text(): String = pullStages.getValue(this)

internal fun SettingPrerequisite.text(): String = prerequisites.getValue(this)
