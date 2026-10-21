package io.github.dohex.openrose.bridge

/** Explicit framework scopes. Runtime process names are signed, never inferred from package alone. */
internal enum class BridgeTarget(val packageName: String, val componentMatch: String) {
    BLUETOOTH("com.android.bluetooth", "com.android.bluetooth.btservice.AdapterService#onCreate():void"),
    XIAOMI_BLUETOOTH("com.xiaomi.bluetooth", "android.app.Application#attach(android.content.Context):void"),
    MILINK("com.milink.service", "android.app.Application#attach(android.content.Context):void"),
    SYSTEM_SERVER("android", "libxposed#onSystemServerStarting:statusbar"),
    ;

    fun acceptsProcess(process: String): Boolean = when (this) {
        BLUETOOTH -> process == packageName
        SYSTEM_SERVER -> process == "system_server"
        else -> process == packageName || process.startsWith("$packageName:")
    }

    companion object {
        fun resolve(packageName: String?, process: String?): BridgeTarget? = entries.firstOrNull {
            it.packageName == packageName && process != null && it.acceptsProcess(process)
        }
    }
}
