package io.github.dohex.openrose.module.presentation

import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.module.systemIntegrationResult
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PresentationActions(
    private val appContext: Context,
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
    private val session: PresentationActionSession,
) {
    private val executor = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_ACTIONS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "OpenRose-presentation-action").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val pending = mutableSetOf<String>()
    private var registration: ActionRegistration? = null
    val registered: Boolean get() = registration != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val sender = intent.senderIdentity() ?: return
            val action = intent.action
            val token = intent.getStringExtra(EXTRA_ACTION_TOKEN)
            val kind = intent.getStringExtra(EXTRA_ACTION_KIND)
            val targetMode = intent.getStringExtra(BridgePresentationWire.NOISE_MODE)
            handler.post {
                val active = session.currentSnapshot ?: return@post
                val binding = registration ?: return@post
                if (action != binding.action || token != binding.token) return@post
                if (active.identity != sender || !active.statusNotificationEnabled || !session.statusActive) return@post
                when (kind) {
                    ACTION_KIND_NOISE -> targetMode?.let { executeNoise(active, it) }
                    ACTION_KIND_DISCONNECT -> executeDisconnect(active)
                }
            }
        }
    }

    fun noiseAction(
        snapshot: RenderedSnapshot,
        mode: String,
    ): Pair<String, Notification.Action>? = registration?.button(appContext, snapshot, ACTION_KIND_NOISE, mode)

    fun disconnectAction(
        snapshot: RenderedSnapshot,
    ): Pair<String, Notification.Action>? = registration?.button(appContext, snapshot, ACTION_KIND_DISCONNECT, null)

    fun register() {
        if (appContext.checkSelfPermission(BLUETOOTH_PRIVILEGED_PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val token = UUID.randomUUID().toString()
        val action = "${appContext.packageName}.OPENROSE_ACTION_$token"
        val filter = IntentFilter(action).apply {
            addDataScheme(ACTION_DATA_SCHEME)
            addDataAuthority(ACTION_DATA_HOST, null)
        }
        systemIntegrationResult {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED_PERMISSION, handler, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED_PERMISSION, handler)
            }
            registration = ActionRegistration(action, token)
        }.onFailure { failure ->
            registration = null
            Log.w(PRESENTATION_TAG, "action_receiver_registration_failed", failure)
        }
    }

    fun unregister() {
        pending.clear()
        if (registered) {
            systemIntegrationResult { appContext.unregisterReceiver(receiver) }.onFailure {
                Log.w(PRESENTATION_TAG, "action_receiver_unregister_failed", it)
            }
        }
        registration = null
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    fun awaitTermination(timeoutMillis: Long): Boolean = try {
        executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun executeNoise(snapshot: RenderedSnapshot, targetMode: String) {
        val noise = snapshot.knownNoiseMode() ?: return
        if (!snapshot.canControlNoise(noise) || targetMode !in snapshot.availableNoiseModes() || targetMode == noise) return
        dispatch(snapshot, targetMode, "noise_action_dispatch", { callbacks.setNoise(Bundle(snapshot.wire), targetMode) }) { result ->
            val response = result.getOrNull()
            if (response?.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK) {
                null
            } else {
                val detail = response?.getString(BridgeWire.MESSAGE)
                    ?: result.exceptionOrNull()?.javaClass?.simpleName
                    ?: "noise_action_rejected"
                "noise_action:$targetMode:$detail"
            }
        }
    }

    private fun executeDisconnect(snapshot: RenderedSnapshot) {
        val address = snapshot.address ?: return
        dispatch(snapshot, ACTION_KIND_DISCONNECT, "disconnect_dispatch", {
            val device = requireNotNull(appContext.bluetoothDevice(address)) { "bluetooth_adapter_unavailable" }
            BluetoothDevice::class.java.getMethod("disconnect").invoke(device) as Int
        }) { result ->
            val code = result.getOrNull()
            if (code == BLUETOOTH_STATUS_SUCCESS) {
                null
            } else {
                val failure = result.exceptionOrNull()
                val detail = code?.toString() ?: (failure?.cause ?: failure)?.javaClass?.simpleName ?: "unknown"
                "disconnect:$detail"
            }
        }
    }

    private fun <T> dispatch(
        snapshot: RenderedSnapshot,
        operation: String,
        dispatchFailure: String,
        perform: () -> T,
        failureDetail: (Result<T>) -> String?,
    ) {
        val token = registration?.token ?: return
        val key = "${snapshot.identity.stableKey}:$token:$operation"
        if (!pending.add(key)) return
        systemIntegrationResult {
            executor.execute {
                val result = runCatching(perform)
                handler.post {
                    pending.remove(key)
                    if (session.currentSnapshot?.identity != snapshot.identity || registration?.token != token) return@post
                    failureDetail(result)?.let { session.reportActionFailure(snapshot.wire, it) }
                }
            }
        }.onFailure { failure ->
            pending.remove(key)
            session.reportActionFailure(snapshot.wire, "$dispatchFailure:${failure.javaClass.simpleName}")
        }
    }
}

internal interface PresentationActionSession {
    val currentSnapshot: RenderedSnapshot?
    val statusActive: Boolean
    fun reportActionFailure(snapshot: Bundle, detail: String)
}

private data class ActionRegistration(val action: String, val token: String) {
    fun button(context: Context, snapshot: RenderedSnapshot, kind: String, targetMode: String?): Pair<String, Notification.Action> {
        val path = listOfNotNull(token, kind, targetMode).joinToString("/")
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            data = Uri.parse("$ACTION_DATA_SCHEME://$ACTION_DATA_HOST/$path")
            putExtra(EXTRA_ACTION_TOKEN, token)
            putExtra(EXTRA_ACTION_KIND, kind)
            putExtra(BridgeWire.HOST_EPOCH, snapshot.identity.hostEpoch)
            putExtra(BridgeWire.DEVICE_ID, snapshot.identity.deviceId)
            putExtra(BridgeWire.SESSION_ID, snapshot.identity.sessionId)
            targetMode?.let { putExtra(BridgePresentationWire.NOISE_MODE, it) }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            path.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (label, icon) = when (kind) {
            ACTION_KIND_NOISE -> "切换降噪" to android.R.drawable.ic_lock_silent_mode
            else -> "断开" to android.R.drawable.ic_delete
        }
        return label to Notification.Action.Builder(Icon.createWithResource(context, icon), label, pendingIntent).build()
    }
}

private fun Intent.senderIdentity(): SessionIdentity? {
    val deviceId = getStringExtra(BridgeWire.DEVICE_ID) ?: return null
    val hostEpoch = getStringExtra(BridgeWire.HOST_EPOCH) ?: return null
    return SessionIdentity(deviceId, getLongExtra(BridgeWire.SESSION_ID, Long.MIN_VALUE), hostEpoch)
}

private const val ACTION_KIND_NOISE = "noise"
private const val ACTION_KIND_DISCONNECT = "disconnect"
private const val EXTRA_ACTION_TOKEN = "openrose_action_token"
private const val EXTRA_ACTION_KIND = "openrose_action_kind"
private const val ACTION_DATA_SCHEME = "openrose"
private const val ACTION_DATA_HOST = "action"
private const val BLUETOOTH_PRIVILEGED_PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED"
private const val BLUETOOTH_STATUS_SUCCESS = 0
private const val MAX_CONCURRENT_ACTIONS = 4
