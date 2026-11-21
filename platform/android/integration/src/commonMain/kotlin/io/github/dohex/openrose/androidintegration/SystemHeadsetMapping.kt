package io.github.dohex.openrose.androidintegration

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

    /**
     * The mode a system entry's "off" selection writes. With [offAsWind] and a model that offers wind reduction
     * it is wind; otherwise the request is unchanged, so a model without wind keeps a working off.
     */
    fun requestedMode(
        requested: String,
        offAsWind: Boolean,
        supportedModes: Set<String>,
    ): String = if (offAsWind && requested == "normal" && "wind" in supportedModes) "wind" else requested

    fun miLinkAncState(fields: Map<DeviceField, FieldReading>): Int? = fields[DeviceField.NOISE_MODE].confirmedText?.let(::miLinkMode)

    /**
     * MiLink's `[case, left, right, caseCharging, leftCharging, rightCharging]`. -1 is its own "unavailable".
     * The list has no unknown slot for charging, so an ear whose battery is real but whose charging state is
     * not is a reading that cannot be represented: the whole list is withheld (null) rather than sending 0
     * as "not charging", as the headset Binder path does. The case has no charging reading at all, so a real
     * case battery is withheld too (-1). Null when no battery is left to show.
     */
    fun miLinkBattery(fields: Map<DeviceField, FieldReading>): List<Int>? {
        val left = fields[DeviceField.LEFT_BATTERY].batteryPercent
        val right = fields[DeviceField.RIGHT_BATTERY].batteryPercent
        if (left == null && right == null) return null
        val leftCharging = miLinkCharging(left, fields[DeviceField.LEFT_CHARGING]) ?: return null
        val rightCharging = miLinkCharging(right, fields[DeviceField.RIGHT_CHARGING]) ?: return null
        return listOf(-1, left ?: -1, right ?: -1, 0, leftCharging, rightCharging)
    }

    /** The slot for an ear: its real charging flag, a placeholder where the ear has no battery to describe, null if unknown. */
    private fun miLinkCharging(battery: Int?, charging: FieldReading?): Int? {
        if (battery == null) return 0
        return charging.confirmedToggle?.let { if (it) 1 else 0 }
    }

    /** MiLink's summary battery: the lower real ear, only when both ears are real. */
    fun miLinkSummaryBattery(fields: Map<DeviceField, FieldReading>): Int? {
        val left = fields[DeviceField.LEFT_BATTERY].batteryPercent ?: return null
        val right = fields[DeviceField.RIGHT_BATTERY].batteryPercent ?: return null
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
        val left = encodedEar(fields[DeviceField.LEFT_BATTERY], fields[DeviceField.LEFT_CHARGING]) ?: return BinderStatus.Unrepresentable
        val right = encodedEar(fields[DeviceField.RIGHT_BATTERY], fields[DeviceField.RIGHT_CHARGING]) ?: return BinderStatus.Unrepresentable
        if (left == UNKNOWN_BATTERY && right == UNKNOWN_BATTERY) return BinderStatus.Unrepresentable
        val case = fields[DeviceField.CASE_BATTERY]
        val caseOmitted = case is FieldReading.Known
        if (caseOmitted && case.batteryPercent == null) return BinderStatus.Unrepresentable
        val values = MutableList(16) { "" }
        values[0] = left.toString()
        values[1] = right.toString()
        values[2] = if (caseOmitted) "" else UNKNOWN_BATTERY.toString()
        // The status carries no distinct value for normal and basic ANC, so only transparency is sent.
        if (fields[DeviceField.NOISE_MODE].confirmedText?.trim()?.lowercase() == "transparency") values[7] = "0200"
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

    private fun encodedEar(battery: FieldReading?, charging: FieldReading?): Int? {
        if (battery !is FieldReading.Known) return UNKNOWN_BATTERY
        val percent = battery.batteryPercent ?: return null
        return when (charging.confirmedToggle) {
            true -> percent or CHARGING_FLAG
            false -> percent
            null -> null
        }
    }

    private const val UNKNOWN_BATTERY = 255
    private const val CHARGING_FLAG = 128
}

private val FieldReading?.batteryPercent: Int?
    get() = ((this as? FieldReading.Known)?.value as? FieldValue.Number)?.value?.takeIf { it in 0..100 }

private val FieldReading?.confirmedText: String?
    get() = ((this as? FieldReading.Known)?.value as? FieldValue.Text)?.value

private val FieldReading?.confirmedToggle: Boolean?
    get() = ((this as? FieldReading.Known)?.value as? FieldValue.Toggle)?.value
