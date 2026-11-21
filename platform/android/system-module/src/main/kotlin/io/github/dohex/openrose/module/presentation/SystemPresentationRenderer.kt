package io.github.dohex.openrose.module.presentation

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire

/** Actions go back to the module host's single owner through this entry's authorised lease; nothing is queued or connected here. */
internal interface PresentationCallbacks {
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean
    fun setNoise(snapshot: Bundle, mode: String): Bundle
    fun report(snapshot: Bundle, feature: String, status: String, detail: String)
}

/** Platform failures in an injected process are reported by the operation that owns the affected state. */
internal fun interface PresentationFailureBoundary {
    fun attempt(block: () -> Unit, onFailure: (Throwable) -> Unit)
}

/**
 * Renders the module owner's trusted snapshot in com.android.bluetooth, the host process itself.
 *
 * The status notification is a focus card (HyperOS 3 template "new image-text + button component 1", `iconTextInfo`
 * plus up to two text buttons). It carries a summary-state island only while the snapshot asks for one (module mode
 * with a display timing) and never floats on update, so battery refreshes cannot re-expand it. That summary state is
 * the module prompt's battery template, so while the card is shown a module prompt is the island's entrance, replayed
 * by re-posting the card; without the card, the prompt is its own prompt-scoped notification. The JSON follows
 * HyperNotification 1.4's public V3 model schema (publisher revision c704a4de3e1d069e213508363303f62b5e057526); that
 * schema alone does not prove SystemUI acceptance or actual display.
 */
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
    private val boundary = PresentationFailureBoundary(::attempt)
    private val notifications = PresentationNotifications(appContext)
    private val actionSession: PresentationActionSession = object : PresentationActionSession {
        override val currentSnapshot: RenderedSnapshot? get() = if (closed) null else current
        override val statusActive: Boolean get() = statusNotification.active
        override fun reportActionFailure(snapshot: Bundle, detail: String) {
            reportOnce(snapshot, BridgePresentationWire.STATUS_NOTIFICATION, BridgePresentationWire.ACTION_FAILED, detail)
        }
    }
    private val actions = PresentationActions(appContext, handler, callbacks, actionSession, boundary)
    private val content = PresentationNotificationContent(appContext, actions)
    private val statusNotification = HeadsetStatusNotification(notifications, content, boundary, ::reportOnce)
    private val prompt = ModuleConnectionPrompt(
        handler,
        callbacks,
        notifications,
        content,
        boundary,
        { actionSession.currentSnapshot?.identity },
        ::reportOnce,
    )

    /** Accepts snapshots from any thread; all Android presentation state is serialized on handler. */
    fun render(snapshot: Bundle) {
        if (closed) return
        val safeSnapshot = Bundle(snapshot)
        dispatchOnHandler {
            if (!closed) renderOnHandler(safeSnapshot)
        }
    }

    /** Withdraws only notifications and module prompts created by this renderer. */
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

    /** Hot reload must know whether an in-flight synchronous Binder wait actually released us. */
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
        if (newSession) actions.register()

        // A merged module prompt makes the status island replay its entrance on this frame.
        prompt.render(decoded, statusNotification.canMergePrompt(decoded))
        val onPromptShown = if (prompt.awaitsStatus(decoded)) prompt::markStatusShown else null
        statusNotification.render(decoded, onPromptShown)
    }

    private fun clearOnHandler() {
        statusNotification.withdraw(current?.wire)
        prompt.clear()
        current = null
        actions.unregister()
        reportedStatus.clear()
    }

    private fun attempt(block: () -> Unit, onFailure: (Throwable) -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            onFailure(failure)
        }
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
}
