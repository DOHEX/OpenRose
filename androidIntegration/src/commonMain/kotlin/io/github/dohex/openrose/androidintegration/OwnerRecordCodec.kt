package io.github.dohex.openrose.androidintegration

/**
 * The durable form of an [OwnerRecord] as string preference entries, and the save decision around it.
 * Platform stores only move the values and perform the writes.
 */
object OwnerRecordCodec {
    private const val KEY_WAY = IntegrationSettingsCodec.CONTROL_WAY_KEY
    private const val KEY_PENDING = "module_release_request"
    private const val KEY_INCARNATION = "module_release_incarnation"
    private const val KEY_SEQUENCE = "module_release_sequence"

    /** Missing or mistyped entries read as the ordinary way with no pending release. */
    fun decode(values: Map<String, Any?>): OwnerRecord {
        val selected = IntegrationSettingsCodec.decodeControlWay(values[KEY_WAY] as? String)
        val pending = values[KEY_PENDING] as? String
        val release = if (pending == null) {
            null
        } else {
            PendingModuleRelease(
                requestId = pending.takeIf { it.isNotEmpty() },
                generation = (values[KEY_INCARNATION] as? String)?.takeIf { it.isNotEmpty() }?.let { incarnation ->
                    (values[KEY_SEQUENCE] as? String)?.toLongOrNull()?.let { OwnerGeneration(incarnation, it) }
                },
            )
        }
        return OwnerRecord(selected, release)
    }

    /** Every key the record owns; a null value means the key must be removed. */
    fun encode(record: OwnerRecord): Map<String, String?> {
        val release = record.moduleRelease
        val generation = release?.generation
        return mapOf(
            KEY_WAY to IntegrationSettingsCodec.encodeControlWay(record.selected),
            KEY_PENDING to release?.let { it.requestId.orEmpty() },
            KEY_INCARNATION to generation?.incarnation,
            KEY_SEQUENCE to generation?.sequence?.toString(),
        )
    }

    /** Only a change that involves module control has to be visible to the module host. */
    private fun needsPublish(previous: OwnerRecord, record: OwnerRecord): Boolean {
        val moduleInvolved = previous.selected != ControlWay.ORDINARY || record.selected != ControlWay.ORDINARY
        return moduleInvolved && previous.selected != record.selected
    }

    /** [canPublish] is asked only when the host would have to be told. */
    fun canSave(previous: OwnerRecord, record: OwnerRecord, canPublish: () -> Boolean): Boolean {
        val mustPublish = needsPublish(previous, record)
        return !mustPublish || canPublish()
    }

    /**
     * Writes [record], then publishes it when the change involves module control. A failed publish restores
     * [previous] and publishes once more, because a concurrent publication may have carried the rejected choice.
     */
    fun save(previous: OwnerRecord, record: OwnerRecord, publish: () -> Boolean, write: (OwnerRecord) -> Boolean): Boolean {
        if (!write(record)) return false
        val published = !needsPublish(previous, record) || publish()
        if (!published) {
            write(previous)
            publish()
        }
        return published
    }
}
