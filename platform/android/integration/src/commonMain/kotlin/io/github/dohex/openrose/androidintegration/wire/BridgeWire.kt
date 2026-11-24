package io.github.dohex.openrose.androidintegration.wire

object BridgeWire {
    const val VERSION = 7
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
    const val CAPTURED_ELAPSED_MILLIS = "captured_elapsed_millis"

    const val PAYLOAD = "payload"

    const val GENERATION = "generation"

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

    const val ACTION_CONTROL_HOST = "io.github.dohex.openrose.action.CONTROL_HOST"
    const val HOST_SERVICE_CLASS = "com.android.bluetooth.agClient.BluetoothAgClientService"

    const val CONFIG_GROUP = "module_config"
    const val CONFIG_REVISION = "config_revision"

    const val FUSION_ADDRESS = "fusion_address"
    const val FUSION_DECISION = "fusion_decision"
    const val FUSION_SYSTEM = "SYSTEM"
    const val FUSION_OPENROSE = "OPENROSE"
    const val ACTION_SHOW_HEADPHONES = "io.github.dohex.openrose.SHOW_HEADPHONES"
    const val EXTRA_HEADPHONE_ADDRESS = "io.github.dohex.openrose.extra.HEADPHONE_ADDRESS"

    const val EXTRA_FALLBACK_REASON = "io.github.dohex.openrose.extra.FALLBACK_REASON"

    const val BONDED_ADDRESSES = "bonded_addresses"
    val RESOLUTIONS = setOf(RESOLUTION_MATCHED, RESOLUTION_CLASS_MISSING, RESOLUTION_METHOD_MISSING, RESOLUTION_HOOK_FAILED)
}
