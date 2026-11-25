package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.IslandPrompt
import io.github.dohex.openrose.androidintegration.IslandTiming
import io.github.dohex.openrose.androidintegration.StatusNotificationTarget
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgePresentationWireTest {
    private val reference = SessionReference(DeviceId("AA:BB:CC:DD:EE:01"), 7)

    @Test
    fun anOpenRosePromptWithItsStatusNotificationIsEncodedWithTheTypesTheConsumersRead() {
        val state = SystemPresentationState(
            reference = reference,
            notificationTag = "rose-a",
            islandMode = IslandMode.OPENROSE,
            statusNotificationEnabled = true,
            allowIslandFields = true,
            statusNotificationTarget = StatusNotificationTarget.ROSELINK,
            prompt = IslandPrompt("p1", IslandTiming.IN_CASE, IslandMode.OPENROSE, reference, claimed = true, durationMillis = 4_000),
        )

        val wire = state.toBridgePresentation().asWireMap()

        assertEquals(BridgePresentationWire.OPENROSE, wire.string(BridgePresentationWire.MODE))
        assertEquals(true, wire.boolean(BridgePresentationWire.STATUS_NOTIFICATION_ENABLED))
        assertEquals(true, wire.boolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS))
        assertEquals(true, wire.boolean(BridgePresentationWire.STATUS_SUMMARY_ISLAND))
        assertEquals(BridgePresentationWire.TARGET_ROSELINK, wire.string(BridgePresentationWire.STATUS_TARGET))
        assertEquals("openrose:rose-a", wire.child(BridgePresentationWire.NOTIFICATION)?.string(BridgePresentationWire.TAG))
        val prompt = requireNotNull(wire.child(BridgePresentationWire.PROMPT))
        assertEquals("p1", prompt.string(BridgePresentationWire.PROMPT_ID))
        assertEquals(BridgePresentationWire.OPENROSE, prompt.string(BridgePresentationWire.MODE))
        assertEquals(BridgePresentationWire.TIMING_IN_CASE, prompt.string(BridgePresentationWire.TIMING))
        assertEquals(true, prompt.boolean(BridgePresentationWire.CLAIMED))
        assertIs<Long>(prompt.value(BridgePresentationWire.DURATION_MILLIS))
        assertEquals(4_000L, prompt.long(BridgePresentationWire.DURATION_MILLIS))
    }

    @Test
    fun withStatusNotificationsOffAndNoPromptNeitherSectionIsSent() {
        val wire = SystemPresentationState(reference = reference, statusNotificationEnabled = false).toBridgePresentation().asWireMap()

        assertEquals(false, wire.boolean(BridgePresentationWire.STATUS_NOTIFICATION_ENABLED))

        assertNull(wire.child(BridgePresentationWire.NOTIFICATION))
        assertNull(wire.child(BridgePresentationWire.PROMPT))
    }

    @Test
    fun everyIslandModeIsOneOfTheModesTheConsumersRecognise() {
        val recognised = setOf(BridgePresentationWire.OFF, BridgePresentationWire.SYSTEM, BridgePresentationWire.OPENROSE)
        IslandMode.entries.forEach { mode ->
            val sent = SystemPresentationState(islandMode = mode).toBridgePresentation().asWireMap().string(BridgePresentationWire.MODE)
            assertTrue(sent in recognised, "$mode is sent as $sent")
        }
    }

    @Test
    fun everyStatusNotificationTargetIsSentAsOneOfTheTargetsTheRendererRecognises() {
        val recognised = mapOf(
            StatusNotificationTarget.OPENROSE to BridgePresentationWire.TARGET_OPENROSE,
            StatusNotificationTarget.SYSTEM to BridgePresentationWire.TARGET_SYSTEM,
            StatusNotificationTarget.ROSELINK to BridgePresentationWire.TARGET_ROSELINK,
            StatusNotificationTarget.QUICK_PANEL to BridgePresentationWire.TARGET_QUICK_PANEL,
        )
        assertEquals(StatusNotificationTarget.entries.toSet(), recognised.keys)
        recognised.forEach { (target, expected) ->
            val sent = SystemPresentationState(statusNotificationTarget = target).toBridgePresentation().asWireMap()
                .string(BridgePresentationWire.STATUS_TARGET)
            assertEquals(expected, sent)
        }
    }
}
