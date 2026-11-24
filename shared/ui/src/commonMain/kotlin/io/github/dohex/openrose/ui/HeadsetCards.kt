package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.supportsCirculation
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform

internal val DeviceCandidate.circulationAddress: String?
    get() = if (supportsCirculation(profile)) classicAddress ?: deviceId.value else null

internal fun DeviceCandidate?.hasCirculationAddress(address: String?) = this?.circulationAddress?.equals(address, ignoreCase = true) == true

internal class HeadsetCards(val local: HeadsetCardSource, val others: List<HeadsetCardSource>, val showLocal: Boolean)

@Composable
internal fun rememberHeadsetCards(
    state: ControlState,
    selection: PrimaryDeviceSelection,
    client: CirculationClient?,
    remoteHeadsets: List<RemoteHeadset>? = null,
): HeadsetCards {
    val primary = selection.primary(state)
    val bonded = state.bondedRoseDevices()
    val peers = remoteHeadsets ?: client?.let { rememberRemoteHeadsets(it) }.orEmpty()
    val audioTarget = bonded.firstOrNull { it.matchesAudioDevice(state.audioDeviceId) }
    val actions = rememberCirculationActions(client, audioTarget, primary, bonded)
    fun card(target: DeviceCandidate?, remote: RemoteHeadset?) = HeadsetCardSource(state, target, remote, actions)

    val remoteCards = remoteHeadsetCards(peers, bonded, state, actions)
    val selected = remoteCards.firstOrNull { it.matches(primary) }
    val local = selected ?: card(primary, null)
    val others = remoteCards.filterNot { it === selected }.toMutableList()
    val localAudio = audioTarget ?: actions?.let { bonded.firstOrNull(it::hasMedia) }
    if (localAudio != null && !local.hasLocalAudio) {
        others.add(card(localAudio, null))
    }

    val transferring = actions?.retainedTarget(bonded)
    if (transferring != null && !actions.isTarget(local.target) && others.none { actions.isTarget(it.target) }) {
        others.add(card(transferring, null))
    }
    return HeadsetCards(local, others, primary != null || state.connection != ConnectionState.Disconnected || others.isEmpty())
}

private fun remoteHeadsetCards(
    peers: List<RemoteHeadset>,
    bonded: List<DeviceCandidate>,
    state: ControlState,
    actions: CirculationActions?,
): List<HeadsetCardSource> = peers.filter { it.holdsHeadset }.map { peer ->
    val device = peer.reading.session?.device
    val target = bonded.firstOrNull { it.matchesAudioDevice(device) || it.hasCirculationAddress(device?.value) }
    HeadsetCardSource(state, target, peer, actions)
}.filterNot { it.hasLocalAudio }

private val RemoteHeadset.holdsHeadset: Boolean
    get() = deviceName != null && when (reading.status) {
        RemoteControlStatus.READY, RemoteControlStatus.INITIALIZING, RemoteControlStatus.RECOVERING -> true
        else -> false
    }

internal fun headsetReconnectLabel(hasLocalAudio: Boolean, ordinary: String): String = if (hasLocalAudio) "恢复耳机控制" else ordinary

internal class HeadsetCardSource(
    private val state: ControlState,
    val target: DeviceCandidate?,
    val remote: RemoteHeadset?,
    private val actions: CirculationActions?,
) {
    private val localName = when (state.platform.hostPlatform) {
        HostPlatform.ANDROID -> "这部手机"
        HostPlatform.LINUX, HostPlatform.WINDOWS -> "这台电脑"
        HostPlatform.UNKNOWN -> "这台设备"
    }

    val hasLocalAudio: Boolean = target?.matchesAudioDevice(state.audioDeviceId) == true ||
        (state.deviceHasSystemAudio && remote == null && (target == null || target.deviceId == state.device?.deviceId)) ||
        actions?.hasMedia(target) == true

    private val canAcquire = actions?.available == true && target?.circulationAddress != null &&
        state.bondedDevices.any { it.hasCirculationAddress(target.circulationAddress) } && state.platform.connectionBlockReason == null

    private val localControlConnecting = state.isConnectingForTray() && when (val connection = state.connection) {
        is ConnectionState.Connecting -> connection.deviceId == target?.deviceId
        is ConnectionState.Connected -> connection.deviceId == target?.deviceId
        ConnectionState.Disconnected -> false
    }

    val offersTransfer: Boolean = canAcquire && !hasLocalAudio && !localControlConnecting
    val canRecoverControl: Boolean
        get() = target != null && hasLocalAudio && state.connection == ConnectionState.Disconnected &&
            state.platform.connectionBlockReason == null && actions?.busy != true

    val sourceLabel: String?
        get() = when {
            hasLocalAudio -> "音源设备 · $localName"
            remote != null -> remote.sourceLabel
            canAcquire -> "${localName}尚未连接"
            else -> null
        }

    val transferBusy: Boolean get() = actions?.busy == true && actions.isTarget(target)
    val transferEnabled: Boolean get() = offersTransfer && actions?.busy == false && remote?.busy != true
    val transferFeedback: String? get() = actions?.feedback?.takeIf { actions.isTarget(target) }

    val transferLabel: String get() = if (remote == null) "将耳机连接到$localName" else "改连$localName"
    val trayTransferLabel: String get() = if (remote == null) "连接本机" else "改连本机"
    val transferHint: String?
        get() = when {
            !offersTransfer -> null
            remote != null -> "改连后，${remote.name} 会断开与这副耳机的音频连接。"
            actions?.offline == true -> "暂时联系不上已配对的音源设备，将尝试直接连接耳机。"
            else -> "会先联系已配对的音源设备，再尝试连接耳机音频。"
        }

    fun matches(headset: DeviceCandidate?): Boolean {
        if (headset == null || target == null) return false
        return target.deviceId == headset.deviceId || target.hasCirculationAddress(headset.circulationAddress)
    }

    fun reconnectLabel(ordinary: String): String = headsetReconnectLabel(hasLocalAudio, ordinary)

    fun pull() {
        if (target != null && transferEnabled) actions?.pull(target)
    }
}
