package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiDefaultStylesUiTest {
    private fun rowValueLabel(style: InterfaceStyle, value: String?): String =
        if (style == InterfaceStyle.MIUIX && value != null) "选项 $value" else confirmedLabel(value)

    @Test
    fun unknownChoiceHasNoSelectedRadioAndKeepsDeviceValueUntilExternalConfirmation() {
        for (style in InterfaceStyle.entries) {
            withChoiceHarness(style, selectedValue = null) { harness ->
                harness.render()
                harness.clickText("功能选择")
                harness.render()

                assertTrue(harness.pickerOpen.value, "the picker should open in $style")
                assertFalse(
                    harness.hasSelectedRadio(),
                    "an unknown device value must not select an option in $style",
                )

                harness.clickText("选项 B")
                harness.render()
                assertEquals(listOf("B"), harness.requests)
                assertFalse(harness.pickerOpen.value)
                assertTrue(harness.hasText("当前状态未知"), "the request must not become confirmed in $style")

                harness.selected.value = "B"
                harness.render()
                assertTrue(harness.hasText(rowValueLabel(style, "B")))
            }
        }
    }

    @Test
    fun knownChoiceSubmitsOnceAndDoesNotReplaceTheConfirmedValue() {
        for (style in InterfaceStyle.entries) {
            withChoiceHarness(style, "A") { harness ->
                harness.render()
                harness.clickText("功能选择")
                harness.render()
                harness.clickText("选项 B")
                harness.render()
                assertEquals(listOf("B"), harness.requests)
                assertTrue(harness.hasText(rowValueLabel(style, "A")))
                harness.selected.value = "B"
                harness.render()
                assertTrue(harness.hasText(rowValueLabel(style, "B")))
            }
        }
    }

    @Test
    fun externalDismissClosesAKnownPickerWithoutSubmitting() {
        for (style in InterfaceStyle.entries) {
            withChoiceHarness(style, "A") { harness ->
                harness.render()
                harness.clickText("功能选择")
                harness.render()
                assertTrue(harness.hasText("选项 B"))
                harness.pickerOpen.value = false
                harness.render()
                assertFalse(harness.hasText("选项 B"))
                assertTrue(harness.requests.isEmpty())
            }
        }
    }

    @Test
    fun openKnownAndUnknownChoicesSurviveStyleSwitchAndCanStillBeSelected() {
        for (initialStyle in InterfaceStyle.entries) {
            for (initialSelection in listOf<String?>(null, "A")) {
                withChoiceHarness(initialStyle, initialSelection) { harness ->
                    harness.render()
                    harness.clickText("功能选择")
                    harness.render()
                    assertTrue(harness.pickerOpen.value)

                    harness.style.value = if (initialStyle == InterfaceStyle.MIUIX) {
                        InterfaceStyle.MATERIAL3
                    } else {
                        InterfaceStyle.MIUIX
                    }
                    harness.render()

                    assertTrue(harness.pickerOpen.value, "the open picker should remain open after a style switch")
                    assertTrue(harness.requests.isEmpty(), "switching styles must not submit a choice")
                    assertTrue(
                        harness.hasText(confirmedLabel(initialSelection)),
                        "the row must keep the last device-confirmed value",
                    )

                    harness.clickText("选项 B")
                    harness.render()
                    assertEquals(listOf("B"), harness.requests)
                    assertTrue(
                        harness.hasText(rowValueLabel(harness.style.value, initialSelection)),
                        "a request must leave the displayed value unchanged until device confirmation",
                    )

                    harness.selected.value = "B"
                    harness.render()
                    assertTrue(harness.hasText(rowValueLabel(harness.style.value, "B")))
                }
            }
        }
    }

    @Test
    fun secondaryPageShellCarriesNoNavigationTabs() {
        for (style in InterfaceStyle.entries) {
            withShellHarness(style, width = 420) { narrow ->
                narrow.render()
                assertTrue(narrow.hasText("二级测试页"))
                assertFalse(narrow.hasRole(Role.Tab), "secondary pages must not draw navigation of their own in $style")
            }
        }
    }

    @Test
    fun shellStyleReplacementKeepsTheSameScrollPosition() {
        withShellHarness(InterfaceStyle.MIUIX, width = 420, longPage = true) { harness ->
            harness.render()
            assertTrue(harness.scrollState.maxValue > 240, "the test page must be scrollable")
            harness.scrollState.scrollTo(240)
            harness.render()
            assertEquals(240, harness.scrollState.value)

            harness.style.value = InterfaceStyle.MATERIAL3
            harness.render()
            assertEquals(240, harness.scrollState.value)
        }
    }

    private fun withChoiceHarness(
        initialStyle: InterfaceStyle,
        selectedValue: String?,
        block: suspend (ChoiceHarness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val style = mutableStateOf(initialStyle)
        val selected = mutableStateOf(selectedValue)
        val pickerOpen = mutableStateOf(false)
        val requests = mutableListOf<String>()
        val scrollState = ScrollState(0)
        setContent {
            TestPageContent(style.value) {
                AppChoiceRow(
                    title = "功能选择",
                    valueText = confirmedLabel(selected.value),
                    options = OPTIONS,
                    selected = selected.value,
                    enabled = true,
                    id = "ui-default-styles-test-choice",
                    open = pickerOpen.value,
                    onOpenChange = { pickerOpen.value = it },
                    onSelect = { requests += it },
                )
            }
        }
        block(ChoiceHarness(this, style, selected, pickerOpen, requests, scrollState))
    }

    private fun withShellHarness(
        initialStyle: InterfaceStyle,
        width: Int,
        longPage: Boolean = false,
        block: suspend (ShellHarness) -> Unit,
    ) = runOpenRoseUiTest(width, 900) {
        val style = mutableStateOf(initialStyle)
        val scrollState = ScrollState(0)
        val pageContent: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppText("二级测试页")
                if (longPage) repeat(50) { AppText("滚动内容") }
            }
        }
        setShellContent(
            style = style,
            scrollState = scrollState,
            pageContent = pageContent,
        )
        block(ShellHarness(this, style, scrollState))
    }

    private fun OpenRoseUiTestScene.setShellContent(
        style: MutableState<InterfaceStyle>,
        scrollState: ScrollState,
        pageContent: @Composable () -> Unit,
    ) = setContent {
        ProvideInterfaceStyle(style.value, dark = false) {
            when (style.value) {
                InterfaceStyle.MIUIX -> MiuixAppShell(
                    page = AppPage.AUDIO,
                    onBack = {},
                    scrollState = scrollState,
                    pageContent = pageContent,
                )
                InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                    page = AppPage.AUDIO,
                    onBack = {},
                    scrollState = scrollState,
                    pageContent = pageContent,
                )
            }
        }
    }

    private fun confirmedLabel(selected: String?): String =
        selected?.let { "设备已确认：$it" } ?: "当前状态未知"

    private class ChoiceHarness(
        scene: OpenRoseUiTestScene,
        style: MutableState<InterfaceStyle>,
        val selected: MutableState<String?>,
        val pickerOpen: MutableState<Boolean>,
        val requests: MutableList<String>,
        scrollState: ScrollState,
    ) : ShellHarness(scene, style, scrollState)

    private open class ShellHarness(
        scene: OpenRoseUiTestScene,
        val style: MutableState<InterfaceStyle>,
        val scrollState: ScrollState,
    ) : OpenRoseUiTestHarness(scene) {
        fun hasSelectedRadio(): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                node.config.getOrNull(SemanticsProperties.Selected) == true
        }
    }

    private companion object {
        val OPTIONS = listOf(
            AppOption("A", "选项 A"),
            AppOption("B", "选项 B"),
        )
    }
}
