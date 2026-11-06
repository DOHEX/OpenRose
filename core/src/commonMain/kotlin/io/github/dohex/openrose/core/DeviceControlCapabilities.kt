package io.github.dohex.openrose.core

import kotlinx.collections.immutable.PersistentSet

data class TouchSlot(val side: String, val gesture: String)
data class TouchControlCapabilities(val slots: PersistentSet<TouchSlot>, val actions: PersistentSet<String>)
data class AudioControlCapabilities(val modes: PersistentSet<String>, val multipointCoupled: Boolean)
