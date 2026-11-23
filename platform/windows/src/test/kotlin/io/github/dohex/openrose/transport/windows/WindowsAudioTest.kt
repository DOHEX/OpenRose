package io.github.dohex.openrose.transport.windows

import kotlin.test.*

class WindowsAudioTest {
    @Test
    fun acquiringWaitsForTheRenderEndpointAndRestoresOnlyTheOutputWindowsMoved() {
        var connecting = false
        var checkedAfterConnect = 0
        val restored = mutableListOf<List<String>>()
        val audio = WindowsAudio({ args ->
            when (args.first()) {
                "defaults" -> if (connecting) listOf("1/abcd/6935/headset", "2/2222/555342/usb") else listOf("1/1111/555342/usb", "2/2222/555342/usb")

                "connect" -> {
                    assertEquals(listOf("connect", "001122334455"), args)
                    connecting = true
                    emptyList()
                }

                "state" -> listOf("headset/${if (connecting && ++checkedAfterConnect >= 2) 1 else 0}/0/0")

                "restore" -> {
                    restored += args
                    emptyList()
                }

                else -> error("Unexpected $args")
            }
        }, wait = {})
        assertEquals("USB", audio.currentOutput())
        audio.acquire("00:11:22:33:44:55")
        assertTrue(checkedAfterConnect >= 2)
        assertEquals(listOf(listOf("restore", "1", "1111")), restored)
    }

    @Test
    fun aConfirmedRecordingRefusesReleaseButPlaybackCanBeReleased() {
        var recording = true
        var released = false
        val audio = WindowsAudio({ args ->
            assertEquals("001122334455", args[1])
            when (args[0]) {
                "state" -> listOf(if (released) "headset/0/0/0" else "headset/1/1/${if (recording) 1 else 0}")

                "disconnect" -> {
                    released = true
                    emptyList()
                }

                else -> error("Unexpected $args")
            }
        })
        val address = "00:11:22:33:44:55"
        assertContains(assertFailsWith<IllegalStateException> { audio.release(address) }.message.orEmpty(), "麦克风")
        assertTrue(audio.isConnected(address))
        recording = false
        audio.release(address)
        assertFalse(audio.isConnected(address))
    }

    @Test
    fun aDriverRequestWithoutAnAudioConnectionTimesOutAndLeavesTheSpeakerAlone() {
        val calls = mutableListOf<String>()
        val audio = WindowsAudio({ args ->
            calls += args.first()
            when (args.first()) {
                "state" -> listOf("headset/0/0/0")
                "defaults" -> listOf("1/2222/555342/speaker")
                "connect" -> emptyList()
                else -> error("Unexpected $args")
            }
        }, wait = {}, timeoutMillis = 0)
        assertContains(assertFails { audio.acquire("00:11:22:33:44:55") }.message.orEmpty(), "超时")
        assertFalse("restore" in calls)
        assertFalse("disconnect" in calls)
    }
}
