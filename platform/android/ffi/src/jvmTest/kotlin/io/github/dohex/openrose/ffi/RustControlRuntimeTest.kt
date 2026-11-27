package io.github.dohex.openrose.ffi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.openrose_control.AudioConnectionState
import uniffi.openrose_control.AudioProfile
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlErrorCode
import uniffi.openrose_control.GattCharacteristic
import uniffi.openrose_control.GattEvent
import uniffi.openrose_control.GattFailureKind
import uniffi.openrose_control.GattService
import uniffi.openrose_control.GattSubscription
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PermissionState
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.RawAudioDevice
import uniffi.openrose_control.RawAudioEvent
import uniffi.openrose_control.RawAudioSnapshot
import uniffi.openrose_control.RawProfileSnapshot
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_control.TransportAvailability
import uniffi.openrose_ffi.AudioEvents
import uniffi.openrose_ffi.AudioFactsDriver
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.GattDriver
import uniffi.openrose_ffi.GattDriverException
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.ScanDriver
import uniffi.openrose_ffi.SimulatedHeadset
import uniffi.openrose_ffi.SppDriver
import uniffi.openrose_ffi.TransportDriverException
import uniffi.openrose_ffi.modelDisplayName
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_headset.FieldReading
import uniffi.openrose_headset.FieldValue
import uniffi.openrose_headset.FirmwareVersion
import uniffi.openrose_headset.HeadsetCommand
import uniffi.openrose_headset.HeadsetField
import uniffi.openrose_headset.HeadsetId
import uniffi.openrose_headset.HeadsetSetting
import uniffi.openrose_headset.LocalDateTime
import uniffi.openrose_headset.ModelProfile
import uniffi.openrose_headset.NoiseMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RustControlRuntimeTest {
    private class SimulatedBleRig(
        val profile: ModelProfile = ModelProfile.I5_ZT,
        val address: String = I5_ADDRESS,
    ) : AutoCloseable {
        val device = SimulatedHeadset(profile, FirmwareVersion(listOf(1, 2, 5), "125"))
        val muted = AtomicBoolean(false)
        val failNextOpen = AtomicBoolean(false)
        val failNextConnect = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<String>()
        val events = Channel<GattEvent>(Channel.UNLIMITED)
        val audioEvents = Channel<RawAudioEvent>(Channel.UNLIMITED)
        val audioConnected = AtomicBoolean(false)

        fun inject(hex: String) {
            events.trySend(GattEvent.Notification(NOTIFIER_ID, hex.hexToBytes()))
        }

        fun platformState(): PlatformState = PlatformState(
            permission = PermissionState.GRANTED,
            scanPermission = PermissionState.GRANTED,
            transport = TransportAvailability.Available,
            hostPlatform = HostPlatform.ANDROID,
            controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
            userUnlocked = true,
        )

        fun timing(): SessionTiming = SessionTiming(
            connectTimeout = Duration.ofMillis(2_000),
            queryTimeout = Duration.ofMillis(500),
            writeInterval = Duration.ZERO,
            writeTimeout = Duration.ofMillis(500),
        )

        fun clock(): LocalClock = object : LocalClock {
            override fun now(): LocalDateTime = LocalDateTime(2026u, 11u, 26u, 10u, 0u, 0u)
        }

        fun transport(): ControlTransport = object : ControlTransport {
            override fun openGatt(headset: HeadsetId): GattDriver {
                if (failNextOpen.compareAndSet(true, false)) {
                    throw IllegalStateException("simulated unexpected driver crash")
                }
                return object : GattDriver {
                    override suspend fun nextEvent(): GattEvent? = events.receiveCatching().getOrNull()

                    override suspend fun connect() {
                        if (failNextConnect.compareAndSet(true, false)) {
                            throw GattDriverException.Failed(GattFailureKind.PERMISSION, "BLUETOOTH_CONNECT revoked")
                        }
                    }

                    override suspend fun discoverServices(): List<GattService> {
                        val gatt = modelGattProfile(profile)
                        return listOf(
                            GattService(
                                uuid = gatt.serviceUuid,
                                characteristics = listOf(
                                    GattCharacteristic(
                                        instanceId = WRITER_ID,
                                        uuid = gatt.writeUuid,
                                        canWrite = true,
                                        canWriteWithoutResponse = true,
                                        canNotify = false,
                                        canIndicate = false,
                                    ),
                                    GattCharacteristic(
                                        instanceId = NOTIFIER_ID,
                                        uuid = gatt.notifyUuid,
                                        canWrite = false,
                                        canWriteWithoutResponse = false,
                                        canNotify = true,
                                        canIndicate = false,
                                    ),
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
                        writes.add(value.toLowerHex())
                        if (!muted.get()) {
                            for (reply in device.receive(value)) {
                                events.trySend(GattEvent.Notification(NOTIFIER_ID, reply))
                            }
                        }
                    }

                    override suspend fun closeLink() = Unit
                }
            }

            override fun startScan(): ScanDriver = throw TransportDriverException.Unsupported("scan unused")

            override fun openSpp(headset: HeadsetId, address: String, serviceUuid: String): SppDriver = throw TransportDriverException.Unsupported("spp unused")
            override fun audioFacts(): AudioFactsDriver = object : AudioFactsDriver {
                override fun subscribe(): AudioEvents = object : AudioEvents {
                    override suspend fun nextEvent(): RawAudioEvent? = audioEvents.receiveCatching().getOrNull()
                }

                override suspend fun bondedDevices(): List<RawAudioDevice> = listOf(pairedDevice())

                override suspend fun snapshot(targetAddresses: List<String>): RawAudioSnapshot {
                    val connected = if (audioConnected.get()) listOf(address) else emptyList()
                    val states = mapOf(
                        address to if (audioConnected.get()) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED,
                    )
                    return RawAudioSnapshot(
                        enabled = true,
                        sppAvailable = false,
                        leAudioObservable = false,
                        bondedDevices = listOf(pairedDevice()),
                        profiles = mapOf(AudioProfile.A2DP to RawProfileSnapshot(connected, states)),
                    )
                }
            }

            override fun supportsInitializationRecovery(): Boolean = false
        }

        private fun pairedDevice(): RawAudioDevice = RawAudioDevice(
            name = modelDisplayName(profile),
            address = address,
            alias = null,
        )

        override fun close() {
            events.close()
            audioEvents.close()
            device.close()
        }
    }

    @Test
    fun stateFlowTracksRevisionsAndConfirmsSettingOnlyAfterHeadsetReadback() = runBlocking {
        SimulatedBleRig().use { rig ->
            RustControlRuntime(
                transport = rig.transport(),
                initialPlatformState = rig.platformState(),
                clock = rig.clock(),
                timing = rig.timing(),
            ).use { runtime ->
                assertEquals(RustControlResult.Success, runtime.connect(I5_ADDRESS))
                val connected = withTimeout(2_000) {
                    runtime.state.first { it.connection is ConnectionState.Connected }
                }
                val session = assertIs<ConnectionState.Connected>(connected.connection).sessionId
                assertEquals(FieldValue.Text("wind"), knownValue(connected.headset?.readings?.fields?.get(HeadsetField.NOISE_MODE)))
                assertEquals(I5_ADDRESS, connected.currentHeadset()?.id)
                assertEquals(session, connected.sessionFor(HeadsetSetting.NOISE_MODE)?.sessionId)

                rig.muted.set(true)
                val writesBefore = rig.writes.size
                val setting = async(Dispatchers.Default) {
                    runtime.set(HeadsetCommand.NoiseMode(NoiseMode.ANC), session)
                }
                awaitCondition("noise readback query") {
                    rig.writes.drop(writesBefore).any { it == NOISE_READBACK_QUERY }
                }

                assertFalse(setting.isCompleted)
                assertEquals(
                    FieldValue.Text("wind"),
                    knownValue(runtime.state.value.headset?.readings?.fields?.get(HeadsetField.NOISE_MODE)),
                )

                rig.inject(NOISE_REPLY_ANC)
                assertEquals(RustControlResult.Success, withTimeout(2_000) { setting.await() })
                val updated = withTimeout(2_000) {
                    runtime.state.first {
                        knownValue(it.headset?.readings?.fields?.get(HeadsetField.NOISE_MODE)) == FieldValue.Text("anc")
                    }
                }
                assertTrue(updated.revision > connected.revision)
                assertTrue(runtime.disconnect())
            }
        }
    }

    @Test
    fun cancellingAddHeadsetCoroutineExplicitlyCancelsCoreAttempt() = runBlocking {
        SimulatedBleRig().use { rig ->
            RustControlRuntime(
                transport = rig.transport(),
                initialPlatformState = rig.platformState(),
                clock = rig.clock(),
                timing = rig.timing(),
            ).use { runtime ->
                rig.muted.set(true)
                val add = async(Dispatchers.Default) {
                    runtime.connectForAddHeadset(I5_ADDRESS)
                }
                withTimeout(2_000) {
                    runtime.state.first { it.connection is ConnectionState.Connecting }
                }

                add.cancel()
                withTimeout(2_000) {
                    runtime.state.first { it.connection is ConnectionState.Disconnected }
                }
                assertTrue(runtime.release())
            }
        }
    }

    @Test
    fun disconnectingCompletesPendingSettingAndSnapshotBytesRoundTrip() = runBlocking {
        SimulatedBleRig().use { rig ->
            RustControlRuntime(
                transport = rig.transport(),
                initialPlatformState = rig.platformState(),
                clock = rig.clock(),
                timing = rig.timing(),
            ).use { runtime ->
                assertEquals(RustControlResult.Success, runtime.connect(I5_ADDRESS))
                val connected = withTimeout(2_000) {
                    runtime.state.first { it.connection is ConnectionState.Connected }
                }

                val encoded = connected.encodeToBytes()
                assertEquals(connected, decodeControlSnapshot(encoded).getOrThrow())
                assertTrue(decodeControlSnapshot("corrupt".encodeToByteArray()).isFailure)

                rig.muted.set(true)
                val writesBefore = rig.writes.size
                val pending = async(Dispatchers.Default) {
                    runtime.set(HeadsetCommand.NoiseMode(NoiseMode.ANC))
                }
                awaitCondition("pending write") {
                    rig.writes.drop(writesBefore).any { it == NOISE_READBACK_QUERY }
                }

                assertTrue(runtime.disconnect())
                val failure = assertIs<RustControlResult.Failure>(withTimeout(2_000) { pending.await() })
                assertEquals(ControlErrorCode.DISCONNECTED, failure.error.code)
            }
        }
    }

    @Test
    fun driverFailureAndUnexpectedExceptionKeepTheJvmAndRuntimeUsable() = runBlocking {
        SimulatedBleRig().use { rig ->
            RustControlRuntime(
                transport = rig.transport(),
                initialPlatformState = rig.platformState(),
                clock = rig.clock(),
                timing = rig.timing(),
            ).use { runtime ->
                rig.failNextConnect.set(true)
                val denied = assertIs<RustControlResult.Failure>(runtime.connect(I5_ADDRESS))
                assertEquals(ControlErrorCode.PERMISSION_REQUIRED, denied.error.code)

                rig.failNextOpen.set(true)
                val crashed = assertIs<RustControlResult.Failure>(runtime.connect(I5_ADDRESS))
                assertEquals(ControlErrorCode.DISCONNECTED, crashed.error.code)

                assertEquals(RustControlResult.Success, runtime.connect(I5_ADDRESS))
                assertTrue(runtime.disconnect())
            }
        }
    }

    private fun knownValue(reading: FieldReading?): FieldValue? = (reading as? FieldReading.Known)?.value

    private suspend fun awaitCondition(description: String, condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) {
                delay(5)
            }
        }
        assertTrue(condition(), "expected $description")
    }

    private companion object {
        const val I5_ADDRESS = "AA:BB:CC:00:00:05"
        const val WRITER_ID = 1uL
        const val NOTIFIER_ID = 2uL
        const val NOISE_READBACK_QUERY = "08ee00000006020a0008"
        const val NOISE_REPLY_ANC = "09ff00000106020e000100000020"
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
