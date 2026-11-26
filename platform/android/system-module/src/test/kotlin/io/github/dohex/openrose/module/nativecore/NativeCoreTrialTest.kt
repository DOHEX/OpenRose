package io.github.dohex.openrose.module.nativecore

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeCoreTrialTest {
    private data class Line(val priority: Int, val message: String, val failure: Throwable?)

    private class Rig {
        val loaded = mutableListOf<String>()
        val loadFailures = mutableMapOf<String, Throwable>()
        var controlFailure: Throwable? = null
        var started = 0
        var stopped = 0
        val lines = mutableListOf<Line>()

        fun trial() = NativeCoreTrial(
            libraries = NativeCoreLibraries { name ->
                loadFailures[name]?.let { throw it }
                loaded += name
            },
            control = {
                controlFailure?.let { throw it }
                started++
                AutoCloseable { stopped++ }
            },
            log = TrialLog { priority, message, failure -> lines += Line(priority, message, failure) },
        )
    }

    @Test
    fun switchOffLoadsNothingStartsNothingAndAllowsHotReload() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = false)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
        assertTrue(trial.allowsHotReload)
    }

    @Test
    fun switchOnLoadsDispatchThenCoreAndStartsControlOnce() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true)
        trial.apply(enabled = true)

        assertEquals(listOf("jnidispatch", "openrose_ffi"), rig.loaded)
        assertEquals(1, rig.started)
    }

    @Test
    fun loadedNativeCoreRefusesHotReloadEvenAfterTheTrialStops() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true)
        assertFalse(trial.allowsHotReload)

        trial.close()
        assertEquals(1, rig.stopped)
        assertFalse(trial.allowsHotReload)
    }

    @Test
    fun turningTheSwitchOffAfterStartKeepsRunningAndKeepsRefusingHotReload() {
        val rig = Rig()
        val trial = rig.trial()
        trial.apply(enabled = true)

        trial.apply(enabled = false)

        assertEquals(0, rig.stopped)
        assertEquals(1, rig.started)
        assertFalse(trial.allowsHotReload)
    }

    @Test
    fun failingToLoadDispatchLogsTheExactErrorStartsNothingAndAllowsHotReload() {
        val rig = Rig()
        val denied = UnsatisfiedLinkError("dlopen failed: couldn't map segment 1: Permission denied")
        rig.loadFailures["jnidispatch"] = denied
        val trial = rig.trial()

        trial.apply(enabled = true)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
        assertTrue(trial.allowsHotReload)
        val error = rig.lines.single { it.priority == Log.ERROR }
        assertTrue("jnidispatch" in error.message)
        assertSame(denied, error.failure)
    }

    @Test
    fun failedLoadIsNotRetriedUntilTheProcessRestarts() {
        val rig = Rig()
        rig.loadFailures["jnidispatch"] = UnsatisfiedLinkError("not found")
        val trial = rig.trial()

        trial.apply(enabled = true)
        rig.loadFailures.clear()
        trial.apply(enabled = true)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
    }

    @Test
    fun loadedDispatchAloneStillRefusesHotReloadWhenTheCoreFailsToLoad() {
        val rig = Rig()
        val missing = UnsatisfiedLinkError("dlopen failed: library \"libopenrose_ffi.so\" not found")
        rig.loadFailures["openrose_ffi"] = missing
        val trial = rig.trial()

        trial.apply(enabled = true)

        assertEquals(listOf("jnidispatch"), rig.loaded)
        assertEquals(0, rig.started)
        assertFalse(trial.allowsHotReload)
        assertSame(missing, rig.lines.single { it.priority == Log.ERROR }.failure)
    }

    @Test
    fun controlStartFailureIsLoggedAndTheLoadedCoreStillRefusesHotReload() {
        val rig = Rig()
        val broken = IllegalStateException("UniFFI contract mismatch")
        rig.controlFailure = broken
        val trial = rig.trial()

        trial.apply(enabled = true)

        assertEquals(listOf("jnidispatch", "openrose_ffi"), rig.loaded)
        assertFalse(trial.allowsHotReload)
        assertSame(broken, rig.lines.single { it.priority == Log.ERROR }.failure)
        trial.close()
        assertEquals(0, rig.stopped)
    }

    @Test
    fun aRetiredHostNeverStartsTheTrialLate() {
        val rig = Rig()
        val trial = rig.trial()
        trial.close()

        trial.apply(enabled = true)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
        assertTrue(trial.allowsHotReload)
    }
}
