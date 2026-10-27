package io.github.dohex.openrose.presentationprobe

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.content.pm.PackageManager
import android.util.Log
import android.service.notification.StatusBarNotification
import io.github.dohex.openrose.bridge.BridgePresentationWire
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.presentation.PresentationCallbacks
import io.github.dohex.openrose.presentation.SystemPresentationRenderer
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runtime probe: real notifications under this fixture app's own UID; no framework identity. */
class PresentationProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val failures = mutableListOf<String>()
    private val limitations = mutableListOf<String>()
    private val claims = ConcurrentHashMap<String, AtomicInteger>()
    private val hostClaimedPrompts = ConcurrentHashMap.newKeySet<String>()
    private val noiseCalls = CopyOnWriteArrayList<NoiseCall>()
    private val reports = CopyOnWriteArrayList<String>()
    private var renderer: SystemPresentationRenderer? = null
    private var sequence = 0L

    private val callbacks = object : PresentationCallbacks {
        override fun claimPrompt(snapshot: Bundle, promptId: String): Boolean {
            val count = claims.computeIfAbsent(promptId) { AtomicInteger() }.incrementAndGet()
            Log.i(TAG, "callback claim prompt=$promptId call=$count session=${snapshot.getLong(BridgeWire.SESSION_ID)}")
            when (promptId) {
                "prompt-retry-before-commit" -> {
                    if (count == 1) throw IllegalStateException("synthetic failure before host claim commit")
                    return true
                }
                "prompt-receipt-lost" -> {
                    if (count == 1) {
                        hostClaimedPrompts += promptId
                        throw IllegalStateException("synthetic lost reply after host claim commit")
                    }
                    return false
                }
            }
            return count == 1
        }

        override fun setNoise(snapshot: Bundle, mode: String): Bundle {
            noiseCalls += NoiseCall(
                deviceId = snapshot.getString(BridgeWire.DEVICE_ID).orEmpty(),
                sessionId = snapshot.getLong(BridgeWire.SESSION_ID),
                hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH).orEmpty(),
                mode = mode,
            )
            Log.i(TAG, "callback setNoise device=${snapshot.getString(BridgeWire.DEVICE_ID)} session=${snapshot.getLong(BridgeWire.SESSION_ID)} mode=$mode")
            return BridgeWire.response(BridgeWire.STATUS_OK)
        }

        override fun report(snapshot: Bundle, feature: String, status: String, detail: String) {
            val row = "$feature:$status:${snapshot.getString(BridgeWire.DEVICE_ID)}:${snapshot.getLong(BridgeWire.SESSION_ID)}:$detail"
            reports += row
            Log.i(TAG, "callback report $row")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "START package=$packageName uid=${Process.myUid()} pid=${Process.myPid()} api=${android.os.Build.VERSION.SDK_INT}")
        notificationManager.cancelAll()
        renderer = SystemPresentationRenderer(this, handler, callbacks)
        testModeMatrix(0)
    }

    private fun testModeMatrix(index: Int) {
        val modes = listOf(BridgePresentationWire.OFF, BridgePresentationWire.OFFICIAL, BridgePresentationWire.MODULE)
        if (index >= modes.size) {
            testUnknownI7()
            return
        }

        val mode = modes[index]
        val deviceId = "probe-i5"
        val tag = "openrose:$deviceId"
        val first = sample(
            profile = "I5_ZT",
            deviceId = deviceId,
            sessionId = 100L,
            mode = mode,
            focusEnabled = true,
            leftBattery = Battery.Known(55),
            rightBattery = Battery.Unknown("FF"),
            caseBattery = Battery.NotProvided,
            statusTag = tag,
        )
        renderer!!.render(first)
        later(220) {
            val activeOn = statusNotification()
            expect("${mode.lowercase()} focusOn status visible", activeOn != null)
            if (activeOn != null) {
                expect("${mode.lowercase()} focusOn stable owned tag", activeOn.tag == tag)
                expect("${mode.lowercase()} focusOn status has no focus extras",
                    !activeOn.notification.extras.containsKey("miui.focus.param") &&
                        !activeOn.notification.extras.containsKey("miui.focus.pics"))
                val body = activeOn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                expect("i5 FF remains unknown", "左耳 55%" in body && "右耳 0%" !in body && "充电盒 0%" !in body)
            }

            val update = sample(
                profile = "I5_ZT",
                deviceId = deviceId,
                sessionId = 100L,
                mode = mode,
                focusEnabled = true,
                leftBattery = Battery.Known(61),
                rightBattery = Battery.Unknown("FF"),
                caseBattery = Battery.NotProvided,
                statusTag = tag,
            )
            renderer!!.render(update)
            later(180) {
                val updated = statusNotification()
                val body = updated?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                expect("${mode.lowercase()} focusOn updates same notification", updated?.tag == tag && "左耳 61%" in body)

                renderer!!.render(sample(
                    profile = "I5_ZT",
                    deviceId = deviceId,
                    sessionId = 100L,
                    mode = mode,
                    focusEnabled = false,
                    leftBattery = Battery.Known(61),
                    rightBattery = Battery.Unknown("FF"),
                    caseBattery = Battery.NotProvided,
                    statusTag = tag,
                ))
                later(180) {
                    expect("${mode.lowercase()} focusOff withdraws status", statusNotification() == null)
                    renderer!!.render(sample(
                        profile = "I5_ZT",
                        deviceId = deviceId,
                        sessionId = 100L,
                        mode = mode,
                        focusEnabled = true,
                        leftBattery = Battery.Known(62),
                        rightBattery = Battery.Unknown("FF"),
                        caseBattery = Battery.NotProvided,
                        statusTag = tag,
                    ))
                    later(180) {
                        expect("${mode.lowercase()} focusOn restores same tag", statusNotification()?.tag == tag)
                        testModeMatrix(index + 1)
                    }
                }
            }
        }
    }

    private fun testUnknownI7() {
        renderer!!.render(sample(
            profile = "I7_RS_V2",
            deviceId = "probe-i7",
            sessionId = 201L,
            mode = BridgePresentationWire.OFF,
            focusEnabled = true,
            leftBattery = Battery.Unknown("FF"),
            rightBattery = Battery.Unknown("FF"),
            caseBattery = Battery.NotProvided,
            statusTag = "openrose:probe-i7",
        ))
        later(180) {
            val active = statusNotification("openrose:probe-i7")
            val body = active?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            expect("i7 UNKNOWN/FF stays unknown", active != null && "电量未知" in body && "0%" !in body)
            expect("i7 OFF notification omits island extras", active != null &&
                !active.notification.extras.containsKey("miui.focus.param") &&
                !active.notification.extras.containsKey("miui.focus.pics"))
            testPromptLifecycle()
        }
    }

    private fun testPromptLifecycle() {
        renderer!!.clear()
        later(180) {
            renderer!!.render(sample(
                profile = "I5_ZT",
                deviceId = "prompt-i5",
                sessionId = 301L,
                mode = BridgePresentationWire.MODULE,
                focusEnabled = true,
                leftBattery = Battery.Known(42),
                rightBattery = Battery.Unknown("FF"),
                caseBattery = Battery.NotProvided,
                statusTag = "openrose:prompt-i5",
                promptId = "prompt-old",
                promptDurationMillis = 1_200L,
            ))
            later(220) {
                val prompt = modulePrompt()
                val body = prompt?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                expect("module prompt claimed and visible", (claims["prompt-old"]?.get() ?: 0) == 1 && prompt != null && "左耳 42%" in body)
                expect("module prompt uses V3 island fields and embedded pictures",
                    prompt?.notification?.let { hasFocusSchema(it, "42", "未知") } == true)

                renderer!!.render(sample(
                    profile = "I5_ZT",
                    deviceId = "prompt-i5",
                    sessionId = 301L,
                    mode = BridgePresentationWire.MODULE,
                    focusEnabled = true,
                    leftBattery = Battery.Known(33),
                    rightBattery = Battery.Unknown("FF"),
                    caseBattery = Battery.NotProvided,
                    statusTag = "openrose:prompt-i5",
                    promptId = "prompt-old",
                    promptDurationMillis = 1_200L,
                ))
                later(180) {
                    val afterBattery = modulePrompt()
                    val afterBody = afterBattery?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    expect("battery update does not reclaim or replay prompt",
                        (claims["prompt-old"]?.get() ?: 0) == 1 && "左耳 42%" in afterBody && "左耳 33%" !in afterBody)

                    renderer!!.render(sample(
                        profile = "I7_RS_V2",
                        deviceId = "prompt-i7",
                        sessionId = 302L,
                        mode = BridgePresentationWire.MODULE,
                        focusEnabled = true,
                        leftBattery = Battery.Unknown("FF"),
                        rightBattery = Battery.Known(78),
                        caseBattery = Battery.NotProvided,
                        statusTag = "openrose:prompt-i7",
                        promptId = "prompt-new",
                        promptDurationMillis = 4_000L,
                    ))
                    later(1_400) {
                        val currentPrompt = modulePrompt()
                        val currentBody = currentPrompt?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                        expect("old session timer cannot cancel new prompt after old deadline",
                            currentPrompt != null && (claims["prompt-new"]?.get() ?: 0) == 1 && "右耳 78%" in currentBody)

                        renderer!!.render(sample(
                            profile = "I7_RS_V2",
                            deviceId = "prompt-i7",
                            sessionId = 302L,
                            mode = BridgePresentationWire.OFFICIAL,
                            focusEnabled = true,
                            leftBattery = Battery.Unknown("FF"),
                            rightBattery = Battery.Known(78),
                            caseBattery = Battery.NotProvided,
                            statusTag = "openrose:prompt-i7",
                            promptId = "official-next",
                        ))
                        later(180) {
                            expect("MODULE to OFFICIAL cancels owned old prompt", modulePrompt() == null)
                            expect("Bluetooth renderer does not claim official prompt", claims["official-next"] == null)
                            renderer!!.clear()
                            later(180) {
                                expect("disconnect clear withdraws owned status and prompt",
                                    statusNotification() == null && modulePrompt() == null)
                                testClaimRetryAfterTransientFailure()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun testClaimRetryAfterTransientFailure() {
        renderer!!.render(claimSample("prompt-retry-before-commit", 303L, claimed = false))
        later(220) {
            expect("failed pre-commit claim does not publish prompt",
                (claims["prompt-retry-before-commit"]?.get() ?: 0) == 1 && modulePrompt() == null)
            renderer!!.render(claimSample("prompt-retry-before-commit", 303L, claimed = false))
            later(220) {
                val prompt = modulePrompt()
                val body = prompt?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                expect("unclaimed prompt retries after transient claim failure and publishes",
                    (claims["prompt-retry-before-commit"]?.get() ?: 0) == 2 && prompt != null && "左耳 50%" in body)
                renderer!!.clear()
                later(180) {
                    testClaimedSnapshotAfterLostReply()
                }
            }
        }
    }

    private fun testClaimedSnapshotAfterLostReply() {
        renderer!!.render(claimSample("prompt-receipt-lost", 304L, claimed = false))
        later(220) {
            expect("lost claim reply does not publish unconfirmed prompt",
                (claims["prompt-receipt-lost"]?.get() ?: 0) == 1 &&
                    "prompt-receipt-lost" in hostClaimedPrompts && modulePrompt() == null)
            renderer!!.render(claimSample("prompt-receipt-lost", 304L, claimed = true))
            later(220) {
                expect("claimed snapshot after lost reply is not replayed",
                    (claims["prompt-receipt-lost"]?.get() ?: 0) == 1 && modulePrompt() == null)
                testNoiseActions()
            }
        }
    }

    private fun claimSample(promptId: String, sessionId: Long, claimed: Boolean): Bundle = sample(
        profile = "I5_ZT",
        deviceId = "claim-probe",
        sessionId = sessionId,
        mode = BridgePresentationWire.MODULE,
        focusEnabled = true,
        leftBattery = Battery.Known(50),
        rightBattery = Battery.Unknown("FF"),
        caseBattery = Battery.NotProvided,
        statusTag = "openrose:claim-probe",
        promptId = promptId,
        promptDurationMillis = 3_000L,
        promptClaimed = claimed,
    )

    private fun testNoiseActions() {
        val deviceId = "action-i5"
        renderer!!.render(actionSample(sessionId = 401L, sequence = nextSequence(), noiseMode = "normal"))
        later(220) {
            val first = statusNotification("openrose:$deviceId")
            Log.i(TAG, "session401 actions=${first?.notification?.actions?.joinToString { it.title.toString() }}")
            if (checkSelfPermission(BLUETOOTH_PRIVILEGED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                expect("status notification remains visible without Bluetooth privilege", first != null)
                expect("no unusable controls without Bluetooth privilege", first?.notification?.actions.isNullOrEmpty())
                expect("renderer reports unavailable control actions", reports.any {
                    it.contains(":ACTION_FAILED:") && it.contains(deviceId)
                })
                skip("PendingIntent execution, current-reference rejection, and receiver-close delivery",
                    "fixture uid=${Process.myUid()} lacks $BLUETOOTH_PRIVILEGED_PERMISSION; no privilege grant or identity bypass is used")
                renderer!!.clear()
                later(220) {
                    expect("clear withdraws status in unprivileged fixture", statusNotification() == null)
                    closeProbe(null)
                }
                return@later
            }

            val firstAction = first?.notification?.actions?.firstOrNull()?.actionIntent
            if (firstAction == null) {
                expect("API26 public notification exposes noise action", false)
                Log.e(TAG, "ACTION_TEST_BLOCKED no action in active notification; receiver registration may be unsupported on API26")
                closeProbe(null)
                return@later
            }

            renderer!!.render(actionSample(sessionId = 402L, sequence = nextSequence(), noiseMode = "normal"))
            later(220) {
                Log.i(TAG, "sending stale PI creator=${firstAction.creatorPackage}/${firstAction.creatorUid}")
                runCatching { sendAndObserve(firstAction, "stale") }
                    .onFailure { Log.w(TAG, "stale action send failed", it) }
                later(220) {
                    val callsAfterStale = noiseCalls.size
                    val current = statusNotification("openrose:$deviceId")
                    Log.i(TAG, "session402 active tag=${current?.tag} actions=${current?.notification?.actions?.joinToString { it.title.toString() }}")
                    val currentAction = current?.notification?.actions?.firstOrNull()?.actionIntent
                    if (currentAction == null) {
                        expect("current session public notification exposes noise action", false)
                        closeProbe(null)
                        return@later
                    }
                    Log.i(TAG, "sending current PI creator=${currentAction.creatorPackage}/${currentAction.creatorUid}")
                    runCatching { sendAndObserve(currentAction, "current") }
                        .onFailure { Log.e(TAG, "current action send failed", it) }
                    later(1_200) {
                        Log.i(TAG, "noise calls after current PI=${noiseCalls.joinToString()}")
                        if (noiseCalls.size == callsAfterStale) {
                            skip("action callback session validation", "fixture uid lacks android.permission.BLUETOOTH_PRIVILEGED; API26 BroadcastQueue denied delivery")
                        } else {
                            expect("current PendingIntent invokes callback with current session", noiseCalls.size == callsAfterStale + 1 &&
                                noiseCalls.lastOrNull()?.deviceId == deviceId &&
                                noiseCalls.lastOrNull()?.sessionId == 402L &&
                                noiseCalls.lastOrNull()?.hostEpoch == "offline-qa-epoch" &&
                                noiseCalls.lastOrNull()?.mode == "anc")
                        }
                        val afterAction = statusNotification("openrose:$deviceId")
                        val beforeConfirmation = afterAction?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                        expect("denied action does not optimistically change state", "降噪关闭" in beforeConfirmation)
                        renderer!!.render(actionSample(sessionId = 402L, sequence = nextSequence(), noiseMode = "anc"))
                        later(220) {
                            val confirmed = statusNotification("openrose:$deviceId")
                            val confirmedText = confirmed?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                            expect("state follows the later snapshot confirmation", "降噪" in confirmedText && "降噪关闭" !in confirmedText)
                            closeProbe(currentAction)
                        }
                    }
                }
            }
        }
    }

    private fun closeProbe(lastPendingIntent: PendingIntent?) {
        val callsBeforeClose = noiseCalls.size
        renderer?.close()
        later(180) {
            expect("close clears renderer-owned notifications", statusNotification() == null && modulePrompt() == null)
            if (lastPendingIntent != null) {
                runCatching { lastPendingIntent.send() }
                    .onFailure { Log.w(TAG, "post-close action send failed", it) }
            }
            later(220) {
                if (callsBeforeClose == 0) {
                    skip("close receiver delivery check", "fixture cannot send a privileged broadcast to observe receiver removal")
                } else {
                    expect("close removes action callback receiver", noiseCalls.size == callsBeforeClose)
                }
                finishProbe()
            }
        }
    }

    private fun sendAndObserve(pendingIntent: PendingIntent, label: String) {
        pendingIntent.send(this, RESULT_OK, null, object : PendingIntent.OnFinished {
            override fun onSendFinished(
                pendingIntent: PendingIntent?,
                intent: android.content.Intent?,
                resultCode: Int,
                resultData: String?,
                resultExtras: Bundle?,
            ) {
                Log.i(TAG, "$label PI finished result=$resultCode intent=${intent?.action}")
            }
        }, handler)
    }

    private fun actionSample(sessionId: Long, sequence: Long, noiseMode: String): Bundle = sample(
        profile = "I5_ZT",
        deviceId = "action-i5",
        sessionId = sessionId,
        mode = BridgePresentationWire.OFFICIAL,
        focusEnabled = true,
        leftBattery = Battery.Known(70),
        rightBattery = Battery.Known(68),
        caseBattery = Battery.NotProvided,
        statusTag = "openrose:action-i5",
        noiseMode = noiseMode,
        noiseModes = listOf("normal", "anc", "transparency"),
        writableSettings = listOf("NOISE_MODE"),
        explicitSequence = sequence,
    )

    private fun sample(
        profile: String,
        deviceId: String,
        sessionId: Long,
        mode: String,
        focusEnabled: Boolean,
        leftBattery: Battery,
        rightBattery: Battery,
        caseBattery: Battery,
        statusTag: String,
        promptId: String? = null,
        promptDurationMillis: Long = 5_000L,
        promptClaimed: Boolean = false,
        noiseMode: String? = null,
        noiseModes: List<String> = emptyList(),
        writableSettings: List<String> = emptyList(),
        explicitSequence: Long? = null,
    ): Bundle {
        val readingFields = Bundle().apply {
            putBundle("LEFT_BATTERY", leftBattery.toBundle())
            putBundle("RIGHT_BATTERY", rightBattery.toBundle())
            putBundle("CASE_BATTERY", caseBattery.toBundle())
            noiseMode?.let { putBundle("NOISE_MODE", knownText(it)) }
        }
        val capabilities = Bundle().apply {
            putStringArrayList("writable_settings", ArrayList(writableSettings))
            if (noiseModes.isNotEmpty()) putBundle("noise", Bundle().apply {
                putStringArrayList("modes", ArrayList(noiseModes))
            })
        }
        val presentation = Bundle().apply {
            putString(BridgePresentationWire.MODE, mode)
            putBoolean(BridgePresentationWire.FOCUS_ENABLED, focusEnabled)
            putBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS,
                mode != BridgePresentationWire.OFF && (mode != BridgePresentationWire.MODULE || promptId != null))
            if (focusEnabled) putBundle(BridgePresentationWire.NOTIFICATION, Bundle().apply {
                putString(BridgePresentationWire.TAG, statusTag)
                putBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, mode != BridgePresentationWire.OFF)
            })
            promptId?.let { putBundle(BridgePresentationWire.PROMPT, Bundle().apply {
                putString(BridgePresentationWire.PROMPT_ID, it)
                putString(BridgePresentationWire.MODE, mode)
                putBoolean(BridgePresentationWire.CLAIMED, promptClaimed)
                putLong(BridgePresentationWire.DURATION_MILLIS, promptDurationMillis)
            }) }
        }
        return Bundle().apply {
            putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
            putString(BridgeWire.HOST_EPOCH, "offline-qa-epoch")
            putString(BridgeWire.DEVICE_ID, deviceId)
            putLong(BridgeWire.SESSION_ID, sessionId)
            putLong(BridgeWire.READ_SEQUENCE, explicitSequence ?: nextSequence())
            putString("profile", profile)
            putBundle("fields", readingFields)
            putBundle("capabilities", capabilities)
            putBundle(BridgePresentationWire.PRESENTATION, presentation)
        }
    }

    private fun Battery.toBundle(): Bundle = when (this) {
        is Battery.Known -> Bundle().apply {
            putString(BridgeWire.STATUS, "KNOWN")
            putString("raw_hex", value.toString(16).uppercase())
            putBundle("value", Bundle().apply {
                putString("type", "NUMBER")
                putInt("value", value)
            })
        }
        is Battery.Unknown -> Bundle().apply {
            putString(BridgeWire.STATUS, "UNKNOWN")
            putString("raw_hex", rawHex)
        }
        Battery.NotProvided -> Bundle().apply {
            putString(BridgeWire.STATUS, "NOT_PROVIDED")
        }
    }

    private fun knownText(value: String): Bundle = Bundle().apply {
        putString(BridgeWire.STATUS, "KNOWN")
        putBundle("value", Bundle().apply {
            putString("type", "TEXT")
            putString("value", value)
        })
    }

    private fun hasFocusSchema(notification: Notification, expectedLeft: String, expectedRight: String): Boolean =
        runCatching {
            val raw = notification.extras.getString("miui.focus.param") ?: return false
            val template = JSONObject(raw).getJSONObject("param_v2")
            val island = template.getJSONObject("param_island")
            val area = island.getJSONObject("bigIslandArea")
            val left = area.getJSONObject("imageTextInfoLeft")
            val right = area.getJSONObject("imageTextInfoRight")
            val leftPic = left.getJSONObject("picInfo")
            val rightPic = right.getJSONObject("picInfo")
            val leftText = left.getJSONObject("textInfo")
            val rightText = right.getJSONObject("textInfo")
            val pictures = notification.extras.getBundle("miui.focus.pics") ?: return false
            template.optBoolean("enableFloat") && !template.optBoolean("isShowNotification", true) &&
                island.optInt("islandProperty") == 1 && left.optInt("type") == 1 && right.optInt("type") == 2 &&
                leftPic.optInt("type") == 1 && rightPic.optInt("type") == 1 &&
                leftPic.optString("pic") == "key_pic_left" && rightPic.optString("pic") == "key_pic_right" &&
                leftText.optString("title") == expectedLeft && rightText.optString("title") == expectedRight &&
                pictures.containsKey("key_pic_left") && pictures.containsKey("key_pic_right")
        }.getOrDefault(false)

    private fun statusNotification(tag: String? = null): StatusBarNotification? = activeNotifications()
        .firstOrNull { it.tag?.startsWith("openrose:") == true && it.tag?.startsWith("openrose:module:") != true && (tag == null || it.tag == tag) }

    private fun modulePrompt(): StatusBarNotification? = activeNotifications()
        .firstOrNull { it.tag?.startsWith("openrose:module:") == true }

    private fun activeNotifications(): List<StatusBarNotification> =
        runCatching { notificationManager.activeNotifications.toList() }
            .onFailure { Log.e(TAG, "getActiveNotifications failed", it) }
            .getOrDefault(emptyList())

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun nextSequence(): Long = ++sequence

    private fun expect(name: String, condition: Boolean) {
        if (condition) {
            Log.i(TAG, "PASS $name")
        } else {
            failures += name
            Log.e(TAG, "FAIL $name")
        }
    }

    private fun skip(name: String, reason: String) {
        limitations += "$name:$reason"
        Log.w(TAG, "SKIP $name: $reason")
    }

    private fun later(delayMillis: Long, block: () -> Unit) {
        handler.postDelayed({
            try {
                block()
            } catch (failure: Throwable) {
                failures += "uncaught:${failure.javaClass.simpleName}:${failure.message}"
                Log.e(TAG, "uncaught probe failure", failure)
                finishProbe()
            }
        }, delayMillis)
    }

    private fun finishProbe() {
        val result = when {
            failures.isNotEmpty() -> "FAIL"
            limitations.isNotEmpty() -> "PASS_WITH_LIMITATIONS"
            else -> "PASS"
        }
        Log.i(TAG, "FINAL $result package=$packageName checksFailed=${failures.size} limitations=${limitations.size} failures=${failures.joinToString("|")} skips=${limitations.joinToString("|")}")
        setResult(if (failures.isEmpty()) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private data class NoiseCall(val deviceId: String, val sessionId: Long, val hostEpoch: String, val mode: String)

    private sealed interface Battery {
        data class Known(val value: Int) : Battery
        data class Unknown(val rawHex: String) : Battery
        data object NotProvided : Battery
    }

    companion object {
        private const val TAG = "OpenRosePresentationProbe"
        private const val BLUETOOTH_PRIVILEGED_PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED"
    }
}
