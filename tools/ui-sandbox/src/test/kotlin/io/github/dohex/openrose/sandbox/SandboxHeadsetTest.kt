package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SandboxHeadsetTest {
    @Test
    fun firstAngelOptionsFollowConfirmedGameModeAndAreUsedForWrites() = runTest {
        val model = SandboxModel(backgroundScope)
        model.updateScenario { it.copy(profile = ModelProfile.FIRST_ANGEL_RS_V2) }
        fun device() = requireNotNull(model.state.value.device)
        fun presets() = requireNotNull(device().capabilities.equalizer).presets.toSet()

        val sessionId = device().sessionId
        assertEquals(FieldValue.Toggle(false), device().knownValue(DeviceField.GAME_MODE))
        assertEquals(setOf("hifi", "pop"), presets())
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.EqualizerPreset("hifi")))
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.GameMode(true)))
        assertEquals(setOf("game_1"), presets())
        assertNull(device().knownValue(DeviceField.EQ))
        assertEquals(OperationResult.Success, model.setInSession(sessionId, HeadsetCommand.EqualizerPreset("game_1")))
        assertEquals(FieldValue.Text("game_1"), device().knownValue(DeviceField.EQ))

        model.updateScenario { it.copy(battery = SandboxBattery.LOW) }
        assertEquals(setOf("game_1"), presets())
    }
}
