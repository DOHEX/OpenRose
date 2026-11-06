package io.github.dohex.openrose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import io.github.dohex.openrose.androidintegration.BackgroundControlBlock
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.OrdinarySessionHold
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The foreground host of ordinary background control (`connectedDevice`). It holds the ordinary session
 * of the one control runtime owned by [OpenRoseApplication] while it runs, so background control keeps
 * the session without any interface and the session ends with the service (unless the interface is open).
 * It never replaces or disconnects a session itself: audio-driven connection, release and manual
 * operations stay entirely in the runtime. It stops when the user turns the intent off and is not
 * restarted by the system after the process is gone (that needs a separate, user-visible wake-up path and
 * never bypasses a force stop).
 */
class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var sessionHold: OrdinarySessionHold? = null
    private val app get() = application as OpenRoseApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            // The notification action is the same user choice as the settings switch.
            app.setBackgroundControlEnabled(false)
            leaveForeground()
            return START_NOT_STICKY
        }
        // Every start request must reach startForeground, including repeated ones.
        if (!enterForeground(notification(app.runtime.state.value))) return START_NOT_STICKY
        if (!app.ordinaryControlSelected()) {
            leaveForeground()
            return START_NOT_STICKY
        }
        app.backgroundServiceRunning()
        if (sessionHold == null) sessionHold = app.runtime.holdOrdinarySession()
        if (observer == null) {
            observer = scope.launch {
                // Background control exists only while ordinary control is the saved choice; module control
                // (or none) ends it without touching the saved intent.
                combine(app.runtime.state, app.runtime.integration) { state, integration ->
                    val backgroundEnabled = integration.settings.backgroundControlEnabled &&
                        integration.owner.selected == ControlWay.ORDINARY
                    backgroundEnabled to notificationText(state)
                }
                    .distinctUntilChanged()
                    .collect { (enabled, _) ->
                        if (!enabled) {
                            leaveForeground()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(app.runtime.state.value))
                        }
                    }
            }
        }
        // Not sticky: reviving after process death belongs to a user-granted wake-up mechanism,
        // and a stopped or force-stopped app must stay stopped.
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (denied: SecurityException) {
        reject(BackgroundControlBlock.SERVICE_START_REFUSED)
    } catch (restricted: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
        reject(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)
    }

    private fun reject(reason: BackgroundControlBlock): Boolean {
        app.backgroundServiceRejected(reason)
        stopSelf()
        return false
    }

    private fun leaveForeground() {
        observer?.cancel()
        observer = null
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseSession() {
        sessionHold?.release()
        sessionHold = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseSession()
        app.backgroundServiceStopped()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenRose 后台控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "后台控制运行期间显示的必要通知"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notification(state: ControlState): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disable = PendingIntent.getService(
            this,
            1,
            Intent(this, ControlService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenRose 后台控制")
            .setContentText(notificationText(state))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停用后台控制",
                    disable,
                ).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationText(state: ControlState): String = when (val connection = state.connection) {
        is ConnectionState.Connected -> "已连接 ${connection.profile.displayName}"

        is ConnectionState.Connecting -> "正在连接耳机"

        ConnectionState.Disconnected -> when (val transport = state.platform.transport) {
            TransportAvailability.Available -> "等待耳机音频连接"
            TransportAvailability.Disabled -> "系统蓝牙已关闭"
            is TransportAvailability.Unavailable -> transport.reason
        }
    }

    companion object {
        const val ACTION_DISABLE = "io.github.dohex.openrose.action.DISABLE_BACKGROUND_CONTROL"
        internal const val CHANNEL_ID = "openrose_control"
        private const val NOTIFICATION_ID = 0x0C01
    }
}
