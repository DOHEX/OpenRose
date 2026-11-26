package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HeadsetDiagnostics
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableCode
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.RustControlResult
import io.github.dohex.openrose.ffi.RustControlRuntime
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.openrose_control.AudioConnectionState
import uniffi.openrose_control.AudioProfile
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.GattCharacteristic
import uniffi.openrose_control.GattEvent
import uniffi.openrose_control.GattService
import uniffi.openrose_control.GattSubscription
import uniffi.openrose_control.RawAudioDevice
import uniffi.openrose_control.RawAudioEvent
import uniffi.openrose_control.RawAudioSnapshot
import uniffi.openrose_control.RawProfileSnapshot
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.AudioEvents
import uniffi.openrose_ffi.AudioFactsDriver
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.GattDriver
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.ScanDriver
import uniffi.openrose_ffi.SimulatedHeadset
import uniffi.openrose_ffi.SppDriver
import uniffi.openrose_ffi.TransportDriverException
import uniffi.openrose_ffi.modelDisplayName
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_headset.FirmwareVersion
import uniffi.openrose_headset.HeadsetField
import uniffi.openrose_headset.HeadsetId
import uniffi.openrose_headset.HeadsetSetting
import uniffi.openrose_headset.LocalDateTime
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import uniffi.openrose_control.ConnectionPhase as RustConnectionPhase
import uniffi.openrose_control.ConnectionState as RustConnectionState
import uniffi.openrose_control.ControlErrorCode as RustControlErrorCode
import uniffi.openrose_control.HeadsetCandidate as RustHeadsetCandidate
import uniffi.openrose_control.HostPlatform as RustHostPlatform
import uniffi.openrose_control.PermissionState as RustPermissionState
import uniffi.openrose_control.PlatformApplicability as RustPlatformApplicability
import uniffi.openrose_control.PlatformState as RustPlatformState
import uniffi.openrose_control.TransportAvailability as RustTransportAvailability
import uniffi.openrose_control.TransportUnavailableCode as RustTransportUnavailableCode
import uniffi.openrose_headset.FieldReading as RustFieldReading
import uniffi.openrose_headset.FieldValue as RustFieldValue
import uniffi.openrose_headset.HeadsetCommand as RustHeadsetCommand
import uniffi.openrose_headset.ModelProfile as RustModelProfile
import uniffi.openrose_headset.NoiseMode as RustNoiseMode

class RustSystemEntryClosedLoopTest {
    private class SimulatedHeadsetTransport : AutoCloseable {
        val device = SimulatedHeadset(RustModelProfile.I5_ZT, FirmwareVersion(listOf(1, 2, 5), "125"))
        val muted = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<String>()
        val gattEvents = Channel<GattEvent>(Channel.UNLIMITED)
        val audioEvents = Channel<RawAudioEvent>(Channel.UNLIMITED)

