package io.github.dohex.openrose.presentation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Constructs the official HyperOS connection-toast payload inside com.xiaomi.bluetooth, where its
 * FileProvider can serve media to SystemUI, and calls that process's StatusBarManager directly.
 * Prompt claiming and local session identity reject stale OpenRose work; external strong-toast
 * replacement is not observed. CALLED records a system call, not a displayed island.
 */
internal class OfficialConnectionPresenter(
    context: Context,
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
) : AutoCloseable {
    private val targetContext = context.applicationContext ?: context
    private val assetLock = Any()
    private val ownedAssets = linkedMapOf<Uri, OwnedAsset>()
    private var active: ActiveToast? = null

    @Volatile private var closed = false

    /** Prepares media, then atomically claims the current prompt immediately before submission. */
    fun show(snapshot: Bundle, promptId: String) {
        dispatchOnHandler {
            if (!closed) showOnHandler(Bundle(snapshot), promptId)
        }
    }

    /** Withdraws only the prompt/session currently held by this presenter. */
    fun withdraw(snapshot: Bundle?, promptId: String? = null, reason: String = "prompt_cancelled") {
        val copy = snapshot?.let(::Bundle)
        dispatchOnHandler {
            if (!closed) withdrawOnHandler(copy, promptId, reason)
        }
    }

    fun clear(snapshot: Bundle? = null) = withdraw(snapshot, reason = "presenter_clear")

    override fun close() {
        if (Looper.myLooper() === handler.looper) {
            closeOnHandler()
        } else if (!handler.post { closeOnHandler() }) {
            // The reader looper has already stopped. Release only this presenter's unique files
            // and grants rather than leaving delayed cleanup behind on a dead looper.
            closed = true
            cleanupAllAssets()
        }
    }

    private fun closeOnHandler() {
        if (closed) {
            cleanupAllAssets()
            return
        }
        closed = true
        try {
            withdrawOnHandler(snapshot = null, promptId = null, reason = "presenter_close")
        } finally {
            cleanupAllAssets()
        }
    }

    private fun showOnHandler(snapshot: Bundle, promptId: String) {
        val request = decodeRequest(snapshot, promptId)
        if (request == null) {
            report(snapshot, BridgePresentationWire.UNAVAILABLE, "official_prompt_or_session_not_ready")
            return
        }
        if (targetContext.packageName != TARGET_PACKAGE) {
            report(snapshot, BridgePresentationWire.UNAVAILABLE, "official_target_context_must_be_com_xiaomi_bluetooth")
            return
        }

        val previous = active
        if (previous?.request?.key == request.key) return
        if (previous != null) withdrawActive(previous, "superseded_by_new_openrose_prompt", report = false)

        val token = UUID.randomUUID().toString()
        val clips = try {
            prepareClips(request, token)
        } catch (failure: Throwable) {
            report(
                snapshot,
                BridgePresentationWire.UNAVAILABLE,
                "official_clip_or_provider_probe_failed:${failure.javaClass.simpleName}",
            )
            return
        }

        val payload = buildShowBundle(clips, request)
        if (!callbacks.claimPrompt(snapshot, promptId)) {
            revokeAssets(clips)
            return
        }
        val failure = runCatching { setStatus(payload) }.exceptionOrNull()
        if (failure != null) {
            revokeAssetsAfter(clips, OFFICIAL_DURATION_MILLIS + URI_RELEASE_GRACE_MILLIS)
            report(
                snapshot,
                BridgePresentationWire.UNAVAILABLE,
                "official_set_status_failed:${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}",
            )
            return
        }

        active = activateToast(request, clips, snapshot)
        report(
            snapshot,
            BridgePresentationWire.CALLED,
            "xiaomi_bluetooth_set_status_returned;provider_and_uri_grant_probed;systemui_display_unverified",
        )
    }

    private fun activateToast(
        request: PromptRequest,
        clips: List<PreparedClip>,
        snapshot: Bundle,
    ): ActiveToast {
        val record = ActiveToast(request, SystemClock.elapsedRealtime() + OFFICIAL_DURATION_MILLIS, clips, Bundle(snapshot))
        record.timeout = Runnable {
            if (active === record) {
                // SystemUI owns the display duration. Expiry only releases this prompt's media.
                active = null
                revokeAssetsAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            }
        }
        handler.postDelayed(record.timeout!!, OFFICIAL_DURATION_MILLIS)
        return record
    }

    private fun withdrawOnHandler(snapshot: Bundle?, promptId: String?, reason: String) {
        val record = active ?: return
        if (snapshot != null && record.request.identity != identityOf(snapshot)) return
        if (promptId != null && record.request.promptId != promptId) return
        withdrawActive(record, reason, report = true)
    }

    private fun withdrawActive(record: ActiveToast, reason: String, report: Boolean) {
        if (active !== record) return
        active = null
        record.timeout?.let(handler::removeCallbacks)

        val left = record.expiresAtElapsed - SystemClock.elapsedRealtime()
        if (left <= 0L) {
            revokeAssetsAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            return
        }

        val payload = Bundle().apply {
            putString(KEY_PACKAGE_NAME, TARGET_PACKAGE)
            putString(KEY_STATUS_BAR_STRONG_TOAST, HIDE_STRONG_TOAST)
        }
        // Match OppoPods' immediate hide behavior. Only local OpenRose ownership is known here.
        val failure = runCatching { setStatus(payload) }.exceptionOrNull()
        if (failure == null) {
            revokeAssetsAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            if (report) {
                report(
                    record.snapshot,
                    BridgePresentationWire.WITHDRAWN,
                    "xiaomi_bluetooth_hide_set_status_returned;systemui_withdrawal_unverified:$reason",
                )
            }
        } else {
            revokeAssetsAfter(record.clips, left + URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            if (report) {
                val detail = failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName
                report(
                    record.snapshot,
                    BridgePresentationWire.ACTION_FAILED,
                    "official_hide_failed:$detail:$reason",
                )
            }
        }
    }

    private fun setStatus(payload: Bundle) {
        val service = targetContext.getSystemService("statusbar")
            ?: throw IllegalStateException("statusbar_service_missing")
        service.javaClass.getMethod(
            "setStatus",
            Int::class.javaPrimitiveType,
            String::class.java,
            Bundle::class.java,
        ).invoke(service, 1, "strong_toast_action", payload)
    }

    private fun decodeRequest(snapshot: Bundle, promptId: String): PromptRequest? {
        if (snapshot.getInt(BridgeWire.KEY_VERSION, -1) != BridgeWire.VERSION) return null
        val identity = identityOf(snapshot) ?: return null
        if (snapshot.getString("profile") !in SUPPORTED_PROFILES) return null
        val presentation = snapshot.getBundle(BridgePresentationWire.PRESENTATION) ?: return null
        if (presentation.getString(BridgePresentationWire.MODE) != BridgePresentationWire.OFFICIAL ||
            !presentation.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, false)
        ) {
            return null
        }
        val prompt = presentation.getBundle(BridgePresentationWire.PROMPT) ?: return null
        if (prompt.getString(BridgePresentationWire.PROMPT_ID) != promptId ||
            prompt.getString(BridgePresentationWire.MODE) != BridgePresentationWire.OFFICIAL
        ) {
            return null
        }
        return PromptRequest(
            identity = identity,
            promptId = promptId,
            leftBattery = battery(snapshot, LEFT_BATTERY),
            rightBattery = battery(snapshot, RIGHT_BATTERY),
            leftCharging = charging(snapshot, LEFT_CHARGING),
            rightCharging = charging(snapshot, RIGHT_CHARGING),
            key = "${identity.hostEpoch}:${identity.deviceId}:${identity.sessionId}:$promptId",
        )
    }

    private fun identityOf(snapshot: Bundle): SessionIdentity? {
        val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
            .takeIf { it > 0L } ?: return null
        val hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        return SessionIdentity(deviceId, sessionId, hostEpoch)
    }

    private fun battery(snapshot: Bundle, name: String): Int? {
        val fields = snapshot.getBundle("fields") ?: return null
        val reading = fields.getBundle(name) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != NUMBER) return null
        return value.getInt("value", INVALID_BATTERY).takeIf { it in 0..100 }
    }

    private fun charging(snapshot: Bundle, name: String): Boolean? {
        val fields = snapshot.getBundle("fields") ?: return null
        val reading = fields.getBundle(name) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != TOGGLE) return null
        return value.untypedValue("value") as? Boolean
    }

    private fun prepareClips(request: PromptRequest, token: String): List<PreparedClip> {
        val provider = targetContext.packageManager.resolveContentProvider(FILE_PROVIDER_AUTHORITY, 0)
            ?: throw IllegalStateException("target_fileprovider_missing")
        if (provider.packageName != TARGET_PACKAGE) throw SecurityException("target_fileprovider_package_mismatch")
        val moduleResources = runCatching {
            targetContext.createPackageContext(BridgeWire.APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
        val clips = mutableListOf<PreparedClip>()
        try {
            listOf(
                ClipSpec("left", "earphone_left", MODULE_LEFT_RAW),
                ClipSpec("right", "earphone_right", MODULE_RIGHT_RAW),
            ).forEach { spec ->
                // Prefer OpenRose's neutral, self-owned video. Never select in-ear or no-in-ear
                // footage from connection state; this wire has no wear observation.
                val resource = moduleResources?.let { findRawResource(it, spec.moduleRawName) }
                    ?: findRawResource(targetContext, spec.targetRawName)
                    ?: throw IllegalStateException("neutral_${spec.side}_clip_missing")
                val name = "openrose_official_${stableDigest("${request.key}:$token:${spec.side}").take(24)}_${spec.side}.mp4"
                val file = File(targetContext.filesDir, name)
                copyRawResource(resource.context, resource.resourceId, file)
                val uri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/internal_files/$name")
                if (uri.authority != FILE_PROVIDER_AUTHORITY || uri.scheme != "content") {
                    file.delete()
                    throw IllegalStateException("target_provider_uri_invalid")
                }
                val clip = PreparedClip(file, uri)
                clips += clip
                synchronized(assetLock) { ownedAssets[uri] = OwnedAsset(clip) }
                // Verify the OEM provider path and video track using the actual target package UID.
                probeProviderVideo(uri)
                targetContext.grantUriPermission(SYSTEM_UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return clips
        } catch (failure: Throwable) {
            revokeAssets(clips)
            throw failure
        }
    }

    private fun findRawResource(context: Context, name: String): RawResource? {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        return id.takeIf { it != 0 }?.let { RawResource(context, it) }
    }

    private fun copyRawResource(context: Context, resourceId: Int, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            context.resources.openRawResource(resourceId).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!temporary.isFile || temporary.length() <= 0L) throw IllegalStateException("empty_official_clip")
            if (!temporary.renameTo(target)) throw IllegalStateException("official_clip_copy_commit_failed")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun probeProviderVideo(uri: Uri) {
        targetContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length == 0L) throw IllegalStateException("target_provider_empty_clip")
        } ?: throw IllegalStateException("target_provider_open_failed")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(targetContext, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw IllegalStateException("target_clip_has_no_video_track")
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) throw IllegalStateException("target_clip_dimensions_unavailable")
        } finally {
            retriever.release()
        }
    }

    private fun buildShowBundle(clips: List<PreparedClip>, request: PromptRequest): Bundle {
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

    private fun report(snapshot: Bundle, status: String, detail: String) {
        runCatching {
            callbacks.report(Bundle(snapshot), BridgePresentationWire.OFFICIAL_CONNECTION, status, detail)
        }.onFailure { Log.w(TAG, "official_connection_report_failed status=$status", it) }
    }

    private fun revokeAssets(clips: List<PreparedClip>) {
        val callbacks = synchronized(assetLock) {
            val removedCallbacks = clips.mapNotNull { ownedAssets.remove(it.uri)?.cleanup }.distinct()
            val remainingCallbacks = ownedAssets.values.mapNotNull { it.cleanup }.toSet()
            removedCallbacks.filterNot { it in remainingCallbacks }
        }
        callbacks.forEach(handler::removeCallbacks)
        releaseAssets(clips)
    }

    private fun releaseAssets(clips: List<PreparedClip>) {
        clips.forEach { clip ->
            runCatching { targetContext.revokeUriPermission(clip.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { clip.file.delete() }
        }
    }

    private fun revokeAssetsAfter(
        clips: List<PreparedClip>,
        delayMillis: Long,
        preserveReadGrace: Boolean = false,
    ) {
        val delay = delayMillis.coerceAtLeast(0L)
        val assets = synchronized(assetLock) { clips.mapNotNull { ownedAssets[it.uri] }.distinct() }
        if (assets.isEmpty()) return

        val deadline = SystemClock.elapsedRealtime() + delay
        val cleanup = object : Runnable {
            override fun run() {
                val releasable = synchronized(assetLock) {
                    assets.filter { asset ->
                        ownedAssets[asset.clip.uri] === asset && asset.cleanup === this
                    }.onEach { asset -> asset.cleanup = null }
                }
                revokeAssets(releasable.map { it.clip })
            }
        }
        synchronized(assetLock) {
            assets.forEach { asset ->
                asset.cleanup?.let(handler::removeCallbacks)
                asset.cleanup = cleanup
                if (preserveReadGrace) {
                    asset.readGraceDeadlineElapsed = maxOf(asset.readGraceDeadlineElapsed, deadline)
                }
            }
        }
        if (!handler.postDelayed(cleanup, delay)) {
            synchronized(assetLock) {
                assets.forEach { asset -> if (asset.cleanup === cleanup) asset.cleanup = null }
            }
            revokeAssets(assets.map { it.clip })
        }
    }

    /** Cancels this presenter's delayed closures and releases only its tracked media. */
    private fun cleanupAllAssets() {
        val (assets, callbacks, graceDeadline) = synchronized(assetLock) {
            val current = ownedAssets.values.toList()
            val pending = current.mapNotNull { it.cleanup }.distinct()
            val grace = current.maxOfOrNull { it.readGraceDeadlineElapsed } ?: 0L
            ownedAssets.clear()
            Triple(current, pending, grace)
        }
        callbacks.forEach(handler::removeCallbacks)
        val remainingGrace = (graceDeadline - SystemClock.elapsedRealtime())
            .coerceIn(0L, URI_RELEASE_GRACE_MILLIS)
        if (remainingGrace > 0L) SystemClock.sleep(remainingGrace)
        releaseAssets(assets.map { it.clip })
    }

    private fun dispatchOnHandler(block: () -> Unit) {
        if (Looper.myLooper() === handler.looper) block() else handler.post(block)
    }

    private fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SessionIdentity(val deviceId: String, val sessionId: Long, val hostEpoch: String)
    private data class PromptRequest(
        val identity: SessionIdentity,
        val promptId: String,
        val leftBattery: Int?,
        val rightBattery: Int?,
        val leftCharging: Boolean?,
        val rightCharging: Boolean?,
        val key: String,
    )
    private data class ClipSpec(val side: String, val targetRawName: String, val moduleRawName: String)
    private data class RawResource(val context: Context, val resourceId: Int)
    private data class PreparedClip(val file: File, val uri: Uri)
    private data class OwnedAsset(
        val clip: PreparedClip,
        var cleanup: Runnable? = null,
        var readGraceDeadlineElapsed: Long = 0L,
    )
    private data class ActiveToast(
        val request: PromptRequest,
        val expiresAtElapsed: Long,
        val clips: List<PreparedClip>,
        val snapshot: Bundle,
        var timeout: Runnable? = null,
    )

    companion object {
        private const val TAG = "OpenRoseOfficialToast"
        private const val TARGET_PACKAGE = "com.xiaomi.bluetooth"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val FILE_PROVIDER_AUTHORITY = "com.xiaomi.bluetooth.fileprovider"
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
        private const val KNOWN = "KNOWN"
        private const val NUMBER = "NUMBER"
        private const val TOGGLE = "TOGGLE"
        private const val INVALID_SESSION_ID = Long.MIN_VALUE
        private const val INVALID_BATTERY = -1
        private const val LOW_BATTERY_THRESHOLD = 20
        private const val OFFICIAL_DURATION_MILLIS = 5_000L
        private const val URI_RELEASE_GRACE_MILLIS = 750L
        private const val MODULE_LEFT_RAW = "openrose_earbud_left"
        private const val MODULE_RIGHT_RAW = "openrose_earbud_right"
        private val SUPPORTED_PROFILES = setOf("I5_ZT", "I7_RS_V2")
    }
}

// The wire value is untyped; the typed Bundle.get(String, Class) needs API 33.
@Suppress("DEPRECATION")
private fun Bundle.untypedValue(key: String): Any? = get(key)
