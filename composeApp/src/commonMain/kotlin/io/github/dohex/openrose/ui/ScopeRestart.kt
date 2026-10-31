package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.ui.resources.Res
import io.github.dohex.openrose.ui.resources.ic_restart_scope
import org.jetbrains.compose.resources.vectorResource
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** A successful command does not prove that the process recovered or the module loaded. */
enum class ScopeRestartStatus { EXECUTED, NOT_RUNNING, FAILED }
data class ScopeRestartResult(val target: String, val status: ScopeRestartStatus, val detail: String? = null)

@Composable
internal fun ScopeRestartAction(
    running: Boolean,
    confirming: Boolean,
    results: List<ScopeRestartResult>?,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onCloseResults: () -> Unit,
) {
    val restartIcon = vectorResource(Res.drawable.ic_restart_scope)
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            top.yukonga.miuix.kmp.basic.IconButton(onClick = onOpen, enabled = !running) {
                top.yukonga.miuix.kmp.basic.Icon(restartIcon, contentDescription = "重启作用域")
            }
            OverlayDialog(show = confirming, title = "重启系统集成作用域？", onDismissRequest = onCancel) {
                AppText("蓝牙连接可能中断。确认后将请求 Root 权限。")
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("取消", onCancel, kind = AppButtonKind.Secondary)
                    AppButton("重启", onConfirm)
                }
            }
            OverlayDialog(show = running || results != null, title = "重启作用域", onDismissRequest = { if (!running) onCloseResults() }) {
                ScopeRestartContent(running, results)
                if (!running) AppButton("关闭", onCloseResults, kind = AppButtonKind.Secondary)
            }
        }
        InterfaceStyle.MATERIAL3 -> {
            androidx.compose.material3.IconButton(onClick = onOpen, enabled = !running) {
                androidx.compose.material3.Icon(restartIcon, contentDescription = "重启作用域")
            }
            if (confirming) AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("重启系统集成作用域？") },
                text = { Text("蓝牙连接可能中断。确认后将请求 Root 权限。") },
                dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
                confirmButton = { TextButton(onClick = onConfirm) { Text("重启") } },
            )
            if (running || results != null) AlertDialog(
                onDismissRequest = { if (!running) onCloseResults() },
                title = { Text("重启作用域") },
                text = { ScopeRestartContent(running, results) },
                confirmButton = { if (!running) TextButton(onClick = onCloseResults) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun ScopeRestartContent(running: Boolean, results: List<ScopeRestartResult>?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) AppText("正在请求 Root 并处理作用域…")
        results?.forEach { result ->
            val status = when (result.status) {
                ScopeRestartStatus.EXECUTED -> "已执行"
                ScopeRestartStatus.NOT_RUNNING -> "未运行，无需重启"
                ScopeRestartStatus.FAILED -> "失败"
            }
            AppText("${result.target}：$status${result.detail?.let { " · $it" } ?: ""}")
        }
        if (!running && results != null) AppText("已执行仅表示命令已执行，不代表进程已恢复或模块已生效。", role = AppTextRole.Summary)
    }
}
