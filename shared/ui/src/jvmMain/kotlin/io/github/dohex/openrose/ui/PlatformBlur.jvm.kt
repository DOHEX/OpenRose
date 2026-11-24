package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val POWER_SAVE_POLL_MILLIS = 30_000L

private const val LOW_BATTERY_PERCENT = 20

@Composable
internal actual fun isPlatformPowerSaveMode(): Boolean {
    val powerSave by produceState(false) {
        while (true) {
            value = withContext(Dispatchers.IO) { isLinuxPowerSaving() }
            delay(POWER_SAVE_POLL_MILLIS)
        }
    }
    return powerSave
}

internal fun isLinuxPowerSaving(sysRoot: File = File("/sys")): Boolean = runCatching {
    if (File(sysRoot, "firmware/acpi/platform_profile").readTextOrNull() == "low-power") return@runCatching true
    File(sysRoot, "class/power_supply").listFiles().orEmpty().any { supply ->
        File(supply, "type").readTextOrNull() == "Battery" &&
            File(supply, "status").readTextOrNull() == "Discharging" &&
            (File(supply, "capacity").readTextOrNull()?.toIntOrNull() ?: 100) <= LOW_BATTERY_PERCENT
    }
}.getOrDefault(false)

private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()?.trim()
