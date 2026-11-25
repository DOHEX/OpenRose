package io.github.dohex.openrose.framework

import android.util.Log
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.ModuleUnavailableCause
import io.github.dohex.openrose.androidintegration.SystemBridgeDiagnostics
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class FrameworkLink(
    private val onChanged: () -> Unit,
) {
    private val lock = Any()
    private var framework: XposedService? = null
    private val publisher = ModuleSettingsPublisher {
        val service = synchronized(lock) { framework }
        service?.takeIf { it.apiVersion >= 102 }?.getRemotePreferences(BridgeWire.CONFIG_GROUP)
    }

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

    fun canPublish(): Boolean = synchronized(lock) { framework?.apiVersion ?: 0 } >= 102

    fun availability(): ModuleAvailability = synchronized(lock) {
        val service = framework
        when {
            service == null -> ModuleAvailability.Unavailable("尚未收到兼容框架服务，应用控制可独立运行", ModuleUnavailableCause.FRAMEWORK_MISSING)
            service.apiVersion < 102 -> ModuleAvailability.Unavailable("框架服务 API 低于 102", ModuleUnavailableCause.FRAMEWORK_OUTDATED)
            else -> ModuleAvailability.Available
        }
    }

    fun diagnostics(): SystemBridgeDiagnostics = synchronized(lock) {
        val service = framework
        if (service == null) {
            SystemBridgeDiagnostics(detail = "尚未收到兼容框架服务")
        } else {
            SystemBridgeDiagnostics(
                frameworkName = service.frameworkName,
                frameworkVersion = service.frameworkVersion,
                frameworkApi = service.apiVersion,
                detail = "框架服务已连接；模块增强仅在模块控制下运行",
            )
        }
    }

    fun publishConfig(settings: () -> HostSettings): Boolean = runCatching { publisher.publish(settings) }
        .onFailure { Log.w(TAG, "Failed to publish module configuration", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "FrameworkLink"
    }
}
