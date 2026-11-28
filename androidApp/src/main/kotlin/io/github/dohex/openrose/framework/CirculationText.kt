package io.github.dohex.openrose.framework

import uniffi.openrose_circulation.PullOutcome
import uniffi.openrose_circulation.PullStage
import uniffi.openrose_circulation.RemoteError
import uniffi.openrose_circulation.YieldRefusal
import uniffi.openrose_control.ControlErrorCode
import uniffi.openrose_headset.SettingPrerequisite

internal const val UNSUPPORTED_REMOTE_SETTING = "不支持远程设置该项"
internal const val PAIRED_MESSAGE = "已配对"
internal const val FORGOTTEN_MESSAGE = "已解除配对"
internal const val MODULE_CONTROL_NOT_READY = "模块控制尚未就绪"
internal const val CIRCULATION_UNAVAILABLE = "流转服务不可用"

@Suppress("CyclomaticComplexMethod")
internal fun RemoteError.text(): String = when (this) {
    is RemoteError.ControlFailed -> "耳机未确认 · ${v1.text()}"
    is RemoteError.YieldRefused -> "原音源未让出耳机 · ${v1.holderText()}"
    is RemoteError.AcquireFailed -> "本机未能接入耳机 · ${v1.localText()}"
    RemoteError.UnsupportedVersion -> "对端流转协议版本不兼容"
    RemoteError.NotPaired -> "对端尚未完成流转配对"
    RemoteError.PeerOffline -> "无法联系对端"
    RemoteError.ServiceClosed -> "流转服务已关闭"
    RemoteError.OwnerInactive -> "控制所有者已结束"
    RemoteError.PairingNotOpen -> "请先在电脑显示配对码"
    RemoteError.PairingExpired -> "配对码已失效"
    RemoteError.PairingAttemptsExceeded -> "请重新显示配对码"
    RemoteError.PairingRejected -> "配对校验中断，请刷新配对码"
    RemoteError.InvalidPairingCode -> "请输入六位配对码"
    RemoteError.InvalidQrPayload -> "无法识别流转二维码"
    RemoteError.SessionExpired -> "耳机会话已结束，请重新读取"
    RemoteError.SessionNotReady -> "控制会话尚未就绪，暂不能设置"
    RemoteError.UnsupportedOperation -> "该平台暂不支持流转"
    RemoteError.UnsupportedSetting -> "耳机不支持该设置"
    RemoteError.InvalidArgument -> "参数无效"
    RemoteError.Protocol -> "流转报文无效"
    RemoteError.Timeout -> "连接超时"
    RemoteError.IdentityError -> "流转身份无效"
    RemoteError.StorageError -> "流转配对存储不可用"
    RemoteError.DiscoveryError -> "局域网发现不可用"
    RemoteError.PullInProgress -> "另一项耳机流转正在进行"
}

private fun ControlErrorCode.text(): String = when (this) {
    ControlErrorCode.PERMISSION_REQUIRED -> "缺少蓝牙权限"
    ControlErrorCode.TRANSPORT_UNAVAILABLE -> "蓝牙通道不可用"
    ControlErrorCode.NOT_READY -> "耳机尚未就绪"
    ControlErrorCode.UNSUPPORTED -> "耳机不支持该操作"
    ControlErrorCode.INVALID_ARGUMENT -> "参数无效"
    ControlErrorCode.NOT_APPLIED -> "耳机未应用该设置"
    ControlErrorCode.TIMEOUT -> "耳机响应超时"
    ControlErrorCode.DISCONNECTED -> "耳机已断开"
    ControlErrorCode.FAILED -> "操作失败"
}

private fun YieldRefusal.holderText(): String = when (this) {
    YieldRefusal.InCall -> "对方正在通话"
    YieldRefusal.MicInUse -> "对方的麦克风正在使用"
    YieldRefusal.StillConnected -> "对方让出后仍与耳机保持连接"
    YieldRefusal.MediaNotConnected -> "对方暂时无法让出耳机"
    is YieldRefusal.AudioStateUnreadable -> "对方读不到耳机的音频状态"
    is YieldRefusal.PlatformUnavailable -> "对方的系统暂时无法让出耳机"
}

private fun YieldRefusal.localText(): String = when (this) {
    YieldRefusal.InCall -> "手机正在通话"
    YieldRefusal.MicInUse -> "麦克风正在使用"
    YieldRefusal.StillConnected -> "耳机仍被占用"
    YieldRefusal.MediaNotConnected -> "耳机已连接但没有接入音频"
    is YieldRefusal.AudioStateUnreadable -> "读不到耳机的音频状态"
    is YieldRefusal.PlatformUnavailable -> "系统蓝牙暂时不可用"
}

internal fun PullOutcome.text(): String = when (this) {
    PullOutcome.ALREADY_CONNECTED -> "本机已接入耳机"
    PullOutcome.ACQUIRED -> "本机已接入耳机"
    PullOutcome.ACQUIRED_WITH_PEERS_UNREACHABLE -> "对端均不可达，本机已接入耳机"
}

internal fun PullStage.text(): String = when (this) {
    PullStage.CONTACTING_PEERS -> "正在联系已配对的音源设备"
    PullStage.ACQUIRING -> "正在接入耳机"
    PullStage.ACQUIRING_OFFLINE -> "对端均不可达，正在尝试本机接入"
}

internal fun SettingPrerequisite.text(): String = when (this) {
    SettingPrerequisite.SPATIAL_MODE_REQUIRED -> "请先开启空间模式"
    SettingPrerequisite.INDEPENDENT_MIC_ACTIVATION_REQUIRED -> "请先激活独立麦克风"
}
