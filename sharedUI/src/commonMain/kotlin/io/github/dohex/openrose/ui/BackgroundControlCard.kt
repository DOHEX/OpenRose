package io.github.dohex.openrose.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.androidintegration.BackgroundControlBlock
import io.github.dohex.openrose.androidintegration.BackgroundControlStatus
import io.github.dohex.openrose.androidintegration.BackgroundRecovery
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.PresenceObservation
import io.github.dohex.openrose.androidintegration.WakeBand
import io.github.dohex.openrose.androidintegration.WakePrerequisite
import io.github.dohex.openrose.androidintegration.backgroundControl
import io.github.dohex.openrose.androidintegration.backgroundRecovery
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.androidintegration.IntegrationSettings

/**
 * Test tags of the background-control group. Rows report their state through the tag, so tests
 * assert which state is shown and never the wording that explains it.
 */
internal object BackgroundControlTags {
    const val SWITCH = "background-control.switch"

    /** Every status row's tag starts with this; none exists while background control is not in force. */
    const val STATUS_PREFIX = "background-control.status."
    const val STATUS_NOT_RUNNING = "${STATUS_PREFIX}not-running"
    const val STATUS_RUNNING = "${STATUS_PREFIX}running"
    const val NOTIFICATION_HIDDEN = "background-control.notification-hidden"
    const val RECOVERY_NEEDS_ASSOCIATION = "background-control.recovery.needs-association"
    const val RECOVERY_PRESENCE_PENDING = "background-control.recovery.presence-pending"
    const val RECOVERY_PRESENCE_REFUSED = "background-control.recovery.presence-refused"
    const val RECOVERY_ARMED = "background-control.recovery.armed"
    const val GRANT_BLUETOOTH = "background-control.grant-bluetooth"
    const val ALLOW_NOTIFICATION = "background-control.allow-notification"
    const val ASSOCIATE = "background-control.associate"
    const val APP_SETTINGS = "background-control.app-settings"

    fun blocked(reason: BackgroundControlBlock) = "${STATUS_PREFIX}blocked.${reason.name}"
}

/** Ordinary background control applies wherever Android hosts the control runtime. */
internal fun ControlState.backgroundControlApplicable(integration: IntegrationState): Boolean =
    platform.hostPlatform == HostPlatform.ANDROID &&
        platform.controlTransportApplicability == PlatformApplicability.APPLICABLE &&
        integration.backgroundControl(this) != BackgroundControlStatus.Unsupported

/**
 * The switch only stores the user's intent. The status rows below it report what the platform
 * actually did, so a saved preference is never shown as background control being ready.
 */
