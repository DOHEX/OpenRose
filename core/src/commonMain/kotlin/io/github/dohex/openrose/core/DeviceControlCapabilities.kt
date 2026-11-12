package io.github.dohex.openrose.core

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf

data class TouchSlot(val side: String, val gesture: String)
data class TouchControlCapabilities(
    val slots: PersistentSet<TouchSlot>,
    val actions: PersistentSet<String>,
    val slotLabels: PersistentMap<TouchSlot, String> = persistentMapOf(),
)
data class AudioControlCapabilities(val modes: PersistentSet<String>, val multipointCoupled: Boolean)
