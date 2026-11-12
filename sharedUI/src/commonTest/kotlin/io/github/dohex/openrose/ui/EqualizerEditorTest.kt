package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EqualizerEditorTest {
    private data class Write(val sessionId: Long, val setting: DeviceSetting, val value: FieldValue) {
        val reply = CompletableDeferred<OperationResult>()
    }

    private class Device(scope: CoroutineScope) {
        val writes = mutableListOf<Write>()
        val editor = EqualizerEditor(scope) { sessionId, setting, value ->
            Write(sessionId, setting, value).also { writes += it }.reply.await()
        }
    }

    private val failed = OperationResult.Failure(ControlError(ControlErrorCode.NOT_APPLIED, "设备状态与请求不一致"))
    private val confirmedCurve = List(10) { 0 }

    @Test
    fun choicesMadeDuringAWriteCollapseIntoOneFollowUpWrite() = runTest {
        val device = Device(backgroundScope)
        device.editor.bind(1L)
        device.editor.choose(EqualizerChoice.Curve(List(10) { 1 }))
        runCurrent()
        device.editor.choose(EqualizerChoice.Curve(List(10) { 2 }))
        device.editor.choose(EqualizerChoice.Preset("rock"))
        device.editor.choose(EqualizerChoice.Curve(List(10) { 3 }))
        runCurrent()
        assertEquals(1, device.writes.size, "nothing is sent while the first write is in flight")
        assertEquals(List(10) { 3 }, device.editor.displayedCurve(confirmedCurve), "the page shows the latest choice")

        device.writes[0].reply.complete(OperationResult.Success)
        runCurrent()
        assertEquals(
            listOf(
                DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(List(10) { 1 }),
                DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(List(10) { 3 }),
            ),
            device.writes.map { it.setting to it.value },
        )
        device.writes[1].reply.complete(OperationResult.Success)
        runCurrent()
        assertNull(device.editor.pending)
        assertEquals(2, device.writes.size)
    }

    @Test
    fun aPresetChosenAfterACurveReplacesTheQueuedCurve() = runTest {
        val device = Device(backgroundScope)
        device.editor.bind(1L)
        device.editor.choose(EqualizerChoice.Curve(List(10) { 1 }))
        runCurrent()
        device.editor.choose(EqualizerChoice.Curve(List(10) { 2 }))
        device.editor.choose(EqualizerChoice.Preset("hifi"))
        assertEquals("hifi", device.editor.displayedMode("custom"))
        assertEquals(confirmedCurve, device.editor.displayedCurve(confirmedCurve), "a pending preset shows the device curve")

        device.writes[0].reply.complete(OperationResult.Success)
        runCurrent()
        assertEquals(DeviceSetting.EQ to FieldValue.Text("hifi"), device.writes[1].let { it.setting to it.value })
    }

    @Test
    fun aFailedOrNotAppliedWriteFallsBackToWhatTheDeviceConfirmed() = runTest {
        val device = Device(backgroundScope)
        device.editor.bind(1L)
        val requested = List(10) { -2 }
        device.editor.choose(EqualizerChoice.Curve(requested))
        runCurrent()
        assertEquals(requested, device.editor.displayedCurve(confirmedCurve))
        assertEquals(CUSTOM_EQUALIZER_MODE, device.editor.displayedMode("pop"))

        device.writes.single().reply.complete(failed)
        runCurrent()
        assertNull(device.editor.pending)
        assertEquals(confirmedCurve, device.editor.displayedCurve(confirmedCurve))
        assertEquals("pop", device.editor.displayedMode("pop"))
    }

    @Test
    fun editingTheCurveUnderAPresetWritesTheCurveWhichSelectsCustom() = runTest {
        val device = Device(backgroundScope)
        device.editor.bind(1L)
        assertEquals("pop", device.editor.displayedMode("pop"))
        device.editor.choose(EqualizerChoice.Curve(List(10) { if (it == 0) 4 else 0 }))
        runCurrent()
        assertEquals(CUSTOM_EQUALIZER_MODE, device.editor.displayedMode("pop"))
        assertEquals(
            Write(1L, DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(List(10) { if (it == 0) 4 else 0 })),
            device.writes.single(),
        )
    }

    @Test
    fun aNewSessionDropsPendingChoicesAndWritesOnlyToItself() = runTest {
        val device = Device(backgroundScope)
        device.editor.bind(1L)
        device.editor.choose(EqualizerChoice.Preset("pop"))
        runCurrent()
        device.editor.choose(EqualizerChoice.Preset("rock"))

        device.editor.bind(2L)
        assertNull(device.editor.pending)
        device.writes[0].reply.complete(OperationResult.Success)
        runCurrent()
        assertEquals(1, device.writes.size, "the old session's queued choice is never sent")

        device.editor.choose(EqualizerChoice.Preset("hifi"))
        runCurrent()
        assertEquals(Write(2L, DeviceSetting.EQ, FieldValue.Text("hifi")), device.writes[1])

        device.editor.bind(null)
        device.editor.choose(EqualizerChoice.Preset("rock"))
        runCurrent()
        assertNull(device.editor.pending, "with no ready session there is nothing to write to")
        assertEquals(2, device.writes.size)
    }
}
