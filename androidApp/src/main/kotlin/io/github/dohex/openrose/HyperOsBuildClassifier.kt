package io.github.dohex.openrose

import io.github.dohex.openrose.core.PlatformApplicability

/**
 * Classify the currently targeted HyperOS 3/4 builds from Android build identity. This adapter
 * helper deliberately does not inspect module, bridge, or target-package state.
 */
internal fun classifyHyperOsBuild(
    manufacturer: String,
    brand: String,
    incrementalVersion: String,
): PlatformApplicability {
    val normalizedManufacturer = manufacturer.trim().lowercase()
    val normalizedBrand = brand.trim().lowercase()
    val version = incrementalVersion.trim()
    if (version.isEmpty() || (normalizedManufacturer.isEmpty() && normalizedBrand.isEmpty())) {
        return PlatformApplicability.UNKNOWN
    }

    val xiaomiFamily = normalizedManufacturer in XIAOMI_DEVICE_NAMES || normalizedBrand in XIAOMI_DEVICE_NAMES
    if (!xiaomiFamily || !version.startsWith("OS", ignoreCase = true)) return PlatformApplicability.NOT_APPLICABLE

    val major = version.drop(2).takeWhile(Char::isDigit).toIntOrNull()
        ?: return PlatformApplicability.UNKNOWN
    return when (major) {
        3, 4 -> PlatformApplicability.APPLICABLE
        1, 2 -> PlatformApplicability.NOT_APPLICABLE
        else -> PlatformApplicability.UNKNOWN
    }
}

private val XIAOMI_DEVICE_NAMES = setOf("xiaomi", "redmi", "poco")
