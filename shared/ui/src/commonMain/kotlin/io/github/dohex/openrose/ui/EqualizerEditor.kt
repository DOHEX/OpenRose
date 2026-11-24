package io.github.dohex.openrose.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val CUSTOM_EQUALIZER_MODE = "custom"

internal sealed interface EqualizerChoice {
    data class Preset(val name: String) : EqualizerChoice

    data class Curve(val gains: List<Int>) : EqualizerChoice
}

internal class EqualizerEditor(
    private val scope: CoroutineScope,
    private val write: suspend (sessionId: Long, command: HeadsetCommand) -> OperationResult,
) {
    private var sessionId: Long? = null
    private var queued: EqualizerChoice? = null
    private var worker: Job? = null

    var pending: EqualizerChoice? by mutableStateOf(null)
        private set

    fun bind(sessionId: Long?) {
        if (sessionId == this.sessionId) return
        worker?.cancel()
        worker = null
        queued = null
        pending = null
        this.sessionId = sessionId
    }

    fun choose(choice: EqualizerChoice) {
        val session = sessionId ?: return
        pending = choice
        if (worker?.isActive == true) {
            queued = choice
            return
        }
        worker = scope.launch {
            var next: EqualizerChoice? = choice
            while (next != null) {
                when (next) {
                    is EqualizerChoice.Preset -> write(session, HeadsetCommand.EqualizerPreset(next.name))
                    is EqualizerChoice.Curve -> write(session, HeadsetCommand.CustomEqualizer(next.gains))
                }
                next = queued
                queued = null
            }
            pending = null
        }
    }

    val pendingMode: String?
        get() = when (val choice = pending) {
            is EqualizerChoice.Preset -> choice.name
            is EqualizerChoice.Curve -> CUSTOM_EQUALIZER_MODE
            null -> null
        }

    fun displayedCurve(curve: List<Int>?): List<Int>? = (pending as? EqualizerChoice.Curve)?.gains ?: curve
}
