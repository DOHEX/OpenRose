package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope

internal interface HostLink {
    val writes: List<String>
    val closed: Boolean
    var initializing: Boolean

    fun deviceReply(hex: String)
}

internal interface HostHeadset : AutoCloseable {
    val links: List<HostLink>

    fun connectAudio(deviceId: DeviceId)

    fun runtime(scope: CoroutineScope, settings: UserSettings, platform: PlatformState, sessionIdBase: Long): ControlRuntime
}

internal fun TestScope.hostHeadset(headset: SharedHeadset, earphones: DeviceCandidate, bonded: List<DeviceCandidate>): HostHeadset = newHostHeadset(this, headset, earphones, bonded).also { host ->
    backgroundScope.coroutineContext[Job]?.invokeOnCompletion { host.close() }
}

internal expect fun newHostHeadset(test: TestScope, headset: SharedHeadset, earphones: DeviceCandidate, bonded: List<DeviceCandidate>): HostHeadset
