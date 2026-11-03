package io.github.dohex.openrose.bridge

import android.util.Log
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.ModuleUnavailableCause
import io.github.dohex.openrose.core.SystemBridgeDiagnostics
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * The application's side of the Xposed framework service: the only way to write the persisted intent
 * the module host reads (remote preferences are read-only inside the host). It carries user intent and
 * display preferences only; it carries no credential, and the host does not depend on it being alive.
 */
class FrameworkLink(
    private val onChanged: () -> Unit,
) {
    private val lock = Any()
    private var framework: XposedService? = null
    private val publisher = SerializedPublisher(::commitConfig)

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
            service == null -> ModuleAvailability.Unavailable("尚未收到兼容框架服务，普通控制可独立运行", ModuleUnavailableCause.FRAMEWORK_MISSING)
            service.apiVersion < 102 -> ModuleAvailability.Unavailable("框架服务 API 低于 102", ModuleUnavailableCause.FRAMEWORK_OUTDATED)
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
     * Persists the settings, including the saved control way, where the host reads them. [settings] is
     * evaluated while the single writer is held, so it must read the newest persisted truth at that
     * moment (the saved control way included) and a late caller can never write an older snapshot over a
     * newer one. Returns whether the framework acknowledged the write, which is the only time the host
     * can be said to know it.
     */
    fun publishConfig(settings: () -> UserSettings): Boolean = publisher.publish(settings)

    private fun commitConfig(settings: UserSettings): Boolean {
        val service = synchronized(lock) { framework } ?: return false
        if (service.apiVersion < 102) return false
        return runCatching {
            val editor = service.getRemotePreferences(BridgeWire.CONFIG_GROUP).edit()
            UserSettingsCodec.encode(settings).forEach { (key, value) -> editor.putString(key, value) }
            editor.putString(BridgeWire.CONFIG_REVISION, System.nanoTime().toString())
            editor.commit()
        }.onFailure { Log.w(TAG, "Failed to publish module configuration", it) }.getOrDefault(false)
    }

    private companion object { const val TAG = "FrameworkLink" }
}
