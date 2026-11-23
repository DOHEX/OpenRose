package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.runBlocking
import org.bluez.Device1
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeBluezAudioBusTest {
    @Test
    fun connectProfileExposesTheBluezBusyReplyToTheAcquisitionLoop() = runBlocking {
        val type = Device1::class.java
        val device = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                assertEquals("ConnectProfile", method.name)
                assertEquals(AUDIO_SINK_UUID, args?.single())
                throw org.bluez.Error.InProgress("browsing")
            },
        )
        val audio = NativeBluezAudioBus(
            deviceAt = { _, _ -> device },
            classicAt = { _, _ -> error("Unexpected bearer lookup") },
            leAt = { _, _ -> error("Unexpected bearer lookup") },
        )
        assertFailsWith<org.bluez.Error.InProgress> { audio.connectAudio("/org/bluez/hci0/dev_00_11_22_33_44_55") }
        Unit
    }

    @Test
    fun dualBearerReleaseTargetsOnlyTheInitiallyConnectedBearersInClassicThenLeOrder() = runBlocking {
        val cases = listOf(
            links(true, true) to listOf("classic.disconnect", "le.disconnect"),
            links(true, false) to listOf("classic.disconnect"),
            links(false, true) to listOf("le.disconnect"),
            links(false, false) to emptyList(),
        )
        for ((interfaces, expected) in cases) {
            val calls = mutableListOf<String>()
            recordingBus(calls).disconnectAudio(PATH, OWNER, interfaces)
            assertEquals(expected, calls)
        }
    }

    @Test
    fun aBearerThatHasAlreadyDroppedDoesNotStopTheReleaseOfTheOtherBearer() = runBlocking {
        for (gone in listOf("classic", "le")) {
            val calls = mutableListOf<String>()
            recordingBus(calls, gone).disconnectAudio(PATH, OWNER, links(true, true))
            assertEquals(listOf("classic.disconnect", "le.disconnect"), calls)
        }
    }

    @Test
    fun aMissingBearerReadingOnEitherSideOnlyDisconnectsDevice1() = runBlocking {
        val cases = listOf(
            emptyMap(),
            mapOf(CLASSIC_BEARER to mapOf("Connected" to true)),
        )
        for (interfaces in cases) {
            val calls = mutableListOf<String>()
            recordingBus(calls).disconnectAudio(PATH, OWNER, interfaces)
            assertEquals(listOf("device.Disconnect"), calls)
        }
    }

    private fun recordingBus(calls: MutableList<String>, gone: String? = null): NativeBluezAudioBus {
        fun <T> lookup(type: Class<T>, label: String, owner: String, path: String): T {
            assertEquals(OWNER, owner)
            assertEquals(PATH, path)
            return type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                    calls += "$label.${method.name}"
                    if (label == gone) throw org.bluez.Error.NotConnected("bearer already down")
                    null
                },
            )
        }
        return NativeBluezAudioBus(
            deviceAt = { owner, path -> lookup(Device1::class.java, "device", owner, path) },
            classicAt = { owner, path -> lookup(ClassicBearer::class.java, "classic", owner, path) },
            leAt = { owner, path -> lookup(LeBearer::class.java, "le", owner, path) },
        )
    }

    private fun links(classic: Boolean, le: Boolean) = mapOf(
        CLASSIC_BEARER to mapOf("Connected" to classic),
        LE_BEARER to mapOf("Connected" to le),
    )

    private companion object {
        const val PATH = "/org/bluez/hci0/dev_00_11_22_33_44_55"
        const val OWNER = ":1.42"
    }
}