        fun inject(hex: String) {
            gattEvents.trySend(GattEvent.Notification(NOTIFIER_ID, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        }

        fun controlTransport(): ControlTransport = object : ControlTransport {
            override fun openGatt(headset: HeadsetId): GattDriver = object : GattDriver {
                override suspend fun nextEvent(): GattEvent? = gattEvents.receiveCatching().getOrNull()

                override suspend fun connect() = Unit

                override suspend fun discoverServices(): List<GattService> {
                    val gatt = modelGattProfile(RustModelProfile.I5_ZT)
                    return listOf(
                        GattService(
                            uuid = gatt.serviceUuid,
                            characteristics = listOf(
                                GattCharacteristic(WRITER_ID, gatt.writeUuid, true, true, false, false),
                                GattCharacteristic(NOTIFIER_ID, gatt.notifyUuid, false, false, true, false),
                            ),
                        ),
                    )
                }

                override suspend fun requestMtu(preferred: UShort): UShort = 185u

                override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) = Unit

                override suspend fun configureSubscription(
                    characteristic: GattCharacteristic,
                    subscription: GattSubscription,
                ) = Unit

                override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
                    writes.add(value.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                    if (!muted.get()) {
                        for (reply in device.receive(value)) {
                            gattEvents.trySend(GattEvent.Notification(NOTIFIER_ID, reply))
                        }
                    }
                }

                override suspend fun closeLink() = Unit
            }

            override fun startScan(): ScanDriver = throw TransportDriverException.Unsupported("scan unused")

            override fun openSpp(headset: HeadsetId, address: String, serviceUuid: String): SppDriver = throw TransportDriverException.Unsupported("spp unused")

            override fun audioFacts(): AudioFactsDriver = object : AudioFactsDriver {
                override fun subscribe(): AudioEvents = object : AudioEvents {
                    override suspend fun nextEvent(): RawAudioEvent? = audioEvents.receiveCatching().getOrNull()
                }

                override suspend fun bondedDevices(): List<RawAudioDevice> = listOf(paired())

                override suspend fun snapshot(targetAddresses: List<String>): RawAudioSnapshot = RawAudioSnapshot(
                    enabled = true,
                    sppAvailable = false,
                    leAudioObservable = false,
                    bondedDevices = listOf(paired()),
                    profiles = mapOf(
                        AudioProfile.A2DP to RawProfileSnapshot(
                            connectedAddresses = listOf(I5_ADDRESS),
                            states = mapOf(I5_ADDRESS to AudioConnectionState.CONNECTED),
                        ),
                    ),
                )
            }

            override fun supportsInitializationRecovery(): Boolean = false
        }

        private fun paired(): RawAudioDevice = RawAudioDevice(
            name = modelDisplayName(RustModelProfile.I5_ZT),
            address = I5_ADDRESS,
            alias = null,
        )

        override fun close() {
            gattEvents.close()
            audioEvents.close()
            device.close()
        }
    }

    private class RustRuntimeAdapter(
        private val rust: RustControlRuntime,
        scope: CoroutineScope,
    ) : ControlRuntime {
        override val state: StateFlow<ControlState> = rust.state
            .map(::toControlState)
            .stateIn(scope, SharingStarted.Eagerly, toControlState(rust.snapshot()))

        override suspend fun refreshBondedDevices() = rust.refreshBondedHeadsets()

        override suspend fun startScan(): OperationResult = rust.startScan().toOperationResult()

        override suspend fun stopScan() = rust.stopScan()

        override suspend fun selectCurrentHeadset(deviceId: DeviceId) = rust.selectCurrentHeadset(deviceId.value)

        override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = rust.connect(deviceId.value, pinned).toOperationResult()

        override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = rust.connectForAddDevice(deviceId.value).toOperationResult()

        override suspend fun disconnect(): Boolean = rust.disconnect()

        override suspend fun release(): Boolean {
            val released = rust.release()
            if (released) rust.close()
            return released
        }

        override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult = rust.set(command.toRust(), expectedSessionId?.toULong()).toOperationResult()

        override fun updateSettings(settings: UserSettings) = rust.updateSettings(ControlSettings(forceBle = settings.forceBle))

        override fun updatePlatformState(platformState: PlatformState) = rust.updatePlatformState(platformState.toRust())
    }

