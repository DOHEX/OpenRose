package io.github.dohex.openrose

import android.content.SharedPreferences
import io.github.dohex.openrose.bridge.FrameworkLink
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.OwnerGeneration
import io.github.dohex.openrose.core.OwnerRecord
import io.github.dohex.openrose.core.OwnerSelectionStore
import io.github.dohex.openrose.core.PendingModuleRelease
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec

/**
 * The user's control way and the module-release bookkeeping, durable in the credential-protected
 * preferences. Any change that involves module control is also written where the host reads it, and is
 * reported as saved only if both writes were acknowledged; otherwise the earlier choice is restored.
 */
internal class AndroidOwnerStore(
    private val preferences: () -> SharedPreferences,
    private val framework: FrameworkLink,
    private val currentSettings: () -> UserSettings,
) : OwnerSelectionStore {
    override fun load(): OwnerRecord {
        val values = preferences().all
        val selected = UserSettingsCodec.decode(mapOf("control_way" to (values[KEY_WAY] as? String).orEmpty())).controlWay
        val pending = values[KEY_PENDING] as? String
        val release = if (pending == null) null else PendingModuleRelease(
            requestId = pending.takeIf { it.isNotEmpty() },
            generation = (values[KEY_INCARNATION] as? String)?.takeIf { it.isNotEmpty() }?.let { incarnation ->
                (values[KEY_SEQUENCE] as? String)?.toLongOrNull()?.let { OwnerGeneration(incarnation, it) }
            },
        )
        return OwnerRecord(selected, release)
    }

    override fun canSave(record: OwnerRecord): Boolean {
        val previous = load()
        return !involvesModule(previous, record) || previous.selected == record.selected || framework.canPublish()
    }

    private fun involvesModule(previous: OwnerRecord, record: OwnerRecord) =
        previous.selected != ControlWay.ORDINARY || record.selected != ControlWay.ORDINARY

    override fun save(record: OwnerRecord): Boolean {
        val previous = load()
        val involvesModule = involvesModule(previous, record)
        val selectionChanged = previous.selected != record.selected
        if (!write(record)) return false
        if (selectionChanged && involvesModule) {
            val published = framework.publishConfig(currentSettings().copy(controlWay = record.selected))
            if (!published) {
                write(previous)
                return false
            }
        }
        return true
    }

    private fun write(record: OwnerRecord): Boolean {
        val editor = preferences().edit()
        UserSettingsCodec.encode(UserSettings(controlWay = record.selected))["control_way"]?.let { editor.putString(KEY_WAY, it) }
        val release = record.moduleRelease
        if (release == null) {
            editor.remove(KEY_PENDING).remove(KEY_INCARNATION).remove(KEY_SEQUENCE)
        } else {
            editor.putString(KEY_PENDING, release.requestId.orEmpty())
            val generation = release.generation
            if (generation == null) {
                editor.remove(KEY_INCARNATION).remove(KEY_SEQUENCE)
            } else {
                editor.putString(KEY_INCARNATION, generation.incarnation)
                editor.putString(KEY_SEQUENCE, generation.sequence.toString())
            }
        }
        // commit(): the choice must be on disk before ownership moves, not eventually.
        return editor.commit()
    }

    private companion object {
        /** The settings codec's own key, so the saved way and the stored settings can never disagree. */
        const val KEY_WAY = "control_way"
        const val KEY_PENDING = "module_release_request"
        const val KEY_INCARNATION = "module_release_incarnation"
        const val KEY_SEQUENCE = "module_release_sequence"
    }
}
