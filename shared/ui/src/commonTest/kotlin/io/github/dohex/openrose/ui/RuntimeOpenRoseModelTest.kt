package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ui.model.OpenRoseSettingsStore
import io.github.dohex.openrose.ui.model.RuntimeOpenRoseModel
import io.github.dohex.openrose.ui.model.settingsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeOpenRoseModelTest {
    private val platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available)
    private val store = object : OpenRoseSettingsStore {
        override val uiSettings = MutableStateFlow(UiSettings())
        override fun updateUiSettings(settings: UiSettings) = Unit
        override fun updateSettings(settings: UserSettings) = Unit
    }

    @Test
    fun leavingBeforeStartReturnsStopsThatScanBeforeReentry() = runTest {
        val acknowledged = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val runtime = object : ControlRuntime by LocalControlRuntime(platform, scope = backgroundScope) {
            override suspend fun startScan(): OperationResult {
                calls += "starting"
                acknowledged.await()
                calls += "started"
                return OperationResult.Success
            }
            override suspend fun stopScan() {
                calls += "stopped"
            }
        }
        val model = RuntimeOpenRoseModel(runtime, store, backgroundScope)
        val flow = AddDeviceFlow(backgroundScope, model)
        flow.startSearch()
        flow.leave()
        flow.startSearch()
        assertEquals(listOf("starting"), calls)
        acknowledged.complete(Unit)
        runCurrent()
        assertEquals(listOf("starting", "started", "stopped", "starting", "started"), calls)
    }

    @Test
    fun settingDispatchRetainsTheSessionShownWhenTheControlWasRendered() = runTest {
        val expectedSessions = mutableListOf<Long?>()
        val runtime = object : ControlRuntime by LocalControlRuntime(platform, scope = backgroundScope) {
            override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult {
                expectedSessions += expectedSessionId
                return OperationResult.Success
            }
        }
        val model = RuntimeOpenRoseModel(runtime, store, backgroundScope)
        val first = model.settingsFor(1L)
        val second = model.settingsFor(2L)
        second(HeadsetCommand.NoiseMode(NoiseMode.ANC))
        first(HeadsetCommand.NoiseMode(NoiseMode.NORMAL))
        runCurrent()
        assertEquals(listOf<Long?>(2L, 1L), expectedSessions)
    }
}
