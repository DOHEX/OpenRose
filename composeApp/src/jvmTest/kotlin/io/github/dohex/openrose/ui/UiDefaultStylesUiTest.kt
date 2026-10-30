@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.InterfaceStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiDefaultStylesUiTest {
    private fun rowValueLabel(style: InterfaceStyle, value: String?): String =
        if (style == InterfaceStyle.MIUIX && value != null) "选项 $value" else confirmedLabel(value)

    @Test
    fun unknownChoiceHasNoSelectedRadioAndKeepsDeviceValueUntilExternalConfirmation() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createChoiceHarness(style, selectedValue = null)
            try {
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun knownChoiceSubmitsOnceAndDoesNotReplaceTheConfirmedValue() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createChoiceHarness(style, "A")
            try {
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun externalDismissClosesAKnownPickerWithoutSubmitting() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createChoiceHarness(style, "A")
            try {
                harness.render()
                harness.clickText("功能选择")
                harness.render()
                assertTrue(harness.hasText("选项 B"))
                harness.pickerOpen.value = false
                harness.render()
                assertFalse(harness.hasText("选项 B"))
                assertTrue(harness.requests.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun openKnownAndUnknownChoicesSurviveStyleSwitchAndCanStillBeSelected() = runBlocking {
        for (initialStyle in InterfaceStyle.entries) {
            for (initialSelection in listOf<String?>(null, "A")) {
                val harness = createChoiceHarness(initialStyle, initialSelection)
                try {
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
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun secondaryPageHidesNarrowNavigationTabsButKeepsTheWideSidebar() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val narrow = createShellHarness(style, width = 420, wide = false)
            try {
                narrow.render()
                assertTrue(narrow.hasText("二级测试页"))
                assertFalse(narrow.hasRole(Role.Tab), "narrow secondary pages must hide the bottom bar in $style")
            } finally {
                narrow.close()
            }

            val wide = createShellHarness(style, width = 1200, wide = true)
            try {
                wide.render()
                assertTrue(wide.hasText("二级测试页"))
                assertTrue(wide.hasText("系统集成"))
                assertTrue(wide.hasText("设置"))
                assertTrue(wide.hasRole(Role.Tab), "wide secondary pages must keep the sidebar in $style")
            } finally {
                wide.close()
            }
        }
    }

    @Test
    fun shellStyleReplacementKeepsTheSameScrollPosition() = runBlocking {
        val harness = createShellHarness(InterfaceStyle.MIUIX, width = 420, wide = false, longPage = true)
        try {
            harness.render()
            assertTrue(harness.scrollState.maxValue > 240, "the test page must be scrollable")
            harness.scrollState.scrollTo(240)
            harness.render()
            assertEquals(240, harness.scrollState.value)

            harness.style.value = InterfaceStyle.MATERIAL3
            harness.render()
            assertEquals(240, harness.scrollState.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun compactDashboardCardAndSwitchRowWithIconRenderAcrossStyles() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val styleState = mutableStateOf(style)
            val scrollState = ScrollState(0)
            val toggled = mutableStateOf(false)
            val scene = createShellScene(
                style = styleState,
                width = 420,
                height = 900,
                wide = false,
                scrollState = scrollState,
                pageContent = {
                    AppDashboardCard(compact = true) {
                        AppSwitchRow(
                            title = "紧凑开关",
                            checked = toggled.value,
                            icon = RoseIcon.CHEVRON,
                            onChange = { toggled.value = it },
                        )
                    }
                },
            )
            val harness = ShellHarness(scene, styleState, scrollState)
            try {
                harness.render()
                assertTrue(harness.hasText("紧凑开关"), "compact switch row title should render in $style")
                harness.clickText("紧凑开关")
                harness.render()
                assertTrue(toggled.value, "clicking compact switch row toggles state in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun choiceIconRendersForKnownAndUnknownValuesAcrossStyles() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for (selected in listOf<String?>("A", null)) {
                val harness = createChoiceHarness(style, selected)
                try {
                    harness.render()
                    val withoutIcon = harness.snapshot()
                    assertTrue(harness.hasText("功能选择"))
                    assertTrue(harness.hasText(rowValueLabel(style, selected)))
                    harness.icon.value = RoseIcon.EQUALIZER
                    harness.render()
                    val withIcon = harness.snapshot()
                    assertTrue(harness.hasText("功能选择"), "the row retains its accessible title in $style")
                    assertTrue(harness.hasText(rowValueLabel(style, selected)), "the row retains its value in $style")
                    val titleNode = harness.nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "功能选择" }
                    }
                    assertTrue(
                        titleNode.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty(),
                        "the decorative icon must not add a redundant description in $style",
                    )
                    val bounds = titleNode.boundsInRoot
                    assertTrue(
                        (bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(withIcon.height)).any { y ->
                            (bounds.left.toInt().coerceAtLeast(0) until (bounds.left.toInt() + 100).coerceAtMost(withIcon.width)).any { x ->
                                withoutIcon.getRGB(x, y) != withIcon.getRGB(x, y)
                            }
                        },
                        "the leading area of the choice row should render the icon in $style with selection $selected",
                    )
                    harness.clickText("功能选择")
                    harness.render()
                    assertTrue(harness.pickerOpen.value, "the row remains clickable in $style")
                } finally {
                    harness.close()
                }
            }
        }
    }

    private suspend fun createChoiceHarness(
        initialStyle: InterfaceStyle,
        selectedValue: String?,
    ): ChoiceHarness {
        val style = mutableStateOf(initialStyle)
        val selected = mutableStateOf(selectedValue)
        val pickerOpen = mutableStateOf(false)
        val requests = mutableListOf<String>()
        val icon = mutableStateOf<RoseIcon?>(null)
        val scrollState = ScrollState(0)
        val scene = createShellScene(
            style = style,
            width = 420,
            height = 900,
            wide = false,
            scrollState = scrollState,
            pageContent = {
                AppChoiceRow(
                    title = "功能选择",
                    valueText = confirmedLabel(selected.value),
                    options = OPTIONS,
                    selected = selected.value,
                    enabled = true,
                    icon = icon.value,
                    id = "ui-default-styles-test-choice",
                    open = pickerOpen.value,
                    onOpenChange = { pickerOpen.value = it },
                    onSelect = { requests += it },
                )
            },
        )
        return ChoiceHarness(scene, style, selected, pickerOpen, requests, scrollState, icon)
    }

    private suspend fun createShellHarness(
        initialStyle: InterfaceStyle,
        width: Int,
        wide: Boolean,
        longPage: Boolean = false,
    ): ShellHarness {
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
        val scene = createShellScene(
            style = style,
            width = width,
            height = 900,
            wide = wide,
            scrollState = scrollState,
            pageContent = pageContent,
        )
        return ShellHarness(scene, style, scrollState)
    }

    private suspend fun createShellScene(
        style: MutableState<InterfaceStyle>,
        width: Int,
        height: Int,
        wide: Boolean,
        scrollState: ScrollState,
        pageContent: @Composable () -> Unit,
    ): ImageComposeScene = ImageComposeScene(
        width = width,
        height = height,
        density = Density(1f),
        coroutineContext = currentCoroutineContext(),
    ) {
        ProvideInterfaceStyle(style.value, dark = false) {
            val backStack = listOf(AppPage.HEADPHONES, AppPage.AUDIO)
            when (style.value) {
                InterfaceStyle.MIUIX -> MiuixAppShell(
                    backStack = backStack,
                    page = AppPage.AUDIO,
                    wide = wide,
                    onNavigate = {},
                    onBack = {},
                    scrollState = scrollState,
                    pageContent = pageContent,
                )
                InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                    backStack = backStack,
                    page = AppPage.AUDIO,
                    wide = wide,
                    onNavigate = {},
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
        scene: ImageComposeScene,
        style: MutableState<InterfaceStyle>,
        val selected: MutableState<String?>,
        val pickerOpen: MutableState<Boolean>,
        val requests: MutableList<String>,
        scrollState: ScrollState,
        val icon: MutableState<RoseIcon?>,
    ) : ShellHarness(scene, style, scrollState)

    private open class ShellHarness(
        private val scene: ImageComposeScene,
        val style: MutableState<InterfaceStyle>,
        val scrollState: ScrollState,
    ) {
        private var nextFrameTimeNanos = 0L

        suspend fun render() {
            repeat(10) {
                kotlinx.coroutines.delay(35)
                scene.render(nextFrameTimeNanos).close()
                nextFrameTimeNanos += 35_000_000L
            }
        }

        fun close() = scene.close()
        fun snapshot(): java.awt.image.BufferedImage {
            val image = scene.render(nextFrameTimeNanos)
            val data = requireNotNull(image.encodeToData())
            return try {
                ImageIO.read(ByteArrayInputStream(data.bytes))
            } finally {
                data.close()
                image.close()
            }
        }


        fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                result += node
                node.children.forEach(::collect)
            }
            scene.semanticsOwners.forEach { collect(it.rootSemanticsNode) }
            return result
        }

        fun hasText(expected: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
        }

        fun hasRole(expected: Role): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == expected
        }

        fun hasSelectedRadio(): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                node.config.getOrNull(SemanticsProperties.Selected) == true
        }

        fun clickText(text: String) {
            val textNodes = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
            for (textNode in textNodes) {
                var current: SemanticsNode? = textNode
                while (current != null) {
                    val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
                    if (action != null) {
                        check(action()) { "click on '$text' was rejected" }
                        return
                    }
                    current = current.parent
                }
            }
            error("no clickable node found for '$text': " + nodes().joinToString(" | ") { node -> "text=${node.config.getOrNull(SemanticsProperties.Text)} click=${node.config.contains(SemanticsActions.OnClick)} role=${node.config.getOrNull(SemanticsProperties.Role)}" })
        }
    }

    private companion object {
        val OPTIONS = listOf(
            AppOption("A", "选项 A"),
            AppOption("B", "选项 B"),
        )
    }
}
