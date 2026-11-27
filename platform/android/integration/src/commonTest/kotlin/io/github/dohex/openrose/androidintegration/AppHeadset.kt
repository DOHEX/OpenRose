package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope

internal const val APP_HEADSET_ADDRESS = "AA:BB:CC:00:00:05"

internal interface AppHeadset : AutoCloseable {
    var closeFails: Boolean
    var audioConnected: Boolean
    var closeDelayMillis: Long
    val connections: Int
    val refusedReleases: Int
    val writes: Int
    val closedRuntimes: Int

    fun runtime(scope: CoroutineScope, settings: UserSettings, platform: PlatformState, sessionIdBase: Long): ControlRuntime
}

internal fun TestScope.appHeadset(headset: SharedHeadset = SharedHeadset(), earphones: DeviceCandidate = rosePair(APP_HEADSET_ADDRESS)): AppHeadset = newAppHeadset(this, headset, earphones).also { app -> backgroundScope.coroutineContext[Job]?.invokeOnCompletion { app.close() } }

internal expect fun newAppHeadset(test: TestScope, headset: SharedHeadset, earphones: DeviceCandidate): AppHeadset

internal expect fun blockFor(millis: Long)
