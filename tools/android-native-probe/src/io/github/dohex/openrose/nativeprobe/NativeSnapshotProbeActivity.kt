package io.github.dohex.openrose.nativeprobe

import android.app.Activity
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import io.github.dohex.openrose.bridge.toBridgeSnapshot
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlSession
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionSnapshotResult
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.integration.NativeFieldReading
import io.github.dohex.openrose.integration.NativeFieldValue
import io.github.dohex.openrose.integration.NativeHeadsetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NativeSnapshotProbeActivity : Activity() {
    private var assertions = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val failures = mutableListOf<String>()
            for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
                try {
                    runBlocking { exercise(profile) }
                    Log.i(TAG, "PASS ${profile.name} public session -> production bridge Bundle -> Parcel bytes -> native consumer")
                } catch (failure: Throwable) {
                    failures += profile.name
                    Log.e(TAG, "FAIL ${profile.name}: ${failure.message}", failure)
                }
            }
            if (failures.isEmpty()) {
                Log.i(TAG, "SUMMARY PASS profiles=2 parcel_round_trips=2 assertions=$assertions skip=none api=${android.os.Build.VERSION.SDK_INT}")
            } else {
                Log.e(TAG, "SUMMARY FAIL profiles=${failures.joinToString(",")} assertions=$assertions api=${android.os.Build.VERSION.SDK_INT}")
            }
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun exercise(profile: ModelProfile) {
        expect(android.os.Build.VERSION.SDK_INT == 26, "fixture must run on Android API 26")
        val deviceId = DeviceId(if (profile == ModelProfile.I5_ZT) I5_BLE_ADDRESS else I7_BLE_ADDRESS)
        val manufacturerData = if (profile == ModelProfile.I5_ZT) {
            mapOf(0x5352 to byteArrayOf(0, 1, 0, 1))
        } else {
            // ROSE i7 advertisement: prefix plus independently encoded BR address 11:22:33:44:55:66.
            mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f, 0, 0, 0, 1, 0, 0, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        }
        val transport = ProbeTransport(profile)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = ownerScope,
            timing = SessionTiming(queryTimeoutMillis = 4_000, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
        )
        try {
            expect(session.startScan() == OperationResult.Success, "public startScan succeeds")
            transport.advertisements.emit(Advertisement(deviceId, profile.displayName, manufacturerData = manufacturerData))
            withTimeout(2_000) { session.state.first { state -> state.candidates.any { it.deviceId == deviceId } } }
            expect(session.connect(deviceId) == OperationResult.Success, "public connect reaches READY for ${profile.name}")
            val connected = session.state.value.connection as? ConnectionState.Connected
            expect(connected?.deviceId == deviceId && connected.profile == profile, "public session identifies ${profile.name}")
            val device = requireNotNull(session.state.value.device)
            val reference = SessionReference(device.deviceId, device.sessionId)

            if (profile == ModelProfile.I5_ZT) {
                // Recorded snapshot-test replies establish values; this literal unknown enum is the shared behavior-test vector.
                transport.links.single().deviceReply("000000000006030000a2")
                withTimeout(2_000) {
                    session.state.first { it.device?.fields?.get(DeviceField.GAME_MODE) is FieldReading.Unknown }
                }
            }

            val result = session.readSession(reference)
            val available = result as? SessionSnapshotResult.Available
            expect(available != null, "public readSession returns the current ready-device snapshot")
            val deviceState = requireNotNull(available).device
            val wire = deviceState.toBridgeSnapshot(hostEpoch = "native-probe-${profile.name.lowercase()}", readSequence = 7L)
            val parcelBytes = parcelRoundTrip(wire)
            val native = NativeHeadsetSnapshot.fromBundle(parcelBytes)
            expect(wire.getInt("version") == 3, "production bridge emits split-capability wire version 3")
            expect(native != null, "native consumer accepts production wire version ${wire.getInt("version")}")
            val snapshot = requireNotNull(native)

            for (oldVersion in listOf(1, 2)) {
                val oldWire = Bundle(wire).apply { putInt("version", oldVersion) }
                expect(NativeHeadsetSnapshot.fromBundle(oldWire) == null, "native consumer rejects old wire version $oldVersion")
            }

            val noiseCapabilities = requireNotNull(deviceState.capabilities.noiseControl)
            val unsupportedAncDevice = deviceState.copy(
                capabilities = deviceState.capabilities.copy(
                    writableSettings = deviceState.capabilities.writableSettings - DeviceSetting.ANC_LEVEL,
                    noiseControl = noiseCapabilities.copy(ancLevel = null),
                ),
            )
            val unsupportedAncWire = unsupportedAncDevice.toBridgeSnapshot(
                hostEpoch = "native-probe-${profile.name.lowercase()}",
                readSequence = 7L,
            )
            val unsupportedAnc = NativeHeadsetSnapshot.fromBundle(parcelRoundTrip(unsupportedAncWire))
            expect(unsupportedAnc?.ancLevel == null, "unsupported ANC capability remains absent after wire and Parcel")
            expect(unsupportedAnc != null && "ANC_LEVEL" !in unsupportedAnc.writableSettings, "unsupported ANC setting is absent from writable settings")
            expect(unsupportedAnc?.transparencyLevel?.levels == setOf(1, 3, 5), "unsupported ANC does not merge into transparency choices")

            val differentRangeDevice = deviceState.copy(
                capabilities = deviceState.capabilities.copy(
                    noiseControl = noiseCapabilities.copy(
                        ancLevel = requireNotNull(noiseCapabilities.ancLevel).copy(levels = setOf(1, 3)),
                    ),
                ),
            )
            val differentRangeWire = differentRangeDevice.toBridgeSnapshot(
                hostEpoch = "native-probe-${profile.name.lowercase()}",
                readSequence = 7L,
            )
            val differentRange = NativeHeadsetSnapshot.fromBundle(parcelRoundTrip(differentRangeWire))
            expect(differentRange?.ancLevel?.levels == setOf(1, 3), "ANC range survives independently from transparency range")
            expect(differentRange?.transparencyLevel?.levels == setOf(1, 3, 5), "different ANC range leaves transparency choices unchanged")
            expect(differentRange != null && !snapshot.sameReportedState(differentRange), "setting capability changes are part of native snapshot state")

            expect(snapshot.sessionId == device.sessionId, "session reference survives wire and Parcel")
            expect(snapshot.profile == profile.name, "model configuration survives wire and Parcel")
            expect(snapshot.matchesAddress(deviceId.value), "BLE device address survives wire and Parcel")
            expect(snapshot.supportsNoiseMode, "noise-mode capability survives wire and Parcel")
            expect("NOISE_MODE" in snapshot.writableSettings, "writable setting capability survives wire and Parcel")
            val expectedModes = if (profile == ModelProfile.I5_ZT) {
                setOf("normal", "transparency", "wind", "anc")
            } else {
                setOf("normal", "transparency", "wind", "anc", "auto", "dynamic")
            }
            expect(snapshot.noiseModes == expectedModes, "${profile.name} available noise options survive wire and Parcel")
            expect(snapshot.ancLevel?.levels == setOf(1, 3, 5), "${profile.name} ANC level choices survive wire and Parcel")
            expect(snapshot.ancLevel?.visibleModes == setOf("anc"), "${profile.name} ANC visibility modes survive wire and Parcel")
            expect(snapshot.transparencyLevel?.levels == setOf(1, 3, 5), "${profile.name} transparency choices survive wire and Parcel")
            expect(snapshot.transparencyLevel?.visibleModes == setOf("transparency"), "${profile.name} transparency visibility modes survive wire and Parcel")
            expect(snapshot.supportsNoise("wind"), "supported noise option is actionable")
            expect(snapshot.supportsNoise("auto") == (profile == ModelProfile.I7_RS_V2), "unsupported/available model-specific noise option stays distinct")

            if (profile == ModelProfile.I5_ZT) {
                expect(snapshot.knownNumber("LEFT_BATTERY") == 100, "i5 left battery remains known at 100")
                expect(snapshot.knownNumber("RIGHT_BATTERY") == 98, "i5 right battery remains known at 98")
                expect(snapshot.fields["CASE_BATTERY"] == NativeFieldReading.NotProvided, "i5 case FF remains not-provided, never percentage zero")
                expect(snapshot.knownNumber("CASE_BATTERY") == null, "i5 case FF has no numeric percentage")
                expect(snapshot.fields["GAME_MODE"] == NativeFieldReading.Unknown, "unknown protocol field remains unknown in native snapshot")
                expect(snapshot.knownText("NOISE_MODE") == "wind", "known noise mode survives wire and Parcel")
                expect(snapshot.knownNumber("ANC_LEVEL") == 5, "i5 ANC level reading survives while current wind mode hides its control")
                expect(snapshot.knownNumber("TRANSPARENCY_LEVEL") == 1, "i5 transparency reading survives while current wind mode hides its control")
                expect(snapshot.ancLevel?.isVisibleIn("wind") == false, "i5 ANC control is hidden in the original wind mode")
                expect(snapshot.transparencyLevel?.isVisibleIn("wind") == false, "i5 transparency control is hidden in the original wind mode")
            } else {
                expect(device.classicAddress == I7_BR_ADDRESS, "public i7 model identification yields independent BR address")
                expect(snapshot.addresses == setOf(I7_BLE_ADDRESS, I7_BR_ADDRESS), "i7 native snapshot carries BLE and BR addresses independently")
                expect(snapshot.matchesAddress(I7_BR_ADDRESS) && snapshot.matchesAddress(I7_BLE_ADDRESS), "both i7 addresses are recognized as addresses")
                expect(snapshot.knownNumber("LEFT_BATTERY") == 100, "i7 left battery remains known at 100")
                expect(snapshot.knownNumber("RIGHT_BATTERY") == 50, "i7 right battery remains known at 50")
                expect(snapshot.knownNumber("CASE_BATTERY") == 80, "i7 case battery remains known at 80")
            }

            session.disconnect()
            val disconnected = session.readSession(reference) as? SessionSnapshotResult.Failure
            expect(disconnected?.error?.code == ControlErrorCode.NOT_READY, "old session reference is rejected after disconnect")
            expect(session.connect(deviceId) == OperationResult.Success, "public session reconnects to a fresh session")
            val stale = session.readSession(reference) as? SessionSnapshotResult.Failure
            expect(stale?.error?.code == ControlErrorCode.DISCONNECTED, "old reference is rejected after a replacement session becomes ready")
            expect(session.readSession(SessionReference(deviceId, requireNotNull(session.state.value.device).sessionId)) is SessionSnapshotResult.Available,
                "new session reference is accepted")
        } finally {
            session.disconnect()
            ownerScope.cancel()
        }
    }

    private fun parcelRoundTrip(bundle: Bundle): Bundle {
        val outgoing = Parcel.obtain()
        val incoming = Parcel.obtain()
        try {
            bundle.writeToParcel(outgoing, 0)
            val bytes = outgoing.marshall()
            incoming.unmarshall(bytes, 0, bytes.size)
            incoming.setDataPosition(0)
            return requireNotNull(Bundle.CREATOR.createFromParcel(incoming)).also {
                it.classLoader = javaClass.classLoader
            }
        } finally {
            outgoing.recycle()
            incoming.recycle()
        }
    }

    private fun expect(condition: Boolean, message: String) {
        assertions++
        check(condition) { message }
    }

    private inner class ProbeTransport(private val profile: ModelProfile) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val links = mutableListOf<ProbeConnection>()

        override fun scan(): Flow<Advertisement> = advertisements

        override suspend fun connect(
            deviceId: DeviceId,
            endpoint: TransportEndpoint,
            onPhase: (ConnectionPhase) -> Unit,
        ): ControlConnection = ProbeConnection(profile).also { links += it }
    }

    private class ProbeConnection(private val profile: ModelProfile) : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications: Flow<ByteArray> = incoming.receiveAsFlow()
        private var initializing = true

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            if (!initializing) return
            val hex = bytes.toHex()
            if (profile == ModelProfile.I5_ZT) {
                I5_REPLIES[hex.substring(10, 14)]?.let { incoming.send(it.fromHex()) }
            } else {
                I7_REPLIES.forEach { incoming.send(it.fromHex()) }
            }
        }

        override suspend fun close() {
            initializing = false
            incoming.close()
        }

        suspend fun deviceReply(hex: String) {
            incoming.send(hex.fromHex())
        }
    }

    private companion object {
        const val TAG = "OpenRoseNativeProbe"
        const val I5_BLE_ADDRESS = "10:20:30:40:50:60"
        const val I7_BLE_ADDRESS = "22:33:44:55:66:77"
        const val I7_BR_ADDRESS = "11:22:33:44:55:66"
        val I5_REPLIES = mapOf(
            "0101" to "09ff00000101011100000164620000ffe2",
            "0602" to "09ff00000106020e000000010020",
            "0201" to "09ff00000102010b000118",
            "0603" to "09ff00000106030b00011e",
            "0801" to "09ff00000108010b00011e",
            "0d01" to "09ff0000010d010e00010205002d",
            "0401" to "09ff00000104011200060502040605030746",
            "0a01" to "09ff0000010a010b00001f",
            "0605" to "09ff00000106050e000101010126",
            "0c01" to "09ff0000010c010b000122",
            "0607" to "09ff00000106070b000223",
            "0604" to "09ff00000106040b00001e",
            "0e03" to "09ff0000010e030b000126",
        )
        val I7_REPLIES = listOf(
            "dd0011010100020003000400110012001300140043aa",
            "dd00020700e6aa",
            "dd00020801e8aa",
            "dd00020904ecaa",
            "dd00040ce4325053aa",
            "dd00040d020103f4aa",
            "dd00020e00edaa",
            "dd00022a0009aa",
            "dd00022b000aaa",
            "dd00022c030eaa",
            "dd00022d030faa",
            "dd00022e010eaa",
            "dd00022f000eaa",
            "dd0002320011aa",
            "dd0005360000000018aa",
            "dd000b3e86838100010203040506c5aa",
            "dd0002450125aa",
            "dd0002460328aa",
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
