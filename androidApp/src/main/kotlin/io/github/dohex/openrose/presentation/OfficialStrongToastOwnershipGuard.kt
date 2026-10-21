package io.github.dohex.openrose.presentation

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.bridge.IStrongToastGuard
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Single owner for the guarded native-payload envelope shared by presenter, Host, and guard. */
internal object OfficialToastWire {
    const val VERSION = 1
    const val GUARD_VERSION = "openrose.guard.version"
    const val GUARD_OPERATION = "openrose.guard.operation"
    const val GUARD_TICKET = "openrose.guard.ticket"
    const val GUARD_HOST_EPOCH = "openrose.guard.host_epoch"
    const val GUARD_DEVICE_ID = "openrose.guard.device_id"
    const val GUARD_SESSION_ID = "openrose.guard.session_id"
    const val GUARD_PROMPT_ID = "openrose.guard.prompt_id"
    const val GUARD_SOURCE_UID = "openrose.guard.source_uid"
    const val GUARD_EXPIRES_ELAPSED = "openrose.guard.expires_elapsed"
    const val GUARD_PAYLOAD_SHA256 = "openrose.guard.payload_sha256"
    const val GUARD_SIGNATURE = "openrose.guard.signature"
    const val GUARD_EPOCH = GUARD_HOST_EPOCH
    const val GUARD_DEVICE = GUARD_DEVICE_ID
    const val GUARD_SESSION = GUARD_SESSION_ID
    const val GUARD_PROMPT = GUARD_PROMPT_ID

    const val OPERATION_SHOW = "show"
    const val OPERATION_WITHDRAW = "withdraw"
    const val TARGET_PACKAGE = "com.xiaomi.bluetooth"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val STATUS_BAR_WHAT = 1
    const val STATUS_BAR_ACTION = "strong_toast_action"
    const val SHOW_ACTION = "show_custom_strong_toast"
    const val HIDE_ACTION = "hide_strong_toast"
    const val NOTIFY_ID = "headset_wear_notification"
    const val CATEGORY = "video_text_text_video"
    const val FILE_PROVIDER_AUTHORITY = "com.xiaomi.bluetooth.fileprovider"
    const val DURATION_MILLIS = 5_000L

    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_STRONG_TOAST_CATEGORY = "strong_toast_category"
    const val KEY_TARGET = "target"
    const val KEY_PARAM = "param"
    const val KEY_ISLAND_PARAM = "island_param"
    const val KEY_NOTIFY_ID = "notifyId"
    const val KEY_DURATION = "duration"
    const val KEY_LEVEL = "level"
    const val KEY_RAPID_RATE = "rapid_rate"
    const val KEY_CHARGE = "charge"
    const val KEY_CHARGE_FLAG = "string_toast_charge_flag"
    const val KEY_STATUS_BAR_STRONG_TOAST = "status_bar_strong_toast"

    private val markerKeys = setOf(
        GUARD_VERSION, GUARD_OPERATION, GUARD_TICKET, GUARD_HOST_EPOCH, GUARD_DEVICE_ID,
        GUARD_SESSION_ID, GUARD_PROMPT_ID, GUARD_SOURCE_UID, GUARD_EXPIRES_ELAPSED,
        GUARD_PAYLOAD_SHA256, GUARD_SIGNATURE,
    )
    private val showKeys = setOf(
        KEY_PACKAGE_NAME, KEY_STRONG_TOAST_CATEGORY, KEY_TARGET, KEY_PARAM, KEY_ISLAND_PARAM,
        KEY_NOTIFY_ID, KEY_DURATION, KEY_LEVEL, KEY_RAPID_RATE, KEY_CHARGE, KEY_CHARGE_FLAG,
        KEY_STATUS_BAR_STRONG_TOAST,
    )
    private val hideKeys = setOf(KEY_PACKAGE_NAME, KEY_STATUS_BAR_STRONG_TOAST)

    data class Envelope(
        val operation: String,
        val ticket: String,
        val hostEpoch: String,
        val deviceId: String,
        val sessionId: Long,
        val promptId: String,
        val sourceUid: Int,
        val expiresAtElapsed: Long,
        val payloadSha256: String,
        val signature: String,
    )

    fun isMarked(bundle: Bundle): Boolean = markerKeys.any { bundle.containsKey(it) }

    fun stripEnvelope(bundle: Bundle): Bundle = Bundle(bundle).apply { markerKeys.forEach { remove(it) } }

