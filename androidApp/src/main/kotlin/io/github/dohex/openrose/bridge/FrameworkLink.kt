package io.github.dohex.openrose.bridge

import android.content.Context
import android.os.UserManager
import android.util.Log
import io.github.dohex.openrose.core.FusionSettingsDestination
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.SystemBridgeDiagnostics
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.integration.NativeHeadsetSnapshot
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONObject

/**
 * The application's side of the Xposed framework service: the only way to write the persisted intent
 * the module host reads (remote preferences are read-only inside the host). It carries user intent and
 * display preferences only; it carries no credential, and the host does not depend on it being alive.
 */
class FrameworkLink(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private val lock = Any()
    private var framework: XposedService? = null

    fun start() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                synchronized(lock) { framework = service }
                onChanged()
            }

            override fun onServiceDied(service: XposedService) {
                synchronized(lock) { if (framework === service) framework = null }
                onChanged()
            }
        })
    }

    /** True when the framework service can currently take persisted module configuration. */
    fun canPublish(): Boolean = synchronized(lock) { framework?.apiVersion ?: 0 } >= 102

    fun availability(): ModuleAvailability = synchronized(lock) {
        val service = framework
        when {
            service == null -> ModuleAvailability.Unavailable("尚未收到兼容框架服务，普通控制可独立运行")
            service.apiVersion < 102 -> ModuleAvailability.Unavailable("框架服务 API 低于 102")
            else -> ModuleAvailability.Available
        }
    }

    fun diagnostics(): SystemBridgeDiagnostics = synchronized(lock) {
        val service = framework
        if (service == null) SystemBridgeDiagnostics(detail = "尚未收到兼容框架服务")
        else SystemBridgeDiagnostics(
            frameworkName = service.frameworkName, frameworkVersion = service.frameworkVersion, frameworkApi = service.apiVersion,
            detail = "框架服务已连接；模块增强仅在模块控制下运行",
        )
    }

    /**
     * Persists the settings, including the saved control way, where the host reads them. Returns whether
     * the framework acknowledged the write, which is the only time the host can be said to know it.
     */
    fun publishConfig(settings: UserSettings): Boolean {
        val service = synchronized(lock) { framework } ?: return false
        if (service.apiVersion < 102) return false
        return runCatching {
            val editor = service.getRemotePreferences(BridgeWire.CONFIG_GROUP).edit()
            UserSettingsCodec.encode(settings).forEach { (key, value) -> editor.putString(key, value) }
            editor.putString(BridgeWire.CONFIG_REVISION, System.nanoTime().toString())
            editor.commit()
        }.onFailure { Log.w(TAG, "Failed to publish module configuration", it) }.getOrDefault(false)
    }

    /** Fusion Device Center auxiliary state, republished from whichever owner's state the application mirrors. */
    fun publishFusion(state: ControlState) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val service = synchronized(lock) { framework } ?: return
        if (service.apiVersion < 102) return
        val destination = state.settings.fusionSettingsDestination
        val mapping = buildDeviceAddressMapping(state)
        val stored = runCatching {
            service.getRemotePreferences(BridgeWire.PREFERENCES_GROUP).edit()
                .putString(BridgeWire.FUSION_SETTINGS_DESTINATION, destination.name)
                .putString(BridgeWire.FUSION_DEVICE_MAPPING, mapping)
                .commit()
        }.getOrDefault(false)
        if (!stored) Log.w(TAG, "RemotePreferences commit returned false for fusion preferences")
    }

    private fun buildDeviceAddressMapping(state: ControlState): String {
        val mapping = mutableMapOf<String, String>()
        fun register(rawAddress: String?, canonicalRaw: String?) {
            val key = NativeHeadsetSnapshot.normalizeBluetoothAddress(rawAddress) ?: return
            val canonical = NativeHeadsetSnapshot.normalizeBluetoothAddress(canonicalRaw) ?: return
            if (!mapping.containsKey(key)) mapping[key] = canonical
        }
        state.device?.let { device ->
            register(device.deviceId.value, device.deviceId.value)
            device.classicAddress?.let { register(it, device.deviceId.value) }
        }
        state.connectionTarget?.takeIf { it.profile != null }?.let { target ->
            register(target.deviceId.value, target.deviceId.value)
            target.classicAddress?.let { register(it, target.deviceId.value) }
        }
        (state.candidates + state.bondedDevices).filter { it.profile != null }.forEach { candidate ->
            register(candidate.deviceId.value, candidate.deviceId.value)
            candidate.classicAddress?.let { register(it, candidate.deviceId.value) }
        }
        val json = JSONObject()
        mapping.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    private companion object { const val TAG = "FrameworkLink" }
}