@Composable
internal fun BackgroundControlGroup(
    state: ControlState,
    integration: IntegrationState,
    onChange: (IntegrationSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestAssociation: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val settings = integration.settings
    AppGroup(
        title = "后台控制",
        footnote = wakeGuidance(integration.platform.backgroundService.wake.band) +
            "在系统中强制停止 OpenRose 会结束后台控制，直到你再次打开它。",
    ) {
        Tagged(BackgroundControlTags.SWITCH) {
            AppSwitchRow(
                title = "后台控制",
                summary = "关闭界面或移除任务后，保持已连接耳机的控制，并显示运行通知",
                checked = settings.backgroundControlEnabled,
                onChange = { onChange(settings.copy(backgroundControlEnabled = it)) },
            )
        }
        when (val status = integration.backgroundControl(state)) {
            BackgroundControlStatus.Unsupported, BackgroundControlStatus.Disabled -> Unit
            BackgroundControlStatus.NotRunning -> Tagged(BackgroundControlTags.STATUS_NOT_RUNNING) {
                ToneInfoRow("后台状态", "已启用，尚未运行；耳机下次音频连接或打开 OpenRose 时启动", StatusTone.CAUTION)
            }
            is BackgroundControlStatus.Running -> {
                Tagged(BackgroundControlTags.STATUS_RUNNING) { ToneInfoRow("后台状态", "运行中", StatusTone.GOOD) }
                if (!status.notificationVisible) {
                    Tagged(BackgroundControlTags.NOTIFICATION_HIDDEN) {
                        ToneInfoRow("运行通知", "系统已隐藏，后台控制仍在运行", StatusTone.CAUTION)
                    }
                    AppButton(
                        text = "允许通知",
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.padding(start = 16.dp).testTag(BackgroundControlTags.ALLOW_NOTIFICATION),
                    )
                }
            }
            is BackgroundControlStatus.Blocked -> {
                val (message, tone) = when (status.reason) {
                    BackgroundControlBlock.BLUETOOTH_PERMISSION ->
                        "缺少蓝牙权限，后台控制无法运行" to StatusTone.ERROR
                    BackgroundControlBlock.BACKGROUND_START_RESTRICTED ->
                        "系统限制了从后台启动，请打开 OpenRose 后重试" to StatusTone.CAUTION
                    BackgroundControlBlock.SERVICE_START_REFUSED ->
                        "系统拒绝了前台服务启动，后台控制未运行" to StatusTone.ERROR
                }
                Tagged(BackgroundControlTags.blocked(status.reason)) { ToneInfoRow("后台状态", message, tone) }
                if (status.reason == BackgroundControlBlock.BLUETOOTH_PERMISSION) {
                    AppButton(
                        text = "授予蓝牙权限",
                        onClick = onRequestPermission,
                        modifier = Modifier.padding(start = 16.dp).testTag(BackgroundControlTags.GRANT_BLUETOOTH),
                    )
                }
            }
        }
        if (settings.backgroundControlEnabled) {
            RecoveryRows(integration.backgroundRecovery(state), integration, onRequestAssociation, onOpenAppSettings)
        }
    }
}

/** What the Android version really offers; the mechanisms differ per version band and are never presented as one. */
private fun wakeGuidance(band: WakeBand?): String = when (band) {
    WakeBand.ANDROID_8_11 -> "Android 8–11：耳机音频连接时由系统广播唤起，无需关联耳机。"
    WakeBand.ANDROID_12_13 -> "Android 12–13：需先在系统中关联耳机，关联后耳机出现时系统会唤起 OpenRose。"
    WakeBand.ANDROID_14_PLUS -> "Android 14 及以上：需先在系统中关联耳机，并保持蓝牙权限已授予，后台服务才可被唤起。"
    null -> ""
}

/** Automatic revival readiness; "configured" never claims the ROM will actually wake the app. */
@Composable
private fun RecoveryRows(
    recovery: BackgroundRecovery,
    integration: IntegrationState,
    onRequestAssociation: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when (recovery) {
        BackgroundRecovery.Unsupported, BackgroundRecovery.Disabled, BackgroundRecovery.WaitingForUnlock -> return
        is BackgroundRecovery.Incomplete -> {
            if (WakePrerequisite.COMPANION_ASSOCIATION in recovery.missing) {
                Tagged(BackgroundControlTags.RECOVERY_NEEDS_ASSOCIATION) {
                    ToneInfoRow("自动唤起", "尚未关联耳机或关联已被取消，进程被回收或手机重启后不会自动恢复", StatusTone.CAUTION)
                }
                AppButton(
                    text = "关联耳机",
                    onClick = onRequestAssociation,
                    modifier = Modifier.padding(start = 16.dp).testTag(BackgroundControlTags.ASSOCIATE),
                )
            } else if (WakePrerequisite.PRESENCE_OBSERVATION in recovery.missing) {
                val refused = integration.platform.backgroundService.wake.presence == PresenceObservation.REFUSED
                Tagged(
                    if (refused) BackgroundControlTags.RECOVERY_PRESENCE_REFUSED else BackgroundControlTags.RECOVERY_PRESENCE_PENDING,
                ) {
                    ToneInfoRow(
                        "自动唤起",
                        if (refused) "系统拒绝了设备在线监听，请检查应用权限后重新关联" else "设备在线监听尚未生效，打开 OpenRose 后建立",
                        if (refused) StatusTone.ERROR else StatusTone.CAUTION,
                    )
                }
                if (refused) {
                    AppButton(
                        text = "重新关联耳机",
                        onClick = onRequestAssociation,
                        modifier = Modifier.padding(start = 16.dp).testTag(BackgroundControlTags.ASSOCIATE),
                    )
                }
            }
            // A missing Bluetooth permission is already explained by the background status above.
        }
        is BackgroundRecovery.Armed -> {
            Tagged(BackgroundControlTags.RECOVERY_ARMED) {
                ToneInfoRow(
                    "自动唤起",
                    "系统条件已配置；自启动和省电限制无法检测，是否真正唤起需以实际表现为准",
                    StatusTone.CAUTION,
                )
            }
        }
    }
    AppButton(
        text = "应用设置（自启动、电量）",
        onClick = onOpenAppSettings,
        modifier = Modifier.padding(start = 16.dp).testTag(BackgroundControlTags.APP_SETTINGS),
    )
}
