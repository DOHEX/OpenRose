package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ui.AndroidPlatformUi
import io.github.dohex.openrose.core.ControlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

internal fun androidTestPlatform(
    integration: MutableStateFlow<IntegrationState>,
    onSettings: (IntegrationSettings) -> Unit = { integration.value = integration.value.copy(settings = it) },
    onSelect: (ControlWay) -> Unit = {},
    onPermission: () -> Unit = {},
    onBluetoothSettings: () -> Unit = {},
    onNotification: () -> Unit = {},
    onAssociation: () -> Unit = {},
    onAppSettings: () -> Unit = {},
    restart: suspend () -> List<ScopeRestartResult> = { emptyList() },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) = AndroidPlatformUi(integration, onSettings, onSelect, onPermission, onBluetoothSettings, onNotification, onAssociation, onAppSettings, restart, scope)

internal open class AndroidPageHarness(
    scene: OpenRoseUiTestScene,
    val page: MutableState<String>,
) : OpenRoseUiTestHarness(scene) {
    fun openPage(label: String) { page.value = label; render() }
}

@Composable
internal fun AndroidTestPage(model: FakeOpenRoseModel, platform: PlatformUi, page: MutableState<String>) {
    val style = model.mutableUiSettings.value.interfaceStyle
    TestPageContent(style) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page.value) {
                "设置" -> PreferencesPage(model, platform)
                "状态" -> StatusPage(model, platform) { page.value = "诊断" }
                "诊断" -> PlatformDiagnosticsCard(model, platform)
                "操作" -> platform.StatusActions()
            }
        }
    }
}
