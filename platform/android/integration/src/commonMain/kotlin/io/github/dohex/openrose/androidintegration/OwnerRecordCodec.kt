package io.github.dohex.openrose.androidintegration

object OwnerRecordCodec {
    private const val KEY_WAY = IntegrationSettingsCodec.CONTROL_WAY_KEY
    private const val KEY_PENDING = "module_release_request"
    private const val KEY_INCARNATION = "module_release_incarnation"
    private const val KEY_SEQUENCE = "module_release_sequence"

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

    private fun needsPublish(previous: OwnerRecord, record: OwnerRecord): Boolean = previous.selected != record.selected

    fun canSave(previous: OwnerRecord, record: OwnerRecord, canPublish: () -> Boolean): Boolean {
        val mustPublish = needsPublish(previous, record)
        return !mustPublish || canPublish()
    }

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
