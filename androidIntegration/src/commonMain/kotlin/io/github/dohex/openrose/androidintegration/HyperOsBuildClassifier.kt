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
    val identities = listOf(manufacturer, brand).map { it.trim().lowercase() }
    if (identities.all { it.isEmpty() || it == BUILD_UNKNOWN }) return PlatformApplicability.UNKNOWN
    if (identities.none { it in XIAOMI_DEVICE_NAMES }) return PlatformApplicability.NOT_APPLICABLE

    val version = incrementalVersion.trim()
    if (KNOWN_MIUI_14_INCREMENTAL.matches(version)) return PlatformApplicability.NOT_APPLICABLE
    val major = HYPER_OS_INCREMENTAL.matchEntire(version)?.groupValues?.get(1)?.toIntOrNull()
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
