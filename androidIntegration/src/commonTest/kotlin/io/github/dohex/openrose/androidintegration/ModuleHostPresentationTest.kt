package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

private const val I5_BATTERY = "09ff00000101011100000164620000ffe2"
private const val I5_NOISE_NORMAL = "09ff00000106020e000001000020"

/**
 * What the system presentation entries see and can do through the module host: the same single owner
 * the application drives, with the real session and the shared headset fixture. No notification tool
 * gets a seam of its own; the entries are ordinary leases of the host.
 */
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
                    SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
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
        val state: ControlState get() = requireNotNull(host.view(app.identity, app.id)).state
        val presentation: SystemPresentationState get() = requireNotNull(host.view(app.identity, app.id)).presentation
        val link: HeadsetConnection get() = transport.links.last()
        val prompt: ConnectionPrompt get() = requireNotNull(presentation.connectionPrompt)
        val reference: SessionReference get() = requireNotNull(presentation.reference)

        suspend fun start() {
            host.updatePlatform(PlatformState(PermissionState.GRANTED, TransportAvailability.Available))
            host.configure(HostConfiguration(true, HostSettings(integration = settings.copy(controlWay = ControlWay.MODULE))))
            test.runCurrent()
            connect()
        }

        suspend fun connect() {
            host.execute(app.identity, app.id, generation, HostCommand.ConnectFromSystem(earphones.deviceId))
            test.runCurrent()
        }

        suspend fun execute(lease: Lease, command: HostCommand, at: OwnerGeneration = generation) = host.execute(lease.identity, lease.id, at, command)

        suspend fun claim(lease: Lease, at: OwnerGeneration = generation, ref: SessionReference = reference, id: String = prompt.id) = execute(lease, HostCommand.ClaimPrompt(ref, id), at)

        suspend fun updateSettings(change: (IntegrationSettings) -> IntegrationSettings) {
            val view = requireNotNull(host.view(app.identity, app.id))
            assertIs<HostCallResult.Operation>(execute(app, HostCommand.UpdateSettings(HostSettings(view.state.settings, change(view.settings)))))
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
            // A claim naming other earphones' session is the owner's own refusal and consumes nothing.
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

        assertNull(rig.presentation.connectionPrompt)
        assertFalse(rig.presentation.allowIslandFields)
        for (lease in listOf(rig.xiaomi, rig.hostUi)) {
            assertIs<HostCallResult.Rejected>(rig.claim(lease, id = "forged"))
        }
        // Control keeps running for the fusion entries while nothing is presented.
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

                val presentation = rig.presentation
                assertEquals(mode, presentation.islandMode)
                assertEquals(focus, presentation.focusNotificationEnabled)
                assertEquals(focus, presentation.showStatusNotification, "$mode/$focus status notification follows only the focus switch")
                assertEquals(mode != IslandMode.OFF, presentation.allowIslandFields, "$mode/$focus island fields follow only the island mode")
                assertEquals(generation, rig.generation)
                assertEquals(sessionId, rig.reference.sessionId)
                assertEquals(writes, rig.link.writes.size, "a presentation preference never reaches the earphones")
                assertEquals(listOf("module:open"), rig.headset.events)
                assertIs<ConnectionState.Connected>(rig.state.connection)
            }
        }
    }

    @Test
    fun aConsumedConnectionEventIsNotReplayedByModeChangesOrBatteryRefreshes() = runTest {
        val rig = Rig(this, IntegrationSettings(islandMode = IslandMode.OFFICIAL))
        rig.start()
        val first = rig.prompt
        assertEquals(HostCallResult.Flag(true), rig.claim(rig.xiaomi))

        rig.link.deviceReply(I5_BATTERY.replace("6462", "6361"))
        runCurrent()
        assertEquals(PresentationReading.Known(99), rig.presentation.battery?.left)
        assertEquals(first.id, rig.prompt.id)
        assertTrue(rig.prompt.claimed, "a battery refresh keeps the consumed prompt consumed")

        rig.updateSettings { it.copy(islandMode = IslandMode.OFF) }
        assertNull(rig.presentation.connectionPrompt)
        rig.updateSettings { it.copy(islandMode = IslandMode.OFFICIAL) }
        assertNull(rig.presentation.connectionPrompt, "restoring the saved mode does not replay the event")

        advanceTimeBy(6_000)
        runCurrent()
        rig.link.deviceReply(I5_BATTERY)
        runCurrent()
        assertNull(rig.presentation.connectionPrompt)
        assertEquals(listOf("module:open"), rig.headset.events)
    }

    @Test
    fun aNotificationNoiseActionIsConfirmedByTheEarphonesAndOnlyForTheCurrentSession() = runTest {
        val rig = Rig(this, IntegrationSettings(islandMode = IslandMode.MODULE))
        rig.start()
        rig.link.initializing = false
        val reference = rig.reference
        val writes = rig.link.writes.size

        val stale = SessionReference(reference.deviceId, reference.sessionId + 1)
        val refused = assertIs<HostCallResult.Operation>(rig.execute(rig.hostUi, HostCommand.Set(stale, DeviceSetting.NOISE_MODE, FieldValue.Text("normal"))))
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(refused.result).error.code)
        val other = SessionReference(DeviceId("other"), reference.sessionId)
        assertIs<OperationResult.Failure>(assertIs<HostCallResult.Operation>(rig.execute(rig.hostUi, HostCommand.Set(other, DeviceSetting.NOISE_MODE, FieldValue.Text("normal")))).result)
        assertEquals(writes, rig.link.writes.size, "refused actions never reach the earphones")

        val pending = async { rig.execute(rig.hostUi, HostCommand.Set(reference, DeviceSetting.NOISE_MODE, FieldValue.Text("normal"))) }
        runCurrent()
        assertFalse(pending.isCompleted, "reaching the host is not success")
        assertEquals(FieldValue.Text("wind"), noiseMode(rig.state), "the last confirmed value stays until the earphones answer")

        rig.link.deviceReply(I5_NOISE_NORMAL)
        runCurrent()
        assertEquals(OperationResult.Success, assertIs<HostCallResult.Operation>(pending.await()).result)
        assertEquals(FieldValue.Text("normal"), noiseMode(rig.state))
    }

    @Test
    fun leavingModuleControlWithdrawsEveryEntryAndTheOldGenerationCannotActOnTheNextSession() = runTest {
        val saved = IntegrationSettings(islandMode = IslandMode.MODULE, focusNotificationEnabled = false, islandTriggers = persistentSetOf(IslandTrigger.CONNECTION))
        val rig = Rig(this, saved)
        rig.start()
        val old = rig.generation
        val oldPrompt = rig.prompt
        val oldReference = rig.reference

        assertIs<ReleaseResult.Released>(rig.host.release(rig.app.identity, rig.app.id, SwitchRequest("to-ordinary", ControlWay.MODULE, ControlWay.ORDINARY, old)))
        runCurrent()

        for (lease in listOf(rig.xiaomi, rig.hostUi)) {
            assertNull(rig.host.view(lease.identity, lease.id), "no state is offered while ordinary control owns the session")
            assertEquals(HostRejection.NO_OWNER, assertIs<HostCallResult.Rejected>(rig.claim(lease, at = old, ref = oldReference, id = oldPrompt.id)).rejection)
            assertEquals(
                HostRejection.NO_OWNER,
                assertIs<HostReadResult.Rejected>(rig.host.readSession(lease.identity, lease.id, old, oldReference)).rejection,
            )
        }
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)

        // Back to module control: the saved presentation preferences apply, the old event is not replayed.
        assertIs<AcquireResult.Acquired>(rig.host.acquire(rig.app.identity, rig.app.id, SwitchRequest("to-module", ControlWay.ORDINARY, ControlWay.MODULE), HostSettings(integration = saved)))
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
        // Session numbers restart with each owner, so only the generation tells the old prompt from this one.
        assertFalse(rig.prompt.claimed, "the old generation's refused claim consumed nothing of the new session")
        assertEquals(2, rig.headset.opens("module"), "exactly one fresh control connection for the new owner")
        assertEquals(0, rig.headset.overlappingOpens)
    }

    private fun noiseMode(state: ControlState): FieldValue = assertIs<FieldReading.Known>(state.device?.fields?.get(DeviceField.NOISE_MODE)).value
}
