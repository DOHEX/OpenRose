package io.github.dohex.openrose

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.OwnerRecord
import io.github.dohex.openrose.androidintegration.OwnerRecordCodec
import io.github.dohex.openrose.androidintegration.OwnerSelectionStore
import io.github.dohex.openrose.framework.FrameworkLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's control way and the module-release bookkeeping, durable in the credential-protected
 * preferences. Any change that involves module control is also written where the host reads it, and is
 * reported as saved only if both writes were acknowledged; otherwise the earlier choice is restored.
 */
internal class AndroidOwnerStore(
    private val preferences: () -> SharedPreferences,
    private val framework: FrameworkLink,
) : OwnerSelectionStore {
    private val settingsStore = AndroidSettingsStore(preferences)

    override fun load(): OwnerRecord = OwnerRecordCodec.decode(preferences().all)

    override fun canSave(record: OwnerRecord): Boolean = OwnerRecordCodec.canSave(load(), record, framework::canPublish)

    override fun save(record: OwnerRecord): Boolean = OwnerRecordCodec.save(load(), record, ::publishConfig, ::write)

    /**
     * Publishes the current settings to the host with the control way read from this store at the moment
     * of the write. Every host-config writer goes through here, so the last write is the newest truth.
     */
    override suspend fun publishSelection(): Boolean = withContext(Dispatchers.IO) { publishConfig() }

    private fun publishConfig(): Boolean = framework.publishConfig(settingsStore::loadHost)

    private fun write(record: OwnerRecord): Boolean = preferences().commitEntries(OwnerRecordCodec.encode(record))
}
