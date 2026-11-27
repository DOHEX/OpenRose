package io.github.dohex.openrose.module.nativecore

import android.util.Log
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.ffi.RustControlResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.openrose_control.ConnectionBlockReason
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlError
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HeadsetCandidate
import uniffi.openrose_headset.FieldReading
import uniffi.openrose_headset.HeadsetField
import uniffi.openrose_headset.HeadsetId
import uniffi.openrose_headset.ModelProfile

internal fun interface NativeCoreLibraries {
    fun load(name: String)
}

internal fun interface TrialLog {
    fun write(priority: Int, message: String, failure: Throwable?)
}

internal interface TrialControl : AutoCloseable {
    val state: StateFlow<ControlSnapshot>

    fun updatePlatformState()

    suspend fun refreshBondedHeadsets()

    suspend fun connect(headsetId: HeadsetId): RustControlResult
}

internal class NativeCoreTrial(
    private val libraries: NativeCoreLibraries,
    private val control: () -> TrialControl,
    private val log: TrialLog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Any()
    private var attempted = false
    private var closed = false
    private var running: AutoCloseable? = null
    private var lastSwitch: Boolean? = null
    private var skippedForModuleControl = false

    @Volatile private var nativeLoaded = false

    val allowsHotReload: Boolean get() = !nativeLoaded

    fun apply(enabled: Boolean, controlWay: ControlWay) {
        var toStop: AutoCloseable? = null
        synchronized(lock) {
            if (closed) return
            observe(enabled)
            if (controlWay == ControlWay.MODULE) {
                val active = running
                if (active != null) {
                    running = null
                    toStop = active
                    log.write(Log.INFO, "trial_stopped reason=module_control_selected", null)
                } else if (enabled && !attempted && !skippedForModuleControl) {
                    skippedForModuleControl = true
                    log.write(Log.INFO, "trial_skipped reason=module_control_selected", null)
                }
            } else {
                skippedForModuleControl = false
                if (enabled && !attempted) {
                    attempted = true
                    start()
                }
            }
        }
        toStop?.close()
    }

    private fun observe(enabled: Boolean) {
        if (enabled == lastSwitch) return
        lastSwitch = enabled
        log.write(Log.INFO, "switch value=$enabled", null)
        if (!enabled && running != null) log.write(Log.INFO, "switch_off_ignored_until_scope_restart", null)
    }

    fun close() {
        val current = synchronized(lock) {
            closed = true
            running.also { running = null }
        }
        current?.close()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun start() {
        log.write(Log.INFO, "trial_start", null)
        for (name in LIBRARIES) {
            try {
                libraries.load(name)
            } catch (failure: LinkageError) {
                log.write(Log.ERROR, "native_library_load_failed library=$name", failure)
                return
            } catch (failure: SecurityException) {
                log.write(Log.ERROR, "native_library_load_failed library=$name", failure)
                return
            }
            nativeLoaded = true
            log.write(Log.INFO, "native_library_loaded library=$name", null)
        }
        try {
            running = TrialSession(control(), log, dispatcher).also { it.start() }
        } catch (failure: LinkageError) {
            log.write(Log.ERROR, "trial_control_start_failed", failure)
        } catch (failure: RuntimeException) {
            log.write(Log.ERROR, "trial_control_start_failed", failure)
        }
    }

    companion object {
        const val TAG = "OpenRoseNativeTrial"
        const val DISPATCH_LIBRARY = "jnidispatch"
        const val CORE_LIBRARY = "openrose_ffi"
        private val LIBRARIES = listOf(DISPATCH_LIBRARY, CORE_LIBRARY)
    }
}

private class TrialSession(
    private val control: TrialControl,
    private val log: TrialLog,
    dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var lastConnection: String? = null
    private var lastError: ControlError? = null
    private var lastBlock: ConnectionBlockReason? = null
    private var lastReadings = emptyMap<HeadsetField, FieldReading>()

    fun start() {
        scope.launch { control.state.collect(::report) }
        scope.launch { connect() }
    }

    override fun close() {
        scope.cancel()
        control.close()
    }

    private suspend fun connect() {
        val target = awaitBondedHeadset() ?: return
        log.write(Log.INFO, "connect_start headset=${target.id} name=${target.name} profile=${target.profile}", null)
        when (val result = control.connect(target.id)) {
            RustControlResult.Success -> log.write(Log.INFO, "connect_result headset=${target.id} result=success", null)

            is RustControlResult.Failure -> log.write(
                Log.ERROR,
                "connect_result headset=${target.id} result=failure code=${result.error.code} message=${result.error.message}",
                null,
            )
        }
    }

    private suspend fun awaitBondedHeadset(): HeadsetCandidate? {
        repeat(BONDED_ATTEMPTS) { attempt ->
            control.updatePlatformState()
            control.refreshBondedHeadsets()
            val snapshot = control.state.value
            val target = snapshot.bondedHeadsets.firstOrNull { it.profile == ModelProfile.I5_ZT }
            if (target != null) {
                log.write(Log.INFO, "bonded_headsets ids=${snapshot.bondedHeadsets.joinToString(",") { it.id }}", null)
                return target
            }
            val bonded = snapshot.bondedHeadsets.joinToString(",") { "${it.id}:${it.profile}" }
            log.write(
                Log.WARN,
                "bonded_i5_missing attempt=${attempt + 1}/$BONDED_ATTEMPTS transport=${snapshot.platform.transport} bonded=[$bonded]",
                null,
            )
            delay(BONDED_RETRY_MILLIS)
        }
        log.write(Log.ERROR, "trial_gave_up reason=no_bonded_i5", null)
        return null
    }

    private fun report(snapshot: ControlSnapshot) {
        val connection = describe(snapshot.connection)
        if (connection != lastConnection) {
            lastConnection = connection
            log.write(Log.INFO, "state revision=${snapshot.revision} connection=$connection", null)
        }
        if (snapshot.lastError != lastError) {
            lastError = snapshot.lastError
            snapshot.lastError?.let { log.write(Log.WARN, "control_error code=${it.code} message=${it.message}", null) }
        }
        if (snapshot.connectionBlockReason != lastBlock) {
            lastBlock = snapshot.connectionBlockReason
            snapshot.connectionBlockReason?.let { log.write(Log.WARN, "connection_blocked reason=$it", null) }
        }
        val fields = snapshot.headset?.readings?.fields.orEmpty()
        fields.entries.sortedBy { it.key.name }
            .filter { lastReadings[it.key] != it.value }
            .forEach { (field, reading) -> log.write(Log.INFO, "reading field=$field value=$reading", null) }
        lastReadings = fields
    }

    private fun describe(state: ConnectionState): String = when (state) {
        ConnectionState.Disconnected -> "disconnected"
        is ConnectionState.Connecting -> "connecting headset=${state.headsetId} session=${state.sessionId} phase=${state.phase}"
        is ConnectionState.Connected -> "connected headset=${state.headsetId} session=${state.sessionId} profile=${state.profile}"
    }

    private companion object {
        const val BONDED_ATTEMPTS = 12
        const val BONDED_RETRY_MILLIS = 5_000L
    }
}
