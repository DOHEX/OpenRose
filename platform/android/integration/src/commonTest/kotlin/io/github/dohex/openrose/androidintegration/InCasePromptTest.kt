package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OUT = 0x64
private const val IN = 0xe4
private const val RIGHT_OUT = 0x32
private const val RIGHT_IN = 0xb2
private const val UNAVAILABLE = 0xff

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InCasePromptTest {
    private val openRoseInCase = IntegrationSettings(islandMode = IslandMode.OPENROSE_STYLE, islandTimings = persistentSetOf(IslandTiming.IN_CASE))

    @Test
    fun onlyModelsWithACaseAndInCaseFlagsCanShowAnInCasePrompt() {
        assertEquals(
            setOf(
                ModelProfile.I7_RS_V2,
                ModelProfile.BUDSFEEL_MK2_RS_V2,
                ModelProfile.OPENFEEL_MK2_RS_V2,
                ModelProfile.OPENFEEL_LDAC_RS_COMMON,
                ModelProfile.OPENFEEL_LHDC_RS_COMMON,
                ModelProfile.OPENFEEL_DUAL_RS_COMMON,
                ModelProfile.CERAMICS_MK2_RS_V2,
                ModelProfile.CERAMICS_MK2_8912F_RS_V2,
                ModelProfile.CERAMICS_U_RS_V2,
                ModelProfile.CERAMICS_U_HSC_2710_RS_V2,
                ModelProfile.FURINA_RS_V2,
                ModelProfile.ZIRCON_RS_V2,
                ModelProfile.HYACINTH_RS_V2,
                ModelProfile.ASTRO_X_RS_V2,
                ModelProfile.OPAL_RS_V2,
                ModelProfile.MIRROR_RS_V2,
                ModelProfile.KY01_RS_V2,
                ModelProfile.CORAL_RS_V2,
                ModelProfile.OPENFEEL_L_RS_V2,
                ModelProfile.EARFEEL_IE2_RS_V2,
                ModelProfile.SPORTFEEL_8912F_RS_V2,
            ),
            ModelProfile.entries.filter { it.reportsInCase }.toSet(),
        )
    }

    @Test
    fun theFirstReadingIsNotAnEventAndEachLaterEntryReplacesThePromptOnce() = runTest {
        val (session, link) = connected(openRoseInCase, initial = i7Battery(IN, RIGHT_OUT))
        assertNull(session.presentation.value.prompt, "an earbud already in the case at connection is not an in-case event")
        assertTrue(session.presentation.value.allowIslandFields, "an OpenRose display timing other than connection still allows island content")

        link.deviceReply(i7Battery(OUT, RIGHT_OUT))
        runCurrent()
        assertNull(session.presentation.value.prompt, "taking an earbud out is not an in-case event")

        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        val left = assertNotNull(session.presentation.value.prompt)
        assertEquals(IslandTiming.IN_CASE, left.timing)
        assertEquals(IslandMode.OPENROSE_STYLE, left.mode)

        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        assertEquals(left, session.presentation.value.prompt, "a repeated frame is no new event")

        link.deviceReply(i7Battery(IN, RIGHT_IN))
        runCurrent()
        val right = assertNotNull(session.presentation.value.prompt)
        assertEquals(IslandTiming.IN_CASE, right.timing)
        assertNotEquals(left.id, right.id, "the other earbud entering replaces the showing prompt")
        session.disconnect()
    }

    @Test
    fun anUnavailableFlagBeforeEnteringIsNotAKnownExit() = runTest {
        val (session, link) = connected(openRoseInCase, initial = i7Battery(OUT, RIGHT_OUT))
        link.deviceReply(i7Battery(UNAVAILABLE, RIGHT_OUT))
        runCurrent()
        link.deviceReply(i7Battery(IN, RIGHT_OUT))
        runCurrent()
        assertNull(session.presentation.value.prompt)
        session.disconnect()
    }

    @Test
    fun systemStyleAndAnUnselectedTimingConsumeTheEventWithoutPrompting() = runTest {
        for (settings in listOf(IntegrationSettings(islandMode = IslandMode.SYSTEM_STYLE), IntegrationSettings(islandMode = IslandMode.OPENROSE_STYLE))) {
            val (session, link) = connected(settings, initial = i7Battery(OUT, RIGHT_OUT))
            val connection = session.presentation.value.prompt
            assertEquals(IslandTiming.CONNECTION, connection?.timing)

            link.deviceReply(i7Battery(IN, RIGHT_OUT))
            runCurrent()
            assertEquals(connection, session.presentation.value.prompt, "${settings.islandMode} shows no in-case prompt")

            session.updateIntegrationSettings(openRoseInCase)
            assertNull(session.presentation.value.prompt, "selecting the timing later does not replay the event")
            session.disconnect()
        }
    }

    private suspend fun TestScope.connected(settings: IntegrationSettings, initial: String): Pair<SystemEntryRuntime, HostLink> {
        val earphones = rosePair(APP_HEADSET_ADDRESS, ModelProfile.I7_RS_V2)
        val headset = hostHeadset(SharedHeadset(), earphones, rewriteReply = { if (it.startsWith("dd00040c")) initial else it })
        val session = SystemEntryRuntime(headset.runtime(backgroundScope), backgroundScope, initialIntegrationSettings = settings)
        session.refreshBondedDevices()
        assertEquals(OperationResult.Success, session.connect(earphones.deviceId))
        runCurrent()
        eventually("the presentation to follow the core") { session.presentation.value.reference != null }
        return session to headset.links.single()
    }
}

private fun i7Battery(left: Int, right: Int, case: Int = 0x50): String {
    val body = listOf(0xdd, 0x00, 0x04, 0x0c, left, right, case)
    return (body + (body.sum() and 0xff) + 0xaa).joinToString("") { it.toString(16).padStart(2, '0') }
}
