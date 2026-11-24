package io.github.dohex.openrose

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.OwnerRecord
import io.github.dohex.openrose.androidintegration.OwnerRecordCodec
import io.github.dohex.openrose.androidintegration.OwnerSelectionStore
import io.github.dohex.openrose.framework.FrameworkLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidOwnerStore(
    private val preferences: () -> SharedPreferences,
    private val framework: FrameworkLink,
) : OwnerSelectionStore {
    private val settingsStore = AndroidSettingsStore(preferences)

    override fun load(): OwnerRecord = OwnerRecordCodec.decode(preferences().all)

    override fun canSave(record: OwnerRecord): Boolean = OwnerRecordCodec.canSave(load(), record, framework::canPublish)

    override fun save(record: OwnerRecord): Boolean = OwnerRecordCodec.save(load(), record, ::publishConfig, ::write)

    override suspend fun publishSelection(): Boolean = withContext(Dispatchers.IO) { publishConfig() }

    private fun publishConfig(): Boolean = framework.publishConfig(settingsStore::loadHost)

    private fun write(record: OwnerRecord): Boolean = preferences().commitEntries(OwnerRecordCodec.encode(record))
}
