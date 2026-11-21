package io.github.dohex.openrose.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxCirculationAudioTest {
    @Test
    fun nativePulseAndPipeWireBothProtectTheSpecificHeadsetMicrophone() {
        val address = "00:11:22:33:44:55"
        for (name in listOf("bluez_source.00_11_22_33_44_55.headset_head_unit", "bluez_input.00:11:22:33:44:55.0")) {
            val sources = "31\t$name\tBluetooth\n32\talsa_input.other\tALSA"
            val active = "Source Output #1\n    Source: 31\n    Corked: no\n"
            assertTrue(headsetMicActive(address, sources, active))
            assertFalse(headsetMicActive(address, sources, active.replace("31", "32")))
            assertFalse(headsetMicActive(address, sources, active.replace("no", "yes")))
        }
    }
}
