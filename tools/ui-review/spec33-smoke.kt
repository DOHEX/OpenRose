@file:JvmName("UiSpec33Smoke")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.*
import io.github.dohex.openrose.ui.OpenRoseApp
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

private val profile = ModelProfile.I7_RS_V2
private val syntheticFirmware = FieldValue.Firmware(listOf(3, 0, 4), "3.0.4")
private val deviceId = DeviceId("synthetic-i7")

private fun connectedState(): ControlState {
    val touchBindings = listOf(
        TouchBinding("left", "single", "play_pause", 1),
        TouchBinding("left", "double", "previous_track", 2),
        TouchBinding("left", "triple", "voice_assistant", 3),
        TouchBinding("left", "long", "noise_cycle", 4),
        TouchBinding("right", "single", "play_pause", 1),
        TouchBinding("right", "double", "next_track", 5),
        TouchBinding("right", "triple", "voice_assistant", 3),
        TouchBinding("right", "long", "noise_cycle", 4),
    )
    val knownFields = mapOf(
        DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(86), "56"),
        DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(82), "52"),
        DeviceField.CASE_BATTERY to FieldReading.Unknown("ff"),
        DeviceField.LEFT_CHARGING to FieldReading.Known(FieldValue.Toggle(false), "00"),
        DeviceField.RIGHT_CHARGING to FieldReading.Known(FieldValue.Toggle(false), "00"),
        DeviceField.FIRMWARE to FieldReading.Known(syntheticFirmware, "030004"),
        DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "02"),
        DeviceField.ANC_LEVEL to FieldReading.Known(FieldValue.Number(3), "03"),
        DeviceField.TRANSPARENCY_LEVEL to FieldReading.Known(FieldValue.Number(3), "03"),
        DeviceField.NOISE_CYCLE to FieldReading.Known(
            FieldValue.NoiseCycle(mapOf("normal" to true, "anc" to true, "transparency" to false, "wind" to true)),
            "0b",
        ),
        DeviceField.EQ to FieldReading.Known(FieldValue.Text("hifi"), "01"),
        DeviceField.EQ_SLOT to FieldReading.Known(FieldValue.Number(1), "01"),
        DeviceField.CUSTOM_EQ to FieldReading.Known(
            FieldValue.IntegerList(listOf(0, 1, 2, 1, 0, -1, -2, -1, 0, 1)),
            "0001020100fffeff0001",
        ),
        DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00"),
        DeviceField.AUDIO_MODE to FieldReading.Known(FieldValue.Text("aac_sbc"), "00"),
        DeviceField.MULTIPOINT to FieldReading.Known(FieldValue.Toggle(true), "01"),
        DeviceField.TOUCH_ENABLED to FieldReading.Known(FieldValue.Toggle(true), "01"),
        DeviceField.EAR_DETECTION to FieldReading.Known(FieldValue.Toggle(true), "01"),
        DeviceField.TOUCH_KEYS to FieldReading.Known(FieldValue.TouchKeys(touchBindings), "synthetic"),
        DeviceField.PROMPT_VOLUME to FieldReading.Known(FieldValue.Number(3), "03"),
        DeviceField.GAIN to FieldReading.Known(FieldValue.Number(1), "01"),
        DeviceField.HEARING_PROTECTION to FieldReading.Known(FieldValue.Number(3), "03"),
    )
    val capabilities = DeviceCapabilities(
        readableFields = knownFields.keys,
        writableSettings = setOf(
            DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL,
            DeviceSetting.NOISE_CYCLE, DeviceSetting.EQ, DeviceSetting.GAME_MODE, DeviceSetting.AUDIO_MODE,
            DeviceSetting.MULTIPOINT, DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION,
            DeviceSetting.TOUCH_BINDING, DeviceSetting.PROMPT_VOLUME, DeviceSetting.CUSTOM_EQ,
            DeviceSetting.GAIN, DeviceSetting.HEARING_PROTECTION,
        ),
        noiseControl = noiseControlCapabilities(profile, syntheticFirmware),
        equalizer = equalizerCapabilities(profile, syntheticFirmware),
        touchControl = touchControlCapabilities(profile),
        audioControl = audioControlCapabilities(profile),
        promptVolumeLevels = promptVolumeLevels(profile, syntheticFirmware),
        gainLevels = gainLevels(profile, syntheticFirmware),
        hearingProtectionLevels = hearingProtectionLevels(profile, syntheticFirmware),
    )
    val device = DeviceState(
        deviceId = deviceId,
        sessionId = 41L,
        profile = profile,
        capabilities = capabilities,
        fields = knownFields,
        rawNotifications = listOf("合成记录：仅供界面预览"),
    )
    val platform = PlatformState(
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        controlTransportApplicability = PlatformApplicability.APPLICABLE,
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        module = ModuleAvailability.NotEnabled,
    )
    return ControlState(
        platform = platform,
        connection = ConnectionState.Connected(deviceId, device.sessionId, profile, ConnectionPhase.READY),
        settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.LIGHT),
        device = device,
        transportSelection = TransportSelection(
            endpoint = TransportEndpoint.BleGatt(profile.gatt.serviceUuid, profile.gatt.writeUuid, profile.gatt.notifyUuid),
            reason = TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE,
        ),
    )
}

