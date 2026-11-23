package io.github.dohex.openrose.module.presentation

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.core.protocol.ModelProfile
import org.json.JSONObject

internal fun Context.setOfficialStatus(payload: Bundle) {
    val service = getSystemService("statusbar")
        ?: throw IllegalStateException("statusbar_service_missing")
    service.javaClass.getMethod(
        "setStatus",
        Int::class.javaPrimitiveType,
        String::class.java,
        Bundle::class.java,
    ).invoke(service, 1, "strong_toast_action", payload)
}

/** The caller has already admitted official island mode and read [promptId] from this snapshot's prompt. */
internal fun decodeRequest(snapshot: Bundle, promptId: String): PromptRequest? {
    if (snapshot.getInt(BridgeWire.KEY_VERSION, -1) != BridgeWire.VERSION) return null
    val identity = identityOf(snapshot) ?: return null
    if (ModelProfile.fromName(snapshot.getString("profile")) == null) return null
    val prompt = snapshot.getBundle(BridgePresentationWire.PRESENTATION)?.getBundle(BridgePresentationWire.PROMPT) ?: return null
    if (prompt.getString(BridgePresentationWire.MODE) != BridgePresentationWire.OFFICIAL) return null
    val fields = snapshot.getBundle("fields")
    return PromptRequest(
        identity = identity,
        promptId = promptId,
        leftBattery = fields?.knownBattery(LEFT_BATTERY),
        rightBattery = fields?.knownBattery(RIGHT_BATTERY),
        leftCharging = fields?.knownCharging(LEFT_CHARGING),
        rightCharging = fields?.knownCharging(RIGHT_CHARGING),
        key = "${identity.stableKey}:$promptId",
    )
}

internal fun identityOf(snapshot: Bundle): SessionIdentity? = sessionIdentity(snapshot)?.takeIf { it.sessionId > 0L }

internal fun buildShowBundle(clips: List<PreparedClip>, request: PromptRequest): Bundle {
    val param = JSONObject()
        .put("left", sidePayload(clips[0], request.leftBattery, request.leftCharging, island = false))
        .put("right", sidePayload(clips[1], request.rightBattery, request.rightCharging, island = false))
        .toString()
    val islandParam = JSONObject()
        .put("left", sidePayload(clips[0], request.leftBattery, request.leftCharging, island = true))
        .put("right", sidePayload(clips[1], request.rightBattery, request.rightCharging, island = true))
        .toString()
    return Bundle().apply {
        putString(KEY_PACKAGE_NAME, TARGET_PACKAGE)
        putString(KEY_STRONG_TOAST_CATEGORY, VIDEO_TEXT_TEXT_VIDEO)
        putParcelable(KEY_TARGET, null as android.os.Parcelable?)
        putString(KEY_PARAM, param)
        putString(KEY_ISLAND_PARAM, islandParam)
        putString(KEY_NOTIFY_ID, HEADSET_NOTIFY_ID)
        putLong(KEY_DURATION, OFFICIAL_DURATION_MILLIS)
        putFloat(KEY_LEVEL, 0f)
        putFloat(KEY_RAPID_RATE, 0f)
        putString(KEY_CHARGE, null)
        putInt(KEY_CHARGE_FLAG, 0)
        putString(KEY_STATUS_BAR_STRONG_TOAST, SHOW_CUSTOM_STRONG_TOAST)
    }
}

private fun sidePayload(clip: PreparedClip, battery: Int?, charging: Boolean?, island: Boolean): JSONObject {
    val icon = JSONObject()
        .put("category", RAW_CATEGORY)
        .put("fileType", MP4_FILE_TYPE)
        .put("iconName", clip.uri.toString())
        .put("iconType", if (island) 0 else 1)
    // The island model of the HyperOS SystemUI (17.03.260226) reads `iconFormat`/`iconResName`, not `fileType`/`iconName`;
    // without them toIconInfo logs "Unknown icon type: null" and the whole island is dropped ("Failed to
    // parseDynamicIslandParam"). Older builds ignore the extra keys and keep reading the legacy pair.
    if (island) icon.put("iconFormat", MP4_FILE_TYPE).put("iconResName", clip.uri.toString())
    val side = JSONObject().put("iconParams", icon)
    // Only a confirmed true charging field adds the charging color. Neutral battery text
    // remains useful when charging is unknown and does not claim a false charging flag.
    if (battery != null) {
        val text = JSONObject()
            .put("text", "$battery%")
            .put(
                "textColor",
                when {
                    charging == true -> Color.GREEN
                    battery <= LOW_BATTERY_THRESHOLD -> Color.RED
                    else -> Color.WHITE
                },
            )
        if (island) text.put("turnAnim", true) else text.put("viewFlags", 0)
        side.put("textParams", text)
    }
    return side
}

internal data class PromptRequest(
    val identity: SessionIdentity,
    val promptId: String,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val leftCharging: Boolean?,
    val rightCharging: Boolean?,
    val key: String,
)

internal fun officialHideBundle() = Bundle().apply {
    putString(KEY_PACKAGE_NAME, TARGET_PACKAGE)
    putString(KEY_STATUS_BAR_STRONG_TOAST, HIDE_STRONG_TOAST)
}

internal const val TARGET_PACKAGE = "com.xiaomi.bluetooth"
private const val SHOW_CUSTOM_STRONG_TOAST = "show_custom_strong_toast"
private const val HIDE_STRONG_TOAST = "hide_strong_toast"
private const val VIDEO_TEXT_TEXT_VIDEO = "video_text_text_video"
private const val RAW_CATEGORY = "raw"
private const val MP4_FILE_TYPE = "mp4"
private const val HEADSET_NOTIFY_ID = "headset_wear_notification"
private const val KEY_PACKAGE_NAME = "package_name"
private const val KEY_STRONG_TOAST_CATEGORY = "strong_toast_category"
private const val KEY_TARGET = "target"
private const val KEY_PARAM = "param"
private const val KEY_ISLAND_PARAM = "island_param"
private const val KEY_NOTIFY_ID = "notifyId"
private const val KEY_DURATION = "duration"
private const val KEY_LEVEL = "level"
private const val KEY_RAPID_RATE = "rapid_rate"
private const val KEY_CHARGE = "charge"
private const val KEY_CHARGE_FLAG = "string_toast_charge_flag"
private const val KEY_STATUS_BAR_STRONG_TOAST = "status_bar_strong_toast"
private const val LEFT_BATTERY = "LEFT_BATTERY"
private const val RIGHT_BATTERY = "RIGHT_BATTERY"
private const val LEFT_CHARGING = "LEFT_CHARGING"
private const val RIGHT_CHARGING = "RIGHT_CHARGING"
private const val LOW_BATTERY_THRESHOLD = 20
internal const val OFFICIAL_DURATION_MILLIS = 5_000L
