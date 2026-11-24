package io.github.dohex.openrose

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.androidintegration.BackgroundServiceFact
import io.github.dohex.openrose.androidintegration.BackgroundServiceFacts
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.RunningNotification
import io.github.dohex.openrose.androidintegration.WakeFacts
import io.github.dohex.openrose.androidintegration.classifyHyperOsBuild
import io.github.dohex.openrose.androidintegration.runningNotificationVisible
import io.github.dohex.openrose.androidintegration.scope.INTEGRATION_SCOPE_PACKAGES
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.platform.StatusTargetLaunch

internal fun Context.readIntegrationPlatform(
    backgroundService: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    wake: WakeFacts = WakeFacts(),
): IntegrationPlatformState = IntegrationPlatformState(
    module = ModuleAvailability.Unavailable("尚未收到兼容框架服务，普通客户端可独立运行"),
    systemIntegrationApplicability = classifyHyperOsBuild(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        incrementalVersion = Build.VERSION.INCREMENTAL,
    ),
    installedScopes = INTEGRATION_SCOPE_PACKAGES.filterTo(mutableSetOf()) { name ->
        try {
            packageManager.getApplicationInfo(name, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    },
    backgroundService = BackgroundServiceFacts(backgroundService, runningNotification(), wake),
    dynamicColorApplicability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PlatformApplicability.APPLICABLE
    } else {
        PlatformApplicability.NOT_APPLICABLE
    },
    unavailableStatusTargets = StatusTargetLaunch.unavailableTargets(packageManager),
)

private fun Context.runningNotification(): RunningNotification {
    val manager = getSystemService(NotificationManager::class.java) ?: return RunningNotification.UNKNOWN
    val importance = manager.getNotificationChannel(ControlService.CHANNEL_ID)?.importance
    val visible = runningNotificationVisible(manager.areNotificationsEnabled(), importance)
    return if (visible) RunningNotification.VISIBLE else RunningNotification.HIDDEN
}