private fun disconnectedScanState(): ControlState {
    val platform = PlatformState(
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        controlTransportApplicability = PlatformApplicability.APPLICABLE,
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        module = ModuleAvailability.NotEnabled,
    )
    val candidates = listOf(
        DeviceCandidate(DeviceId("synthetic-nearby-i7"), "ROSE i7", -42, ModelProfile.I7_RS_V2, "e5b8290f"),
        DeviceCandidate(DeviceId("synthetic-nearby-i5"), "ROSE i5", -58, ModelProfile.I5_ZT, "525300010001"),
        DeviceCandidate(DeviceId("synthetic-nearby-unknown"), "Bluetooth device", -67, null, "unknown"),
    )
    return ControlState(
        platform = platform,
        connection = ConnectionState.Disconnected,
        settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.LIGHT),
        scan = ScanState.Scanning,
        candidates = candidates,
    )
}

private fun disconnectedState(): ControlState = ControlState(
    platform = PlatformState(
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        controlTransportApplicability = PlatformApplicability.APPLICABLE,
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        module = ModuleAvailability.NotEnabled,
    ),
    settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.LIGHT),
)

private data class SmokeCallbackCapture(
    val settingsChanges: MutableList<UserSettings> = mutableListOf(),
    val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>> = mutableListOf(),
    val disconnects: MutableList<Unit> = mutableListOf(),
)

private fun createScene(
    width: Int,
    height: Int,
    initialState: ControlState,
    coroutineContext: CoroutineContext,
    callbackCapture: SmokeCallbackCapture? = null,
): ImageComposeScene {
    var state by mutableStateOf(initialState)
    return ImageComposeScene(width, height, density = Density(1f), coroutineContext = coroutineContext) {
        OpenRoseApp(
            state = state,
            onSettingsChange = {
                state = state.copy(settings = it)
                callbackCapture?.settingsChanges?.add(it)
            },
            onRequestPermission = {},
            onOpenSystemSettings = {},
            onStartScan = { state = state.copy(scan = ScanState.Scanning) },
            onStopScan = { state = state.copy(scan = ScanState.Idle) },
            onConnect = {},
            onDisconnect = { callbackCapture?.disconnects?.add(Unit) },
            onSet = { setting, value -> callbackCapture?.settingWrites?.add(setting to value) },
        )
    }
}

private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
}

