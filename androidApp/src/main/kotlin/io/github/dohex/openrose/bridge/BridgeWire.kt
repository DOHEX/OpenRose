package io.github.dohex.openrose.bridge

import android.os.Bundle

/**
 * Versioned wire constants for the module host's authenticated endpoint. There is no shared secret:
 * the host authorises each caller from the Binder uid and package, so nothing here is a credential.
 * Protocol commands and device state travel as the core module's rendered wire text in [PAYLOAD].
 */
internal object BridgeWire {
    const val VERSION = 4
    const val KEY_VERSION = "version"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val PACKAGE = "package"
    const val PROCESS = "process"
    const val COMPONENT_MATCH_KEY = "component_match"
    const val RESOLUTION = "resolution"
    const val LEASE_ID = "lease_id"
    const val SNAPSHOT = "snapshot"
    const val DEVICE_ID = "device_id"
    const val SESSION_ID = "session_id"
    const val READ_SEQUENCE = "read_sequence"
    /** Rendered core wire text: a command, request, result, status or frame. */
    const val PAYLOAD = "payload"
    /** The owner generation (incarnation#sequence) a request was issued for. */
    const val GENERATION = "generation"
    /** On `open` replies: the host incarnation. On every session-scoped request/reply: the owner generation. */
    const val HOST_EPOCH = "host_epoch"
    const val STATUS_OK = "OK"
    const val STATUS_NO_SESSION = "NO_SESSION"
    const val STATUS_TARGET_INCOMPATIBLE = "TARGET_INCOMPATIBLE"
    const val STATUS_STALE_REPORT = "STALE_REPORT"
    const val STATUS_STALE_HOST = "STALE_HOST"
    const val STATUS_STALE_SESSION = "STALE_SESSION"
    const val STATUS_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val STATUS_INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val STATUS_NO_PENDING_READ = "NO_PENDING_READ"
    const val STATUS_STALE_RECEIPT = "STALE_RECEIPT"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_ACTION_FAILED = "ACTION_FAILED"
    const val STATUS_UNAUTHORIZED = "UNAUTHORIZED"
    const val RESOLUTION_MATCHED = "MATCHED"
    const val RESOLUTION_CLASS_MISSING = "CLASS_MISSING"
    const val RESOLUTION_METHOD_MISSING = "METHOD_MISSING"
    const val RESOLUTION_HOOK_FAILED = "HOOK_FAILED"
    const val APP_PACKAGE = "io.github.dohex.openrose"
    const val TARGET_PACKAGE = "com.android.bluetooth"
    const val TARGET_PROCESS = TARGET_PACKAGE
    const val TARGET_COMPONENT_CLASS = "com.android.bluetooth.btservice.AdapterService"
    const val TARGET_COMPONENT_METHOD = "onCreate"
    const val COMPONENT_MATCH = "$TARGET_COMPONENT_CLASS#$TARGET_COMPONENT_METHOD():void"

    /**
     * Discovery: an existing service in the host process hands out the endpoint Binder for this action.
     * Reaching it is not authorisation; [IControlBridge.open] verifies the caller.
     */
    const val ACTION_CONTROL_HOST = "io.github.dohex.openrose.action.CONTROL_HOST"
    const val HOST_SERVICE_CLASS = "com.android.bluetooth.agClient.BluetoothAgClientService"

    /** Settings the application mirrors for the host, in the core settings codec's own keys. */
    const val CONFIG_GROUP = "module_config"
    const val CONFIG_REVISION = "config_revision"
    /** Request: the Bluetooth address the fusion center asked about. Reply: [FUSION_DECISION] and, for OpenRose, [DEVICE_ID]. */
    const val FUSION_ADDRESS = "fusion_address"
    const val FUSION_DECISION = "fusion_decision"
    const val FUSION_SYSTEM = "SYSTEM"
    const val FUSION_OPENROSE = "OPENROSE"
    const val ACTION_SHOW_HEADPHONES = "io.github.dohex.openrose.SHOW_HEADPHONES"
    const val EXTRA_HEADPHONE_ADDRESS = "io.github.dohex.openrose.extra.HEADPHONE_ADDRESS"
    val RESOLUTIONS = setOf(RESOLUTION_MATCHED, RESOLUTION_CLASS_MISSING, RESOLUTION_METHOD_MISSING, RESOLUTION_HOOK_FAILED)

    fun hello(processName: String?, target: BridgeTarget?, resolution: String = RESOLUTION_MATCHED): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        target?.let {
            putString(PACKAGE, it.packageName)
            putString(COMPONENT_MATCH_KEY, it.componentMatch)
        }
        processName?.let { putString(PROCESS, it) }
        putString(RESOLUTION, resolution)
    }

    fun response(status: String, message: String? = null): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(STATUS, status)
        message?.let { putString(MESSAGE, it) }
    }

    fun payload(status: String, text: String): Bundle = response(status).apply { putString(PAYLOAD, text) }
}
