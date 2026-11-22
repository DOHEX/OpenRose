@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * What the headphones tab's top-bar actions need. The app root owns the state, so an open menu or dialog
 * survives a style switch; [onAddDevice] opens the add-device page.
 */
internal class HeadphoneMenuState(
    val connectedHome: Boolean,
    val menuExpanded: Boolean,
    val onMenuExpandedChange: (Boolean) -> Unit,
    val confirmingDisconnect: Boolean,
    val onConfirmingDisconnectChange: (Boolean) -> Unit,
    val onAddDevice: () -> Unit,
    val onDisconnect: () -> Unit,
)

/** The top-bar actions of a main tab: the platform's own, and on the headphones tab the headphone menu. */
@Composable
internal fun MainTabActions(page: AppPage, platformActions: @Composable (AppPage) -> Unit, headphoneMenu: HeadphoneMenuState) {
    platformActions(page)
    if (page == AppPage.HEADPHONES) HeadphoneActions(headphoneMenu)
}

/**
 * Top-bar actions of the headphones tab, identical in both styles: the overflow menu while connected, the
 * add-device button otherwise. 断开控制连接 sits in the menu and asks for confirmation first; only the
 * rendering of the button, menu and dialog follows the interface style.
 */
@Composable
private fun HeadphoneActions(state: HeadphoneMenuState) {
    val style = LocalInterfaceStyle.current
    val onMenuDisconnect = {
        state.onMenuExpandedChange(false)
        state.onConfirmingDisconnectChange(true)
    }
    Box {
        val icon = if (state.connectedHome) RoseIcons[RoseIcon.MORE] else RoseIcons.Plus
        val label = if (state.connectedHome) "耳机菜单" else "添加耳机"
        val onClick = if (state.connectedHome) ({ state.onMenuExpandedChange(true) }) else state.onAddDevice
        when (style) {
            InterfaceStyle.MIUIX -> MiuixIconButton(onClick = onClick) { MiuixIcon(icon, label) }
            InterfaceStyle.MATERIAL3 -> MaterialIconButton(onClick = onClick) { MaterialIcon(icon, contentDescription = label) }
        }
        val showMenu = state.connectedHome && state.menuExpanded
        val dismissMenu = { state.onMenuExpandedChange(false) }
        when (style) {
            InterfaceStyle.MIUIX -> MiuixDisconnectMenu(showMenu, dismissMenu, onMenuDisconnect)
            InterfaceStyle.MATERIAL3 -> MaterialDisconnectMenu(showMenu, dismissMenu, onMenuDisconnect)
        }
    }
    AppConfirmDialog(
        show = state.connectedHome && state.confirmingDisconnect,
        title = "断开控制连接？",
        message = "断开后无法调整耳机设置，需要重新连接才能继续控制。",
        confirmLabel = "断开",
        onConfirm = {
            state.onConfirmingDisconnectChange(false)
            state.onDisconnect()
        },
        onDismiss = { state.onConfirmingDisconnectChange(false) },
    )
}

/**
 * Both menus hold one entry in the error colour. Neither is blurred (ADR 0007's popup blur): Miuix's
 * [OverlayListPopup] paints an opaque `surfaceContainer` over whatever its `popupModifier` draws and has no colour
 * parameter, and a Material 3 [DropdownMenu] is its own window on Android, so the page backdrop would be sampled
 * at the wrong offset.
 */
@Composable
private fun MiuixDisconnectMenu(show: Boolean, onDismiss: () -> Unit, onDisconnect: () -> Unit) {
    OverlayListPopup(
        show = show,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = onDismiss,
    ) {
        ListPopupColumn {
            BasicComponent(
                title = "断开控制连接",
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                onClick = onDisconnect,
                role = Role.Button,
            )
        }
    }
}

@Composable
private fun MaterialDisconnectMenu(show: Boolean, onDismiss: () -> Unit, onDisconnect: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    DropdownMenu(expanded = show, onDismissRequest = onDismiss) {
        LaunchedEffect(show) {
            if (show) focusRequester.requestFocus()
        }
        DropdownMenuItem(
            modifier = Modifier.focusRequester(focusRequester).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
            text = { Text("断开控制连接", color = MaterialTheme.colorScheme.error) },
            onClick = onDisconnect,
        )
    }
}
