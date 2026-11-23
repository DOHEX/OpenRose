package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val xiaomiIdentity = CallerIdentity(10_060, 1_001, setOf("com.xiaomi.bluetooth"), "com.xiaomi.bluetooth")
private val hostUiIdentity = CallerIdentity(1_002, 777, setOf(HOST_PACKAGE), HOST_PACKAGE)

private val presentationAuthority = CallerAuthority { identity ->
    when {
        identity.uid == 1_002 -> ClientRole.HOST_PRESENTATION
        APP_PACKAGE in identity.packages -> ClientRole.APP
        "com.milink.service" in identity.packages -> ClientRole.MILINK
        "com.xiaomi.bluetooth" in identity.packages -> ClientRole.XIAOMI_BLUETOOTH
        else -> null
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModuleHostPresentationTest {
    private class Rig(private val test: TestScope, private val settings: IntegrationSettings) {
        val earphones = rosePair("rose-a")
        val headset = SharedHeadset()
        val transport = HeadsetTransport("module", headset, earphones.profile!!, listOf(earphones))
        private val ids = generateSequence(1) { it + 1 }.map { "lease-$it" }.iterator()
        val host = ModuleControlHost(
            packageName = HOST_PACKAGE, processName = HOST_PACKAGE, incarnation = "host-1",
            authority = presentationAuthority,
            runtimeFactory = { scope, saved, platform, base ->
                LocalControlRuntime(
                    platform,
                    saved,
                    transport,
                    scope,
                    SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
                    base,
                )
            },
            parentScope = test.backgroundScope, newId = { ids.next() }, uid = 1_002, pid = 777,
        )
        val app = lease(appIdentity)
        val xiaomi = lease(xiaomiIdentity)
        val hostUi = lease(hostUiIdentity)
        val milink = lease(milinkIdentity)

        class Lease(val identity: CallerIdentity, val id: String)

        private fun lease(identity: CallerIdentity) = Lease(identity, (host.open(identity) as HostOpenResult.Opened).lease.id)

        val generation: OwnerGeneration get() = (host.status.value as HostStatus.Active).generation
        val state: ControlState get() = requireNotNull(host.control.view(app.identity, app.id)).state
        val presentation: SystemPresentationState get() = requireNotNull(host.control.view(app.identity, app.id)).presentation
        val link: HeadsetConnection get() = transport.links.last()
        val prompt: IslandPrompt get() = requireNotNull(presentation.prompt)
        val reference: SessionReference get() = requireNotNull(presentation.reference)

        suspend fun start() {
            host.updatePlatform(PlatformState(PermissionState.GRANTED, TransportAvailability.Available))
            host.configure { HostConfiguration(true, HostSettings(integration = settings.copy(controlWay = ControlWay.MODULE))) }
            test.runCurrent()
            connect()
        }

        suspend fun connect() {
            host.control.execute(app.identity, app.id, generation, HostCommand.ConnectFromSystem(earphones.deviceId))
            test.runCurrent()
        }

        suspend fun execute(lease: Lease, command: HostCommand, at: OwnerGeneration = generation) = host.control.execute(lease.identity, lease.id, at, command)

        suspend fun claim(lease: Lease, at: OwnerGeneration = generation, ref: SessionReference = reference, id: String = prompt.id) = execute(lease, HostCommand.ClaimPrompt(ref, id), at)

        suspend fun updateSettings(change: (IntegrationSettings) -> IntegrationSettings) {
            val view = requireNotNull(host.control.view(app.identity, app.id))
            val saved = HostConfiguration(true, HostSettings(view.state.settings, change(view.settings)))
            host.configure { saved }
            test.runCurrent()
        }
    }

    @Test
    fun onlyTheEntryThatOwnsTheSavedIslandModeMayClaimTheConnectionPromptAndOnlyOnce() = runTest {
        for ((mode, owner) in listOf(IslandMode.OFFICIAL to ClientRole.XIAOMI_BLUETOOTH, IslandMode.MODULE to ClientRole.HOST_PRESENTATION)) {
            val rig = Rig(this, IntegrationSettings(islandMode = mode))
            rig.start()
            val entries = mapOf(
                ClientRole.XIAOMI_BLUETOOTH to rig.xiaomi,
                ClientRole.HOST_PRESENTATION to rig.hostUi,
                ClientRole.MILINK to rig.milink,
                ClientRole.APP to rig.app,
            )
            for ((role, lease) in entries - owner) {
                assertIs<HostCallResult.Rejected>(rig.claim(lease), "$mode prompt must not be claimable by $role")
                assertFalse(rig.prompt.claimed, "$mode prompt stays unclaimed after a refused $role")
            }
            val other = SessionReference(DeviceId("other"), rig.reference.sessionId)
            assertEquals(HostCallResult.Flag(false), rig.claim(entries.getValue(owner), ref = other))
            assertFalse(rig.prompt.claimed)

            assertEquals(HostCallResult.Flag(true), rig.claim(entries.getValue(owner)), "$mode owner claims")
            assertEquals(HostCallResult.Flag(false), rig.claim(entries.getValue(owner)), "$mode claim is atomic")
            assertTrue(rig.prompt.claimed)
        }
    }

    @Test
    fun hidingTheIslandKeepsNoPromptEntryAndNeverStopsTheModuleOwner() = runTest {
        val rig = Rig(this, IntegrationSettings(islandMode = IslandMode.OFF))
        rig.start()

        assertNull(rig.presentation.prompt)
        assertFalse(rig.presentation.allowIslandFields)
        for (lease in listOf(rig.xiaomi, rig.hostUi)) {
            assertIs<HostCallResult.Rejected>(rig.claim(lease, id = "forged"))
        }
        assertIs<ConnectionState.Connected>(rig.state.connection)
        assertEquals(listOf("module:open"), rig.headset.events)
    }

    @Test
    fun everyPresentationChoiceKeepsTheSameOwnerConnectionAndSendsNoCommand() = runTest {
        val rig = Rig(this, IntegrationSettings())
        rig.start()
        val generation = rig.generation
        val sessionId = rig.reference.sessionId
        val writes = rig.link.writes.size

        for (mode in IslandMode.entries) {
            for (focus in listOf(true, false)) {
                rig.updateSettings { it.copy(islandMode = mode, focusNotificationEnabled = focus) }
                assertEquals(generation, rig.generation)
                assertEquals(sessionId, rig.reference.sessionId)
                assertEquals(writes, rig.link.writes.size, "a presentation preference never reaches the earphones")
                assertEquals(listOf("module:open"), rig.headset.events)
                assertIs<ConnectionState.Connected>(rig.state.connection)
            }
        }
    }

    @Test
    fun aNotificationNoiseActionForAStaleOrOtherSessionIsRefusedBeforeAnyWrite() = runTest {
        val rig = Rig(this, IntegrationSettings(islandMode = IslandMode.MODULE))
        rig.start()
        rig.link.initializing = false
        val reference = rig.reference
        val writes = rig.link.writes.size

        val stale = SessionReference(reference.deviceId, reference.sessionId + 1)
        val refused = assertIs<HostCallResult.Operation>(rig.execute(rig.hostUi, HostCommand.Set(stale, HeadsetCommand.NoiseMode(NoiseMode.NORMAL))))
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(refused.result).error.code)
        val other = SessionReference(DeviceId("other"), reference.sessionId)
        assertIs<OperationResult.Failure>(assertIs<HostCallResult.Operation>(rig.execute(rig.hostUi, HostCommand.Set(other, HeadsetCommand.NoiseMode(NoiseMode.NORMAL)))).result)
        assertEquals(writes, rig.link.writes.size, "refused actions never reach the earphones")
    }

    @Test
    fun theOldGenerationCannotActOnTheNextSessionAndTheSavedPresentationApplies() = runTest {
        val saved = IntegrationSettings(islandMode = IslandMode.MODULE, focusNotificationEnabled = false, islandTriggers = persistentSetOf(IslandTrigger.CONNECTION))
        val rig = Rig(this, saved)
        rig.start()
        val old = rig.generation
        val oldPrompt = rig.prompt
        val oldReference = rig.reference

        assertIs<ReleaseResult.Released>(rig.host.release(rig.app.identity, rig.app.id, SwitchRequest("to-ordinary", ControlWay.MODULE, ControlWay.ORDINARY, old)))
        runCurrent()

        assertEquals(listOf("module:open", "module:close"), rig.headset.events)

        val newLease = assertIs<HostOpenResult.Opened>(rig.host.open(appIdentity)).lease.id
        assertIs<AcquireResult.Acquired>(rig.host.acquire(appIdentity, newLease, SwitchRequest("to-module", ControlWay.ORDINARY, ControlWay.MODULE)))
        runCurrent()
        rig.connect()
        val next = rig.generation
        assertNotEquals(old, next)
        assertEquals(IslandMode.MODULE, rig.presentation.islandMode)
        assertFalse(rig.presentation.focusNotificationEnabled)
        assertEquals(
            HostRejection.STALE_GENERATION,
            assertIs<HostCallResult.Rejected>(rig.claim(rig.hostUi, at = old, ref = oldReference, id = oldPrompt.id)).rejection,
        )
        assertFalse(rig.prompt.claimed, "the old generation's refused claim consumed nothing of the new session")
        assertEquals(2, rig.headset.opens("module"), "exactly one fresh control connection for the new owner")
        assertEquals(0, rig.headset.overlappingOpens)
    }
}
