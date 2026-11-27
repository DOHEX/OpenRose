package io.github.dohex.openrose.module.nativecore

import android.util.Log
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.ffi.RustControlResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HeadsetCandidate
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PermissionState
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.ScanState
import uniffi.openrose_control.TransportAvailability
import uniffi.openrose_headset.HeadsetId
import uniffi.openrose_headset.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeCoreTrialTest {
    private data class Line(val priority: Int, val message: String, val failure: Throwable?)

    private class Rig(private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()) {
        val loaded = mutableListOf<String>()
        val loadFailures = mutableMapOf<String, Throwable>()
        var controlFailure: Throwable? = null
        var started = 0
        var stopped = 0
        var refreshCount = 0
        val connectCalls = mutableListOf<HeadsetId>()
        var connectResult: RustControlResult = RustControlResult.Success
        var onRefresh: (Int) -> List<HeadsetCandidate> = { emptyList() }
        val state = MutableStateFlow(snapshot(emptyList()))
        val lines = mutableListOf<Line>()

        fun trial() = NativeCoreTrial(
            libraries = NativeCoreLibraries { name ->
                loadFailures[name]?.let { throw it }
                loaded += name
            },
            control = {
                controlFailure?.let { throw it }
                started++
                object : TrialControl {
                    override val state: StateFlow<ControlSnapshot> = this@Rig.state

                    override fun updatePlatformState() = Unit

                    override suspend fun refreshBondedHeadsets() {
                        refreshCount++
                        this@Rig.state.value = snapshot(onRefresh(refreshCount), revision = refreshCount.toULong())
                    }

                    override suspend fun connect(headsetId: HeadsetId): RustControlResult {
                        connectCalls += headsetId
                        return connectResult
                    }

                    override fun close() {
                        stopped++
                    }
                }
            },
            log = TrialLog { priority, message, failure -> lines += Line(priority, message, failure) },
            dispatcher = dispatcher,
        )
    }

    @Test
    fun switchOffLoadsNothingAndStartsNothing() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = false, controlWay = ControlWay.APP)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
    }

    @Test
    fun switchOnLoadsDispatchThenCoreAndStartsControlOnce() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)
        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(listOf("jnidispatch", "openrose_ffi"), rig.loaded)
        assertEquals(1, rig.started)
    }

    @Test
    fun closeStopsARunningTrial() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)
        trial.close()
        assertEquals(1, rig.stopped)
    }

    @Test
    fun turningTheSwitchOffAfterStartKeepsRunning() {
        val rig = Rig()
        val trial = rig.trial()
        trial.apply(enabled = true, controlWay = ControlWay.APP)

        trial.apply(enabled = false, controlWay = ControlWay.APP)

        assertEquals(0, rig.stopped)
        assertEquals(1, rig.started)
    }

    @Test
    fun failingToLoadDispatchLogsTheExactErrorAndStartsNothing() {
        val rig = Rig()
        val denied = UnsatisfiedLinkError("dlopen failed: couldn't map segment 1: Permission denied")
        rig.loadFailures["jnidispatch"] = denied
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
        val error = rig.lines.single { it.priority == Log.ERROR }
        assertTrue("jnidispatch" in error.message)
        assertSame(denied, error.failure)
    }

    @Test
    fun failedLoadIsNotRetriedUntilTheProcessRestarts() {
        val rig = Rig()
        rig.loadFailures["jnidispatch"] = UnsatisfiedLinkError("not found")
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)
        rig.loadFailures.clear()
        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
    }

    @Test
    fun failingToLoadCoreAfterDispatchLogsTheExactErrorAndStartsNothing() {
        val rig = Rig()
        val missing = UnsatisfiedLinkError("dlopen failed: library \"libopenrose_ffi.so\" not found")
        rig.loadFailures["openrose_ffi"] = missing
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(listOf("jnidispatch"), rig.loaded)
        assertEquals(0, rig.started)
        assertSame(missing, rig.lines.single { it.priority == Log.ERROR }.failure)
    }

    @Test
    fun controlStartFailureIsLoggedAndCloseDoesNotStopUnstartedControl() {
        val rig = Rig()
        val broken = IllegalStateException("UniFFI contract mismatch")
        rig.controlFailure = broken
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(listOf("jnidispatch", "openrose_ffi"), rig.loaded)
        assertSame(broken, rig.lines.single { it.priority == Log.ERROR }.failure)
        trial.close()
        assertEquals(0, rig.stopped)
    }

    @Test
    fun aRetiredHostNeverStartsTheTrialLate() {
        val rig = Rig()
        val trial = rig.trial()
        trial.close()

        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
    }

    @Test
    fun reportsTheSwitchOnlyWhenItChangesAndSaysTurningItOffNeedsAScopeRestart() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = false, controlWay = ControlWay.APP)
        trial.apply(enabled = false, controlWay = ControlWay.APP)
        trial.apply(enabled = true, controlWay = ControlWay.APP)
        trial.apply(enabled = false, controlWay = ControlWay.APP)
        trial.apply(enabled = false, controlWay = ControlWay.APP)

        assertEquals(
            listOf("switch value=false", "switch value=true", "switch value=false", "switch_off_ignored_until_scope_restart"),
            rig.lines.map { it.message }.filter { it.startsWith("switch") },
        )
    }

    @Test
    fun moduleControlSelectedSkipsLoadingAndStartingUntilAppControlIsChosen() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.MODULE)
        trial.apply(enabled = true, controlWay = ControlWay.MODULE)

        assertEquals(emptyList(), rig.loaded)
        assertEquals(0, rig.started)
        assertEquals(
            listOf("trial_skipped reason=module_control_selected"),
            rig.lines.map { it.message }.filter { it.startsWith("trial_skipped") },
        )

        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(listOf("jnidispatch", "openrose_ffi"), rig.loaded)
        assertEquals(1, rig.started)
    }

    @Test
    fun switchingToModuleControlStopsARunningTrialAndDoesNotRestartOnAppControl() {
        val rig = Rig()
        val trial = rig.trial()

        trial.apply(enabled = true, controlWay = ControlWay.APP)
        assertEquals(1, rig.started)
        assertEquals(0, rig.stopped)

        trial.apply(enabled = true, controlWay = ControlWay.MODULE)
        trial.apply(enabled = true, controlWay = ControlWay.APP)

        assertEquals(1, rig.started)
        assertEquals(1, rig.stopped)
        assertTrue(rig.lines.any { it.message == "trial_stopped reason=module_control_selected" })
    }

    @Test
    fun connectsOnlyToAnI5HeadsetWhenOtherBondedModelsAppearFirst() = runTest {
        val rig = Rig(StandardTestDispatcher(testScheduler))
        val i7 = candidate("11:22:33:44:55:66", "EarFeel i7", ModelProfile.I7_RS_V2)
        val i5 = candidate("AA:BB:CC:DD:EE:FF", "EarFree i5", ModelProfile.I5_ZT)
        rig.onRefresh = { listOf(i7, i5) }
        val trial = rig.trial()

        try {
            trial.apply(enabled = true, controlWay = ControlWay.APP)
            runCurrent()

            assertEquals(listOf(i5.id), rig.connectCalls)
            assertTrue(rig.lines.any { it.message == "connect_start headset=${i5.id} name=EarFree i5 profile=I5_ZT" })
            assertTrue(rig.lines.any { it.message == "connect_result headset=${i5.id} result=success" })
        } finally {
            trial.close()
        }
    }

    @Test
    fun skipsNonI5AndUnidentifiedHeadsetsAndGivesUpWhenNoI5Appears() = runTest {
        val rig = Rig(StandardTestDispatcher(testScheduler))
        val i7 = candidate("11:22:33:44:55:66", "EarFeel i7", ModelProfile.I7_RS_V2)
        val unknown = candidate("22:33:44:55:66:77", "Other", null)
        rig.onRefresh = { listOf(i7, unknown) }
        val trial = rig.trial()

        try {
            trial.apply(enabled = true, controlWay = ControlWay.APP)
            advanceTimeBy(65_000L)
            runCurrent()

            assertEquals(emptyList(), rig.connectCalls)
            assertEquals(12, rig.refreshCount)
            assertTrue(rig.lines.any { it.priority == Log.ERROR && it.message == "trial_gave_up reason=no_bonded_i5" })
        } finally {
            trial.close()
        }
    }

    @Test
    fun connectsOnceAnI5HeadsetAppearsOnALaterRefresh() = runTest {
        val rig = Rig(StandardTestDispatcher(testScheduler))
        val i7 = candidate("11:22:33:44:55:66", "EarFeel i7", ModelProfile.I7_RS_V2)
        val i5 = candidate("AA:BB:CC:DD:EE:FF", "EarFree i5", ModelProfile.I5_ZT)
        rig.onRefresh = { attempt -> if (attempt == 1) listOf(i7) else listOf(i7, i5) }
        val trial = rig.trial()

        try {
            trial.apply(enabled = true, controlWay = ControlWay.APP)
            runCurrent()
            assertEquals(emptyList(), rig.connectCalls)

            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(listOf(i5.id), rig.connectCalls)
        } finally {
            trial.close()
        }
    }

    @Test
    fun switchingToModuleControlCancelsPendingI5RetryAndClosesControl() = runTest {
        val rig = Rig(StandardTestDispatcher(testScheduler))
        val i5 = candidate("AA:BB:CC:DD:EE:FF", "EarFree i5", ModelProfile.I5_ZT)
        rig.onRefresh = { attempt -> if (attempt == 1) emptyList() else listOf(i5) }
        val trial = rig.trial()

        try {
            trial.apply(enabled = true, controlWay = ControlWay.APP)
            runCurrent()
            assertEquals(1, rig.refreshCount)
            assertEquals(emptyList(), rig.connectCalls)

            trial.apply(enabled = true, controlWay = ControlWay.MODULE)
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(1, rig.stopped)
            assertEquals(1, rig.refreshCount)
            assertEquals(emptyList(), rig.connectCalls)
        } finally {
            trial.close()
        }
    }

    private companion object {
        fun candidate(id: String, name: String?, profile: ModelProfile?) = HeadsetCandidate(
            id = id,
            name = name,
            rssi = null,
            profile = profile,
            manufacturerDataHex = "",
            classicAddress = id,
        )

        fun snapshot(bonded: List<HeadsetCandidate>, revision: ULong = 0uL) = ControlSnapshot(
            revision = revision,
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                userUnlocked = true,
                scanPermission = PermissionState.GRANTED,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            ),
            settings = ControlSettings(forceBle = false),
            connection = ConnectionState.Disconnected,
            bondedHeadsets = bonded,
            candidates = emptyList(),
            scan = ScanState.Idle,
            headset = null,
            lastError = null,
            settingOperations = emptyList(),
            audioHeadsetId = null,
            connectionTarget = null,
            selectedHeadsetId = null,
            connectionRecovery = null,
            connectionBlockReason = null,
            reconnectGuidance = null,
        )
    }
}
