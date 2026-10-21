package io.github.dohex.openrose.bridge

/** Primitive presentation contract shared by the host and the target-process renderer. */
internal object BridgePresentationWire {
    const val PRESENTATION = "presentation"
    const val MODE = "mode"
    const val OFF = "OFF"
    const val OFFICIAL = "OFFICIAL"
    const val MODULE = "MODULE"
    const val FOCUS_ENABLED = "focus_enabled"
    const val NOTIFICATION = "notification"
    const val TAG = "tag"
    const val ALLOW_ISLAND_FIELDS = "allow_island_fields"
    const val PROMPT = "prompt"
    const val PROMPT_ID = "id"
    const val CLAIMED = "claimed"
    const val DURATION_MILLIS = "duration_millis"
    const val FEATURE = "feature"
    const val DETAIL = "detail"
    const val NOISE_MODE = "noise_mode"
    const val OFFICIAL_CONNECTION = "OFFICIAL_CONNECTION"
    const val MODULE_CONNECTION = "MODULE_CONNECTION"
    const val STATUS_NOTIFICATION = "STATUS_NOTIFICATION"
    const val CALLED = "CALLED"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val WITHDRAWN = "WITHDRAWN"
    const val ACTION_FAILED = "ACTION_FAILED"
    const val SYSTEM_HEADSET = "SYSTEM_HEADSET"
    const val FUSION_CENTER = "FUSION_CENTER"
    const val OFFICIAL_GUARD = "OFFICIAL_GUARD"
    const val OFFICIAL_GUARD_READY = "official_guard_ready"
    val FEATURES = setOf(OFFICIAL_CONNECTION, MODULE_CONNECTION, STATUS_NOTIFICATION, SYSTEM_HEADSET, FUSION_CENTER, OFFICIAL_GUARD)
    val STATUSES = setOf(CALLED, UNAVAILABLE, WITHDRAWN, ACTION_FAILED)
    val COMPONENT_STATUSES = STATUSES + setOf("RESOLVED", "IDENTIFIED", "SUBMITTED", "CONFIRMED", "REJECTED", "UNSUPPORTED")
}
