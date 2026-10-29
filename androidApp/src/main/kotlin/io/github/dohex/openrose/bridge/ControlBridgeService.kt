package io.github.dohex.openrose.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.dohex.openrose.OpenRoseApplication

/** A bound endpoint in the Application's process, independent of any Activity or component style. */
class ControlBridgeService : Service() {
    private val host get() = (application as OpenRoseApplication).bridge
    private val endpoint = object : IControlBridge.Stub() {
        override fun open(proof: Bundle, clientToken: IBinder): Bundle =
            host.open(proof, clientToken, Binder.getCallingUid(), Binder.getCallingPid())

        override fun currentSession(leaseId: String): Bundle =
            host.currentSession(leaseId, Binder.getCallingUid(), Binder.getCallingPid())

        override fun read(request: Bundle): Bundle =
            host.read(request, Binder.getCallingUid(), Binder.getCallingPid())

        override fun acknowledge(receipt: Bundle): Bundle =
            host.acknowledge(receipt, Binder.getCallingUid(), Binder.getCallingPid())

        override fun close(leaseId: String) =
            host.close(leaseId, Binder.getCallingUid(), Binder.getCallingPid())

        override fun watch(request: Bundle, observer: IControlObserver): Bundle =
            host.watch(request, observer, Binder.getCallingUid(), Binder.getCallingPid())

        override fun claimPrompt(request: Bundle): Bundle =
            host.claimPrompt(request, Binder.getCallingUid(), Binder.getCallingPid())

        override fun setNoise(request: Bundle): Bundle =
            host.setNoise(request, Binder.getCallingUid(), Binder.getCallingPid())

        override fun reportPresentation(report: Bundle): Bundle =
            host.reportPresentation(report, Binder.getCallingUid(), Binder.getCallingPid())

        override fun reportComponent(report: Bundle): Bundle =
            host.reportComponent(report, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun onBind(intent: Intent): IBinder? = if (canonicalIntent(intent)) endpoint else null

    override fun onUnbind(intent: Intent): Boolean {
        if (canonicalIntent(intent)) host.endpointUnbound()
        return false
    }

    // Different Intent filter keys have separate binding lifetimes. Reject them so an unrelated
    // binding cannot revoke a reader still bound through the canonical explicit component.
    private fun canonicalIntent(intent: Intent): Boolean = intent.action == null && intent.data == null &&
        intent.type == null && intent.`package` == null && intent.categories.isNullOrEmpty()
}
