package io.github.dohex.openrose

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.OrdinarySessionHold
import io.github.dohex.openrose.androidintegration.SwitchingControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class VisibleInterfaceHold(
    private val runtime: SwitchingControlRuntime,
    private val scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private val live = mutableSetOf<Activity>()
    private var hold: OrdinarySessionHold? = null
    private var pendingRelease: Job? = null

    private fun enter(activity: Activity) {
        live += activity
        pendingRelease?.cancel()
        pendingRelease = null
        if (hold == null) hold = runtime.holdOrdinarySession()
    }

    private fun leave(activity: Activity) {
        live -= activity
        if (live.isNotEmpty() || hold == null || pendingRelease != null) return
        pendingRelease = scope.launch {
            delay(RECREATION_GRACE_MILLIS)
            hold?.release()
            hold = null
            pendingRelease = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = enter(activity)
    override fun onActivityStarted(activity: Activity) = enter(activity)
    override fun onActivityStopped(activity: Activity) = leave(activity)
    override fun onActivityDestroyed(activity: Activity) = leave(activity)
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val RECREATION_GRACE_MILLIS = 700L
    }
}
