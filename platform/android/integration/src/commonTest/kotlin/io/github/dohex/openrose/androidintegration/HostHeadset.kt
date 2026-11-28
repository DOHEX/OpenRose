package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.kotlincore.CoreBackedRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope

internal val grantedAndroidPlatform = PlatformState(
    PermissionState.GRANTED,
    TransportAvailability.Available,
    hostPlatform = HostPlatform.ANDROID,
    scanPermission = PermissionState.GRANTED,
)

internal interface HostLink {
    val writes: List<String>
    val closed: Boolean
    var initializing: Boolean

    fun deviceReply(hex: String)
}

internal interface HostHeadset : AutoCloseable {
    val links: List<HostLink>

    fun connectAudio(deviceId: DeviceId)

    suspend fun endProcess()

    fun runtime(scope: CoroutineScope, settings: UserSettings, platform: PlatformState, sessionIdBase: Long): CoreBackedRuntime
}

internal fun HostHeadset.runtime(scope: CoroutineScope): CoreBackedRuntime = runtime(scope, UserSettings(), grantedAndroidPlatform, 0L)

internal fun TestScope.hostHeadset(
    headset: SharedHeadset,
    earphones: DeviceCandidate,
    bonded: List<DeviceCandidate> = listOf(earphones),
    rewriteReply: ((String) -> String)? = null,
): HostHeadset = newHostHeadset(this, headset, earphones, bonded, rewriteReply).also { host ->
    backgroundScope.coroutineContext[Job]?.invokeOnCompletion { host.close() }
}

internal expect fun newHostHeadset(
    test: TestScope,
    headset: SharedHeadset,
    earphones: DeviceCandidate,
    bonded: List<DeviceCandidate>,
    rewriteReply: ((String) -> String)?,
): HostHeadset