    private suspend fun createConnectedHost(
        simulated: SimulatedHeadsetTransport,
        hostScope: CoroutineScope,
    ): ModuleControlHost {
        val ids = AtomicInteger(0)
        val host = ModuleControlHost(
            packageName = HOST_PACKAGE,
            processName = HOST_PACKAGE,
            incarnation = "rust-host-1",
            authority = testAuthority,
            runtimeFactory = { runtimeScope, settings, platform, sessionIdBase ->
                val rust = RustControlRuntime(
                    transport = simulated.controlTransport(),
                    initialPlatformState = platform.toRust(),
                    initialSettings = ControlSettings(forceBle = settings.forceBle),
                    clock = object : LocalClock {
                        override fun now(): LocalDateTime = LocalDateTime(2026u, 11u, 26u, 10u, 0u, 0u)
                    },
                    timing = SessionTiming(
                        connectTimeout = Duration.ofMillis(2_000),
                        queryTimeout = Duration.ofMillis(500),
                        writeInterval = Duration.ZERO,
                        writeTimeout = Duration.ofMillis(500),
                    ),
                    sessionIdBase = sessionIdBase.toULong(),
                )
                RustRuntimeAdapter(rust, runtimeScope)
            },
            parentScope = hostScope,
            newId = { "lease-${ids.incrementAndGet()}" },
        )
        host.updatePlatform(
            PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                userUnlocked = true,
                scanPermission = PermissionState.GRANTED,
            ),
        )
        assertTrue(
            host.configure {
                HostConfiguration(
                    userUnlocked = true,
                    settings = HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE)),
                )
            },
        )
        return host
    }

    @Test
    fun aFusionCenterNoiseTapSucceedsOnlyWhenTheRustCoreReceivesHeadsetReadback() = runBlocking {
        SimulatedHeadsetTransport().use { simulated ->
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = createConnectedHost(simulated, hostScope)
            try {
                val generation = assertIs<HostStatus.Active>(host.status.value).generation
                val lease = assertIs<HostOpenResult.Opened>(host.open(milinkIdentity)).lease.id
                val connection = awaitValue("rust core connected") {
                    host.control.view(milinkIdentity, lease)?.state?.connection as? ConnectionState.Connected
                }
                val reference = SessionReference(connection.deviceId, connection.sessionId)
                assertEquals(DeviceId(I5_ADDRESS), reference.deviceId)

                val initialRead = assertIs<HostReadResult.Available>(
                    host.control.readSession(milinkIdentity, lease, generation, reference),
                )
                val initialDevice = assertIs<SessionSnapshotResult.Available>(initialRead.snapshot).device
                assertEquals(0, SystemHeadsetMapping.miLinkAncState(initialDevice.diagnostics.fields))
                assertTrue(initialDevice.diagnostics.fields[DeviceField.LEFT_BATTERY] is FieldReading.Known)

                simulated.muted.set(true)
                val writesBefore = simulated.writes.size
                val tap = async(Dispatchers.Default) {
                    host.control.execute(
                        milinkIdentity,
                        lease,
                        generation,
                        HostCommand.Set(reference, HeadsetCommand.NoiseMode(NoiseMode.ANC)),
                    )
                }
                awaitCondition("noise readback query") {
                    simulated.writes.drop(writesBefore).any { it == NOISE_READBACK_QUERY }
                }

                assertFalse(tap.isCompleted)
                val pendingRead = assertIs<HostReadResult.Available>(
                    host.control.readSession(milinkIdentity, lease, generation, reference),
                )
                val pendingFields = assertIs<SessionSnapshotResult.Available>(pendingRead.snapshot).device.diagnostics.fields
                assertEquals(0, SystemHeadsetMapping.miLinkAncState(pendingFields))

                simulated.inject(NOISE_REPLY_ANC)
                assertEquals(HostCallResult.Operation(OperationResult.Success), withTimeout(2_000) { tap.await() })
                val confirmedMode = awaitValue("confirmed anc mode") {
                    val read = host.control.readSession(milinkIdentity, lease, generation, reference) as? HostReadResult.Available
                    val snapshot = read?.snapshot as? SessionSnapshotResult.Available
                    snapshot?.let { SystemHeadsetMapping.miLinkAncState(it.device.diagnostics.fields) }?.takeIf { it == 1 }
                }
                assertEquals(1, confirmedMode)
            } finally {
                assertTrue(host.stop())
                hostScope.cancel()
            }
        }
    }

    private suspend fun <T : Any> awaitValue(description: String, read: suspend () -> T?): T = withTimeout(3_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable: $description")
    }

    private suspend fun awaitCondition(description: String, check: () -> Boolean) {
        withTimeout(3_000) {
            while (!check()) {
                delay(5)
            }
        }
        assertTrue(check(), "expected $description")
    }

    private companion object {
        const val I5_ADDRESS = "AA:BB:CC:00:00:05"
        const val WRITER_ID = 1uL
        const val NOTIFIER_ID = 2uL
        const val NOISE_READBACK_QUERY = "08ee00000006020a0008"
        const val NOISE_REPLY_ANC = "09ff00000106020e000100000020"
    }
}

