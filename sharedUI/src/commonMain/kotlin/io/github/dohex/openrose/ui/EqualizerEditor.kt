package io.github.dohex.openrose.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The equalizer mode reading that means the device plays its custom curve. */
internal const val CUSTOM_EQUALIZER_MODE = "custom"

/** One choice made on the equalizer page. */
internal sealed interface EqualizerChoice {
    data class Preset(val name: String) : EqualizerChoice

    /** Writing a curve also selects the custom mode; both protocol families send the mode before the gains. */
    data class Curve(val gains: List<Int>) : EqualizerChoice
}

/**
 * Sends the equalizer page's choices to one device session, one write at a time.
 *
 * While a write is in flight, newer choices replace each other and only the last is sent once it ends. Until that
 * queue drains the page shows the latest choice; afterwards it shows what the device confirmed, so a failed or
 * not-applied write falls back to the device's own state without a separate rollback path.
 *
 * Kept with the other app-level UI state so replacing the component style does not drop a choice still in flight.
 */
internal class EqualizerEditor(
    private val scope: CoroutineScope,
    private val write: suspend (sessionId: Long, setting: DeviceSetting, value: FieldValue) -> OperationResult,
) {
    private var sessionId: Long? = null
    private var queued: EqualizerChoice? = null
    private var worker: Job? = null

    /** The latest choice not yet settled by the device; null once every write has ended. */
    var pending: EqualizerChoice? by mutableStateOf(null)
        private set

    /** Choices belong to one session: a new or ended session drops them and any write still waiting to be sent. */
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
                    is EqualizerChoice.Preset -> write(session, DeviceSetting.EQ, FieldValue.Text(next.name))
                    is EqualizerChoice.Curve -> write(session, DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(next.gains))
                }
                next = queued
                queued = null
            }
            pending = null
        }
    }

    /** The mode to show: the pending choice's, else the device's confirmed [mode]. */
    fun displayedMode(mode: String?): String? = when (val choice = pending) {
        is EqualizerChoice.Preset -> choice.name
        is EqualizerChoice.Curve -> CUSTOM_EQUALIZER_MODE
        null -> mode
    }

    /** The curve to show: the pending curve, else the device's confirmed [curve]. */
    fun displayedCurve(curve: List<Int>?): List<Int>? = (pending as? EqualizerChoice.Curve)?.gains ?: curve
}
