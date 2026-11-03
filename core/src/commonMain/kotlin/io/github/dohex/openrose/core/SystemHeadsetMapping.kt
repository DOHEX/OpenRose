package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

/**
 * What the system's own headset UIs (the fusion device center and the system headset Binder) may be told
 * about a ROSE device. Pure rules over the owner's readings: a missing, unknown or not-provided reading
 * never becomes a real number, a charging state or a wear state, and nothing here widens a capability.
 */
object SystemHeadsetMapping {
    /** The fusion device center's code for [mode], or null when it has no such selection. Wind reduction shows as off. */
    fun miLinkMode(mode: String): Int? = when (mode.trim().lowercase()) {
        "normal", "wind" -> 0
        "anc" -> 1
        "transparency" -> 2
        else -> null
    }

    /** The ROSE noise mode for a system entry's mode code (identical for MiLink and the headset Binder). */
    fun noiseModeFromSystem(code: Int): String? = when (code) {
        0 -> "normal"
        1 -> "anc"
        2 -> "transparency"
        else -> null
    }

    fun miLinkAncState(fields: Map<DeviceField, FieldReading>): Int? = text(fields, DeviceField.NOISE_MODE)?.let(::miLinkMode)

    /**
     * MiLink's `[case, left, right, caseCharging, leftCharging, rightCharging]`. -1 is its own "unavailable".
     * The list has no unknown slot for charging, so an ear whose battery is real but whose charging state is
     * not is a reading that cannot be represented: the whole list is withheld (null) rather than sending 0
     * as "not charging", as the headset Binder path does. The case has no charging reading at all, so a real
     * case battery is withheld too (-1). Null when no battery is left to show.
     */
    fun miLinkBattery(fields: Map<DeviceField, FieldReading>): List<Int>? {
        val left = battery(fields, DeviceField.LEFT_BATTERY)
        val right = battery(fields, DeviceField.RIGHT_BATTERY)
        if (left == null && right == null) return null
        val leftCharging = miLinkCharging(fields, left, DeviceField.LEFT_CHARGING) ?: return null
        val rightCharging = miLinkCharging(fields, right, DeviceField.RIGHT_CHARGING) ?: return null
        return listOf(-1, left ?: -1, right ?: -1, 0, leftCharging, rightCharging)
    }

    /** The slot for an ear: its real charging flag, a placeholder where the ear has no battery to describe, null if unknown. */
    private fun miLinkCharging(fields: Map<DeviceField, FieldReading>, battery: Int?, field: DeviceField): Int? {
        if (battery == null) return 0
        return toggle(fields, field)?.let { if (it) 1 else 0 }
    }

    /** MiLink's summary battery: the lower real ear, only when both ears are real. */
    fun miLinkSummaryBattery(fields: Map<DeviceField, FieldReading>): Int? {
        val left = battery(fields, DeviceField.LEFT_BATTERY) ?: return null
        val right = battery(fields, DeviceField.RIGHT_BATTERY) ?: return null
        return minOf(left, right)
    }

    sealed interface BinderStatus {
        /** [caseOmitted]: a real case battery was withheld because its charging bit cannot be represented. */
        data class Payload(val text: String, val caseOmitted: Boolean) : BinderStatus
        data object Unrepresentable : BinderStatus
    }

    /**
     * The headset Binder's comma-separated status. Positions: 0 left, 1 right, 2 case, 7 transparency; every other
     * position, in particular 8 (wear state), stays empty because no real reading backs it.
     */
    fun binderStatus(fields: Map<DeviceField, FieldReading>): BinderStatus {
        val left = encodedEar(fields, DeviceField.LEFT_BATTERY, DeviceField.LEFT_CHARGING) ?: return BinderStatus.Unrepresentable
        val right = encodedEar(fields, DeviceField.RIGHT_BATTERY, DeviceField.RIGHT_CHARGING) ?: return BinderStatus.Unrepresentable
        if (left == UNKNOWN_BATTERY && right == UNKNOWN_BATTERY) return BinderStatus.Unrepresentable
        var caseOmitted = false
        val case = when (val reading = fields[DeviceField.CASE_BATTERY]) {
            null, FieldReading.Loading, is FieldReading.Unknown, is FieldReading.NotProvided -> UNKNOWN_BATTERY.toString()
            is FieldReading.Known -> {
                val value = (reading.value as? FieldValue.Number)?.value ?: return BinderStatus.Unrepresentable
                if (value !in 0..100) return BinderStatus.Unrepresentable
                caseOmitted = true
                ""
            }
        }
        val values = MutableList(16) { "" }
        values[0] = left.toString()
        values[1] = right.toString()
        values[2] = case
        // The status carries no distinct value for normal and basic ANC, so only transparency is sent.
        if (text(fields, DeviceField.NOISE_MODE)?.trim()?.lowercase() == "transparency") values[7] = "0200"
        return BinderStatus.Payload(values.joinToString(","), caseOmitted)
    }

    /** How a system entry reports the host's answer to a noise command; a component status plus bounded detail. */
    data class CommandReport(val status: String, val detail: String)

    /**
     * Maps the host's wire status for a noise command to what the entry reports. Only [OK] is a success, and
     * it is the earphones' confirmation; every refusal stays distinguishable instead of collapsing into one failure.
     */
    fun commandReport(mode: String, wireStatus: String?, message: String?): CommandReport = when (wireStatus) {
        "OK" -> CommandReport("CONFIRMED", "ROSE device confirmed $mode")
        "UNAUTHORIZED" -> CommandReport("REJECTED", message ?: "the control host did not authorise this caller")
        "UNSUPPORTED" -> CommandReport("UNSUPPORTED", message ?: "$mode is not supported by this model")
        "TIMEOUT" -> CommandReport("ACTION_FAILED", "the earphones did not confirm $mode in time")
        "DISCONNECTED" -> CommandReport("ACTION_FAILED", "the earphone link ended before $mode was confirmed")
        "STALE_HOST", "STALE_SESSION", "NO_SESSION" -> CommandReport("ACTION_FAILED", "the owner or session changed; $mode was dropped")
        else -> CommandReport("ACTION_FAILED", message ?: "control rejected: ${wireStatus ?: "no answer"}")
    }

    private fun encodedEar(fields: Map<DeviceField, FieldReading>, field: DeviceField, charging: DeviceField): Int? =
        when (val reading = fields[field]) {
            null, FieldReading.Loading, is FieldReading.Unknown, is FieldReading.NotProvided -> UNKNOWN_BATTERY
            is FieldReading.Known -> {
                val level = (reading.value as? FieldValue.Number)?.value?.takeIf { it in 0..100 }
                val isCharging = toggle(fields, charging)
                if (level == null || isCharging == null) null else level or if (isCharging) CHARGING_FLAG else 0
            }
        }

    private fun battery(fields: Map<DeviceField, FieldReading>, field: DeviceField): Int? = number(fields, field)?.takeIf { it in 0..100 }

    private fun number(fields: Map<DeviceField, FieldReading>, field: DeviceField): Int? =
        ((fields[field] as? FieldReading.Known)?.value as? FieldValue.Number)?.value

    private fun text(fields: Map<DeviceField, FieldReading>, field: DeviceField): String? =
        ((fields[field] as? FieldReading.Known)?.value as? FieldValue.Text)?.value

    private fun toggle(fields: Map<DeviceField, FieldReading>, field: DeviceField): Boolean? =
        ((fields[field] as? FieldReading.Known)?.value as? FieldValue.Toggle)?.value

    private const val UNKNOWN_BATTERY = 255
    private const val CHARGING_FLAG = 128
}
