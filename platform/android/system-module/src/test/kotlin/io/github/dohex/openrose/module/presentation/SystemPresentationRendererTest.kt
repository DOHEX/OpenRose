package io.github.dohex.openrose.module.presentation

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.IslandPrompt
import io.github.dohex.openrose.androidintegration.IslandTiming
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.toBridgePresentation
import io.github.dohex.openrose.bridge.toBundle
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SystemPresentationRendererTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = requireNotNull(app.getSystemService(NotificationManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private val claims = mutableListOf<Pair<Long, String>>()
    private val callbacks = object : PresentationCallbacks {
        override fun claimPrompt(snapshot: Bundle, promptId: String): Boolean = claims.add(snapshot.getLong(BridgeWire.SESSION_ID) to promptId)
        override fun setNoise(snapshot: Bundle, mode: String): Bundle = error("Unexpected noise command")
        override fun report(snapshot: Bundle, feature: String, status: String, detail: String) = Unit
    }
    private val renderer = SystemPresentationRenderer(app, handler, callbacks)

    @BeforeTest
    fun grantNotificationPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @AfterTest
    fun closeRenderer() = renderer.close()

    @Test
    fun withdrawingTheStatusCardTransfersTheClaimedPromptForItsRemainingTime() {
        val frame = snapshot(sequence = 5)
        renderer.render(frame)
        val status = manager.activeNotifications.single()
        assertEquals("openrose:headset", status.tag)
        assertTrue(template(status.notification).getBoolean("islandFirstFloat"))

        renderer.render(frame)
        renderer.render(snapshot(sequence = 4, statusNotificationEnabled = false))
        assertEquals(status.tag, manager.activeNotifications.single().tag)

        val looper = shadowOf(handler.looper)
        looper.idleFor(1_500, TimeUnit.MILLISECONDS)
        renderer.render(snapshot(sequence = 6, statusNotificationEnabled = false))
        val prompt = manager.activeNotifications.single()
        assertTrue(prompt.tag.startsWith("openrose:module:"))
        assertEquals(3, template(prompt.notification).getJSONObject("param_island").getInt("islandTimeout"))
        assertEquals(listOf(1L to "connection"), claims)

        looper.idleFor(2_499, TimeUnit.MILLISECONDS)
        assertEquals(prompt.tag, manager.activeNotifications.single().tag)
        looper.idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun aNewSessionCanClaimTheSamePromptAndCleanupPreservesOtherNotifications() {
        renderer.render(snapshot(sequence = 5))
        val owned = manager.activeNotifications.single()
        manager.notify("another-device", owned.id, owned.notification)
        renderer.render(snapshot(sequence = 1, sessionId = 2))
        assertEquals(listOf(1L to "connection", 2L to "connection"), claims)

        renderer.clear()
        assertEquals("another-device", manager.activeNotifications.single().tag)
        renderer.render(snapshot(sequence = 1, sessionId = 3, statusNotificationEnabled = false))
        assertTrue(manager.activeNotifications.any { it.tag.startsWith("openrose:module:") })
        renderer.close()
        renderer.render(snapshot(sequence = 1, sessionId = 4))
        assertEquals("another-device", manager.activeNotifications.single().tag)
        assertEquals(listOf(1L to "connection", 2L to "connection", 3L to "connection"), claims)
    }

    @Test
    fun aliasIsUsedForPromptTitleAndUpdatesStatusNotificationLive() {
        val adapter = requireNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
        val address = "AA:BB:CC:DD:EE:FF"
        val device = adapter.getRemoteDevice(address)
        shadowOf(device).setName("ROSE EarFree i5")
        shadowOf(device).setAlias("主力耳机")

        val frame = snapshot(sequence = 5, address = address)
        renderer.render(frame)
        val status = manager.activeNotifications.single()
        assertEquals("主力耳机", status.notification.extras.getString(Notification.EXTRA_TITLE))

        shadowOf(device).setAlias("通勤耳机")
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_ALIAS_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        shadowOf(handler.looper).idle()
        assertEquals("通勤耳机", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))

        renderer.render(snapshot(sequence = 6, statusNotificationEnabled = false, address = address))
        val prompt = manager.activeNotifications.single()
        assertEquals("通勤耳机", prompt.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    private fun template(notification: Notification): JSONObject = JSONObject(requireNotNull(notification.extras.getString("miui.focus.param"))).getJSONObject("param_v2")

    private fun snapshot(sequence: Long, sessionId: Long = 1, statusNotificationEnabled: Boolean = true, address: String? = null): Bundle {
        val reference = SessionReference(DeviceId("headset"), sessionId)
        val presentation = SystemPresentationState(
            reference = reference,
            notificationTag = "headset",
            islandMode = IslandMode.OPENROSE_STYLE,
            statusNotificationEnabled = statusNotificationEnabled,
            allowIslandFields = true,
            prompt = IslandPrompt("connection", IslandTiming.CONNECTION, IslandMode.OPENROSE_STYLE, reference, durationMillis = 4_000),
        )
        return Bundle().apply {
            putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
            putString(BridgeWire.HOST_EPOCH, "host")
            putString(BridgeWire.DEVICE_ID, reference.deviceId.value)
            address?.let { putString("device_address", it) }
            putLong(BridgeWire.SESSION_ID, sessionId)
            putLong(BridgeWire.READ_SEQUENCE, sequence)
            putBundle("fields", Bundle())
            putBundle("capabilities", Bundle())
            putBundle(BridgePresentationWire.PRESENTATION, presentation.toBridgePresentation().toBundle())
        }
    }
}
