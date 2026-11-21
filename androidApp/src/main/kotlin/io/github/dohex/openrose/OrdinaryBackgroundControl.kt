package io.github.dohex.openrose

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dohex.openrose.androidintegration.BackgroundControlBlock
import io.github.dohex.openrose.androidintegration.BackgroundControlStatus
import io.github.dohex.openrose.androidintegration.BackgroundServiceFact
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.SwitchingControlRuntime
import io.github.dohex.openrose.androidintegration.backgroundControl
import io.github.dohex.openrose.androidintegration.mayStartBackgroundService
import io.github.dohex.openrose.androidintegration.ordinaryBackgroundRequested

/** Owns the ordinary background service's observed status and companion wake-up registration. */
internal class OrdinaryBackgroundControl(
    private val context: Context,
    private val runtime: SwitchingControlRuntime,
    val companion: CompanionWake,
    private val refreshPlatform: () -> Unit,
) {
    var service: BackgroundServiceFact = BackgroundServiceFact.NotRunning
        private set

    val ordinarySelected: Boolean get() = runtime.integration.value.owner.selected == ControlWay.ORDINARY

    fun syncObservation() {
        if (!runtime.state.value.platform.userUnlocked) return
        companion.syncObservation(runtime.integration.value.ordinaryBackgroundRequested)
        refreshPlatform()
    }

    /** Both a settings change and a wake-up reconcile registration before checking the current service facts. */
    fun followSavedIntent() {
        syncObservation()
        startIfEnabled()
    }

    fun startIfEnabled() {
        val state = runtime.state.value
        val integration = runtime.integration.value
        if (!integration.mayStartBackgroundService(state) || integration.backgroundControl(state) is BackgroundControlStatus.Running) return
        update(BackgroundServiceFact.NotRunning)
        tryStart { context.startForegroundService(Intent(context, ControlService::class.java)) }
    }

    /** Android can reject either the launch or the service's promotion to the foreground. */
    fun tryStart(start: () -> Unit): Boolean {
        val rejection = try {
            start()
            return true
        } catch (denied: SecurityException) {
            Log.w("OpenRose", "Foreground service permission refused", denied)
            BackgroundControlBlock.SERVICE_START_REFUSED
        } catch (restricted: IllegalStateException) {
            Log.w("OpenRose", "Foreground service start restricted", restricted)
            BackgroundControlBlock.BACKGROUND_START_RESTRICTED
        }
        update(BackgroundServiceFact.StartRejected(rejection))
        return false
    }

    fun running() = update(BackgroundServiceFact.Running)

    fun stopped() {
        if (service == BackgroundServiceFact.Running) update(BackgroundServiceFact.NotRunning)
    }

    private fun update(fact: BackgroundServiceFact) {
        service = fact
        refreshPlatform()
    }
}
