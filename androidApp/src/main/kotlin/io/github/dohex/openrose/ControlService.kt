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
import io.github.dohex.openrose.androidintegration.OrdinarySessionHold
import io.github.dohex.openrose.androidintegration.ordinaryBackgroundRequested
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

class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var sessionHold: OrdinarySessionHold? = null
    private val app get() = application as OpenRoseApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            app.setBackgroundControlEnabled(false)
            leaveForeground()
            return START_NOT_STICKY
        }
        if (!enterForeground(notification(app.runtime.state.value))) return START_NOT_STICKY
        if (!app.runtime.integration.value.ordinaryBackgroundRequested) {
            leaveForeground()
            return START_NOT_STICKY
        }
        app.background.running()
        if (sessionHold == null) sessionHold = app.runtime.holdOrdinarySession()
        if (observer == null) {
            observer = scope.launch {
                combine(app.runtime.state, app.runtime.integration) { state, integration ->
                    integration.ordinaryBackgroundRequested to notificationText(state)
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
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean {
        val started = app.background.tryStart {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (!started) stopSelf()
        return started
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
        app.background.stopped()
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