    fun isValidProofKey(value: String): Boolean = value.length == 64 && value.all { it.isHexDigit() }

    fun payloadHash(payload: Bundle): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                val keys = payload.keySet().sorted()
                out.writeInt(keys.size)
                keys.forEach { key ->
                    out.writeUTF(key)
                    when (val value = payload.get(key)) {
                        null -> out.writeByte(0)
                        is String -> { out.writeByte(1); out.writeUTF(value) }
                        is Int -> { out.writeByte(2); out.writeInt(value) }
                        is Long -> { out.writeByte(3); out.writeLong(value) }
                        is Float -> { out.writeByte(4); out.writeInt(value.toRawBits()) }
                        is Boolean -> { out.writeByte(5); out.writeBoolean(value) }
                        else -> throw IllegalArgumentException("unsupported strong-toast payload value: ${value.javaClass.name}")
                    }
                }
            }
            buffer.toByteArray()
        }
        return sha256(bytes)
    }

    fun attachSignedEnvelope(payload: Bundle, envelope: Envelope, proofKey: String): Bundle {
        require(isValidProofKey(proofKey))
        val signature = sign(envelope.copy(signature = ""), proofKey)
        return Bundle(payload).apply {
            putInt(GUARD_VERSION, VERSION)
            putString(GUARD_OPERATION, envelope.operation)
            putString(GUARD_TICKET, envelope.ticket)
            putString(GUARD_HOST_EPOCH, envelope.hostEpoch)
            putString(GUARD_DEVICE_ID, envelope.deviceId)
            putLong(GUARD_SESSION_ID, envelope.sessionId)
            putString(GUARD_PROMPT_ID, envelope.promptId)
            putInt(GUARD_SOURCE_UID, envelope.sourceUid)
            putLong(GUARD_EXPIRES_ELAPSED, envelope.expiresAtElapsed)
            putString(GUARD_PAYLOAD_SHA256, envelope.payloadSha256)
            putString(GUARD_SIGNATURE, signature)
        }
    }

    fun readEnvelope(bundle: Bundle): Envelope? {
        if (!markerKeys.all(bundle::containsKey) || bundle.getInt(GUARD_VERSION, -1) != VERSION) return null
        val operation = bundle.getString(GUARD_OPERATION)?.takeIf { it == OPERATION_SHOW || it == OPERATION_WITHDRAW } ?: return null
        val ticket = bundle.getString(GUARD_TICKET)?.takeIf { it.length in 16..64 } ?: return null
        val hostEpoch = bundle.getString(GUARD_HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val deviceId = bundle.getString(GUARD_DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val sessionId = bundle.getLong(GUARD_SESSION_ID, Long.MIN_VALUE).takeIf { it > 0L } ?: return null
        val promptId = bundle.getString(GUARD_PROMPT_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val sourceUid = bundle.getInt(GUARD_SOURCE_UID, -1).takeIf { it >= 0 } ?: return null
        val expiry = bundle.getLong(GUARD_EXPIRES_ELAPSED, 0L).takeIf { it > 0L } ?: return null
        val payloadHash = bundle.getString(GUARD_PAYLOAD_SHA256)?.takeIf { isSha256(it) } ?: return null
        val signature = bundle.getString(GUARD_SIGNATURE)?.takeIf { isSha256(it) } ?: return null
        return Envelope(operation, ticket, hostEpoch, deviceId, sessionId, promptId, sourceUid, expiry, payloadHash, signature)
    }

    fun verify(envelope: Envelope, proofKey: String): Boolean {
        if (!isValidProofKey(proofKey)) return false
        val expected = runCatching { sign(envelope.copy(signature = ""), proofKey) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected.hexBytes(), envelope.signature.hexBytes())
    }

    fun validShowPayload(payload: Bundle): Boolean {
        return runCatching {
            if (payload.keySet() != showKeys ||
                payload.getString(KEY_PACKAGE_NAME) != TARGET_PACKAGE ||
                payload.getString(KEY_STRONG_TOAST_CATEGORY) != CATEGORY ||
                payload.get(KEY_TARGET) != null || !payload.containsKey(KEY_TARGET) ||
                payload.getString(KEY_NOTIFY_ID) != NOTIFY_ID ||
                payload.getLong(KEY_DURATION, -1L) != DURATION_MILLIS ||
                payload.getFloat(KEY_LEVEL, Float.NaN) != 0f ||
                payload.getFloat(KEY_RAPID_RATE, Float.NaN) != 0f ||
                payload.get(KEY_CHARGE) != null || !payload.containsKey(KEY_CHARGE) ||
                payload.getInt(KEY_CHARGE_FLAG, -1) != 0 ||
                payload.getString(KEY_STATUS_BAR_STRONG_TOAST) != SHOW_ACTION
            ) return@runCatching false
            val param = JSONObject(payload.getString(KEY_PARAM) ?: return@runCatching false)
            val island = JSONObject(payload.getString(KEY_ISLAND_PARAM) ?: return@runCatching false)
            if (jsonKeys(param) != setOf("left", "right") || jsonKeys(island) != setOf("left", "right")) return@runCatching false
            listOf("left", "right").all { side ->
                val regularSide = param.getJSONObject(side)
                val islandSide = island.getJSONObject(side)
                val regularIcon = regularSide.getJSONObject("iconParams")
                val islandIcon = islandSide.getJSONObject("iconParams")
                if (jsonKeys(regularSide) != expectedSideKeys(regularSide) ||
                    jsonKeys(islandSide) != expectedSideKeys(islandSide) ||
                    !validIcon(regularIcon, if (side == "left") "left" else "right", expectedType = 1) ||
                    !validIcon(islandIcon, if (side == "left") "left" else "right", expectedType = 0) ||
                    regularIcon.getString("iconName") != islandIcon.getString("iconName")
                ) return@all false
                val regularText = regularSide.optJSONObject("textParams")
                val islandText = islandSide.optJSONObject("textParams")
                when {
                    regularText == null && islandText == null -> true
                    regularText == null || islandText == null -> false
                    else -> validText(regularText, island = false) && validText(islandText, island = true) &&
                        regularText.getString("text") == islandText.getString("text") &&
                        regularText.getInt("textColor") == islandText.getInt("textColor")
                }
            }
        }.getOrDefault(false)
    }

    fun validHidePayload(payload: Bundle): Boolean =
        payload.keySet() == hideKeys &&
            payload.getString(KEY_PACKAGE_NAME) == TARGET_PACKAGE &&
            payload.getString(KEY_STATUS_BAR_STRONG_TOAST) == HIDE_ACTION

    fun validLifetime(envelope: Envelope, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        envelope.expiresAtElapsed > nowElapsed && envelope.expiresAtElapsed <= nowElapsed + DURATION_MILLIS + MAX_RELAY_LATENCY_MILLIS

    private fun sign(envelope: Envelope, proofKey: String): String {
        val key = proofKey.hexBytes()
        val body = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(envelope.operation)
                out.writeUTF(envelope.ticket)
                out.writeUTF(envelope.hostEpoch)
                out.writeUTF(envelope.deviceId)
                out.writeLong(envelope.sessionId)
                out.writeUTF(envelope.promptId)
                out.writeInt(envelope.sourceUid)
                out.writeLong(envelope.expiresAtElapsed)
                out.writeUTF(envelope.payloadSha256)
            }
            buffer.toByteArray()
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(body).toHex()
        }
    }

    private fun validIcon(icon: JSONObject, side: String, expectedType: Int): Boolean {
        if (jsonKeys(icon) != setOf("category", "fileType", "iconName", "iconType") ||
            icon.getString("category") != "raw" || icon.getString("fileType") != "mp4" ||
            icon.getInt("iconType") != expectedType
        ) return false
        val uri = Uri.parse(icon.getString("iconName"))
        val name = uri.lastPathSegment ?: return false
        return uri.scheme == "content" && uri.authority == FILE_PROVIDER_AUTHORITY &&
            uri.query == null && uri.fragment == null && uri.path == "/internal_files/$name" &&
            name.matches(Regex("openrose_official_[0-9a-f]{24}_${side}\\.mp4"))
    }

    private fun validText(text: JSONObject, island: Boolean): Boolean {
        val keys = if (island) setOf("text", "textColor", "turnAnim") else setOf("text", "textColor", "viewFlags")
        if (jsonKeys(text) != keys || !text.getString("text").matches(Regex("(?:100|[1-9]?[0-9])%")) ||
            text.getInt("textColor") !in setOf(Color.GREEN, Color.RED, Color.WHITE)
        ) return false
        if (island) return text.optBoolean("turnAnim", false)
        return text.optInt("viewFlags", Int.MIN_VALUE) == 0
    }

    private fun expectedSideKeys(side: JSONObject): Set<String> =
        if (side.has("textParams")) setOf("iconParams", "textParams") else setOf("iconParams")

    private fun jsonKeys(value: JSONObject): Set<String> = buildSet {
        val keys = value.keys()
        while (keys.hasNext()) add(keys.next())
    }

    private fun isSha256(value: String): Boolean = value.length == 64 && value.all { it.isHexDigit() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val MAX_RELAY_LATENCY_MILLIS = 1_000L
}

/** Reads only credentials published by the existing application host; missing readiness fails closed. */
internal class OfficialToastGuardCredentials(
    private val remotePreferences: android.content.SharedPreferences,
    private val guardReady: (hostEpoch: String) -> Boolean,
) {
    fun sign(
        snapshot: Bundle,
        promptId: String,
        operation: String,
        ticket: String,
        sourceUid: Int,
        expiresAtElapsed: Long,
        payloadHash: String,
        payload: Bundle,
    ): Bundle? {
        val hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        if (operation != OfficialToastWire.OPERATION_SHOW && operation != OfficialToastWire.OPERATION_WITHDRAW) return null
        if (!guardReady(hostEpoch) || remotePreferences.getString(BridgeWire.HOST_EPOCH, null) != hostEpoch) return null
        val proofKey = remotePreferences.getString(BridgeWire.PROOF_KEY, null)?.takeIf { it.length == 64 } ?: return null
        val envelope = OfficialToastWire.Envelope(
            operation = operation,
            ticket = ticket,
            hostEpoch = hostEpoch,
            deviceId = deviceId,
            sessionId = sessionId,
            promptId = promptId,
            sourceUid = sourceUid,
            expiresAtElapsed = expiresAtElapsed,
            payloadSha256 = payloadHash,
            signature = "",
        )
        when (operation) {
            OfficialToastWire.OPERATION_SHOW -> if (runCatching { OfficialToastWire.payloadHash(payload) }.getOrNull() != payloadHash) return null
            OfficialToastWire.OPERATION_WITHDRAW -> if (!OfficialToastWire.validHidePayload(payload)) return null
            else -> return null
        }
        return runCatching { OfficialToastWire.attachSignedEnvelope(payload, envelope, proofKey) }.getOrNull()
    }
}

/**
 * System-server owner for the vendor strong-toast slot. Root starts it from onSystemServerStarting,
 * registers endpoint() with the authenticated host, and closes it before replacing a hot-reloaded
 * instance. All statusbar forwarding and ownership checks share one lock.
 */
internal class OfficialStrongToastOwnershipGuard(
    private val xposed: XposedInterface,
    private val onReady: (IStrongToastGuard?) -> Unit,
    private val remotePreferences: android.content.SharedPreferences = xposed.getRemotePreferences(BridgeWire.PREFERENCES_GROUP),
) : AutoCloseable {
    private val stateLock = Any()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val internalMutation = ThreadLocal<Pair<OfficialToastWire.Envelope, Bundle>?>()
    private val endpoint = object : IStrongToastGuard.Stub() {
        override fun post(request: Bundle): Bundle = handlePost(request, Binder.getCallingUid())
        override fun withdraw(request: Bundle): Bundle = handleWithdraw(request, Binder.getCallingUid())
    }

    @Volatile private var active = false
    @Volatile private var statusBarReady = false
    @Volatile var unavailableReason: String? = null
        private set
    private var systemContext: Context? = null
    private var statusBarBinder: IBinder? = null
    private var statusBarService: Any? = null
    private var statusBarSetStatus: Method? = null
    private var statusBarHook: XposedInterface.HookHandle? = null
    private var owner: OfficialToastWire.Envelope? = null
    private var highWaterHostEpoch: String? = null
    private var highWaterSessionId = Long.MIN_VALUE
    private val postedPromptKeys = mutableSetOf<String>()
    private val usedTickets = mutableSetOf<String>()

    fun endpoint(): IStrongToastGuard = endpoint

    fun isReadyFor(hostEpoch: String): Boolean = active && statusBarReady &&
        remotePreferences.getString(BridgeWire.HOST_EPOCH, null) == hostEpoch &&
        remotePreferences.getString(BridgeWire.PROOF_KEY, null)?.let(OfficialToastWire::isValidProofKey) == true

    /** Installs addService observation and also resolves an already-published statusbar on reload. */
    fun install(classLoader: ClassLoader, context: Context? = null): Boolean {
        synchronized(stateLock) {
            if (active) return statusBarReady
            active = true
        }
        return try {
            systemContext = context ?: resolveSystemContext(classLoader)
            val serviceManager = Class.forName("android.os.ServiceManager", false, classLoader)
            val addServiceMethods = serviceManager.declaredMethods.filter(::isAddServiceSignature)
            if (addServiceMethods.isEmpty()) throw NoSuchMethodException("ServiceManager.addService(String, IBinder, ...)")
            addServiceMethods.forEachIndexed { index, method ->
                val handle = xposed.hook(method)
                    .setId("openrose.official.addService.$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (chain.getArg(0) == STATUS_BAR_SERVICE_NAME) {
                            val binder = chain.getArg(1) as? IBinder
                            if (binder != null) attachStatusBar(binder) else markUnavailable("statusbar_service_binder_missing")
                        }
                        result
                    }
                synchronized(stateLock) { handles += handle }
            }
            currentStatusBarBinder(serviceManager)?.let(::attachStatusBar)
            statusBarReady
        } catch (failure: Throwable) {
            markUnavailable("systemserver_guard_install_failed:${rootCause(failure).javaClass.simpleName}")
            false
        }
    }

    override fun close() {
        synchronized(stateLock) {
            active = false
            statusBarReady = false
            owner = null
            unavailableReason = "guard_closed"
        }
        runCatching { onReady(null) }
        val oldHandles = synchronized(stateLock) { handles.toList().also { handles.clear() } }
        oldHandles.asReversed().forEach { runCatching { it.unhook() } }
        synchronized(stateLock) {
            statusBarHook = null
            statusBarBinder = null
            statusBarService = null
            statusBarSetStatus = null
        }
    }

    private fun attachStatusBar(binder: IBinder) {
        synchronized(stateLock) {
            if (!active) return
            if (statusBarBinder === binder && statusBarHook != null) return
            statusBarReady = false
            owner = null
            statusBarHook?.let { previous ->
                statusBarHook = null
                handles.remove(previous)
                previous.unhook()
            }
        }
        try {
            val method = resolveSetStatus(binder.javaClass)
            val hook = xposed.hook(method)
                .setId("openrose.official.statusbar.${binder.javaClass.name.hashCode()}")
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> interceptExternalSetStatus(chain) }
            synchronized(stateLock) {
                if (!active) {
                    hook.unhook()
                    return
                }
                statusBarHook?.let { previous ->
                    handles.remove(previous)
                    previous.unhook()
                }
                statusBarHook = hook
                handles += hook
                statusBarBinder = binder
                statusBarService = binder
                statusBarSetStatus = method
                statusBarReady = true
                unavailableReason = null
            }
            runCatching { onReady(endpoint) }
                .onFailure { xposed.log(Log.ERROR, TAG, "guard_endpoint_publish_callback_failed", it) }
            xposed.log(Log.INFO, TAG, "guard_statusbar_hook_ready class=${binder.javaClass.name} method=${method.name}")
        } catch (failure: Throwable) {
            markUnavailable("statusbar_setStatus_unavailable:${rootCause(failure).javaClass.simpleName}")
        }
    }

    private fun interceptExternalSetStatus(chain: XposedInterface.Chain): Any? {
        val what = chain.getArg(0) as? Int
        val action = chain.getArg(1) as? String
        if (what != OfficialToastWire.STATUS_BAR_WHAT || action != OfficialToastWire.STATUS_BAR_ACTION) {
            return chain.proceed()
        }
        if (internalMutation.get()?.second === chain.getArg(2)) return chain.proceed()

        // Keep the call ordered with relay-owned hide/show. A racing external headset toast either
        // wins this lock first and clears ownership, or runs after our completed hide/show.
        synchronized(stateLock) {
            if (!active) return chain.proceed()
            owner = null
            return chain.proceed()
        }
    }

    private fun handlePost(request: Bundle, callingUid: Int): Bundle {
        if (!isOpenRoseUid(callingUid)) return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "guard_caller_not_application_host")
        if (!active || !statusBarReady) return BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, unavailableReason ?: "statusbar_guard_unavailable")
        val envelope = OfficialToastWire.readEnvelope(request)
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_guard_envelope_invalid")
        val payload = OfficialToastWire.stripEnvelope(request)
        if (envelope.operation != OfficialToastWire.OPERATION_SHOW ||
            !OfficialToastWire.validShowPayload(payload) ||
            OfficialToastWire.payloadHash(payload) != envelope.payloadSha256
        ) return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_show_payload_invalid")
        if (!validateEnvelope(envelope, callingUid)) {
            return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_guard_signature_or_source_invalid")
        }

        return synchronized(stateLock) {
            if (!active || !statusBarReady) return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_guard_closed")
            val service = statusBarService ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_service_missing")
            val method = statusBarSetStatus ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_setStatus_missing")
            if (!OfficialToastWire.validLifetime(envelope)) return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_RECEIPT, "official_show_ticket_expired")
            if (!validateEnvelope(envelope, callingUid)) return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_HOST, "official_guard_credentials_changed")
            if (!advanceSessionAndConsumePrompt(envelope)) {
                return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_SESSION, "official_prompt_is_stale_or_already_used")
            }
            val invocation = invokeWithSystemIdentity(envelope, service, method, payload)
            if (invocation != null) return@synchronized BridgeWire.response(BridgeWire.STATUS_ACTION_FAILED, "official_setStatus_failed:${invocation.javaClass.simpleName}")
            owner = envelope
            BridgeWire.response(BridgeWire.STATUS_OK, "guarded_setStatus_returned;systemui_display_unverified")
        }
    }

    private fun handleWithdraw(request: Bundle, callingUid: Int): Bundle {
        if (!isOpenRoseUid(callingUid)) return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "guard_caller_not_application_host")
        if (!active || !statusBarReady) return BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, unavailableReason ?: "statusbar_guard_unavailable")
        val envelope = OfficialToastWire.readEnvelope(request)
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_guard_envelope_invalid")
        val payload = OfficialToastWire.stripEnvelope(request)
        if (envelope.operation != OfficialToastWire.OPERATION_WITHDRAW || !OfficialToastWire.validHidePayload(payload)) {
            return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_hide_payload_invalid")
        }
        if (!validateEnvelope(envelope, callingUid)) {
            return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT, "official_guard_signature_or_source_invalid")
        }

        return synchronized(stateLock) {
            if (!active || !statusBarReady) return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_guard_closed")
            val current = owner
            if (current == null || !sameTicket(current, envelope)) {
                return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_RECEIPT, "official_slot_not_owned_by_ticket")
            }
            if (!OfficialToastWire.validLifetime(envelope)) {
                owner = null
                return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_RECEIPT, "official_hide_ticket_expired")
            }
            val service = statusBarService ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_service_missing")
            val method = statusBarSetStatus ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "statusbar_setStatus_missing")
            val invocation = invokeWithSystemIdentity(envelope, service, method, payload)
            if (invocation != null) return@synchronized BridgeWire.response(BridgeWire.STATUS_ACTION_FAILED, "official_hide_failed:${invocation.javaClass.simpleName}")
            owner = null
            BridgeWire.response(BridgeWire.STATUS_OK, "owned_hide_setStatus_returned;systemui_withdrawal_unverified")
        }
    }

    private fun validateEnvelope(envelope: OfficialToastWire.Envelope, callingUid: Int): Boolean {
        if (envelope.sourceUid < 0 || !isXiaomiBluetoothUid(envelope.sourceUid)) return false
        if (envelope.hostEpoch != remotePreferences.getString(BridgeWire.HOST_EPOCH, null)) return false
        val key = remotePreferences.getString(BridgeWire.PROOF_KEY, null) ?: return false
        return OfficialToastWire.validLifetime(envelope) && OfficialToastWire.verify(envelope, key) &&
            isOpenRoseUid(callingUid)
    }

    private fun sameTicket(current: OfficialToastWire.Envelope, request: OfficialToastWire.Envelope): Boolean =
        current.operation == OfficialToastWire.OPERATION_SHOW &&
            request.operation == OfficialToastWire.OPERATION_WITHDRAW &&
            current.ticket == request.ticket && current.hostEpoch == request.hostEpoch &&
            current.deviceId == request.deviceId && current.sessionId == request.sessionId &&
            current.promptId == request.promptId && current.sourceUid == request.sourceUid &&
            current.expiresAtElapsed == request.expiresAtElapsed &&
            current.payloadSha256 == request.payloadSha256

    /** Session IDs come from ControlSession's monotonic generation counter within one host epoch. */
    private fun advanceSessionAndConsumePrompt(envelope: OfficialToastWire.Envelope): Boolean {
        if (highWaterHostEpoch != envelope.hostEpoch) {
            highWaterHostEpoch = envelope.hostEpoch
            highWaterSessionId = Long.MIN_VALUE
            postedPromptKeys.clear()
            usedTickets.clear()
            owner = null
        }
        if (envelope.sessionId < highWaterSessionId) return false
        if (envelope.sessionId > highWaterSessionId) {
            highWaterSessionId = envelope.sessionId
            postedPromptKeys.clear()
            usedTickets.clear()
            owner = null
        }
        val promptKey = "${envelope.deviceId.length}:${envelope.deviceId}:${envelope.sessionId}:${envelope.promptId.length}:${envelope.promptId}"
        if (promptKey in postedPromptKeys || envelope.ticket in usedTickets) return false
        postedPromptKeys += promptKey
        usedTickets += envelope.ticket
        return true
    }

    private fun invokeWithSystemIdentity(
        envelope: OfficialToastWire.Envelope,
        service: Any,
        method: Method,
        payload: Bundle,
    ): Throwable? {
        val threadState = internalMutation.get()
        if (threadState != null) return IllegalStateException("nested_guarded_statusbar_mutation")
        val identity = Binder.clearCallingIdentity()
        internalMutation.set(envelope to payload)
        return try {
            method.invoke(service, OfficialToastWire.STATUS_BAR_WHAT, OfficialToastWire.STATUS_BAR_ACTION, payload)
            null
        } catch (failure: Throwable) {
            rootCause(failure)
        } finally {
            internalMutation.remove()
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun isOpenRoseUid(uid: Int): Boolean = packagesForUid(uid).contains(BridgeWire.APP_PACKAGE)

    private fun isXiaomiBluetoothUid(uid: Int): Boolean = packagesForUid(uid).contains(OfficialToastWire.TARGET_PACKAGE)

    private fun packagesForUid(uid: Int): Set<String> = try {
        systemContext?.packageManager?.getPackagesForUid(uid)?.toSet().orEmpty()
    } catch (_: RuntimeException) {
        emptySet()
    }

    private fun currentStatusBarBinder(serviceManager: Class<*>): IBinder? = try {
        val method = serviceManager.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        method.invoke(null, STATUS_BAR_SERVICE_NAME) as? IBinder
    } catch (failure: Throwable) {
        xposed.log(Log.WARN, TAG, "statusbar_service_initial_probe_failed:${rootCause(failure).javaClass.simpleName}")
        null
    }

    private fun resolveSetStatus(type: Class<*>): Method {
        val method = type.methods.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            candidate.name == "setStatus" && candidate.returnType == Void.TYPE &&
                !Modifier.isStatic(candidate.modifiers) && parameters.size == 3 &&
                parameters[0] == Int::class.javaPrimitiveType && parameters[1] == String::class.java &&
                parameters[2] == Bundle::class.java
        } ?: throw NoSuchMethodException("${type.name}.setStatus(int, String, Bundle):void")
        return method
    }

    private fun isAddServiceSignature(method: Method): Boolean {
        val parameters = method.parameterTypes
        return method.name == "addService" && Modifier.isStatic(method.modifiers) && parameters.size >= 2 &&
            parameters[0] == String::class.java && parameters[1] == IBinder::class.java
    }

    private fun resolveSystemContext(classLoader: ClassLoader): Context {
        val type = Class.forName("android.app.ActivityThread", false, classLoader)
        val current = type.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: throw IllegalStateException("activity_thread_unavailable")
        return type.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            .invoke(current) as? Context ?: throw IllegalStateException("system_context_unavailable")
    }

    private fun markUnavailable(reason: String) {
        synchronized(stateLock) {
            statusBarReady = false
            statusBarService = null
            statusBarSetStatus = null
            owner = null
            unavailableReason = reason
        }
        xposed.log(Log.ERROR, TAG, reason)
        runCatching { onReady(null) }
    }

    private fun rootCause(failure: Throwable): Throwable {
        var current = failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    companion object {
        private const val TAG = "OpenRoseToastGuard"
        private const val STATUS_BAR_SERVICE_NAME = "statusbar"
    }
}
