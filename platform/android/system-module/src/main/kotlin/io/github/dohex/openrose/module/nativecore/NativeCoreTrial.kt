package io.github.dohex.openrose.module.nativecore

import android.util.Log

internal fun interface NativeCoreLibraries {
    fun load(name: String)
}

internal fun interface TrialLog {
    fun write(priority: Int, message: String, failure: Throwable?)
}

internal class NativeCoreTrial(
    private val libraries: NativeCoreLibraries,
    private val control: () -> AutoCloseable,
    private val log: TrialLog,
) {
    private val lock = Any()
    private var attempted = false
    private var closed = false
    private var running: AutoCloseable? = null

    @Volatile private var nativeLoaded = false

    val allowsHotReload: Boolean get() = !nativeLoaded

    fun apply(enabled: Boolean) {
        synchronized(lock) {
            when {
                closed -> Unit

                !enabled -> if (attempted) log.write(Log.INFO, "switch_off_ignored_until_scope_restart", null)

                !attempted -> {
                    attempted = true
                    start()
                }
            }
        }
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
            running = control()
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
