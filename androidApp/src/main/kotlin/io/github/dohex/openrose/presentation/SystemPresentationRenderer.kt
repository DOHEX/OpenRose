package io.github.dohex.openrose.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.util.Log
import io.github.dohex.openrose.MainActivity
import io.github.dohex.openrose.bridge.BridgePresentationWire
import io.github.dohex.openrose.bridge.BridgeWire
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** IPC operations stay with the application's existing ControlSession owner. */
internal interface PresentationCallbacks {
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean
    fun setNoise(snapshot: Bundle, mode: String): Bundle
    fun report(snapshot: Bundle, feature: String, status: String, detail: String)
}

/**
 * Renders the trusted application snapshot in com.android.bluetooth.
 *
 * The status notification deliberately has no MIUI focus extras. Module islands use a separate,
 * prompt-scoped notification so ordinary battery refreshes cannot retrigger an island and OFF can
 * never leak focus fields. The prompt JSON follows HyperNotification 1.4's public V3 model schema
 * (publisher revision c704a4de3e1d069e213508363303f62b5e057526); that schema alone does not prove
 * SystemUI acceptance or actual display.
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
    private val pendingNoiseActions = mutableSetOf<String>()
    private val reportedStatus = mutableMapOf<String, String>()

    private val noiseActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            val token = intent.getStringExtra(EXTRA_ACTION_TOKEN)
            val deviceId = intent.getStringExtra(BridgeWire.DEVICE_ID)
            val sessionId = intent.getLongExtra(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
            val hostEpoch = intent.getStringExtra(BridgeWire.HOST_EPOCH)
            val targetMode = intent.getStringExtra(BridgePresentationWire.NOISE_MODE)
            handler.post {
                if (closed || action == null || action != actionReceiverAction || token == null ||
                    token != actionToken || deviceId == null || hostEpoch == null || targetMode == null
                ) return@post
                val active = current ?: return@post
                if (!active.focusEnabled || !statusNotificationActive) return@post
                if (active.identity.deviceId != deviceId || active.identity.sessionId != sessionId ||
                    active.identity.hostEpoch != hostEpoch
                ) return@post
                executeNoiseAction(active, token, targetMode)
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
            unregisterNoiseActionReceiver()
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
            unregisterNoiseActionReceiver()
            actionToken = null
            processedPrompts.clear()
            reportedStatus.clear()
        } else if (previous != null && decoded.readSequence < previous.readSequence) {
            return
        }

        current = decoded
        if (previous == null || previous.identity != decoded.identity) {
            registerNoiseActionReceiver()
        }

        renderStatusNotification(decoded)
        renderConnectionPrompt(decoded)
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
        )
    }

    private fun renderStatusNotification(snapshot: RenderedSnapshot) {
        val notificationSpec = snapshot.presentation.getBundle(BridgePresentationWire.NOTIFICATION)
        val requestedTag = notificationSpec?.getString(BridgePresentationWire.TAG)
            ?.takeIf { it.startsWith(STATUS_TAG_PREFIX) && it.length <= MAX_TAG_LENGTH }
        if (!snapshot.focusEnabled || notificationManager == null || requestedTag == null) {
            withdrawStatusNotification(snapshot.wire)
            if (snapshot.focusEnabled && notificationManager == null) {
                reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.UNAVAILABLE, "notification_manager_unavailable")
            } else if (snapshot.focusEnabled && requestedTag == null) {
                reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.UNAVAILABLE, "missing_owned_notification_tag")
            }
            return
        }

        if (statusTag != null && statusTag != requestedTag) withdrawStatusNotification(snapshot.wire)
        if (Build.VERSION.SDK_INT >= 33 && appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            withdrawStatusNotification(snapshot.wire)
            reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.UNAVAILABLE, "target_process_notification_permission_missing")
            return
        }
        statusTag = requestedTag
        try {
            ensureStatusChannel()
            val noise = knownNoise(snapshot)
            val body = batterySummary(snapshot) + noise?.let { " · 降噪：${modeLabel(it.mode)}" }.orEmpty()
            val title = profileLabel(snapshot.wire.getString("profile"))
            val actionModes = if (noise != null && canControlNoise(snapshot, noise.mode) && actionReceiverRegistered) {
                val modes = availableNoiseModes(snapshot)
                val currentIndex = modes.indexOf(noise.mode)
                // Android surfaces at most three actions. Rotate from the confirmed mode so all i7
                // modes remain reachable instead of permanently hiding auto/dynamic after the first three.
                (1 until modes.size).map { modes[(currentIndex + it) % modes.size] }.take(MAX_NOTIFICATION_ACTIONS)
            } else {
                emptyList()
            }
            val fingerprint = listOf(
                snapshot.identity.stableKey,
                requestedTag,
                title,
                body,
                actionModes.joinToString(","),
            ).joinToString("|")
            if (statusNotificationActive && statusTag == requestedTag && statusFingerprint == fingerprint) return

            val builder = Notification.Builder(appContext, STATUS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(
                    appContext,
                    STATUS_NOTIFICATION_ID,
                    Intent(MainActivity.ACTION_SHOW_HEADPHONES).apply {
                        setClassName(BridgeWire.APP_PACKAGE, "${BridgeWire.APP_PACKAGE}.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                .setWhen(0L)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)

            actionModes.forEach { target ->
                val pendingIntent = createNoisePendingIntent(snapshot, target)
                if (pendingIntent != null) {
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(appContext, android.R.drawable.ic_lock_silent_mode),
                            modeLabel(target),
                            pendingIntent,
                        ).build(),
                    )
                }
            }

            // Notification tags are host-generated and renderer-owned. No focus extras are attached
            // here in any mode. Island-capable output is confined to the prompt below.
            postNotification(requestedTag, STATUS_NOTIFICATION_ID, builder.build())
            statusTag = requestedTag
            statusFingerprint = fingerprint
            statusNotificationActive = true
            reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.CALLED, "notification_manager_notify_returned")
            if (noise != null && canControlNoise(snapshot, noise.mode) && !actionReceiverRegistered) {
                reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                    BridgePresentationWire.ACTION_FAILED, "notification_view_available_but_control_receiver_unavailable")
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "status_notification_failed", failure)
            runCatching { notificationManager?.cancel(requestedTag, STATUS_NOTIFICATION_ID) }
            statusTag = null
            statusFingerprint = null
            statusNotificationActive = false
            reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.UNAVAILABLE, "notification_publish_failed:${failure.javaClass.simpleName}")
        }
    }

    private fun renderConnectionPrompt(snapshot: RenderedSnapshot) {
        val prompt = snapshot.presentation.getBundle(BridgePresentationWire.PROMPT)
        val promptId = prompt?.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.isNotBlank() && it.length <= 128 }
        val promptMode = prompt?.getString(BridgePresentationWire.MODE)
        val pending = activePrompt
        val islandAllowed = snapshot.presentation.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, false)
        if (pending != null && (!islandAllowed || promptId == null || pending.identity != snapshot.identity ||
                pending.key != promptKey(snapshot.identity, promptId) ||
                promptId != pending.id || promptMode != pending.mode || snapshot.mode != pending.mode)
        ) {
            cancelActivePrompt("prompt_or_mode_changed")
        }
        // Clean up our previous module prompt before handing the new policy to its owner.
        // Official prompts are claimed only by the separate Xiaomi Bluetooth consumer.
        if (promptMode == BridgePresentationWire.OFFICIAL) return
        if (!islandAllowed || prompt == null || promptId == null || promptMode == null || promptMode != snapshot.mode ||
            promptMode == BridgePresentationWire.OFF || promptMode !in PROMPT_MODES
        ) return

        val key = promptKey(snapshot.identity, promptId)
        if (key in processedPrompts) return
        if (prompt.getBoolean(BridgePresentationWire.CLAIMED, false)) {
            processedPrompts += key
            return
        }
        val claimed = runCatching { callbacks.claimPrompt(Bundle(snapshot.wire), promptId) }
            .onFailure {
                Log.w(TAG, "prompt_claim_failed", it)
                reportOnce(snapshot.wire,
                    if (promptMode == BridgePresentationWire.MODULE) BridgePresentationWire.MODULE_CONNECTION else BridgePresentationWire.OFFICIAL_CONNECTION,
                    BridgePresentationWire.UNAVAILABLE, "prompt_claim_temporarily_unavailable")
            }
            .getOrDefault(false)
        // A failed RPC may have failed before submission. Retry only while the next authority
        // snapshot still says unclaimed; the host's atomic claim prevents duplicate publication.
        if (!claimed) return

        publishModulePrompt(snapshot, promptId, prompt)
    }

    private fun publishModulePrompt(snapshot: RenderedSnapshot, promptId: String, prompt: Bundle) {
        val key = promptKey(snapshot.identity, promptId)
        if (Build.VERSION.SDK_INT >= 33 && appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            processedPrompts += key
            reportOnce(snapshot.wire, BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE, "target_process_notification_permission_missing")
            return
        }
        val tag = "$MODULE_TAG_PREFIX${stableDigest(key)}"
        val duration = prompt.getLong(BridgePresentationWire.DURATION_MILLIS, DEFAULT_PROMPT_DURATION_MILLIS)
            .coerceIn(1L, MAX_PROMPT_DURATION_MILLIS)
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
            reportOnce(snapshot.wire, BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.CALLED, "notification_manager_notify_returned")

            val record = ActivePrompt(snapshot.identity, promptId, BridgePresentationWire.MODULE, key, tag, snapshot.wire)
            activePrompt = record
            record.timeout = Runnable {
                val active = activePrompt
                if (!closed && active === record && current?.identity == record.identity) {
                    cancelActivePrompt("prompt_timeout")
                }
            }
            handler.postDelayed(record.timeout!!, duration)
        } catch (failure: Throwable) {
            Log.w(TAG, "module_prompt_failed", failure)
            runCatching { notificationManager?.cancel(tag, MODULE_PROMPT_NOTIFICATION_ID) }
            processedPrompts += key
            reportOnce(snapshot.wire, BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE, "module_template_or_publish_failed:${failure.javaClass.simpleName}")
        }
    }

    private fun buildFocusExtras(snapshot: RenderedSnapshot, showNotification: Boolean, durationMillis: Long): Bundle {
        val left = batteryValue(snapshot, LEFT_BATTERY)
        val right = batteryValue(snapshot, RIGHT_BATTERY)
        val leftPicture = "key_pic_left"
        val rightPicture = "key_pic_right"
        val durationSeconds = ((durationMillis + 999L) / 1_000L).coerceIn(1L, 5L).toInt()

        fun imageText(type: Int, pictureKey: String, value: Int?): JSONObject = JSONObject()
            .put("type", type)
            .put("picInfo", JSONObject().put("type", 1).put("pic", pictureKey))
            .put("textInfo", JSONObject()
                .put("title", value?.toString() ?: UNKNOWN_VALUE)
                .put("content", if (value == null) "" else "%"))

        val shareContent = listOfNotNull(
            left?.let { "L: $it%" },
            right?.let { "R: $it%" },
        ).joinToString("  ").ifBlank { "电量未知" }

        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", durationSeconds)
        .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", imageText(1, leftPicture, left))
                .put("imageTextInfoRight", imageText(2, rightPicture, right)))
            .put("shareData", JSONObject()
                .put("title", "OpenRose")
                .put("content", shareContent)
                .put("shareContent", shareContent))

        val template = JSONObject()
            .put("enableFloat", true)
            .put("ticker", "OpenRose")
            .put("tickerPic", leftPicture)
            .put("isShowNotification", showNotification)
            .put("param_island", island)
        if (showNotification) template.put("updatable", true)

        return Bundle().apply {
            putString(FOCUS_PARAM, JSONObject().put("param_v2", template).toString())
            putBundle(FOCUS_PICTURES, Bundle().apply {
                putParcelable(leftPicture, Icon.createWithBitmap(createEarbudBitmap(leftSide = true)))
                putParcelable(rightPicture, Icon.createWithBitmap(createEarbudBitmap(leftSide = false)))
            })
        }
    }

    private fun createEarbudBitmap(leftSide: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
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

    private fun batterySummary(snapshot: RenderedSnapshot): String {
        val parts = buildList {
            batteryValue(snapshot, LEFT_BATTERY)?.let { add("左耳 $it%") }
            batteryValue(snapshot, RIGHT_BATTERY)?.let { add("右耳 $it%") }
            batteryValue(snapshot, CASE_BATTERY)?.let { add("充电盒 $it%") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "电量未知"
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

    private fun createNoisePendingIntent(snapshot: RenderedSnapshot, target: String): PendingIntent? {
        val token = actionToken ?: return null
        val action = actionReceiverAction ?: return null
        val intent = Intent(action).apply {
            setPackage(appContext.packageName)
            data = Uri.parse("openrose://noise/$token/$target")
            putExtra(EXTRA_ACTION_TOKEN, token)
            putExtra(BridgeWire.HOST_EPOCH, snapshot.identity.hostEpoch)
            putExtra(BridgeWire.DEVICE_ID, snapshot.identity.deviceId)
            putExtra(BridgeWire.SESSION_ID, snapshot.identity.sessionId)
            putExtra(BridgePresentationWire.NOISE_MODE, target)
        }
        val requestCode = (token.hashCode() * 31 + target.hashCode()) and 0x7fffffff
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun executeNoiseAction(snapshot: RenderedSnapshot, token: String, targetMode: String) {
        val noise = knownNoise(snapshot) ?: return
        if (!canControlNoise(snapshot, noise.mode) || targetMode !in availableNoiseModes(snapshot) || targetMode == noise.mode) return
        val operationKey = "${snapshot.identity.stableKey}:$token:$targetMode"
        if (!pendingNoiseActions.add(operationKey)) return

        try {
            actionExecutor.execute {
                val result = runCatching { callbacks.setNoise(Bundle(snapshot.wire), targetMode) }
                handler.post {
                    pendingNoiseActions.remove(operationKey)
                    if (closed || current?.identity != snapshot.identity || actionToken != token) return@post
                    val response = result.getOrNull()
                    if (response == null || response.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                        val detail = response?.getString(BridgeWire.MESSAGE)
                            ?: result.exceptionOrNull()?.javaClass?.simpleName
                            ?: "noise_action_rejected"
                        reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.ACTION_FAILED, "noise_action:$targetMode:$detail")
                    }
                    // State changes only when a later authenticated snapshot reports the device value.
                }
            }
        } catch (failure: Throwable) {
            pendingNoiseActions.remove(operationKey)
            reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.ACTION_FAILED, "noise_action_dispatch:${failure.javaClass.simpleName}")
        }
    }

    private fun registerNoiseActionReceiver() {
        // PendingIntent broadcasts execute with their creator's UID. Publishing an action without
        // the permission required by this receiver would create a button that can never work.
        if (appContext.checkSelfPermission(BLUETOOTH_PRIVILEGED_PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val token = UUID.randomUUID().toString()
        val action = "${appContext.packageName}.OPENROSE_NOISE_$token"
        val filter = IntentFilter(action).apply {
            addDataScheme(ACTION_DATA_SCHEME)
            addDataAuthority(ACTION_DATA_HOST, null)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    noiseActionReceiver,
                    filter,
                    BLUETOOTH_PRIVILEGED_PERMISSION,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                // Pre-33 has no NOT_EXPORTED flag. The privileged sender permission, package-scoped
                // PendingIntent, exact random action and session token form the private boundary.
                appContext.registerReceiver(noiseActionReceiver, filter, BLUETOOTH_PRIVILEGED_PERMISSION, handler)
            }
            actionToken = token
            actionReceiverAction = action
            actionReceiverRegistered = true
        } catch (failure: Throwable) {
            actionToken = null
            actionReceiverAction = null
            actionReceiverRegistered = false
            Log.w(TAG, "noise_receiver_registration_failed", failure)
        }
    }

    private fun unregisterNoiseActionReceiver() {
        if (actionReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(noiseActionReceiver) }
                .onFailure { Log.w(TAG, "noise_receiver_unregister_failed", it) }
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
        if (Build.VERSION.SDK_INT >= 33 && appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                        reportOnce(snapshot, BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.WITHDRAWN, "owned_status_notification_cancel_returned")
                    }
                }
                .onFailure { failure ->
                    if (snapshot != null) reportOnce(snapshot, BridgePresentationWire.STATUS_NOTIFICATION,
                        BridgePresentationWire.ACTION_FAILED, "status_notification_cancel:${failure.javaClass.simpleName}")
                }
        }
        statusTag = null
        statusFingerprint = null
        statusNotificationActive = false
    }

    private fun cancelActivePrompt(reason: String) {
        val active = activePrompt ?: return
        activePrompt = null
        active.timeout?.let(handler::removeCallbacks)
        val manager = notificationManager
        if (manager != null) {
            runCatching { manager.cancel(active.tag, MODULE_PROMPT_NOTIFICATION_ID) }
                .onSuccess {
                    reportOnce(active.snapshot, BridgePresentationWire.MODULE_CONNECTION,
                        BridgePresentationWire.WITHDRAWN, "owned_module_prompt_cancel_returned:$reason")
                }
                .onFailure { failure ->
                    reportOnce(active.snapshot, BridgePresentationWire.MODULE_CONNECTION,
                        BridgePresentationWire.ACTION_FAILED, "module_prompt_cancel:${failure.javaClass.simpleName}")
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
        pendingNoiseActions.clear()
    }

    private fun clearOnHandler(reportWithdrawal: Boolean) {
        val previous = current
        clearRenderedState(previous, reportWithdrawal)
        unregisterNoiseActionReceiver()
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

    private fun profileLabel(profile: String?): String = when (profile) {
        "I5_ZT" -> "EarFree i5"
        "I7_RS_V2" -> "EarFeel i7"
        else -> "OpenRose 耳机"
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "normal" -> "降噪关闭"
        "anc" -> "降噪"
        "transparency" -> "通透"
        "wind" -> "抗风噪"
        "auto" -> "自适应"
        "dynamic" -> "动态"
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
    )

    private data class NoiseState(val mode: String)

    private data class ActivePrompt(
        val identity: SessionIdentity,
        val id: String,
        val mode: String,
        val key: String,
        val tag: String,
        val snapshot: Bundle,
        var timeout: Runnable? = null,
    )

    companion object {
        private const val TAG = "OpenRosePresentation"
        private const val KNOWN = "KNOWN"
        private const val NUMBER = "NUMBER"
        private const val TEXT = "TEXT"
        private const val NOISE_FIELD = "NOISE_MODE"
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
        private const val FOCUS_PARAM = "miui.focus.param"
        private const val FOCUS_PICTURES = "miui.focus.pics"
        private const val EXTRA_ACTION_TOKEN = "openrose_action_token"
        private const val ACTION_DATA_SCHEME = "openrose"
        private const val ACTION_DATA_HOST = "noise"
        private const val BLUETOOTH_PRIVILEGED_PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED"
        private const val DEFAULT_PROMPT_DURATION_MILLIS = 5_000L
        private const val MAX_PROMPT_DURATION_MILLIS = 5_000L
        private const val MAX_NOTIFICATION_ACTIONS = 3
        private const val MAX_CONCURRENT_ACTIONS = 4
        private val PROMPT_MODES = setOf(BridgePresentationWire.OFFICIAL, BridgePresentationWire.MODULE)
        private val NOISE_MODE_ORDER = listOf("normal", "anc", "transparency", "wind", "auto", "dynamic")
    }
}
