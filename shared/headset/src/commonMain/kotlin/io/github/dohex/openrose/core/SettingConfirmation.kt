package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ProtocolQuery

/** Tracks each requested field from its own query until the device supplies a fresh reading. */
internal class SettingConfirmation(private val expected: Map<DeviceField, FieldValue>) {
    private val fieldBaselines = mutableMapOf<DeviceField, Long>()
    private var actualValues = emptyMap<DeviceField, FieldValue>()
    val applied: Boolean get() = expected.all { (field, value) -> actualValues[field] == value }
    val confirmedFields: Set<DeviceField> get() = actualValues.filter { (field, value) -> value == expected[field] }.keys

    fun beginQuery(query: ProtocolQuery, baseline: ReadProgress) {
        for (field in query.expectedFields) {
            if (field in expected) fieldBaselines[field] = baseline.fieldRevisions[field] ?: 0L
        }
    }

    fun observe(query: ProtocolQuery, baseline: ReadProgress, progress: ReadProgress, fields: Map<DeviceField, FieldReading>): Boolean {
        val received = query.responseKeys.all { (progress.keyRevisions[it] ?: 0) > (baseline.keyRevisions[it] ?: 0) }
        actualValues = fieldBaselines.mapNotNull { (field, revision) ->
            if (!received && field in query.expectedFields) return@mapNotNull null
            if ((progress.fieldRevisions[field] ?: 0) <= revision) return@mapNotNull null
            confirmationValue(fields[field], expected.getValue(field))?.let { field to it }
        }.toMap()
        return received && actualValues.size == fieldBaselines.size
    }

    /** A touch assignment confirms only its target; the device still retains its full bindings. */
    private fun confirmationValue(reading: FieldReading?, expected: FieldValue): FieldValue? {
        val actual = (reading as? FieldReading.Known)?.value ?: return null
        return when {
            expected is FieldValue.TouchAssignment -> {
                val binding = (actual as? FieldValue.TouchKeys)?.bindings?.firstOrNull {
                    it.side == expected.side && it.gesture == expected.gesture
                }
                binding?.action?.let { expected.copy(action = it) }
            }

            actual is FieldValue.NoiseCycle && actual.flags.values.any { it == null } -> null

            else -> actual
        }
    }
}
