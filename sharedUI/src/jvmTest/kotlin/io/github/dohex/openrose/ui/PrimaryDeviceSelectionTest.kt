package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrimaryDeviceSelectionTest {
    private val first = candidate("rose-1", ModelProfile.I5_ZT)
    private val second = candidate("rose-2", ModelProfile.I7_RS_V2)
    private val third = candidate("rose-3", ModelProfile.I5_ZT)

    @Test
    fun firstBondedRoseDeviceIsPrimaryUntilAnotherIsChosen() {
        val selection = PrimaryDeviceSelection()
        val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))

        assertEquals(first, selection.primary(state))
    }

    @Test
    fun connectionTargetWinsOverEveryOtherChoice() {
        val selection = PrimaryDeviceSelection()
        val state = disconnectedAndroidState().copy(
            bondedDevices = persistentListOf(first, second),
            connectionTarget = third,
        )
        selection.select(state, second.deviceId)

        assertEquals(third, selection.primary(state))
    }

    @Test
    fun manualChoiceAppliesWhileTheBondedListIsUnchanged() {
        val selection = PrimaryDeviceSelection()
        val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
        selection.select(state, second.deviceId)

        assertEquals(second, selection.primary(state))
        assertEquals(second, selection.primary(state.copy(bondedDevices = persistentListOf(first, second))))
    }

    @Test
    fun manualChoiceIsForgottenWhenTheBondedListChanges() {
        val selection = PrimaryDeviceSelection()
        val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
        selection.select(state, second.deviceId)

        val changed = state.copy(bondedDevices = persistentListOf(first, second, third))
        assertEquals(first, selection.primary(changed))
    }

    @Test
    fun noPrimaryWithoutABondedRoseDeviceOrTarget() {
        val selection = PrimaryDeviceSelection()
        val unrelated = candidate("speaker", profile = null)

        assertNull(selection.primary(ControlState(platform = disconnectedAndroidState().platform)))
        assertNull(
            selection.primary(disconnectedAndroidState().copy(bondedDevices = persistentListOf(unrelated))),
        )
    }

    private fun candidate(id: String, profile: ModelProfile?) = DeviceCandidate(
        deviceId = DeviceId(id),
        name = "Device $id",
        rssi = null,
        profile = profile,
        manufacturerDataHex = "",
    )
}
