package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OUT = 0x64 // 100 %, out of the case
private const val IN = 0xe4 // 100 %, bit7 in-case flag
private const val RIGHT_OUT = 0x32 // 50 %, out of the case
private const val RIGHT_IN = 0xb2 // 50 %, in the case
private const val UNAVAILABLE = 0xff

/** 入盒提示: an earbud's in-case flag going from known out to known in within one session, on RS Common V2 models with a case. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InCasePromptTest {
    private val moduleInCase = IntegrationSettings(islandMode = IslandMode.MODULE, islandTriggers = persistentSetOf(IslandTrigger.IN_CASE))

    @Test
    fun onlyModelsWithACaseAndInCaseFlagsCanShowAnInCasePrompt() {
        assertEquals(
            setOf(ModelProfile.I7_RS_V2, ModelProfile.BUDSFEEL_MK2_RS_V2, ModelProfile.OPENFEEL_MK2_RS_V2),
            ModelProfile.entries.filter { it.reportsInCase }.toSet(),
        )
    }

    @Test
    fun theFirstReadingIsNotAnEventAndEachLaterEntryReplacesThePromptOnce() = runTest {
        val (session, link) = connected(moduleInCase, initial = i7Battery(IN, RIGHT_OUT))
        assertNull(session.presentation.value.prompt, "an earbud already in the case at connection is not an in-case event")
        assertTrue(session.presentation.value.allowIslandFields, "a module display timing other than connection still allows island content")

        link.deviceReply(i7Battery(OUT, RIGHT_OUT))
        runCurrent()
        assertNull(session.presentation.value.prompt, "taking an earbud out is not an in-case event")

        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        val left = assertNotNull(session.presentation.value.prompt)
        assertEquals(IslandTrigger.IN_CASE, left.trigger)
        assertEquals(IslandMode.MODULE, left.mode)

        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        assertEquals(left, session.presentation.value.prompt, "a repeated frame is no new event")

        link.deviceReply(i7Battery(IN, RIGHT_IN))
        runCurrent()
        val right = assertNotNull(session.presentation.value.prompt)
        assertEquals(IslandTrigger.IN_CASE, right.trigger)
        assertNotEquals(left.id, right.id, "the other earbud entering replaces the showing prompt")
        session.disconnect()
    }

    @Test
    fun anUnavailableFlagBeforeEnteringIsNotAKnownExit() = runTest {
        val (session, link) = connected(moduleInCase, initial = i7Battery(OUT, RIGHT_OUT))
        link.deviceReply(i7Battery(UNAVAILABLE, RIGHT_OUT))
        runCurrent()
        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        assertNull(session.presentation.value.prompt)
        session.disconnect()
    }

    @Test
    fun officialModeAndAnUnselectedTimingConsumeTheEventWithoutPrompting() = runTest {
        for (settings in listOf(IntegrationSettings(islandMode = IslandMode.OFFICIAL), IntegrationSettings(islandMode = IslandMode.MODULE))) {
            val (session, link) = connected(settings, initial = i7Battery(OUT, RIGHT_OUT))
            val connection = session.presentation.value.prompt
            assertEquals(IslandTrigger.CONNECTION, connection?.trigger)

            link.deviceReply(i7Battery(IN, RIGHT_OUT))
            runCurrent()
            assertEquals(connection, session.presentation.value.prompt, "${settings.islandMode} shows no in-case prompt")

            session.updateIntegrationSettings(moduleInCase)
            assertNull(session.presentation.value.prompt, "selecting the timing later does not replay the event")
            session.disconnect()
        }
    }

    private suspend fun TestScope.connected(settings: IntegrationSettings, initial: String): Pair<SystemEntryRuntime, I7Connection> {
        val link = I7Connection(initial)
        val transport = I7Transport(link)
        val session = SystemEntryRuntime(
            LocalControlRuntime(
                PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                transport = transport,
                scope = backgroundScope,
                timing = SessionTiming(writeIntervalMillis = 0),
            ),
            backgroundScope,
            initialIntegrationSettings = settings,
        )
        val id = DeviceId("i7")
        session.startScan()
        runCurrent()
        transport.advertisements.emit(roseAdvertisement(ModelProfile.I7_RS_V2, id))
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(id))
        runCurrent()
        return session to link
    }

    private class I7Transport(private val link: I7Connection) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = link
    }

    /** Answers initialization with the shared synthetic i7 state, its battery record replaced by [battery]. */
    private class I7Connection(private val battery: String) : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            initializationReplies(ModelProfile.I7_RS_V2, bytes.toHex())
                .map { if (it.startsWith("dd00040c")) battery else it }
                .forEach { incoming.send(it.fromHex()) }
        }

        suspend fun deviceReply(hex: String) = incoming.send(hex.fromHex())

        override suspend fun close() {
            incoming.close()
        }
    }
}

/** An RS Common V2 left/right/case battery record; the checksum sums every byte from `dd`. */
private fun i7Battery(left: Int, right: Int, case: Int = 0x50): String {
    val body = listOf(0xdd, 0x00, 0x04, 0x0c, left, right, case)
    return (body + (body.sum() and 0xff) + 0xaa).joinToString("") { it.toString(16).padStart(2, '0') }
}

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
