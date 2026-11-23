package io.github.dohex.openrose.module.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.platform.StatusTargetLaunch

internal class PresentationNotificationContent(
    private val appContext: Context,
    private val actions: PresentationActions,
) {
    private val focusTemplate = FocusNotificationTemplate(appContext)

    fun statusCard(snapshot: RenderedSnapshot): StatusCard {
        val noise = snapshot.knownNoiseMode()
        val writableNoise = noise?.takeIf { snapshot.canControlNoise(it) }
        return StatusCard(
            summary = snapshot.batterySummary(),
            noiseText = noise?.let(::modeLabel),
            title = cardTitle(snapshot),
            nextNoise = if (actions.registered) writableNoise?.let { snapshot.nextNoiseMode(it) } else null,
            canDisconnect = actions.registered && snapshot.address != null,
            iconColors = focusTemplate.statusCardColors(snapshot),
            controlReceiverMissing = writableNoise != null && !actions.registered,
        )
    }

    fun statusNotificationBuilder(snapshot: RenderedSnapshot, card: StatusCard, showsPrompt: Boolean): Notification.Builder {
        val buttons = listOfNotNull(
            card.nextNoise?.let { actions.noiseAction(snapshot, it) },
            if (card.canDisconnect) actions.disconnectAction(snapshot) else null,
        )
        val builder = Notification.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(card.title)
            .setContentText(card.body)
            .setStyle(Notification.BigTextStyle().bigText(card.body))
            .setContentIntent(statusContentIntent(snapshot))
            .setWhen(0L)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        buttons.forEach { (_, action) -> builder.addAction(action) }
        return builder.addExtras(focusTemplate.statusExtras(snapshot, card, buttons, showsPrompt))
    }

    fun promptExtras(
        snapshot: RenderedSnapshot,
        durationMillis: Long,
    ): Bundle = focusTemplate.promptExtras(snapshot, cardTitle(snapshot), durationMillis)

    fun promptNotification(snapshot: RenderedSnapshot, extras: Bundle): Notification = Notification.Builder(appContext, PROMPT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(snapshot.profileLabel())
        .setContentText(snapshot.batterySummary())
        .setTicker("OpenRose")
        .setWhen(0L)
        .addExtras(extras)
        .build()

    private fun cardTitle(snapshot: RenderedSnapshot): String = deviceAlias(snapshot) ?: snapshot.profileLabel()

    @SuppressLint("MissingPermission")
    private fun deviceAlias(snapshot: RenderedSnapshot): String? {
        val address = snapshot.address ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { appContext.bluetoothDevice(address)?.alias }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun statusContentIntent(snapshot: RenderedSnapshot): PendingIntent {
        val intent = when (val launch = statusTargetLaunch(snapshot)) {
            is StatusLaunch.Open -> launch.intent

            is StatusLaunch.Fallback -> openRoseIntent().putExtra(
                BridgeWire.EXTRA_FALLBACK_REASON,
                StatusTargetLaunch.fallbackNotice(launch.reason),
            )
        }
        return PendingIntent.getActivity(
            appContext,
            STATUS_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun statusTargetLaunch(snapshot: RenderedSnapshot): StatusLaunch = when (snapshot.statusTarget) {
        BridgePresentationWire.TARGET_SYSTEM -> systemHeadsetLaunch(snapshot)

        BridgePresentationWire.TARGET_ROSELINK -> StatusTargetLaunch.roseLinkIntent(appContext.packageManager)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(StatusLaunch::Open)
            ?: StatusLaunch.Fallback(StatusTargetLaunch.ROSELINK_MISSING)

        BridgePresentationWire.TARGET_QUICK_PANEL -> StatusLaunch.Open(
            Intent()
                .setClassName(BridgeWire.APP_PACKAGE, "${BridgeWire.APP_PACKAGE}.QuickPanelActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        else -> StatusLaunch.Open(openRoseIntent())
    }

    private fun openRoseIntent(): Intent = Intent(BridgeWire.ACTION_SHOW_HEADPHONES).apply {
        setClassName(BridgeWire.APP_PACKAGE, "${BridgeWire.APP_PACKAGE}.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun systemHeadsetLaunch(snapshot: RenderedSnapshot): StatusLaunch {
        val address = snapshot.address
        val device = address?.let { runCatching { appContext.bluetoothDevice(it) }.getOrNull() }
        if (address == null || device == null) return StatusLaunch.Fallback(StatusTargetLaunch.SYSTEM_DEVICE_MISSING)
        val compatibilityId = NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID
        val intent = StatusTargetLaunch.systemHeadsetIntent().apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra("bluetoothaddress", address)
            putExtra("MIUI_HEADSET_SUPPORT", "$compatibilityId,$SYSTEM_HEADSET_SUPPORT_FLAGS")
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", compatibilityId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!StatusTargetLaunch.resolves(appContext.packageManager, intent)) {
            return StatusLaunch.Fallback(StatusTargetLaunch.SYSTEM_PAGE_UNAVAILABLE)
        }
        return StatusLaunch.Open(intent)
    }

    private sealed interface StatusLaunch {
        class Open(val intent: Intent) : StatusLaunch
        class Fallback(val reason: String) : StatusLaunch
    }
}

internal data class StatusCard(
    val summary: String,
    val noiseText: String?,
    val title: String,
    val nextNoise: String?,
    val canDisconnect: Boolean,
    val iconColors: IconColors,
    val controlReceiverMissing: Boolean,
) {
    val body = summary + noiseText?.let { " · $it" }.orEmpty()

    fun fingerprint(snapshot: RenderedSnapshot, tag: String): String = listOf(
        snapshot.identity.stableKey, tag, title, body, nextNoise.orEmpty(), canDisconnect,
        snapshot.statusIsland, snapshot.statusTarget, iconColors,
    ).joinToString("|")
}

private const val SYSTEM_HEADSET_SUPPORT_FLAGS = "000000000000000010000000"
