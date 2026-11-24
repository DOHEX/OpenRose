package io.github.dohex.openrose.module.presentation

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire

internal interface PresentationCallbacks {
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean
    fun setNoise(snapshot: Bundle, mode: String): Bundle
    fun report(snapshot: Bundle, feature: String, status: String, detail: String)
}

internal class SystemPresentationRenderer(
    context: Context,
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val lifecycleLock = Any()

    @Volatile private var closed = false
    private var current: RenderedSnapshot? = null
    private val reportedStatus = mutableMapOf<String, String>()
    private var aliasReceiverRegistered = false
    private val aliasReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ALIAS_CHANGED) return
            dispatchOnHandler {
                val active = current ?: return@dispatchOnHandler
                if (!closed && statusNotification.active) statusNotification.render(active, null)
            }
        }
    }
    private val notifications = PresentationNotifications(appContext)
    private val actionSession: PresentationActionSession = object : PresentationActionSession {
        override val currentSnapshot: RenderedSnapshot? get() = if (closed) null else current
        override val statusActive: Boolean get() = statusNotification.active
        override fun reportActionFailure(snapshot: Bundle, detail: String) {
            reportOnce(snapshot, BridgePresentationWire.STATUS_NOTIFICATION, BridgePresentationWire.ACTION_FAILED, detail)
        }
    }
    private val actions = PresentationActions(appContext, handler, callbacks, actionSession)
    private val content = PresentationNotificationContent(appContext, actions)
    private val statusNotification = HeadsetStatusNotification(notifications, content, ::reportOnce)
    private val prompt = ModuleConnectionPrompt(
        handler,
        callbacks,
        notifications,
        content,
        { actionSession.currentSnapshot?.identity },
        ::reportOnce,
    )

    fun render(snapshot: Bundle) {
        if (closed) return
        val safeSnapshot = Bundle(snapshot)
        dispatchOnHandler {
            if (!closed) renderOnHandler(safeSnapshot)
        }
    }

    fun clear() {
        if (closed) return
        dispatchOnHandler(::clearOnHandler)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        actions.shutdown()
        dispatchOnHandler(::clearOnHandler)
    }

    fun awaitActionTermination(timeoutMillis: Long): Boolean = actions.awaitTermination(timeoutMillis)

    private fun renderOnHandler(snapshot: Bundle) {
        val decoded = decodePresentationSnapshot(snapshot) ?: return
        val previous = current
        val newSession = previous?.identity != decoded.identity
        if (previous != null) {
            if (newSession) {
                clearOnHandler()
            } else if (decoded.readSequence < previous.readSequence) {
                return
            }
        }
        current = decoded
        if (newSession) {
            actions.register()
            registerAliasReceiver()
        }

        prompt.render(decoded, statusNotification.canMergePrompt(decoded))
        val onPromptShown = if (prompt.awaitsStatus(decoded)) prompt::markStatusShown else null
        statusNotification.render(decoded, onPromptShown)
    }

    private fun clearOnHandler() {
        statusNotification.withdraw(current?.wire)
        prompt.clear()
        current = null
        actions.unregister()
        unregisterAliasReceiver()
        reportedStatus.clear()
    }

    private fun reportOnce(snapshot: Bundle, feature: String, status: String, detail: String) {
        if (feature !in BridgePresentationWire.FEATURES || status !in BridgePresentationWire.STATUSES) return
        val identity = sessionIdentity(snapshot) ?: return
        val key = "${identity.stableKey}:$feature"
        if (reportedStatus[key] == status) return
        reportedStatus[key] = status
        runCatching { callbacks.report(Bundle(snapshot), feature, status, detail) }
            .onFailure { Log.w(PRESENTATION_TAG, "presentation_report_failed feature=$feature status=$status", it) }
    }

    private fun dispatchOnHandler(block: () -> Unit) {
        if (Looper.myLooper() === handler.looper) block() else handler.post { block() }
    }

    private fun registerAliasReceiver() {
        if (aliasReceiverRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val filter = IntentFilter(BluetoothDevice.ACTION_ALIAS_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(aliasReceiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(aliasReceiver, filter, null, handler)
            }
            aliasReceiverRegistered = true
        }.onFailure { failure ->
            Log.w(PRESENTATION_TAG, "alias_receiver_registration_failed", failure)
        }
    }

    private fun unregisterAliasReceiver() {
        if (!aliasReceiverRegistered) return
        aliasReceiverRegistered = false
        runCatching { appContext.unregisterReceiver(aliasReceiver) }
    }
}
