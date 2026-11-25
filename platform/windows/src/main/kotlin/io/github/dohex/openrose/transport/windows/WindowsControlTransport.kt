package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableCode
import io.github.dohex.openrose.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.HexFormat

class WindowsControlTransport internal constructor(
    scope: CoroutineScope,
    snapshots: Flow<String>,
    private val start: (List<String>) -> Process,
) : DesktopControlTransport {
    constructor(scope: CoroutineScope) : this(scope, windowsSnapshots(), ::startWindowsHelper)

    private val initial = Snapshot(TransportAvailability.Unavailable(TransportUnavailableCode.INITIALIZING))
    private val facts = MutableStateFlow(initial)
    private val monitor = scope.launch { snapshots.collect { facts.value = snapshot(it) } }
    override val availability = facts.map { it.availability }.stateIn(scope, SharingStarted.Eagerly, initial.availability)
    override val audioConnectedDevice = facts.map { it.audio }.distinctUntilChanged()
    override val supportsInitializationRecovery = true

    override suspend fun bondedDevices(): List<DeviceCandidate> = facts.first { it !== initial }.paired

    override fun scan(): Flow<Advertisement> = helperLines { start(listOf("scan")) }.map { line ->
        val parts = line.split('\t')
        check(parts.first() == "ad") { line }
        val manufacturer = parts.drop(4).associate {
            val (key, value) = it.split(':', limit = 2)
            key.toInt() to hex.parseHex(value)
        }
        Advertisement(DeviceId(address(parts[1])), hex.parseHex(parts[2]).decodeToString().ifBlank { null }, parts[3].toInt(), manufacturer)
    }

    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
        require(endpoint is TransportEndpoint.BleGatt) { "Windows 仅支持 BLE 控制" }
        val address = requireNotNull(normalizeBluetoothAddress(deviceId.value)) { "蓝牙地址无效" }
        val process = withContext(Dispatchers.IO) { start(listOf("gatt", address.replace(":", ""), endpoint.serviceUuid)) }
        return GattControlConnection.open(WindowsGattDriver(process, endpoint.serviceUuid), endpoint, onPhase)
    }

    override suspend fun close() {
        monitor.cancelAndJoin()
        facts.value = Snapshot(TransportAvailability.Unavailable(TransportUnavailableCode.CLOSED))
    }
}

private data class Snapshot(
    val availability: TransportAvailability,
    val paired: List<DeviceCandidate> = emptyList(),
    val audio: DeviceId? = null,
)

private fun snapshot(line: String): Snapshot {
    val parts = line.split('\t')
    return when (parts.first()) {
        "available" -> {
            val active = mutableSetOf<String>()
            val devices = parts.drop(1).map { entry ->
                val (id, name, connected) = entry.split('/')
                val address = address(id)
                if (connected == "1") active.add(address)
                RawAudioDevice(hex.parseHex(name).decodeToString(), address)
            }
            val paired = pairedAudioDevices(devices)
            Snapshot(TransportAvailability.Available, paired, paired.firstOrNull { it.deviceId.value in active }?.deviceId)
        }

        "disabled" -> Snapshot(TransportAvailability.Disabled)

        else -> Snapshot(unavailableSnapshot(parts.drop(1)))
    }
}

private fun unavailableSnapshot(args: List<String>): TransportAvailability.Unavailable {
    val code = args.firstOrNull()?.takeIf(String::isNotBlank)
    val detail = args.getOrNull(1)?.takeIf(String::isNotBlank)
    return when (code) {
        "ble_unsupported" -> TransportAvailability.Unavailable(TransportUnavailableCode.BLE_UNSUPPORTED)
        "adapter_state_unknown" -> TransportAvailability.Unavailable(TransportUnavailableCode.ADAPTER_STATE_UNKNOWN)
        "recovering" -> TransportAvailability.Unavailable(TransportUnavailableCode.RECOVERING)
        "backend_unavailable" -> TransportAvailability.Unavailable(TransportUnavailableCode.BACKEND_UNAVAILABLE, detail)
        else -> TransportAvailability.Unavailable(TransportUnavailableCode.BACKEND_UNAVAILABLE)
    }
}

private fun address(id: String): String = requireNotNull(normalizeBluetoothAddress(id.chunked(2).joinToString(":")))
internal val hex: HexFormat = HexFormat.of()
