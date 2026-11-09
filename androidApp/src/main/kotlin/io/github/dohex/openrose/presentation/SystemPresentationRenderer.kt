package io.github.dohex.openrose.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.PathParser
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.protocol.ModelProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Actions go back to the module host's single owner through this entry's authorised lease; nothing is queued or connected here. */
internal interface PresentationCallbacks {
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean
    fun setNoise(snapshot: Bundle, mode: String): Bundle
    fun report(snapshot: Bundle, feature: String, status: String, detail: String)
}

/**
 * Renders the module owner's trusted snapshot in com.android.bluetooth, the host process itself.
 *
 * The status notification is a focus card (HyperOS 3 template "new image-text + button component 1", `iconTextInfo`
 * plus up to two text buttons). It carries a summary-state island only while the snapshot asks for one (module mode
 * with a display timing) and never floats on update, so battery refreshes cannot re-expand it. That summary state is
 * the module prompt's battery template, so while the card is shown a module prompt is the island's entrance, replayed
 * by re-posting the card; without the card, the prompt is its own prompt-scoped notification. The JSON follows
 * HyperNotification 1.4's public V3 model schema (publisher revision c704a4de3e1d069e213508363303f62b5e057526); that
 * schema alone does not prove SystemUI acceptance or actual display.
 */