private fun RustControlResult.toOperationResult(): OperationResult = when (this) {
    RustControlResult.Success -> OperationResult.Success
    is RustControlResult.Failure -> OperationResult.Failure(ControlError(error.code.toKotlin(), error.message))
}

private fun RustControlErrorCode.toKotlin(): ControlErrorCode = when (this) {
    RustControlErrorCode.PERMISSION_REQUIRED -> ControlErrorCode.PERMISSION_REQUIRED
    RustControlErrorCode.TRANSPORT_UNAVAILABLE -> ControlErrorCode.TRANSPORT_UNAVAILABLE
    RustControlErrorCode.NOT_READY -> ControlErrorCode.NOT_READY
    RustControlErrorCode.UNSUPPORTED -> ControlErrorCode.UNSUPPORTED
    RustControlErrorCode.INVALID_ARGUMENT -> ControlErrorCode.INVALID_ARGUMENT
    RustControlErrorCode.NOT_APPLIED -> ControlErrorCode.NOT_APPLIED
    RustControlErrorCode.TIMEOUT -> ControlErrorCode.TIMEOUT
    RustControlErrorCode.DISCONNECTED -> ControlErrorCode.DISCONNECTED
    RustControlErrorCode.FAILED -> ControlErrorCode.FAILED
}

private fun HeadsetCommand.toRust(): RustHeadsetCommand = when (this) {
    is HeadsetCommand.NoiseMode -> RustHeadsetCommand.NoiseMode(RustNoiseMode.valueOf(mode.name))
    else -> error("command $this unused in closed-loop noise test")
}

private fun PlatformState.toRust(): RustPlatformState = RustPlatformState(
    permission = permission.toRust(),
    scanPermission = scanPermission.toRust(),
    transport = when (val current = transport) {
        TransportAvailability.Available -> RustTransportAvailability.Available

        TransportAvailability.Disabled -> RustTransportAvailability.Disabled

        is TransportAvailability.Unavailable -> RustTransportAvailability.Unavailable(
            code = RustTransportUnavailableCode.valueOf(current.code.name),
            detail = current.detail,
        )
    },
    hostPlatform = when (hostPlatform) {
        HostPlatform.UNKNOWN -> RustHostPlatform.UNKNOWN
        HostPlatform.ANDROID -> RustHostPlatform.ANDROID
        HostPlatform.WINDOWS -> RustHostPlatform.WINDOWS
        HostPlatform.LINUX -> RustHostPlatform.LINUX
    },
    controlTransportApplicability = when (controlTransportApplicability) {
        PlatformApplicability.UNKNOWN -> RustPlatformApplicability.UNKNOWN
        PlatformApplicability.NOT_APPLICABLE -> RustPlatformApplicability.NOT_APPLICABLE
        PlatformApplicability.APPLICABLE -> RustPlatformApplicability.APPLICABLE
    },
    userUnlocked = userUnlocked,
)

private fun PermissionState.toRust(): RustPermissionState = when (this) {
    PermissionState.NOT_REQUIRED -> RustPermissionState.NOT_REQUIRED
    PermissionState.REQUIRED -> RustPermissionState.REQUIRED
    PermissionState.GRANTED -> RustPermissionState.GRANTED
    PermissionState.DENIED -> RustPermissionState.DENIED
}

