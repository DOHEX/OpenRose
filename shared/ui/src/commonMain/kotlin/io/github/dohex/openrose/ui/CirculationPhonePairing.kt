package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

@Composable
internal fun PhonePairing(
    nearby: Map<String, String>,
    busy: Boolean,
    scan: @Composable ((String) -> Unit) -> (() -> Unit),
    onQr: (String) -> Unit,
    onPair: (String, String) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val openScanner = scan(onQr)
    PairingIntroduction(
        title = "与电脑配对",
        summary = "手机和电脑连入同一局域网后，扫描电脑上显示的二维码。",
        icon = RoseIcon.LINK,
    ) {
        AppButton("扫描二维码", openScanner, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }

    AppGroup(title = "附近的电脑") {
        if (nearby.isEmpty()) {
            AppFactRow(RoseIcon.DESKTOP, "暂未发现电脑", "在电脑上打开 OpenRose 的「设置 → 流转配对」。")
        }
        nearby.forEach { (id, name) ->
            key(id) {
                val selected = selectedId == id
                NearbyComputerRow(name, selected, !busy) { selectedId = if (selected) null else id }
                if (selected) PairingCodeEntry(busy) { code -> onPair(id, code) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NearbyComputerRow(name: String, expanded: Boolean, enabled: Boolean, toggle: () -> Unit) {
    val action: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(if (expanded) "收起" else "输入配对码", role = AppTextRole.Summary)
            ExpansionIcon(expanded, Modifier.size(16.dp), tint = appColor(AppColorRole.Summary))
        }
    }
    val button = Modifier.semantics { role = Role.Button }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            title = name,
            modifier = button,
            startAction = { AppIcon(RoseIcon.DESKTOP) },
            endActions = { action() },
            enabled = enabled,
            onClick = toggle,
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            onClick = toggle,
            modifier = button,
            enabled = enabled,
            leadingContent = { AppIcon(RoseIcon.DESKTOP) },
            trailingContent = action,
            colors = rowColors(),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(name) }
    }
}

@Composable
private fun PairingCodeEntry(busy: Boolean, onPair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val ready = code.length == 6 && !busy
    val focus = LocalFocusManager.current
    val submit = {
        if (ready) {
            focus.clearFocus()
            onPair(code)
        }
    }
    val requester = remember { FocusRequester() }
    val field = Modifier.fillMaxWidth().focusRequester(requester)
    val update: (String) -> Unit = { code = it.filter { char -> char in '0'..'9' }.take(6) }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
    val actions = KeyboardActions(onDone = { submit() })
    val material = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val container = if (material) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier
    Column(
        container.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (material) {
            OutlinedTextField(
                value = code,
                onValueChange = update,
                modifier = field,
                enabled = !busy,
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                label = { Text("六位配对码") },
                keyboardOptions = keyboard,
                keyboardActions = actions,
            )
        } else {
            MiuixTextField(
                value = code,
                onValueChange = update,
                modifier = field,
                enabled = !busy,
                singleLine = true,
                label = "六位配对码",
                keyboardOptions = keyboard,
                keyboardActions = actions,
            )
        }
        AppButton("确认配对", submit, enabled = ready)
    }
    LaunchedEffect(requester) { requester.requestFocus() }
}
