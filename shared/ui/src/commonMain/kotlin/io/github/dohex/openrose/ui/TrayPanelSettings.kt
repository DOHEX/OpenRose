package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

enum class TrayPanelControl(val id: String, val label: String, val summary: String) {
    GAME_MODE("game_mode", "游戏模式", "在支持的耳机上快速开关低延迟模式"),
    EQUALIZER("equalizer", "EQ 预设", "在托盘面板内展开并切换预设均衡器"),
    SPATIAL_AUDIO("spatial_audio", "空间音频", "控制空间音频开关、空间模式与空间音频内容"),
    INDEPENDENT_MIC("independent_mic", "独立麦克风模式", "在已激活独立麦克风的耳机上开关独立拾音"),
    ;

    companion object {
        fun fromId(id: String): TrayPanelControl? = entries.firstOrNull { it.id == id }
    }
}

data class TrayPanelSettings(
    val showNoiseLevels: Boolean = true,
    val enabledControls: Set<TrayPanelControl> = DEFAULT_ENABLED_CONTROLS,
    val controlOrder: List<TrayPanelControl> = DEFAULT_CONTROL_ORDER,
) {
    fun normalized(): TrayPanelSettings {
        val ordered = buildList {
            controlOrder.forEach { if (it !in this) add(it) }
            TrayPanelControl.entries.forEach { if (it !in this) add(it) }
        }
        return if (ordered == controlOrder) this else copy(controlOrder = ordered)
    }

    val orderedEnabledControls: List<TrayPanelControl>
        get() = normalized().controlOrder.filter { it in enabledControls }

    val isDefault: Boolean
        get() = normalized() == DEFAULT

    fun setControlEnabled(control: TrayPanelControl, enabled: Boolean): TrayPanelSettings {
        val next = if (enabled) enabledControls + control else enabledControls - control
        return normalized().copy(enabledControls = next)
    }

    fun moveControl(control: TrayPanelControl, delta: Int): TrayPanelSettings {
        val current = normalized()
        val index = current.controlOrder.indexOf(control)
        val target = index + delta
        if (index < 0 || target !in current.controlOrder.indices || target == index) return current
        val next = current.controlOrder.toMutableList()
        next[index] = next[target]
        next[target] = control
        return current.copy(controlOrder = next)
    }

    fun summaryText(): String {
        val items = buildList {
            if (showNoiseLevels) add("降噪／通透档位")
            orderedEnabledControls.forEach { add(it.label) }
        }
        return if (items.isEmpty()) "仅显示基础信息与降噪模式" else items.joinToString(" · ")
    }

    companion object {
        val DEFAULT_ENABLED_CONTROLS: Set<TrayPanelControl> = setOf(
            TrayPanelControl.GAME_MODE,
            TrayPanelControl.EQUALIZER,
        )
        val DEFAULT_CONTROL_ORDER: List<TrayPanelControl> = TrayPanelControl.entries.toList()
        val DEFAULT: TrayPanelSettings = TrayPanelSettings()
    }
}

@Composable
fun TrayPanelSettingsPage(
    settings: TrayPanelSettings,
    onChange: (TrayPanelSettings) -> Unit,
) {
    val normalized = settings.normalized()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup {
            AppInfoRow(
                "按当前耳机型号能力显示",
                "托盘面板固定显示连接状态、电量与降噪模式。以下配置全局生效，仅在当前耳机支持对应能力时呈现。",
            )
        }

        AppGroup(title = "降噪控制") {
            AppSwitchRow(
                title = "降噪／通透档位",
                summary = "处于主动降噪或通透模式时，固定显示在降噪模式下方",
                checked = normalized.showNoiseLevels,
                onChange = { onChange(normalized.copy(showNoiseLevels = it)) },
            )
        }

        AppGroup(
            title = "快捷控制项",
            titleAction = AppTitleAction("恢复默认", enabled = !normalized.isDefault) {
                onChange(TrayPanelSettings.DEFAULT)
            },
        ) {
            normalized.controlOrder.forEachIndexed { index, control ->
                TrayControlOrderRow(
                    control = control,
                    checked = control in normalized.enabledControls,
                    canMoveUp = index > 0,
                    canMoveDown = index < normalized.controlOrder.lastIndex,
                    onCheckedChange = { onChange(normalized.setControlEnabled(control, it)) },
                    onMoveUp = { onChange(normalized.moveControl(control, -1)) },
                    onMoveDown = { onChange(normalized.moveControl(control, 1)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrayControlOrderRow(
    control: TrayPanelControl,
    checked: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val controls: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrderStepButton(
                directionUp = true,
                enabled = canMoveUp,
                description = "上移 ${control.label}",
                onClick = onMoveUp,
            )
            OrderStepButton(
                directionUp = false,
                enabled = canMoveDown,
                description = "下移 ${control.label}",
                onClick = onMoveDown,
            )
            when (LocalInterfaceStyle.current) {
                InterfaceStyle.MIUIX -> MiuixSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.semantics {
                        contentDescription = control.label
                    },
                )

                InterfaceStyle.MATERIAL3 -> Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.semantics {
                        contentDescription = control.label
                    },
                )
            }
        }
    }

    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            title = control.label,
            summary = control.summary,
            endActions = { controls() },
            onClick = { onCheckedChange(!checked) },
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            onClick = { onCheckedChange(!checked) },
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
            trailingContent = controls,
            verticalAlignment = Alignment.CenterVertically,
            supportingContent = { Text(control.summary) },
            colors = rowColors(),
        ) { Text(control.label) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OrderStepButton(
    directionUp: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val tint = appColor(if (enabled) AppColorRole.OnSurface else AppColorRole.DisabledContent)
    val buttonModifier = Modifier.semantics {
        contentDescription = description
        if (!enabled) disabled()
    }
    val icon: @Composable () -> Unit = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            AppIcon(
                RoseIcon.CHEVRON,
                modifier = Modifier.size(18.dp).rotate(if (directionUp) -90f else 90f),
                tint = tint,
            )
        }
    }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixIconButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            minWidth = 32.dp,
            minHeight = 32.dp,
            content = icon,
        )

        InterfaceStyle.MATERIAL3 -> IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = buttonModifier.size(32.dp),
            enabled = enabled,
            content = icon,
        )
    }
}