private fun toControlState(snapshot: ControlSnapshot): ControlState = ControlState(
    platform = PlatformState(
        permission = PermissionState.valueOf(snapshot.platform.permission.name),
        transport = when (val transport = snapshot.platform.transport) {
            RustTransportAvailability.Available -> TransportAvailability.Available

            RustTransportAvailability.Disabled -> TransportAvailability.Disabled

            is RustTransportAvailability.Unavailable -> TransportAvailability.Unavailable(
                TransportUnavailableCode.valueOf(transport.code.name),
                transport.detail,
            )
        },
        hostPlatform = HostPlatform.valueOf(snapshot.platform.hostPlatform.name),
        controlTransportApplicability = PlatformApplicability.valueOf(snapshot.platform.controlTransportApplicability.name),
        userUnlocked = snapshot.platform.userUnlocked,
        scanPermission = PermissionState.valueOf(snapshot.platform.scanPermission.name),
    ),
    connection = when (val connection = snapshot.connection) {
        RustConnectionState.Disconnected -> ConnectionState.Disconnected

        is RustConnectionState.Connecting -> ConnectionState.Connecting(
            DeviceId(connection.headsetId),
            connection.sessionId.toLong(),
            ConnectionPhase.valueOf(connection.phase.name),
        )

        is RustConnectionState.Connected -> ConnectionState.Connected(
            DeviceId(connection.headsetId),
            connection.sessionId.toLong(),
            ModelProfile.valueOf(connection.profile.name),
            ConnectionPhase.READY,
        )
    },
    settings = UserSettings(forceBle = snapshot.settings.forceBle),
    bondedDevices = snapshot.bondedHeadsets.map(RustHeadsetCandidate::toKotlin).toPersistentList(),
    candidates = snapshot.candidates.map(RustHeadsetCandidate::toKotlin).toPersistentList(),
    device = snapshot.headset?.toDeviceState(),
    audioDeviceId = snapshot.audioHeadsetId?.let(::DeviceId),
    connectionTarget = snapshot.connectionTarget?.toKotlin(),
    selectedHeadsetId = snapshot.selectedHeadsetId?.let(::DeviceId),
)
private fun uniffi.openrose_control.HeadsetState.toDeviceState(): DeviceState {
    val caps = readings.capabilities
    return DeviceState(
        deviceId = DeviceId(headsetId),
        sessionId = sessionId.toLong(),
        profile = ModelProfile.valueOf(readings.profile.name),
        capabilities = DeviceCapabilities(
            readableFields = caps.readableFields.mapNotNull(HeadsetField::toKotlinOrNull).toPersistentSet(),
            writableSettings = caps.writableSettings.mapNotNull(HeadsetSetting::toKotlinOrNull).toPersistentSet(),
            noiseControl = caps.noiseControl?.let { noise ->
                NoiseControlCapabilities(
                    modes = noise.modes.toPersistentSet(),
                    cycleModes = noise.cycleModes.toPersistentSet(),
                    minimumCycleSelections = noise.minimumCycleSelections.toInt(),
                )
            },
        ),
        diagnostics = HeadsetDiagnostics(
            fields = readings.fields.entries.mapNotNull { (field, reading) ->
                field.toKotlinOrNull()?.let { it to reading.toKotlin() }
            }.toMap().toPersistentMap(),
        ),
        classicAddress = classicAddress,
    )
}

private fun RustHeadsetCandidate.toKotlin(): DeviceCandidate = DeviceCandidate(
    deviceId = DeviceId(id),
    name = name,
    rssi = rssi?.toInt(),
    profile = profile?.let { ModelProfile.valueOf(it.name) },
    manufacturerDataHex = manufacturerDataHex,
    classicAddress = classicAddress,
)

private fun HeadsetField.toKotlinOrNull(): DeviceField? = DeviceField.entries.firstOrNull { it.name == name }

private fun HeadsetSetting.toKotlinOrNull(): DeviceSetting? = DeviceSetting.entries.firstOrNull { it.name == name }

private fun RustFieldReading.toKotlin(): FieldReading = when (this) {
    RustFieldReading.Loading -> FieldReading.Loading

    is RustFieldReading.NotProvided -> FieldReading.NotProvided(rawHex)

    is RustFieldReading.Known -> FieldReading.Known(
        value = when (val v = value) {
            is RustFieldValue.Number -> FieldValue.Number(v.v1)
            is RustFieldValue.Toggle -> FieldValue.Toggle(v.v1)
            is RustFieldValue.Text -> FieldValue.Text(v.v1)
            else -> FieldValue.Text(v.toString())
        },
        rawHex = rawHex,
    )

    is RustFieldReading.Unknown -> FieldReading.Unknown(rawHex)
}
