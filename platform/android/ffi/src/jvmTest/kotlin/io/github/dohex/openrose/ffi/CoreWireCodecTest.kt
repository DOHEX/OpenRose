package io.github.dohex.openrose.ffi

import io.github.dohex.openrose.ffi.drivers.ADDRESS
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.clock
import io.github.dohex.openrose.ffi.drivers.platformState
import io.github.dohex.openrose.ffi.drivers.rig
import io.github.dohex.openrose.ffi.drivers.timing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlCommand
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.SessionReference
import uniffi.openrose_headset.HeadsetCommand
import uniffi.openrose_headset.NoiseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreWireCodecTest {
    @Test
    fun simulatedI5SessionSnapshotRoundTripsAndRejectsBadBytes() = runBlocking<Unit> {
        rig().use { rig ->
            ControlTransportAdapter(rig).use { transport ->
                RustControlRuntime(
                    transport = transport,
                    initialPlatformState = rig.platformState(),
                    clock = rig.clock(),
                    timing = rig.timing(),
                ).use { runtime ->
                    assertEquals(RustControlResult.Success, runtime.connect(ADDRESS))
                    assertEquals(RustControlResult.Success, runtime.set(HeadsetCommand.NoiseMode(NoiseMode.ANC)))
                    val connected = withTimeout(2_000) {
                        runtime.state.first { it.connection is ConnectionState.Connected }
                    }

                    val encoded = connected.encodeToBytes()
                    assertEquals(coreWireFormatVersion(), encoded[3].toUByte())
                    val decoded = assertIs<CoreDecodeResult.Ok<ControlSnapshot>>(decodeControlSnapshot(encoded))
                    assertEquals(connected, decoded.value)

                    assertIs<CoreDecodeResult.Error.Malformed>(
                        decodeControlSnapshot("corrupt".encodeToByteArray()),
                    )

                    val wrongVersion = encoded.copyOf().apply {
                        this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
                    }
                    val versionError = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(
                        decodeControlSnapshot(wrongVersion),
                    )
                    assertEquals(coreWireFormatVersion(), versionError.expected)
                    assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), versionError.actual)

                    runtime.handle.disconnect()
                }
            }
        }
    }

    @Test
    fun kotlinEncodedCommandsRoundTripThroughCoreAndRejectBadBytes() {
        val reference = SessionReference(headsetId = ADDRESS, sessionId = 42uL)
        val commands = listOf(
            ControlCommand.RefreshBondedHeadsets,
            ControlCommand.StartScan,
            ControlCommand.StopScan,
            ControlCommand.SelectCurrentHeadset(headsetId = ADDRESS),
            ControlCommand.Connect(headsetId = ADDRESS),
            ControlCommand.ConnectPinned(headsetId = ADDRESS),
            ControlCommand.ConnectForAddHeadset(headsetId = ADDRESS),
            ControlCommand.Disconnect,
            ControlCommand.Set(reference = null, command = HeadsetCommand.NoiseMode(NoiseMode.ANC)),
            ControlCommand.Set(
                reference = reference,
                command = HeadsetCommand.NoiseCycle(mapOf("anc" to true, "normal" to true)),
            ),
            ControlCommand.Set(
                reference = reference,
                command = HeadsetCommand.TouchBinding(
                    side = "left",
                    gesture = "double_tap",
                    action = "play_pause",
                ),
            ),
            ControlCommand.ClaimPrompt(reference = reference, promptId = "prompt-1"),
        )

        for (command in commands) {
            val encoded = command.encodeToBytes()
            assertEquals(coreWireFormatVersion(), encoded[3].toUByte())
            val decoded = assertIs<CoreDecodeResult.Ok<ControlCommand>>(decodeControlCommand(encoded))
            assertEquals(command, decoded.value)
        }

        assertIs<CoreDecodeResult.Error.Malformed>(
            decodeControlCommand("corrupt".encodeToByteArray()),
        )

        val wrongVersion = ControlCommand.StartScan.encodeToBytes().apply {
            this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
        }
        val versionError = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(
            decodeControlCommand(wrongVersion),
        )
        assertEquals(coreWireFormatVersion(), versionError.expected)
        assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), versionError.actual)
    }
}
