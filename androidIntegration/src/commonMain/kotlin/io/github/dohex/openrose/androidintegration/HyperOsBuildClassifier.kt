package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.PlatformApplicability

/**
 * Classify the currently targeted HyperOS 3/4 builds from Android build identity. This adapter
 * helper deliberately does not inspect module, bridge, or target-package state.
 */
fun classifyHyperOsBuild(
    manufacturer: String,
    brand: String,
    incrementalVersion: String,
): PlatformApplicability {
    val normalizedManufacturer = manufacturer.trim().lowercase()
    val normalizedBrand = brand.trim().lowercase()
    val version = incrementalVersion.trim()
    val manufacturerKnown = normalizedManufacturer.isNotEmpty() && normalizedManufacturer != BUILD_UNKNOWN
    val brandKnown = normalizedBrand.isNotEmpty() && normalizedBrand != BUILD_UNKNOWN
    if (!manufacturerKnown && !brandKnown) {
        return PlatformApplicability.UNKNOWN
    }

    val xiaomiFamily = normalizedManufacturer in XIAOMI_DEVICE_NAMES || normalizedBrand in XIAOMI_DEVICE_NAMES
    if (!xiaomiFamily) return PlatformApplicability.NOT_APPLICABLE
    if (version.isEmpty() || version.equals(BUILD_UNKNOWN, ignoreCase = true)) return PlatformApplicability.UNKNOWN

    val hyperOsMatch = HYPER_OS_INCREMENTAL.matchEntire(version)
    if (hyperOsMatch == null) {
        return if (KNOWN_MIUI_14_INCREMENTAL.matches(version)) {
            PlatformApplicability.NOT_APPLICABLE
        } else {
            PlatformApplicability.UNKNOWN
        }
    }

    val major = hyperOsMatch.groupValues[1].toIntOrNull() ?: return PlatformApplicability.UNKNOWN
    return when (major) {
        3, 4 -> PlatformApplicability.APPLICABLE
        1, 2 -> PlatformApplicability.NOT_APPLICABLE
        else -> PlatformApplicability.UNKNOWN
    }
}

private const val BUILD_UNKNOWN = "unknown"
private val HYPER_OS_INCREMENTAL = Regex("^OS(\\d+)\\.\\d+(?:\\.\\d+)*\\.[A-Z0-9]+$", RegexOption.IGNORE_CASE)
private val KNOWN_MIUI_14_INCREMENTAL = Regex("^V14\\.\\d+(?:\\.\\d+)*\\.[A-Z0-9]+$", RegexOption.IGNORE_CASE)
private val XIAOMI_DEVICE_NAMES = setOf("xiaomi", "redmi", "poco")