internal class SystemPresentationRenderer(
    context: Context,
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val actionExecutor = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_ACTIONS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "OpenRose-presentation-action").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val lifecycleLock = Any()

    @Volatile private var closed = false
    private var current: RenderedSnapshot? = null
    private var statusTag: String? = null
    private var statusFingerprint: String? = null
    private var statusNotificationActive = false
    private var actionReceiverRegistered = false
    private var actionReceiverAction: String? = null
    private var actionToken: String? = null
    private var activePrompt: ActivePrompt? = null
    private val processedPrompts = mutableSetOf<String>()
    private val pendingActions = mutableSetOf<String>()
    private val reportedStatus = mutableMapOf<String, String>()
    private var statusIslandPosted = false

    /** A merged module prompt the status notification has yet to show by (re)posting itself. */
    private var promptAwaitingStatus = false

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            val token = intent.getStringExtra(EXTRA_ACTION_TOKEN)
            val kind = intent.getStringExtra(EXTRA_ACTION_KIND)
            val deviceId = intent.getStringExtra(BridgeWire.DEVICE_ID)
            val hostEpoch = intent.getStringExtra(BridgeWire.HOST_EPOCH)
            val sender = if (deviceId != null && hostEpoch != null) {
                SessionIdentity(deviceId, intent.getLongExtra(BridgeWire.SESSION_ID, INVALID_SESSION_ID), hostEpoch)
            } else {
                null
            }
            val targetMode = intent.getStringExtra(BridgePresentationWire.NOISE_MODE)
            handler.post {
                if (closed || sender == null) return@post
                if (action == null || action != actionReceiverAction) return@post
                if (token == null || token != actionToken) return@post
                val active = current?.takeIf { it.identity == sender } ?: return@post
                if (!active.focusEnabled || !statusNotificationActive) return@post
                when (kind) {
                    ACTION_KIND_NOISE -> targetMode?.let { executeNoiseAction(active, token, it) }
                    ACTION_KIND_DISCONNECT -> executeDisconnect(active, token)
                }
            }
        }
    }

    /** Accepts snapshots from any thread; all Android presentation state is serialized on handler. */
    fun render(snapshot: Bundle) {
        if (closed) return
        val safeSnapshot = Bundle(snapshot)
        dispatchOnHandler {
            if (!closed) renderOnHandler(safeSnapshot)
        }
    }

    /** Withdraws only notifications and module prompts created by this renderer. */
    fun clear() {
        if (closed) return
        dispatchOnHandler { clearOnHandler(reportWithdrawal = true) }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        actionExecutor.shutdownNow()
        dispatchOnHandler {
            clearOnHandler(reportWithdrawal = true)
            unregisterActionReceiver()
        }
    }

    /** Hot reload must know whether an in-flight synchronous Binder wait actually released us. */
    fun awaitActionTermination(timeoutMillis: Long): Boolean = try {
        actionExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun renderOnHandler(snapshot: Bundle) {
        val decoded = decode(snapshot) ?: return
        val previous = current

        if (previous != null && previous.identity != decoded.identity) {
            clearRenderedState(previous, reportWithdrawal = true)
            unregisterActionReceiver()
            actionToken = null
            processedPrompts.clear()
            reportedStatus.clear()
        } else if (previous != null && decoded.readSequence < previous.readSequence) {
            return
        }

        current = decoded
        if (previous == null || previous.identity != decoded.identity) {
            registerActionReceiver()
        }

        // The prompt goes first: a module prompt merged into the status notification makes that notification re-post.
        renderConnectionPrompt(decoded)
        renderStatusNotification(decoded)
    }

    private fun decode(snapshot: Bundle): RenderedSnapshot? {
        val identity = SessionIdentity(
            deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: return null,
            sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
                .takeIf { it != INVALID_SESSION_ID } ?: return null,
            hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: return null,
        )
        if (snapshot.getInt(BridgeWire.KEY_VERSION, -1) != BridgeWire.VERSION) return null
        val fields = snapshot.getBundle("fields") ?: return null
        val capabilities = snapshot.getBundle("capabilities") ?: return null
        val presentation = snapshot.getBundle(BridgePresentationWire.PRESENTATION) ?: return null
        val mode = presentation.getString(BridgePresentationWire.MODE)?.takeIf {
            it == BridgePresentationWire.OFF || it == BridgePresentationWire.OFFICIAL || it == BridgePresentationWire.MODULE
        } ?: return null
        if (!presentation.containsKey(BridgePresentationWire.FOCUS_ENABLED)) return null
        return RenderedSnapshot(
            wire = Bundle(snapshot),
            identity = identity,
            readSequence = snapshot.getLong(BridgeWire.READ_SEQUENCE, 0L),
            fields = Bundle(fields),
            capabilities = Bundle(capabilities),
            presentation = Bundle(presentation),
            mode = mode,
            focusEnabled = presentation.getBoolean(BridgePresentationWire.FOCUS_ENABLED),
            statusIsland = presentation.getBoolean(BridgePresentationWire.STATUS_SUMMARY_ISLAND, false),
            statusTarget = presentation.getString(BridgePresentationWire.STATUS_TARGET, BridgePresentationWire.TARGET_OPENROSE),
            dynamicColor = presentation.getBoolean(BridgePresentationWire.DYNAMIC_COLOR, false),
            address = bluetoothAddress(snapshot),
        )
    }

    /** The classic address names the system's audio device; an address-shaped control ID is the fallback. */
    private fun bluetoothAddress(snapshot: Bundle): String? {
        val address = snapshot.getString(CLASSIC_ADDRESS) ?: snapshot.getString(DEVICE_ADDRESS)
        return address?.takeIf(BluetoothAdapter::checkBluetoothAddress)
    }

    private fun renderStatusNotification(snapshot: RenderedSnapshot) {
        val requestedTag = requestedStatusTag(snapshot)
        if (!snapshot.focusEnabled || notificationManager == null || requestedTag == null) {
            withdrawStatusNotification(snapshot.wire)
            if (snapshot.focusEnabled && notificationManager == null) {
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.UNAVAILABLE,
                    "notification_manager_unavailable",
                )
            } else if (snapshot.focusEnabled && requestedTag == null) {
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.UNAVAILABLE,
                    "missing_owned_notification_tag",
                )
            }
            return
        }

        if (statusTag != null && statusTag != requestedTag) withdrawStatusNotification(snapshot.wire)
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            withdrawStatusNotification(snapshot.wire)
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.UNAVAILABLE,
                "target_process_notification_permission_missing",
            )
            return
        }
        statusTag = requestedTag
        // A merged module prompt is shown as the status island's entrance, replayed by posting the notification afresh.
        val showsPrompt = promptAwaitingStatus && snapshot.statusIsland &&
            activePrompt?.let { it.tag == null && it.identity == snapshot.identity } == true
        try {
            ensureStatusChannel()
            val noise = knownNoise(snapshot)
            val summary = batterySummary(snapshot)
            val body = summary + noise?.let { " · ${modeLabel(it.mode)}" }.orEmpty()
            val title = deviceAlias(snapshot) ?: profileLabel(snapshot.wire.getString("profile"))
            val nextNoise = noise?.takeIf { canControlNoise(snapshot, it.mode) && actionReceiverRegistered }
                ?.let { nextNoiseMode(snapshot, it.mode) }
            val canDisconnect = actionReceiverRegistered && snapshot.address != null
            val iconColors = statusCardColors(snapshot)
            val fingerprint = listOf(
                snapshot.identity.stableKey,
                requestedTag,
                title,
                body,
                nextNoise.orEmpty(),
                canDisconnect,
                snapshot.statusIsland,
                snapshot.statusTarget,
                iconColors,
            ).joinToString("|")
            val unchanged = statusNotificationActive && statusTag == requestedTag && statusFingerprint == fingerprint
            if (unchanged && !showsPrompt) return

            // The same two actions back the card's text buttons and, where the card is not rendered, the plain actions.
            val actions = listOfNotNull(
                nextNoise?.let { statusAction(snapshot, ACTION_KIND_NOISE, it) },
                if (canDisconnect) statusAction(snapshot, ACTION_KIND_DISCONNECT) else null,
            )

            val builder = Notification.Builder(appContext, STATUS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(statusContentIntent(snapshot))
                .setWhen(0L)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            actions.forEach { (_, action) -> builder.addAction(action) }
            builder.addExtras(
                buildStatusCardExtras(
                    title = title,
                    content = summary,
                    noiseText = noise?.let { modeLabel(it.mode) },
                    buttons = actions,
                    island = snapshot.statusIsland,
                    snapshot = snapshot,
                    aodTitle = aodTitle(snapshot),
                    iconColors = iconColors,
                ),
            )
            // An update without island data is not known to remove a summary state already shown, and one with it changes
            // the island in place unnoticed; withdraw first to remove the island or to replay its entrance.
            val islandShown = statusNotificationActive && statusIslandPosted
            if (islandShown && (!snapshot.statusIsland || showsPrompt)) {
                notificationManager.cancel(requestedTag, STATUS_NOTIFICATION_ID)
            }
            postNotification(requestedTag, STATUS_NOTIFICATION_ID, builder.build())
            statusTag = requestedTag
            statusFingerprint = fingerprint
            statusNotificationActive = true
            statusIslandPosted = snapshot.statusIsland
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.CALLED,
                "notification_manager_notify_returned",
            )
            if (showsPrompt) {
                promptAwaitingStatus = false
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.MODULE_CONNECTION,
                    BridgePresentationWire.CALLED,
                    "status_notification_island_notify_returned",
                )
            }
            if (noise != null && canControlNoise(snapshot, noise.mode) && !actionReceiverRegistered) {
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.ACTION_FAILED,
                    "notification_view_available_but_control_receiver_unavailable",
                )
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "status_notification_failed", failure)
            runCatching { notificationManager?.cancel(requestedTag, STATUS_NOTIFICATION_ID) }
            statusTag = null
            statusFingerprint = null
            statusNotificationActive = false
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.UNAVAILABLE,
                "notification_publish_failed:${failure.javaClass.simpleName}",
            )
            if (showsPrompt) {
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.MODULE_CONNECTION,
                    BridgePresentationWire.UNAVAILABLE,
                    "status_notification_island_publish_failed:${failure.javaClass.simpleName}",
                )
            }
        }
    }

    private fun requestedStatusTag(snapshot: RenderedSnapshot): String? {
        val tag = snapshot.presentation.getBundle(BridgePresentationWire.NOTIFICATION)?.getString(BridgePresentationWire.TAG)
        return tag?.takeIf { it.startsWith(STATUS_TAG_PREFIX) && it.length <= MAX_TAG_LENGTH }
    }

    /** A module prompt rides on the status notification's island whenever that notification is shown with one. */
    private fun canMergePrompt(snapshot: RenderedSnapshot): Boolean {
        if (!snapshot.focusEnabled || !snapshot.statusIsland || notificationManager == null) return false
        return requestedStatusTag(snapshot) != null
    }

    private fun renderConnectionPrompt(snapshot: RenderedSnapshot) {
        val prompt = snapshot.presentation.getBundle(BridgePresentationWire.PROMPT)
        val promptId = prompt?.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.isNotBlank() && it.length <= 128 }
        val promptMode = prompt?.getString(BridgePresentationWire.MODE)
        val pending = activePrompt
        val islandAllowed = snapshot.presentation.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, false)
        if (pending != null && (
                !islandAllowed || promptId == null || pending.identity != snapshot.identity ||
                    pending.key != promptKey(snapshot.identity, promptId) ||
                    promptId != pending.id || promptMode != pending.mode || snapshot.mode != pending.mode
                )
        ) {
            cancelActivePrompt("prompt_or_mode_changed")
        }
        val merged = activePrompt?.takeIf { it.tag == null }
        if (merged != null && prompt != null && !canMergePrompt(snapshot)) {
            // The status card went away mid-prompt. The prompt does not depend on the card, so it carries on as its own
            // notification for the time it has left.
            val remaining = merged.deadline - SystemClock.uptimeMillis()
            cancelActivePrompt("status_notification_withdrawn")
            if (remaining > 0) publishModulePrompt(snapshot, merged.id, prompt, remaining)
        }
        // Clean up our previous module prompt before handing the new policy to its owner.
        // Official prompts are claimed only by the separate Xiaomi Bluetooth consumer.
        if (promptMode == BridgePresentationWire.OFFICIAL) return
        if (!islandAllowed || prompt == null || promptId == null || promptMode == null || promptMode != snapshot.mode ||
            promptMode == BridgePresentationWire.OFF || promptMode !in PROMPT_MODES
        ) {
            return
        }

        val key = promptKey(snapshot.identity, promptId)
        if (key in processedPrompts) return
        if (prompt.getBoolean(BridgePresentationWire.CLAIMED, false)) {
            processedPrompts += key
            return
        }
        val claimed = runCatching { callbacks.claimPrompt(Bundle(snapshot.wire), promptId) }
            .onFailure {
                Log.w(TAG, "prompt_claim_failed", it)
                reportOnce(
                    snapshot.wire,
                    if (promptMode == BridgePresentationWire.MODULE) {
                        BridgePresentationWire.MODULE_CONNECTION
                    } else {
                        BridgePresentationWire.OFFICIAL_CONNECTION
                    },
                    BridgePresentationWire.UNAVAILABLE,
                    "prompt_claim_temporarily_unavailable",
                )
            }
            .getOrDefault(false)
        // A failed RPC may have failed before submission. Retry only while the next authority
        // snapshot still says unclaimed; the host's atomic claim prevents duplicate publication.
        if (!claimed) return

        publishModulePrompt(snapshot, promptId, prompt)
    }

    private fun publishModulePrompt(snapshot: RenderedSnapshot, promptId: String, prompt: Bundle, remainingMillis: Long? = null) {
        val key = promptKey(snapshot.identity, promptId)
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE,
                "target_process_notification_permission_missing",
            )
            return
        }
        val duration = (remainingMillis ?: prompt.getLong(BridgePresentationWire.DURATION_MILLIS, DEFAULT_PROMPT_DURATION_MILLIS))
            .coerceIn(1L, MAX_PROMPT_DURATION_MILLIS)
        if (remainingMillis == null && canMergePrompt(snapshot)) {
            // One island at a time: the status island already is the prompt's battery template, so
            // renderStatusNotification, which runs next, shows the prompt by (re)posting the status notification.
            promptAwaitingStatus = true
            processedPrompts += key
            startPrompt(ActivePrompt(snapshot.identity, promptId, BridgePresentationWire.MODULE, key, null, snapshot.wire), duration)
            return
        }
        val tag = "$MODULE_TAG_PREFIX${stableDigest(key)}"
        try {
            val extras = buildFocusExtras(snapshot, showNotification = false, durationMillis = duration)
            ensurePromptChannel()
            val notification = Notification.Builder(appContext, PROMPT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(profileLabel(snapshot.wire.getString("profile")))
                .setContentText(batterySummary(snapshot))
                .setTicker("OpenRose")
                .setWhen(0L)
                .addExtras(extras)
                .build()

            postNotification(tag, MODULE_PROMPT_NOTIFICATION_ID, notification)
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.CALLED,
                "notification_manager_notify_returned",
            )

            startPrompt(ActivePrompt(snapshot.identity, promptId, BridgePresentationWire.MODULE, key, tag, snapshot.wire), duration)
        } catch (failure: Throwable) {
            Log.w(TAG, "module_prompt_failed", failure)
            runCatching { notificationManager?.cancel(tag, MODULE_PROMPT_NOTIFICATION_ID) }
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE,
                "module_template_or_publish_failed:${failure.javaClass.simpleName}",
            )
        }
    }

    private fun startPrompt(record: ActivePrompt, durationMillis: Long) {
        record.deadline = SystemClock.uptimeMillis() + durationMillis
        activePrompt = record
        record.timeout = Runnable {
            if (!closed && activePrompt === record && current?.identity == record.identity) {
                cancelActivePrompt("prompt_timeout")
            }
        }
        handler.postDelayed(record.timeout!!, durationMillis)
    }

    private fun buildFocusExtras(snapshot: RenderedSnapshot, showNotification: Boolean, durationMillis: Long): Bundle {
        val durationSeconds = ((durationMillis + 999L) / 1_000L).coerceIn(1L, 5L).toInt()
        val template = JSONObject()
            .put("enableFloat", true)
            .put("ticker", "OpenRose")
            .put("tickerPic", PROMPT_LEFT_PICTURE)
            .put("isShowNotification", showNotification)
            .put("param_island", promptIsland(snapshot).put("islandTimeout", durationSeconds))
        if (showNotification) template.put("updatable", true)

        return Bundle().apply {
            putString(FOCUS_PARAM, JSONObject().put("param_v2", template).toString())
            putBundle(FOCUS_PICTURES, Bundle().apply { putPromptPictures(this) })
        }
    }

    /**
     * The battery island, as in OppoPods' `showBatteryIsland`: each earbud's picture with its battery. It is both the
     * module prompt and the status notification's summary state.
     */
    private fun promptIsland(snapshot: RenderedSnapshot): JSONObject {
        val left = batteryValue(snapshot, LEFT_BATTERY)
        val right = batteryValue(snapshot, RIGHT_BATTERY)

        fun imageText(type: Int, pictureKey: String, value: Int?): JSONObject = JSONObject()
            .put("type", type)
            .put("picInfo", JSONObject().put("type", 1).put("pic", pictureKey))
            .put(
                "textInfo",
                JSONObject()
                    .put("title", value?.toString() ?: UNKNOWN_VALUE)
                    .put("content", if (value == null) "" else "%"),
            )

        val shareContent = listOfNotNull(
            left?.let { "L: $it%" },
            right?.let { "R: $it%" },
        ).joinToString("  ").ifBlank { "电量未知" }

        return JSONObject()
            .put("islandProperty", 1)
            .put(
                "bigIslandArea",
                JSONObject()
                    .put("imageTextInfoLeft", imageText(1, PROMPT_LEFT_PICTURE, left))
                    .put("imageTextInfoRight", imageText(2, PROMPT_RIGHT_PICTURE, right)),
            )
            .put(
                "shareData",
                JSONObject()
                    .put("title", "OpenRose")
                    .put("content", shareContent)
                    .put("shareContent", shareContent),
            )
    }

    private fun putPromptPictures(pictures: Bundle) {
        pictures.putParcelable(PROMPT_LEFT_PICTURE, Icon.createWithBitmap(createEarbudBitmap(leftSide = true)))
        pictures.putParcelable(PROMPT_RIGHT_PICTURE, Icon.createWithBitmap(createEarbudBitmap(leftSide = false)))
    }

    private fun createEarbudBitmap(leftSide: Boolean): Bitmap {
        val bitmap = squareBitmap(72)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val direction = if (leftSide) 1f else -1f
        canvas.save()
        if (direction < 0f) canvas.scale(-1f, 1f, 36f, 36f)
        canvas.drawRoundRect(RectF(26f, 12f, 47f, 38f), 10f, 10f, paint)
        canvas.drawRoundRect(RectF(37f, 31f, 48f, 57f), 5f, 5f, paint)
        canvas.drawCircle(36.5f, 25f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303030.toInt() })
        canvas.restore()
        return bitmap
    }

    private fun squareBitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    /**
     * Focus card: `iconTextInfo` plus up to two `textButton` entries, with the battery-template summary state when
     * [island] is set. SystemUI renders a button's text from the JSON `actionTitle` (Html.fromHtml, not from the
     * Notification.Action title) and crashes the whole UI when it is missing, so every button must carry a non-blank title.
     */
    private fun buildStatusCardExtras(
        title: String,
        content: String,
        noiseText: String?,
        buttons: List<Pair<String, Notification.Action>>,
        island: Boolean,
        snapshot: RenderedSnapshot,
        aodTitle: String?,
        iconColors: IconColors,
    ): Bundle {
        val info = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("animIconInfo", JSONObject().put("type", 0).put("src", STATUS_CARD_PICTURE))
        if (noiseText != null) info.put("subContent", noiseText)
        val template = JSONObject()
            .put("protocol", 1)
            .put("business", STATUS_CARD_BUSINESS)
            .put("ticker", title)
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("updatable", true)
            .put("iconTextInfo", info)
        if (aodTitle != null) template.put("aodTitle", aodTitle).put("aodPic", STATUS_CARD_PICTURE)
        // Without param_island, HyperOS 4 SystemUI still derives a summary state from the ticker and title.
        template.put("param_island", if (island) promptIsland(snapshot) else JSONObject().put("dismissIsland", true))
        val titled = buttons.filter { it.first.isNotBlank() }.take(MAX_CARD_BUTTONS)
        if (titled.isNotEmpty()) {
            val entries = JSONArray()
            titled.forEachIndexed { index, (label, _) ->
                entries.put(JSONObject().put("action", "$STATUS_CARD_ACTION_PREFIX$index").put("actionTitle", label))
            }
            template.put("textButton", entries)
        }
        return Bundle().apply {
            putString(FOCUS_PARAM, JSONObject().put("param_v2", template).toString())
            putBundle(
                FOCUS_PICTURES,
                Bundle().apply {
                    putParcelable(STATUS_CARD_PICTURE, Icon.createWithBitmap(createStatusCardBitmap(iconColors)))
                    if (island) putPromptPictures(this)
                },
            )
            if (titled.isNotEmpty()) {
                val actions = Bundle()
                titled.forEachIndexed { index, (_, action) -> actions.putParcelable("$STATUS_CARD_ACTION_PREFIX$index", action) }
                putBundle(FOCUS_ACTIONS, actions)
            }
        }
    }

    /**
     * The app icon from `sharedUI/.../drawable/app_icon.xml` at 224px in [colors]; the template requires a picture, so this
     * stands in for the app icon. Keep the path data and transforms in sync with that file.
     */
    private fun createStatusCardBitmap(colors: IconColors): Bitmap {
        val bitmap = squareBitmap(STATUS_CARD_PICTURE_SIZE)
        val canvas = Canvas(bitmap)
        val scale = STATUS_CARD_PICTURE_SIZE / APP_ICON_VIEWPORT
        // The canvas is discarded after drawing, so the transforms need no save/restore.
        canvas.scale(scale, scale)
        canvas.drawPath(
            PathParser.createPathFromPathData(APP_ICON_TILE_PATH),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.tile },
        )
        canvas.translate(APP_ICON_GLYPH_OFFSET, APP_ICON_GLYPH_OFFSET)
        canvas.scale(APP_ICON_GLYPH_SCALE, APP_ICON_GLYPH_SCALE)
        canvas.translate(0f, APP_ICON_VIEWPORT)
        canvas.drawPath(
            PathParser.createPathFromPathData(APP_ICON_GLYPH_PATH),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.glyph },
        )
        return bitmap
    }

    /**
     * The app icon's tones (tile 90, glyph 30) under 动态取色 from the system wallpaper palette, whose shade N is tone
     * (1000 - N) / 10; otherwise, and before Android 12, from the rose palette of the static icon.
     */
    private fun statusCardColors(snapshot: RenderedSnapshot): IconColors {
        if (!snapshot.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return IconColors(tile = APP_ICON_TILE_COLOR, glyph = APP_ICON_GLYPH_COLOR)
        }
        return IconColors(
            tile = appContext.getColor(android.R.color.system_accent1_100),
            glyph = appContext.getColor(android.R.color.system_accent1_700),
        )
    }

    private fun batterySummary(snapshot: RenderedSnapshot): String {
        val parts = buildList {
            batteryValue(snapshot, LEFT_BATTERY)?.let { add("L $it%${chargingMark(snapshot, LEFT_CHARGING)}") }
            batteryValue(snapshot, RIGHT_BATTERY)?.let { add("R $it%${chargingMark(snapshot, RIGHT_CHARGING)}") }
            batteryValue(snapshot, CASE_BATTERY)?.let { add("C $it%") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "电量未知"
    }

    /** Only a confirmed charging reading is marked; unknown charging leaves the battery text neutral. */
    private fun chargingMark(snapshot: RenderedSnapshot, fieldName: String): String {
        val reading = snapshot.fields.getBundle(fieldName) ?: return ""
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return ""
        val value = reading.getBundle("value") ?: return ""
        return if (value.getString("type") == TOGGLE && value.getBoolean("value", false)) "⚡" else ""
    }

    private fun aodTitle(snapshot: RenderedSnapshot): String? = listOfNotNull(
        batteryValue(snapshot, LEFT_BATTERY)?.let { "L $it%" },
        batteryValue(snapshot, RIGHT_BATTERY)?.let { "R $it%" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" | ")

    @SuppressLint("MissingPermission") // Runs in com.android.bluetooth, which holds BLUETOOTH_CONNECT.
    private fun deviceAlias(snapshot: RenderedSnapshot): String? {
        val address = snapshot.address ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { bluetoothDevice(address)?.alias }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun bluetoothDevice(address: String): BluetoothDevice? {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        return adapter?.getRemoteDevice(address)
    }

    private fun statusAction(snapshot: RenderedSnapshot, kind: String, targetMode: String? = null): Pair<String, Notification.Action>? {
        val pendingIntent = createActionPendingIntent(snapshot, kind, targetMode) ?: return null
        val (label, icon) = when (kind) {
            ACTION_KIND_NOISE -> CYCLE_NOISE_LABEL to android.R.drawable.ic_lock_silent_mode
            else -> DISCONNECT_LABEL to android.R.drawable.ic_delete
        }
        return label to Notification.Action.Builder(Icon.createWithResource(appContext, icon), label, pendingIntent).build()
    }

    private fun statusContentIntent(snapshot: RenderedSnapshot): PendingIntent {
        val openRose = Intent(BridgeWire.ACTION_SHOW_HEADPHONES).apply {
            setClassName(BridgeWire.APP_PACKAGE, "${BridgeWire.APP_PACKAGE}.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val intent = when (snapshot.statusTarget) {
            BridgePresentationWire.TARGET_SYSTEM -> systemHeadsetIntent(snapshot)

            // The Bluetooth app holds QUERY_ALL_PACKAGES, so RoseLink resolves here; a missing RoseLink falls back.
            BridgePresentationWire.TARGET_ROSELINK -> appContext.packageManager.getLaunchIntentForPackage(ROSELINK_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            else -> null
        } ?: openRose
        return PendingIntent.getActivity(
            appContext,
            STATUS_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** HyperOS' own headset page, opened with the extras the reference OppoPods passes for its compatibility device. */
    private fun systemHeadsetIntent(snapshot: RenderedSnapshot): Intent? {
        val address = snapshot.address ?: return null
        val device = runCatching { bluetoothDevice(address) }.getOrNull() ?: return null
        val compatibilityId = NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID
        return Intent(SYSTEM_HEADSET_ACTION).apply {
            setClassName(SETTINGS_PACKAGE, SYSTEM_HEADSET_ACTIVITY)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra("bluetoothaddress", address)
            putExtra("MIUI_HEADSET_SUPPORT", "$compatibilityId,$SYSTEM_HEADSET_SUPPORT_FLAGS")
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", compatibilityId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun batteryValue(snapshot: RenderedSnapshot, fieldName: String): Int? {
        val reading = snapshot.fields.getBundle(fieldName) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != NUMBER) return null
        return value.getInt("value", INVALID_BATTERY).takeIf { it in 0..100 }
    }

    private fun knownNoise(snapshot: RenderedSnapshot): NoiseState? {
        val reading = snapshot.fields.getBundle(NOISE_FIELD) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != TEXT) return null
        val mode = value.getString("value")?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val modes = availableNoiseModes(snapshot)
        if (mode !in modes) return null
        return NoiseState(mode)
    }

    private fun availableNoiseModes(snapshot: RenderedSnapshot): List<String> {
        val modeBundle = snapshot.capabilities.getBundle("noise") ?: return emptyList()
        val modes = modeBundle.getStringArrayList("modes")?.map { it.lowercase() }?.toSet() ?: return emptyList()
        return NOISE_MODE_ORDER.filter { it in modes }
    }

    private fun canControlNoise(snapshot: RenderedSnapshot, currentMode: String): Boolean {
        val writable = snapshot.capabilities.getStringArrayList("writable_settings") ?: return false
        return "NOISE_MODE" in writable && currentMode in availableNoiseModes(snapshot)
    }

    /** The mode after the confirmed one in this model's order; null when there is nothing to switch to. */
    private fun nextNoiseMode(snapshot: RenderedSnapshot, currentMode: String): String? {
        val modes = availableNoiseModes(snapshot)
        if (modes.size < 2) return null
        return modes[(modes.indexOf(currentMode) + 1) % modes.size]
    }

    private fun createActionPendingIntent(snapshot: RenderedSnapshot, kind: String, targetMode: String?): PendingIntent? {
        val token = actionToken ?: return null
        val action = actionReceiverAction ?: return null
        val path = listOfNotNull(token, kind, targetMode).joinToString("/")
        val intent = Intent(action).apply {
            setPackage(appContext.packageName)
            data = Uri.parse("$ACTION_DATA_SCHEME://$ACTION_DATA_HOST/$path")
            putExtra(EXTRA_ACTION_TOKEN, token)
            putExtra(EXTRA_ACTION_KIND, kind)
            putExtra(BridgeWire.HOST_EPOCH, snapshot.identity.hostEpoch)
            putExtra(BridgeWire.DEVICE_ID, snapshot.identity.deviceId)
            putExtra(BridgeWire.SESSION_ID, snapshot.identity.sessionId)
            targetMode?.let { putExtra(BridgePresentationWire.NOISE_MODE, it) }
        }
        return PendingIntent.getBroadcast(
            appContext,
            path.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun executeNoiseAction(snapshot: RenderedSnapshot, token: String, targetMode: String) {
        val noise = knownNoise(snapshot) ?: return
        if (!canControlNoise(snapshot, noise.mode) || targetMode !in availableNoiseModes(snapshot) || targetMode == noise.mode) return
        val operationKey = "${snapshot.identity.stableKey}:$token:$targetMode"
        if (!pendingActions.add(operationKey)) return

        try {
            actionExecutor.execute {
                val result = runCatching { callbacks.setNoise(Bundle(snapshot.wire), targetMode) }
                handler.post {
                    pendingActions.remove(operationKey)
                    if (closed || current?.identity != snapshot.identity || actionToken != token) return@post
                    val response = result.getOrNull()
                    if (response == null || response.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                        val detail = response?.getString(BridgeWire.MESSAGE)
                            ?: result.exceptionOrNull()?.javaClass?.simpleName
                            ?: "noise_action_rejected"
                        reportOnce(
                            snapshot.wire,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.ACTION_FAILED,
                            "noise_action:$targetMode:$detail",
                        )
                    }
                    // State changes only when a later authenticated snapshot reports the device value.
                }
            }
        } catch (failure: Throwable) {
            pendingActions.remove(operationKey)
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.ACTION_FAILED,
                "noise_action_dispatch:${failure.javaClass.simpleName}",
            )
        }
    }

    /**
     * 断开: drops the headset's system Bluetooth connection, like the native Xiaomi headset notification; the control
     * connection ends with it. `BluetoothDevice.disconnect()` is a system API (API 33) and this code runs in the
     * Bluetooth app itself, which holds BLUETOOTH_PRIVILEGED.
     */
    private fun executeDisconnect(snapshot: RenderedSnapshot, token: String) {
        val address = snapshot.address ?: return
        val operationKey = "${snapshot.identity.stableKey}:$token:disconnect"
        if (!pendingActions.add(operationKey)) return
        try {
            actionExecutor.execute {
                val result = runCatching {
                    val device = requireNotNull(bluetoothDevice(address)) { "bluetooth_adapter_unavailable" }
                    BluetoothDevice::class.java.getMethod("disconnect").invoke(device) as Int
                }
                handler.post {
                    pendingActions.remove(operationKey)
                    if (closed || current?.identity != snapshot.identity || actionToken != token) return@post
                    val code = result.getOrNull()
                    if (code != BLUETOOTH_STATUS_SUCCESS) {
                        val failure = result.exceptionOrNull()
                        val detail = code?.toString() ?: (failure?.cause ?: failure)?.javaClass?.simpleName ?: "unknown"
                        reportOnce(
                            snapshot.wire,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.ACTION_FAILED,
                            "disconnect:$detail",
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            pendingActions.remove(operationKey)
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.ACTION_FAILED,
                "disconnect_dispatch:${failure.javaClass.simpleName}",
            )
        }
    }

    private fun registerActionReceiver() {
        // PendingIntent broadcasts execute with their creator's UID. Publishing an action without
        // the permission required by this receiver would create a button that can never work.
        if (appContext.checkSelfPermission(BLUETOOTH_PRIVILEGED_PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val token = UUID.randomUUID().toString()
        val action = "${appContext.packageName}.OPENROSE_ACTION_$token"
        val filter = IntentFilter(action).apply {
            addDataScheme(ACTION_DATA_SCHEME)
            addDataAuthority(ACTION_DATA_HOST, null)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    actionReceiver,
                    filter,
                    BLUETOOTH_PRIVILEGED_PERMISSION,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                // Pre-33 has no NOT_EXPORTED flag. The privileged sender permission, package-scoped
                // PendingIntent, exact random action and session token form the private boundary.
                appContext.registerReceiver(actionReceiver, filter, BLUETOOTH_PRIVILEGED_PERMISSION, handler)
            }
            actionToken = token
            actionReceiverAction = action
            actionReceiverRegistered = true
        } catch (failure: Throwable) {
            actionToken = null
            actionReceiverAction = null
            actionReceiverRegistered = false
            Log.w(TAG, "action_receiver_registration_failed", failure)
        }
    }

    private fun unregisterActionReceiver() {
        if (actionReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(actionReceiver) }
                .onFailure { Log.w(TAG, "action_receiver_unregister_failed", it) }
        }
        actionReceiverRegistered = false
        actionReceiverAction = null
        actionToken = null
    }

    // This code is injected into Bluetooth and uses that package's Context/UID/manifest, not the
    // module APK's notification permission. Lint only sees the module manifest; retain the runtime
    // check at this one posting boundary rather than requesting an unrelated app permission.
    @SuppressLint("NotificationPermission")
    private fun postNotification(tag: String, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Target process has no notification permission")
        }
        val manager = notificationManager ?: throw IllegalStateException("notification_manager_unavailable")
        manager.notify(tag, id, notification)
    }

    private fun ensureStatusChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "OpenRose 耳机状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun ensurePromptChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(PROMPT_CHANNEL_ID, "OpenRose 系统提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun withdrawStatusNotification(snapshot: Bundle?) {
        val ownedTag = statusTag
        if (ownedTag != null && ownedTag.startsWith(STATUS_TAG_PREFIX)) {
            runCatching { notificationManager?.cancel(ownedTag, STATUS_NOTIFICATION_ID) }
                .onSuccess {
                    if (statusNotificationActive && snapshot != null) {
                        reportOnce(
                            snapshot,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.WITHDRAWN,
                            "owned_status_notification_cancel_returned",
                        )
                    }
                }
                .onFailure { failure ->
                    if (snapshot != null) {
                        reportOnce(
                            snapshot,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.ACTION_FAILED,
                            "status_notification_cancel:${failure.javaClass.simpleName}",
                        )
                    }
                }
        }
        statusTag = null
        statusFingerprint = null
        statusNotificationActive = false
        statusIslandPosted = false
    }

    private fun cancelActivePrompt(reason: String) {
        val active = activePrompt ?: return
        activePrompt = null
        active.timeout?.let(handler::removeCallbacks)
        val tag = active.tag
        if (tag == null) {
            // Merged into the status notification, whose island stays as the summary state.
            promptAwaitingStatus = false
            reportOnce(
                active.snapshot,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.WITHDRAWN,
                "merged_module_prompt_ended:$reason",
            )
            return
        }
        val manager = notificationManager
        if (manager != null) {
            runCatching { manager.cancel(tag, MODULE_PROMPT_NOTIFICATION_ID) }
                .onSuccess {
                    reportOnce(
                        active.snapshot,
                        BridgePresentationWire.MODULE_CONNECTION,
                        BridgePresentationWire.WITHDRAWN,
                        "owned_module_prompt_cancel_returned:$reason",
                    )
                }
                .onFailure { failure ->
                    reportOnce(
                        active.snapshot,
                        BridgePresentationWire.MODULE_CONNECTION,
                        BridgePresentationWire.ACTION_FAILED,
                        "module_prompt_cancel:${failure.javaClass.simpleName}",
                    )
                }
        }
    }

    private fun clearRenderedState(snapshot: RenderedSnapshot?, reportWithdrawal: Boolean) {
        if (snapshot != null) {
            withdrawStatusNotification(if (reportWithdrawal) snapshot.wire else null)
        } else {
            withdrawStatusNotification(null)
        }
        cancelActivePrompt("renderer_clear")
        current = null
        pendingActions.clear()
    }

    private fun clearOnHandler(reportWithdrawal: Boolean) {
        val previous = current
        clearRenderedState(previous, reportWithdrawal)
        unregisterActionReceiver()
        processedPrompts.clear()
        reportedStatus.clear()
    }

    private fun reportOnce(snapshot: Bundle, feature: String, status: String, detail: String) {
        if (feature !in BridgePresentationWire.FEATURES || status !in BridgePresentationWire.STATUSES) return
        val identity = sessionIdentity(snapshot) ?: return
        val key = "${identity.stableKey}:$feature"
        if (reportedStatus[key] == status) return
        reportedStatus[key] = status
        runCatching { callbacks.report(Bundle(snapshot), feature, status, detail) }
            .onFailure { Log.w(TAG, "presentation_report_failed feature=$feature status=$status", it) }
    }

    private fun promptKey(identity: SessionIdentity, promptId: String): String = "${identity.stableKey}:$promptId"

    private fun dispatchOnHandler(block: () -> Unit) {
        if (Looper.myLooper() === handler.looper) block() else handler.post { block() }
    }

    private fun sessionIdentity(snapshot: Bundle): SessionIdentity? {
        val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
        val hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() } ?: return null
        if (sessionId == INVALID_SESSION_ID) return null
        return SessionIdentity(deviceId, sessionId, hostEpoch)
    }

    private fun profileLabel(profile: String?): String = ModelProfile.fromName(profile)?.displayName ?: "OpenRose 耳机"

    /** A copy of the app's `noiseModeLabel` (sharedUI `NoiseControlCard.kt`), so this process need not load the UI module. */
    private fun modeLabel(mode: String): String = when (mode) {
        "normal" -> "标准"
        "anc" -> "降噪"
        "transparency" -> "通透"
        "wind" -> "抗风噪"
        "auto" -> "自动"
        "dynamic" -> "动态降噪"
        else -> mode
    }

    private fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SessionIdentity(val deviceId: String, val sessionId: Long, val hostEpoch: String) {
        val stableKey: String get() = "$hostEpoch:$deviceId:$sessionId"
    }

    private data class RenderedSnapshot(
        val wire: Bundle,
        val identity: SessionIdentity,
        val readSequence: Long,
        val fields: Bundle,
        val capabilities: Bundle,
        val presentation: Bundle,
        val mode: String,
        val focusEnabled: Boolean,
        val statusIsland: Boolean,
        val statusTarget: String,
        val dynamicColor: Boolean,
        val address: String?,
    )

    private data class IconColors(val tile: Int, val glyph: Int)

    private data class NoiseState(val mode: String)

    private data class ActivePrompt(
        val identity: SessionIdentity,
        val id: String,
        val mode: String,
        val key: String,
        /** The prompt's own notification tag; null while the prompt is merged into the status notification. */
        val tag: String?,
        val snapshot: Bundle,
        var timeout: Runnable? = null,
        var deadline: Long = 0L,
    )

    companion object {
        private const val TAG = "OpenRosePresentation"
        private const val KNOWN = "KNOWN"
        private const val NUMBER = "NUMBER"
        private const val TEXT = "TEXT"
        private const val TOGGLE = "TOGGLE"
        private const val NOISE_FIELD = "NOISE_MODE"
        private const val LEFT_CHARGING = "LEFT_CHARGING"
        private const val RIGHT_CHARGING = "RIGHT_CHARGING"
        private const val CLASSIC_ADDRESS = "classic_address"
        private const val DEVICE_ADDRESS = "device_address"
        private const val CYCLE_NOISE_LABEL = "切换降噪"
        private const val DISCONNECT_LABEL = "断开"
        private const val ACTION_KIND_NOISE = "noise"
        private const val ACTION_KIND_DISCONNECT = "disconnect"
        private const val BLUETOOTH_STATUS_SUCCESS = 0
        private const val ROSELINK_PACKAGE = "cn.ikaile.ruoshui.client"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEM_HEADSET_ACTIVITY = "com.android.settings.bluetooth.MiuiHeadsetActivity"
        private const val SYSTEM_HEADSET_ACTION = "miui.bluetooth.action.HEADSET_SETTINGS"

        /** The feature flags OppoPods sends with its compatibility device ID; their individual meaning is unverified. */
        private const val SYSTEM_HEADSET_SUPPORT_FLAGS = "000000000000000010000000"
        private const val LEFT_BATTERY = "LEFT_BATTERY"
        private const val RIGHT_BATTERY = "RIGHT_BATTERY"
        private const val CASE_BATTERY = "CASE_BATTERY"
        private const val INVALID_SESSION_ID = Long.MIN_VALUE
        private const val INVALID_BATTERY = -1
        private const val UNKNOWN_VALUE = "未知"
        private const val STATUS_TAG_PREFIX = "openrose:"
        private const val MAX_TAG_LENGTH = 256
        private const val STATUS_CHANNEL_ID = "openrose_headset_status_v1"
        private const val PROMPT_CHANNEL_ID = "openrose_module_prompt_v1"
        private const val STATUS_NOTIFICATION_ID = 10003
        private const val MODULE_PROMPT_NOTIFICATION_ID = 10086
        private const val MODULE_TAG_PREFIX = "openrose:module:"
        private const val PROMPT_LEFT_PICTURE = "key_pic_left"
        private const val PROMPT_RIGHT_PICTURE = "key_pic_right"
        private const val FOCUS_PARAM = "miui.focus.param"
        private const val FOCUS_PICTURES = "miui.focus.pics"
        private const val FOCUS_ACTIONS = "miui.focus.actions"
        private const val STATUS_CARD_ACTION_PREFIX = "miui.focus.action_status_"
        private const val MAX_CARD_BUTTONS = 2
        private const val STATUS_CARD_PICTURE = "miui.focus.pic_status"
        private const val STATUS_CARD_BUSINESS = "openrose_headset_status"
        private const val STATUS_CARD_PICTURE_SIZE = 224
        private const val APP_ICON_VIEWPORT = 960f
        private const val APP_ICON_GLYPH_OFFSET = 192f
        private const val APP_ICON_GLYPH_SCALE = 0.6f
        private const val APP_ICON_TILE_COLOR = 0xFFFFD9DF.toInt()
        private const val APP_ICON_GLYPH_COLOR = 0xFF8F003F.toInt()
        private const val APP_ICON_TILE_PATH = "M240,0H720Q960,0 960,240V720Q960,960 720,960H240Q0,960 0,720V240Q0,0 240,0Z"
        private const val APP_ICON_GLYPH_PATH = "M280-120h-80q-33 0-56.5-23.5T120-200v-280q0-75 28.5-140.5t77-114q48.5-48.5 " +
            "114-77T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480v280q0 33-23.5 56.5T760-120h-80q-33 " +
            "0-56.5-23.5T600-200v-160q0-33 23.5-56.5T680-440h80v-40q0-117-81.5-198.5T480-760q-117 0-198.5 81.5T200-480v40h80q33 " +
            "0 56.5 23.5T360-360v160q0 33-23.5 56.5T280-120Zm0-240h-80v160h80v-160Zm400 0v160h80v-160h-80Zm-400 0h-80 80Zm400 " +
            "0h80-80Z"
        private const val EXTRA_ACTION_TOKEN = "openrose_action_token"
        private const val EXTRA_ACTION_KIND = "openrose_action_kind"
        private const val ACTION_DATA_SCHEME = "openrose"
        private const val ACTION_DATA_HOST = "action"
        private const val BLUETOOTH_PRIVILEGED_PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED"
        private const val DEFAULT_PROMPT_DURATION_MILLIS = 5_000L
        private const val MAX_PROMPT_DURATION_MILLIS = 5_000L
        private const val MAX_CONCURRENT_ACTIONS = 4
        private val PROMPT_MODES = setOf(BridgePresentationWire.OFFICIAL, BridgePresentationWire.MODULE)
        private val NOISE_MODE_ORDER = listOf("normal", "anc", "transparency", "wind", "auto", "dynamic")
    }
}
