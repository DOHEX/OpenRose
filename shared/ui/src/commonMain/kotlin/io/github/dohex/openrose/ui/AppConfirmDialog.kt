package io.github.dohex.openrose.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Asks before an action that ends something the user was using. Material 3 colours the confirming action as an
 * error; the Miuix dialog has no danger button, so its confirming action is the primary one on the right.
 * [onDismiss] is called for 取消, a tap outside and Escape.
 */
@Composable
internal fun AppConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> OverlayDialog(show = show, title = title, summary = message, onDismissRequest = onDismiss) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("取消", onDismiss, modifier = Modifier.weight(1f), kind = AppButtonKind.Secondary)
                AppButton(confirmLabel, onConfirm, modifier = Modifier.weight(1f))
            }
        }

        InterfaceStyle.MATERIAL3 -> if (show) {
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.focusRequester(focusRequester).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }.focusable(),
                title = { Text(title) },
                text = {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    Text(message)
                },
                confirmButton = {
                    val errorColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onConfirm, colors = errorColors) {
                        Text(confirmLabel)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }
    }
}
