package io.github.dohex.openrose.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPowerSaveTest {

    @Test
    fun aMissingSysfsIsNotPowerSaving() {
        assertFalse(isLinuxPowerSaving(sysfs()))
    }

    @Test
    fun theLowPowerPlatformProfileIsPowerSaving() {
        assertTrue(isLinuxPowerSaving(sysfs(profile = "low-power")))
        assertFalse(isLinuxPowerSaving(sysfs(profile = "balanced")))
    }

    @Test
    fun aDischargingBatteryAtOrBelowTwentyPercentIsPowerSaving() {
        assertTrue(isLinuxPowerSaving(sysfs(battery = Battery("Discharging", "20"))))
        assertFalse(isLinuxPowerSaving(sysfs(battery = Battery("Discharging", "21"))))
        assertFalse(isLinuxPowerSaving(sysfs(battery = Battery("Charging", "5"))))
        assertFalse(isLinuxPowerSaving(sysfs(battery = Battery("Discharging", "n/a"))))
    }

    @Test
    fun aMainsSupplyIsNotABattery() {
        assertFalse(isLinuxPowerSaving(sysfs(battery = Battery("Discharging", "5", type = "Mains"))))
    }

    private class Battery(val status: String, val capacity: String, val type: String = "Battery")

    private fun sysfs(profile: String? = null, battery: Battery? = null): File {
        val root = Files.createTempDirectory("sysfs").toFile().also { it.deleteOnExit() }
        profile?.let { File(root, "firmware/acpi").apply { mkdirs() }.resolve("platform_profile").writeText("$it\n") }
        battery?.let {
            File(root, "class/power_supply/BAT0").apply { mkdirs() }.also { dir ->
                dir.resolve("type").writeText("${it.type}\n")
                dir.resolve("status").writeText("${it.status}\n")
                dir.resolve("capacity").writeText("${it.capacity}\n")
            }
        }
        return root
    }
}
