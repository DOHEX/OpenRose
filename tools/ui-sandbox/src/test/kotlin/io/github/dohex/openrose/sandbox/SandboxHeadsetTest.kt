package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxHeadsetTest {
    @Test
    fun queuedPresetIsValidatedAgainstTheConfirmedGameMode() = runTest {
        val model = SandboxModel(backgroundScope)
        model.updateScenario { it.copy(profile = ModelProfile.FIRST_ANGEL_RS_V2) }
        val sessionId = requireNotNull(model.state.value.readySession).sessionId
        model.set(sessionId, HeadsetCommand.GameMode(true))
        val preset = async(start = CoroutineStart.UNDISPATCHED) { model.setInSession(sessionId, HeadsetCommand.EqualizerPreset("pop")) }
        assertEquals(listOf(SettingStage.SUBMITTED, SettingStage.SUBMITTED), model.state.value.settingOperations.map { it.stage })

        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(preset.await()).error.code)
        val final = model.state.value
        assertEquals(listOf(SettingStage.CONFIRMED, SettingStage.FAILED), final.settingOperations.map { it.stage })
        assertEquals(0, final.settingOperations.last().writtenFrames)
        assertEquals(FieldReading.Loading, final.device?.reading(DeviceField.EQ))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun restartingInitializationIsolatesLateReadbacksAndErrorsFromTheNewSession() = runTest {
        for (outcome in SandboxWriteOutcome.entries) {
            val model = SandboxModel(backgroundScope)
            model.updateScenario { it.copy(profile = ModelProfile.FIRST_ANGEL_RS_V2, writeOutcome = outcome) }
            val old = requireNotNull(model.state.value.readySession).sessionId
            val pending = async(start = CoroutineStart.UNDISPATCHED) { model.setInSession(old, HeadsetCommand.GameMode(true)) }
            model.state.first { it.settingOperations.single().stage == SettingStage.WRITTEN }
            model.updateScenario { it.copy(link = SandboxLink.INITIALIZING) }
            val replacement = requireNotNull(model.state.value.device).sessionId
            assertTrue(replacement > old)
            model.updateScenario { it.copy(link = SandboxLink.READY) }
            model.updateScenario { it.copy(battery = SandboxBattery.LOW) }
            assertEquals(replacement, model.state.value.readySession?.sessionId)
            model.set(replacement, HeadsetCommand.EqualizerPreset("hifi"))
            advanceTimeBy(251)
            assertEquals(SettingStage.WRITTEN, model.state.value.settingOperations.single { it.sessionId == replacement }.stage)

            val result = assertIs<OperationResult.Failure>(pending.await())
            assertEquals(ControlErrorCode.DISCONNECTED, result.error.code, outcome.name)
            val operation = model.state.value.settingOperations.single { it.sessionId == old }
            assertEquals(SettingStage.DISCONNECTED, operation.stage)
            assertTrue(operation.confirmedFields.isEmpty())
            assertEquals(false, model.state.value.device?.confirmedToggle(DeviceField.GAME_MODE))
            assertNull(model.state.value.lastError)
        }
    }

    @Test
    fun androidAcceptsSettingsOnlyAfterInitialization() = runTest {
        val model = SandboxModel(backgroundScope)
        model.updateScenario { it.copy(profile = ModelProfile.I5_ZT, link = SandboxLink.INITIALIZING) }
        val sessionId = requireNotNull(model.state.value.device).sessionId
        assertNull(model.state.value.readySession)
        model.set(sessionId, HeadsetCommand.NoiseMode(NoiseMode.ANC))
        val command = HeadsetCommand.GameMode(true)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(model.setInSession(sessionId, command)).error.code)
        assertTrue(model.state.value.settingOperations.isEmpty())

        model.updateScenario { it.copy(link = SandboxLink.READY) }
        assertEquals(OperationResult.Success, model.setInSession(sessionId, command))
    }

    @Test
    fun firstAngelWritesFollowConfirmedGameModeAndTheReadingSurvivesScenarioChanges() = runTest {
        val model = SandboxModel(backgroundScope)
        model.updateScenario { it.copy(profile = ModelProfile.FIRST_ANGEL_RS_V2) }
        fun device() = requireNotNull(model.state.value.device)

        val sessionId = device().sessionId
        assertEquals(FieldValue.Toggle(false), device().knownValue(DeviceField.GAME_MODE))
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.EqualizerPreset("hifi")))
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.GameMode(true)))
        assertEquals(FieldReading.Loading, device().reading(DeviceField.EQ))
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.EqualizerPreset("game_1")))
        assertEquals(FieldValue.Text("game_1"), device().knownValue(DeviceField.EQ))

        model.updateScenario { it.copy(battery = SandboxBattery.LOW) }
        assertEquals(FieldValue.Toggle(true), device().knownValue(DeviceField.GAME_MODE))
    }
}
