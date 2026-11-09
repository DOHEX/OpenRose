package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.IslandPrompt
import io.github.dohex.openrose.androidintegration.IslandTrigger
import io.github.dohex.openrose.androidintegration.StatusNotificationTarget
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system-side consumers read the presentation through typed Bundle getters and compare the mode with the wire
 * constants, so each value must carry the getter's type: a duration sent as Int would read as the default.
 */
class BridgePresentationWireTest {
    private val reference = SessionReference(DeviceId("AA:BB:CC:DD:EE:01"), 7)

    @Test
    fun aModulePromptWithItsStatusNotificationIsEncodedWithTheTypesTheConsumersRead() {
        val state = SystemPresentationState(
            reference = reference,
            notificationTag = "rose-a",
            islandMode = IslandMode.MODULE,
            focusNotificationEnabled = true,
            allowIslandFields = true,
            statusNotificationTarget = StatusNotificationTarget.ROSELINK,
            prompt = IslandPrompt("p1", IslandTrigger.IN_CASE, IslandMode.MODULE, reference, claimed = true, durationMillis = 4_000),
        )

        val wire = state.toBridgePresentation().asWireMap()

        assertEquals(BridgePresentationWire.MODULE, wire.string(BridgePresentationWire.MODE))
        assertEquals(true, wire.boolean(BridgePresentationWire.FOCUS_ENABLED))
        assertEquals(true, wire.boolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS))
        assertEquals(true, wire.boolean(BridgePresentationWire.STATUS_SUMMARY_ISLAND))
        assertEquals(BridgePresentationWire.TARGET_ROSELINK, wire.string(BridgePresentationWire.STATUS_TARGET))
        assertEquals("openrose:rose-a", wire.child(BridgePresentationWire.NOTIFICATION)?.string(BridgePresentationWire.TAG))
        val prompt = requireNotNull(wire.child(BridgePresentationWire.PROMPT))
        assertEquals("p1", prompt.string(BridgePresentationWire.PROMPT_ID))
        assertEquals(BridgePresentationWire.MODULE, prompt.string(BridgePresentationWire.MODE))
        assertEquals(BridgePresentationWire.TRIGGER_IN_CASE, prompt.string(BridgePresentationWire.TRIGGER))
        assertEquals(true, prompt.boolean(BridgePresentationWire.CLAIMED))
        assertIs<Long>(prompt.value(BridgePresentationWire.DURATION_MILLIS))
        assertEquals(4_000L, prompt.long(BridgePresentationWire.DURATION_MILLIS))
    }

    @Test
    fun withFocusNotificationsOffAndNoPromptNeitherSectionIsSent() {
        val wire = SystemPresentationState(reference = reference, focusNotificationEnabled = false).toBridgePresentation().asWireMap()

        assertEquals(false, wire.boolean(BridgePresentationWire.FOCUS_ENABLED))

        assertNull(wire.child(BridgePresentationWire.NOTIFICATION))
        assertNull(wire.child(BridgePresentationWire.PROMPT))
    }

    @Test
    fun everyIslandModeIsOneOfTheModesTheConsumersRecognise() {
        val recognised = setOf(BridgePresentationWire.OFF, BridgePresentationWire.OFFICIAL, BridgePresentationWire.MODULE)
        IslandMode.entries.forEach { mode ->
            val sent = SystemPresentationState(islandMode = mode).toBridgePresentation().asWireMap().string(BridgePresentationWire.MODE)
            assertTrue(sent in recognised, "$mode is sent as $sent")
        }
    }

    @Test
    fun managedAddressTombstonesMatchAnySpellingOfARememberedAddressUntilCleared() {
        val tombstones = NativeManagedAddressTombstones()
        val snapshot = requireNotNull(
            NativeHeadsetSnapshot.fromWire(
                mapOf<String, Any?>(
                    BridgeWire.KEY_VERSION to BridgeWire.VERSION,
                    BridgeWire.HOST_EPOCH to "epoch",
                    BridgeWire.DEVICE_ID to "rose-a",
                    BridgeWire.SESSION_ID to 7L,
                    "profile" to "I7_RS_V2",
                    "addresses" to listOf("aa:bb:cc:dd:ee:01", "not an address"),
                    "captured_elapsed_millis" to 1L,
                    "capabilities" to emptyMap<String, Any?>(),
                    "fields" to emptyMap<String, Any?>(),
                ).asWireMap(),
            ),
        )
        assertEquals(setOf("AA:BB:CC:DD:EE:01"), snapshot.addresses)

        assertFalse(tombstones.contains("AA:BB:CC:DD:EE:01"))
        tombstones.remember(snapshot)
        assertTrue(tombstones.contains(" aa:bb:cc:dd:ee:01"))
        assertFalse(tombstones.contains("AA:BB:CC:DD:EE:02"))
        assertFalse(tombstones.contains(null))
        tombstones.clear()
        assertFalse(tombstones.contains("AA:BB:CC:DD:EE:01"))
    }
}