private fun nodeText(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

private fun nodeDescription(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

private fun semanticClick(scene: ImageComposeScene, label: String, exact: Boolean = false) {
    val node = nodes(scene)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .filter {
            if (exact) nodeText(it) == label || nodeDescription(it) == label
            else nodeText(it).contains(label) || nodeDescription(it).contains(label)
        }
        .minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        ?: error("No semantic click target for '$label'. Nodes: " + nodes(scene).joinToString(" | ") {
            "text=${nodeText(it)} description=${nodeDescription(it)} actions=${it.config.contains(SemanticsActions.OnClick)}"
        })
    val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
        ?: error("Semantic click action disappeared for '$label'")
    check(action()) { "Semantic click was not handled for '$label'" }
}

private fun semanticClickExact(scene: ImageComposeScene, label: String) = semanticClick(scene, label, exact = true)

private fun semanticClickTab(scene: ImageComposeScene, label: String) {
    val node = nodes(scene).firstOrNull {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            nodeText(it) == label
    } ?: error("No semantic tab '$label'. Nodes: " + nodes(scene).joinToString(" | ") {
        "text=${nodeText(it)} role=${it.config.getOrNull(SemanticsProperties.Role)} click=${it.config.contains(SemanticsActions.OnClick)}"
    })
    val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
        ?: error("Semantic tab click action disappeared for '$label'")
    check(action()) { "Semantic tab click was not handled for '$label'" }
}

private fun styleName(style: InterfaceStyle) = when (style) {
    InterfaceStyle.MIUIX -> "miuix"
    InterfaceStyle.MATERIAL3 -> "material3"
}

private fun appearanceName(appearance: Appearance) = when (appearance) {
    Appearance.LIGHT -> "light"
    Appearance.DARK -> "dark"
    Appearance.SYSTEM -> "system"
}

fun main() = runBlocking {
    val output = File(System.getenv("OPENROSE_UI_OUTPUT") ?: "output/ui-review/2026-10-27/spec-33").apply { mkdirs() }
    var frameTime = 0L
    suspend fun settle(scene: ImageComposeScene) {
        repeat(5) { delay(35); scene.render(frameTime).close(); frameTime += 35_000_000L }
    }
    suspend fun capture(scene: ImageComposeScene, filename: String) {
        settle(scene)
        scene.render(frameTime).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(output, filename).writeBytes(it.bytes) }
        }
        frameTime += 35_000_000L
        File(output, filename.removeSuffix(".png") + ".txt").writeText(nodes(scene).joinToString("\n") {
            "${nodeText(it)} | ${nodeDescription(it)} | ${it.boundsInRoot}"
        })
    }
    for (style in InterfaceStyle.entries) {
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            val state = connectedState().copy(settings = UserSettings(interfaceStyle = style, appearance = appearance))
            val scene = createScene(420, 900, state, coroutineContext)
            try {
                val key = "${styleName(style)}-${appearanceName(appearance)}"
                capture(scene, "$key-headphones-420x900.png")
                semanticClickTab(scene, "系统")
                capture(scene, "$key-system-420x900.png")
                semanticClickTab(scene, "设置")
                capture(scene, "$key-settings-420x900.png")
            } finally { scene.close() }
        }
        val wide = createScene(1280, 900, connectedState().copy(settings = UserSettings(interfaceStyle = style, appearance = Appearance.LIGHT)), coroutineContext)
        try { capture(wide, "${styleName(style)}-light-headphones-1280x900.png") } finally { wide.close() }
        val disconnected = createScene(360, 900, disconnectedScanState().copy(settings = UserSettings(interfaceStyle = style, appearance = Appearance.LIGHT)), coroutineContext)
        try { capture(disconnected, "${styleName(style)}-light-nearby-360x900.png") } finally { disconnected.close() }
    }
    for (style in InterfaceStyle.entries) {
        val settings = UserSettings(interfaceStyle = style, appearance = Appearance.LIGHT)
        for (host in listOf(HostPlatform.ANDROID, HostPlatform.WINDOWS, HostPlatform.LINUX)) {
            val platform = PlatformState(
                permission = if (host == HostPlatform.ANDROID) PermissionState.GRANTED else PermissionState.NOT_REQUIRED,
                hostPlatform = host,
                systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                controlTransportApplicability = if (host == HostPlatform.ANDROID) PlatformApplicability.APPLICABLE else PlatformApplicability.NOT_APPLICABLE,
                transport = if (host == HostPlatform.ANDROID) TransportAvailability.Available else TransportAvailability.Unavailable("本平台蓝牙控制传输尚未实现"),
            )
            val scene = createScene(420, 900, ControlState(platform = platform, settings = settings), coroutineContext)
            try {
                settle(scene)
                semanticClickTab(scene, "系统")
                capture(scene, "${styleName(style)}-${host.name.lowercase()}-system-420x900.png")
                semanticClickTab(scene, "设置")
                capture(scene, "${styleName(style)}-${host.name.lowercase()}-settings-420x900.png")
            } finally { scene.close() }
        }
        for (width in listOf(679, 680, 899, 900)) {
            val scene = createScene(width, 900, connectedState().copy(settings = settings), coroutineContext)
            try { capture(scene, "${styleName(style)}-boundary-${width}x900.png") } finally { scene.close() }
        }
    }
    if (System.getenv("OPENROSE_UI_KEYBOARD") == "1") {
        val keyboardRows = mutableListOf("style\tcheck\tresult")
        fun focused(scene: ImageComposeScene): SemanticsNode? = nodes(scene).lastOrNull {
            it.config.getOrNull(SemanticsProperties.Focused) == true
        }
        fun focusLabel(node: SemanticsNode?): String = node?.let { nodeText(it) + " " + nodeDescription(it) }.orEmpty()
        fun focusedTargetVisible(scene: ImageComposeScene, width: Int, height: Int): Boolean {
            val bounds = focused(scene)?.boundsInRoot ?: return false
            return bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width.toFloat() && bounds.bottom <= height.toFloat()
        }
        fun key(scene: ImageComposeScene, key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
        }
        suspend fun focus(scene: ImageComposeScene, label: String) {
            repeat(100) {
                if (focusLabel(focused(scene)).contains(label)) return
                key(scene, Key.Tab); settle(scene)
            }
            error("Could not focus $label: ${focusLabel(focused(scene))}")
        }
        for (style in InterfaceStyle.entries) {
            val callbacks = SmokeCallbackCapture()
            val scene = createScene(
                420,
                900,
                connectedState().copy(settings = UserSettings(interfaceStyle = style, appearance = Appearance.LIGHT)),
                coroutineContext,
                callbacks,
            )
            try {
                settle(scene)
                focus(scene, "耳机菜单")
                val first = focusLabel(focused(scene))
                check(focusedTargetVisible(scene, 420, 900)) { "Tab focus target is not visible after settle in $style: $first" }
                key(scene, Key.Tab); settle(scene)
                val second = focusLabel(focused(scene))
                check(focusedTargetVisible(scene, 420, 900)) { "Second Tab focus target is not visible after settle in $style: $second" }
                key(scene, Key.Tab, shift = true); settle(scene)
                check(focusLabel(focused(scene)) == first && second != first) { "Shift+Tab did not reverse traversal in $style" }
                check(focusedTargetVisible(scene, 420, 900)) { "Shift+Tab focus target is not visible after settle in $style: ${focusLabel(focused(scene))}" }
                keyboardRows += "$style\tTab/Shift+Tab\tpassed"
                key(scene, Key.Enter); settle(scene)
                check(nodes(scene).any { nodeText(it).contains("断开控制连接") }) { "Enter did not open headphones menu in $style" }
                capture(scene, "${styleName(style)}-keyboard-menu-420x900.png")
                key(scene, Key.Escape); settle(scene)
                capture(scene, "${styleName(style)}-keyboard-menu-after-escape-420x900.png")
                File(output, "${styleName(style)}-keyboard-menu-after-escape.txt").writeText(
                    nodes(scene).filter { nodeText(it).contains("断开控制连接") }.joinToString("\n") {
                        "${nodeText(it)} | ${nodeDescription(it)} | ${it.boundsInRoot}"
                    },
                )
                val menuClosed = nodes(scene).none { nodeText(it).contains("断开控制连接") }
                if (!menuClosed && System.getenv("OPENROSE_UI_CONTINUE_ON_MENU_ESCAPE_FAILURE") != "1") {
                    error("Escape did not close headphones menu in $style")
                }
                keyboardRows += "$style\tEnter/Escape menu\t${if (menuClosed) "passed" else "failed: still visible after Escape"}"
                semanticClick(scene, "设备详情"); settle(scene)
                check(nodes(scene).any { nodeText(it) == "设备状态" })
                key(scene, Key.Escape); settle(scene)
                check(nodes(scene).none { nodeText(it) == "设备状态" }) { "Escape did not return from details in $style" }
                keyboardRows += "$style\tEscape page\tpassed"
                semanticClickTab(scene, "设置"); settle(scene)
                focus(scene, "强制使用 BLE")
                val beforeBleCallbackCount = callbacks.settingsChanges.size
                key(scene, Key.Spacebar); settle(scene)
                check(callbacks.settingsChanges.size == beforeBleCallbackCount + 1 && callbacks.settingsChanges.last().forceBle) {
                    "Space did not toggle the BLE preference callback in $style: callbacks=${callbacks.settingsChanges.drop(beforeBleCallbackCount).map { it.forceBle }} focus=${focusLabel(focused(scene))}"
                }
                keyboardRows += "$style\tSpace switch\tpassed"
                if (style == InterfaceStyle.MATERIAL3) {
                    val callbackCount = callbacks.settingsChanges.size
                    focus(scene, "超级岛")
                    key(scene, Key.Enter); settle(scene)
                    focus(scene, "模块")
                    check(nodes(scene).any {
                        nodeText(it).contains("模块") && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
                    }) { "Enter target was not exposed as an island-mode radio option" }
                    key(scene, Key.Enter); settle(scene)
                    check(callbacks.settingsChanges.size == callbackCount + 1) {
                        "Enter radio selection did not call onSettingsChange exactly once"
                    }
                    check(callbacks.settingsChanges.last().islandMode == IslandMode.MODULE) {
                        "Enter radio selection did not request module island mode"
                    }
                    check(callbacks.settingWrites.isEmpty() && callbacks.disconnects.isEmpty()) {
                        "Island preference selection sent a device command or disconnected"
                    }
                    keyboardRows += "$style\tEnter radio callback only\tpassed"
                }
                val settingWriteCount = callbacks.settingWrites.size
                val settingsChangeCount = callbacks.settingsChanges.size
                semanticClickTab(scene, "耳机"); settle(scene)
                semanticClick(scene, "更多降噪设置"); settle(scene)
                focus(scene, "标准")
                key(scene, Key.Enter); settle(scene)
                check(callbacks.settingWrites.size == settingWriteCount + 1) {
                    "Enter on the noise-mode radio did not call onSet exactly once in $style"
                }
                check(callbacks.settingWrites.last() == (DeviceSetting.NOISE_MODE to FieldValue.Text("normal"))) {
                    "Enter on 标准 did not request the normal noise mode in $style"
                }
                check(callbacks.settingsChanges.size == settingsChangeCount && callbacks.disconnects.isEmpty()) {
                    "Noise-mode selection changed preferences or disconnected in $style"
                }
                check(nodes(scene).any { nodeText(it).contains("设备已确认：主动降噪") }) {
                    "Noise-mode selection changed the displayed confirmed value before device confirmation in $style"
                }
                keyboardRows += "$style\tEnter noise radio callback only\tpassed"
            } finally { scene.close() }
        }
        File(output, "keyboard.tsv").writeText(keyboardRows.joinToString("\n") + "\n")
    }
    println("Rendered spec #33 matrix to ${output.absolutePath}")
}
