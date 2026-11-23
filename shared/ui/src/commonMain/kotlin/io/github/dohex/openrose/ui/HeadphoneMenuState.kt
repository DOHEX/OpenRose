package io.github.dohex.openrose.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton

/**
 * The app root owns the headphones menu state so an open menu survives a style switch.
 */
internal class HeadphoneMenuState(
    val connectedHome: Boolean,
    val menuExpanded: Boolean,
    val onMenuExpandedChange: (Boolean) -> Unit,
    val onAddDevice: () -> Unit,
    val onDisconnect: () -> Unit,
)

/** The top-bar actions of a main tab: the platform's own, and on the headphones tab the headphone menu. */
@Composable
internal fun MainTabActions(page: AppPage, platformActions: @Composable (AppPage) -> Unit, headphoneMenu: HeadphoneMenuState) {
    platformActions(page)
    if (page == AppPage.HEADPHONES) HeadphoneActions(headphoneMenu)
}

/** The headphones tab shows the overflow menu while connected and the add-device button otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeadphoneActions(state: HeadphoneMenuState) {
    val style = LocalInterfaceStyle.current
    val onMenuDisconnect = {
        state.onMenuExpandedChange(false)
        state.onDisconnect()
    }
    Box {
        val icon = if (state.connectedHome) RoseIcons[RoseIcon.MORE] else RoseIcons.Plus
        val label = if (state.connectedHome) "耳机菜单" else "添加耳机"
        val onClick = if (state.connectedHome) ({ state.onMenuExpandedChange(true) }) else state.onAddDevice
        when (style) {
            InterfaceStyle.MIUIX -> MiuixIconButton(onClick = onClick) { MiuixIcon(icon, label) }

            InterfaceStyle.MATERIAL3 -> TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(label) } },
                state = rememberTooltipState(),
            ) {
                MaterialIconButton(onClick = onClick) { MaterialIcon(icon, contentDescription = label) }
            }
        }
        val showMenu = state.connectedHome && state.menuExpanded
        val dismissMenu = { state.onMenuExpandedChange(false) }
        when (style) {
            InterfaceStyle.MIUIX -> MiuixDisconnectMenu(showMenu, dismissMenu, onMenuDisconnect)
            InterfaceStyle.MATERIAL3 -> MaterialDisconnectMenu(showMenu, dismissMenu, onMenuDisconnect)
        }
    }
}

/**
 * Both menus hold one entry in the error colour. Neither is blurred (ADR 0007's popup blur): Miuix's
 * [OverlayListPopup] paints an opaque `surfaceContainer` over whatever its `popupModifier` draws and has no colour
 * parameter, and a Material 3 [DropdownMenuPopup] is its own window on Android, so the page backdrop would be sampled
 * at the wrong offset.
 */
@Composable
private fun MiuixDisconnectMenu(show: Boolean, onDismiss: () -> Unit, onDisconnect: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    OverlayListPopup(
        show = show,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = onDismiss,
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        ListPopupColumn {
            BasicComponent(
                title = "断开控制连接",
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                modifier = Modifier.focusRequester(focusRequester).closeOnEscape(onDismiss).focusable(),
                onClick = onDisconnect,
                role = Role.Button,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialDisconnectMenu(show: Boolean, onDismiss: () -> Unit, onDisconnect: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    DropdownMenuPopup(expanded = show, onDismissRequest = onDismiss) {
        // The single action is inset 4 dp horizontally by DropdownMenuItem. Match that inset on all sides.
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShapes(inactiveShape = MenuDefaults.standaloneGroupShape),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            val actionColor = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                onClick = onDisconnect,
                text = { Text("断开控制连接") },
                shape = MaterialTheme.shapes.medium,
                leadingIcon = { AppIcon(RoseIcon.LINK_OFF) },
                colors = MenuDefaults.itemColors(textColor = actionColor, leadingIconColor = actionColor),
                modifier = Modifier.focusRequester(focusRequester).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            )
        }
        LaunchedEffect(show) {
            if (show) focusRequester.requestFocus()
        }
    }
}
