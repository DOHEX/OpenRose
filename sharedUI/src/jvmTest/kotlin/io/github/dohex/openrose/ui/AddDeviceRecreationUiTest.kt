package io.github.dohex.openrose.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.dohex.openrose.core.OperationResult
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddDeviceRecreationUiTest {
    @Test
    fun replacingTheScreenKeepsTheRequestAndDeliversSuccessToTheNewScreen() {
        for (finishesBetweenScreens in listOf(false, true)) {
            runOpenRoseUiTest(390, 640) {
                val model = FakeOpenRoseModel(mutableStateOf(disconnectedAndroidState()))
                val result = CompletableDeferred<OperationResult>()
                var scans = 0
                var connections = 0
                var navigations = 0
                model.onStartScan = { scans++ }
                model.onAddDeviceConnect = {
                    connections++
                    result.await()
                }
                val attached = mutableStateOf(true)
                lateinit var flow: AddDeviceFlow
                setContent {
                    val scope = rememberCoroutineScope()
                    val retained = remember { AddDeviceFlow(scope, model) }
                    SideEffect { flow = retained }
                    if (attached.value) AddDeviceLifecycle(true, model.state.value, retained) { navigations++ }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                runOnUiThread { flow.connect(addDeviceCandidate.deviceId) }
                attached.value = false
                harness.render()
                assertTrue(flow.pending)
                if (finishesBetweenScreens) result.complete(OperationResult.Success)
                harness.render()
                assertEquals(0, navigations)
                attached.value = true
                harness.render()
                if (!finishesBetweenScreens) result.complete(OperationResult.Success)
                harness.render()
                assertEquals(1, connections)
                assertEquals(1, scans)
                assertEquals(1, navigations)
            }
        }
    }
}
