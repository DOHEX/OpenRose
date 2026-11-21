package io.github.dohex.openrose.sandbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ui.configurationLabel
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SandboxProfilePickerTest {
    @Test
    fun eachHardwareProfileIsReachableWithADistinctLabel() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent { MaterialTheme { SandboxProfilePicker(ModelProfile.I7_RS_V2) {} } }
        onNodeWithText("EarFeel i7").performClick()
        val series = onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.Text].single().text
        }
        val labels = mutableListOf<String>()
        for (name in series) {
            onNodeWithText(name).performClick()
            val options = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
            labels += options.map { it.config[SemanticsProperties.Text].single().text }
            onNodeWithText("全部系列").performClick()
        }
        assertEquals(ModelProfile.entries.map { it.configurationLabel }.sorted(), labels.sorted())
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun browsingAndReturningKeepsTheScenarioUntilAHardwareBranchIsChosen() = runDesktopComposeUiTest(width = 420, height = 900) {
        val selected = mutableStateOf(ModelProfile.I7_RS_V2)
        val selections = mutableListOf<ModelProfile>()
        setContent {
            MaterialTheme {
                SandboxProfilePicker(selected.value) { profile ->
                    selections += profile
                    selected.value = profile
                }
            }
        }

        onNodeWithText("EarFeel i7").performClick()
        onNodeWithText("CERAMICS-X · 7034").assertDoesNotExist()
        onNodeWithText("CERAMICS").performClick()
        onNodeWithText("CERAMICS-X · 7034").assertExists()
        onNodeWithText("全部系列").performClick()
        onNodeWithText("CERAMICS-X · 7034").assertDoesNotExist()
        onNodeWithText("Open 系列").performClick()
        onNodeWithText("OpenFun · 有触控开关").assertExists()
        assertEquals(emptyList(), selections)
        assertEquals(ModelProfile.I7_RS_V2, selected.value)

        onNodeWithText("OpenFeel · LDAC + LHDC").performClick()
        assertEquals(listOf(ModelProfile.OPENFEEL_DUAL_RS_COMMON), selections)
        onNodeWithText("全部系列").assertDoesNotExist()
        onNodeWithText("OpenFeel · LDAC + LHDC").assertExists()
    }

    @Test
    fun externallySelectedHardwareBranchIsShownInTheCollapsedPicker() = runDesktopComposeUiTest(width = 420, height = 900) {
        val selected = mutableStateOf(ModelProfile.OPENFUN_TOUCH_RS_COMMON)
        setContent { MaterialTheme { SandboxProfilePicker(selected.value) { selected.value = it } } }

        onNodeWithText("OpenFun · 有触控开关").assertExists()
        runOnUiThread { selected.value = ModelProfile.CERAMICS_X_7034_F8 }
        onNodeWithText("CERAMICS-X · 7034").assertExists()
        onNodeWithText("OpenFun · 有触控开关").assertDoesNotExist()
    }
}
