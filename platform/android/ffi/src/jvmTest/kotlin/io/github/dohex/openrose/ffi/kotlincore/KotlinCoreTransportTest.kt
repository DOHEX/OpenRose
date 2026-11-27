package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.ffi.RustControlResult
import io.github.dohex.openrose.ffi.RustControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlErrorCode
import uniffi.openrose_control.GattCharacteristic
import uniffi.openrose_control.GattEvent
import uniffi.openrose_control.GattFailureKind
import uniffi.openrose_control.GattSubscription
import uniffi.openrose_control.SppFailureKind
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_headset.FieldReading
import uniffi.openrose_headset.FieldValue
import uniffi.openrose_headset.HeadsetField
import uniffi.openrose_headset.ModelProfile
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.dohex.openrose.core.transport.Advertisement as KotlinAdvertisement
import io.github.dohex.openrose.core.transport.AudioProfile as KotlinAudioProfile
import io.github.dohex.openrose.core.transport.DeviceId as KotlinDeviceId
import io.github.dohex.openrose.core.transport.GattException as KotlinGattException
import io.github.dohex.openrose.core.transport.GattFailureKind as KotlinGattFailureKind
import io.github.dohex.openrose.core.transport.RawAudioDevice as KotlinRawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent as KotlinRawAudioEvent

class KotlinCoreTransportTest {
    @Test
    fun theCoreConnectsAndReadsThroughTheKotlinGattDriver() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                RustControlRuntime(transport, rig.platformState(), clock = rig.clock(), timing = rig.timing()).use { runtime ->
                    assertEquals(RustControlResult.Success, runtime.connect(ADDRESS))
                    val connected = withTimeout(2_000) { runtime.state.first { it.connection is ConnectionState.Connected } }
                    val reading = connected.headset?.readings?.fields?.get(HeadsetField.NOISE_MODE)
                    assertEquals(FieldValue.Text("wind"), (reading as? FieldReading.Known)?.value)
                    assertEquals(listOf(ADDRESS), rig.gattRequests)
                    assertTrue(rig.gatts.single().writes.isNotEmpty())
                    assertTrue(runtime.handle.disconnect())
                    assertEquals(1, rig.gatts.single().closes.size)
                }
            }
        }
    }

    @Test
    fun aGattFailureKindKeepsItsTypeAndCauseAcrossTheBoundary() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                rig.connectFailure = KotlinGattException(KotlinGattFailureKind.PERMISSION, "BLUETOOTH_CONNECT revoked")
                val gatt = transport.openGatt(ADDRESS)
                val failure = assertFailsWith<DriverException.Gatt> { gatt.connect() }
                assertEquals(GattFailureKind.PERMISSION, failure.kind)
                assertEquals("BLUETOOTH_CONNECT revoked", failure.detail)
                assertIs<KotlinGattException>(failure.cause)
            }
        }
    }

    @Test
    fun theCoreReportsARevokedPermissionDuringConnectAsPermissionRequired() = runBlocking<Unit> {
        rig().use { rig ->
            rig.audioConnected = false
            KotlinCoreTransport(rig).use { transport ->
                RustControlRuntime(transport, rig.platformState(), clock = rig.clock(), timing = rig.timing()).use { runtime ->
                    rig.connectFailure = KotlinGattException(KotlinGattFailureKind.PERMISSION, "BLUETOOTH_CONNECT revoked")
                    val denied = assertIs<RustControlResult.Failure>(runtime.connect(ADDRESS))
                    assertEquals(ControlErrorCode.PERMISSION_REQUIRED, denied.error.code)
                    rig.connectFailure = null
                    assertEquals(RustControlResult.Success, runtime.connect(ADDRESS))
                    assertTrue(runtime.handle.disconnect())
                }
            }
        }
    }

    @Test
    fun gattFactoryFailuresAreTypedTransportErrors() {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                rig.gattFailure = IllegalArgumentException("not an address")
                val invalid = assertFailsWith<DriverException.InvalidArgument> { transport.openGatt("nope") }
                assertEquals("not an address", invalid.detail)
                rig.gattFailure = SecurityException("no BLUETOOTH_CONNECT")
                assertFailsWith<DriverException.PermissionDenied> { transport.openGatt(ADDRESS) }
                rig.gattFailure = IOException("adapter missing")
                assertFailsWith<DriverException.Terminal> { transport.openGatt(ADDRESS) }
            }
        }
    }

    @Test
    fun characteristicsKeepTheirIdentityAcrossDiscoveryAndNotifications() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val gatt = transport.openGatt(ADDRESS)
                val services = gatt.discoverServices()
                val characteristics = services.single().characteristics
                assertEquals(2, characteristics.map { it.instanceId }.toSet().size)
                val notifier = characteristics.single { it.canNotify }
                val writer = characteristics.single { it.canWrite }
                gatt.configureSubscription(notifier, GattSubscription.NOTIFY)
                gatt.setNotificationRouting(notifier, true)
                gatt.write(writer, "08ee00000006020a0008".hexToBytes(), true)
                val event = withTimeout(2_000) { gatt.nextEvent() }
                val notification = assertIs<GattEvent.Notification>(event)
                assertEquals(notifier.instanceId, notification.instanceId)
                val missing = GattCharacteristic(999uL, "x", false, false, false, false)
                val unknown = assertFailsWith<DriverException.Gatt> { gatt.write(missing, byteArrayOf(1), true) }
                assertEquals(GattFailureKind.UNSUPPORTED, unknown.kind)
            }
        }
    }

    @Test
    fun linkLossAndServicesChangedArriveAsEventsAndTheEventStreamEndsAfterClose() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val gatt = transport.openGatt(ADDRESS)
                gatt.discoverServices()
                rig.gatts.single().servicesChanged()
                assertEquals(GattEvent.ServicesChanged, withTimeout(2_000) { gatt.nextEvent() })
                rig.gatts.single().disconnect(KotlinGattException(KotlinGattFailureKind.DISCONNECTED, "gone"))
                val lost = assertIs<GattEvent.Disconnected>(withTimeout(2_000) { gatt.nextEvent() })
                assertEquals(GattFailureKind.DISCONNECTED, lost.v1.kind)
                assertEquals("gone", lost.v1.message)
                gatt.closeLink()
                assertNull(withTimeout(2_000) { gatt.nextEvent() })
            }
        }
    }

    @Test
    fun subscribeReturnsOnlyAfterTheDriverRegisteredItsReceiversAndDropsTheReadyEvent() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val events = transport.audioFacts().subscribe()
                assertTrue(rig.registered.get())
                rig.audioEvents.trySend(KotlinRawAudioEvent.AdapterOff)
                assertEquals(uniffi.openrose_control.RawAudioEvent.AdapterOff, withTimeout(2_000) { events.nextEvent() })
                rig.audioEvents.trySend(
                    KotlinRawAudioEvent.ProfileChanged(KotlinAudioProfile.HEADSET, KotlinRawAudioDevice("i5", ADDRESS), bonded = true, connected = true),
                )
                val changed = assertIs<uniffi.openrose_control.RawAudioEvent.ProfileChanged>(withTimeout(2_000) { events.nextEvent() })
                assertEquals(uniffi.openrose_control.AudioProfile.HEADSET, changed.profile)
                assertEquals(ADDRESS, changed.device.address)
                assertTrue(changed.bonded && changed.connected)
            }
        }
    }

    @Test
    fun audioFactsMapBondedDevicesAndSnapshots() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val audio = transport.audioFacts()
                val bonded = audio.bondedDevices()
                assertEquals(listOf(ADDRESS), bonded.map { it.address })
                assertEquals("My i5", bonded.single().alias)
                val snapshot = assertNotNullSnapshot(audio.snapshot(listOf(ADDRESS)))
                assertTrue(snapshot.enabled)
                val a2dp = snapshot.profiles.getValue(uniffi.openrose_control.AudioProfile.A2DP)
                assertEquals(listOf(ADDRESS), a2dp?.connectedAddresses)
                assertEquals(uniffi.openrose_control.AudioConnectionState.CONNECTED, a2dp?.states?.getValue(ADDRESS))
            }
        }
    }

    @Test
    fun everyPlatformEnumEntryCrossesTheBoundaryUnderItsOwnName() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                for (kind in KotlinGattFailureKind.entries) {
                    rig.connectFailure = KotlinGattException(kind, "failed")
                    val failure = assertFailsWith<DriverException.Gatt> { transport.openGatt(ADDRESS).connect() }
                    assertEquals(kind.name, failure.kind.name)
                }
                rig.connectFailure = null
                val gatt = transport.openGatt(ADDRESS)
                val notifier = gatt.discoverServices().single().characteristics.single { it.canNotify }
                GattSubscription.entries.forEach { gatt.configureSubscription(notifier, it) }
                assertEquals(GattSubscription.entries.map { it.name }, rig.gatts.last().subscriptions.map { it.name })
                val events = transport.audioFacts().subscribe()
                for (profile in KotlinAudioProfile.entries) {
                    val device = KotlinRawAudioDevice("i5", ADDRESS)
                    rig.audioEvents.trySend(KotlinRawAudioEvent.ProfileChanged(profile, device, bonded = true, connected = true))
                    val changed = assertIs<uniffi.openrose_control.RawAudioEvent.ProfileChanged>(withTimeout(2_000) { events.nextEvent() })
                    assertEquals(profile.name, changed.profile.name)
                }
                val observedStates = listOf(true, false).map { connected ->
                    rig.audioConnected = connected
                    val snapshot = assertNotNullSnapshot(transport.audioFacts().snapshot(listOf(ADDRESS)))
                    snapshot.profiles.getValue(uniffi.openrose_control.AudioProfile.A2DP)?.states?.getValue(ADDRESS)?.name
                }
                assertEquals(io.github.dohex.openrose.core.transport.AudioConnectionState.entries.map { it.name }, observedStates)
            }
        }
    }

    @Test
    fun coreDiscoversPairedHeadsetsAndFollowsAudioThroughTheKotlinAudioDriver() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                RustControlRuntime(transport, rig.platformState(), clock = rig.clock(), timing = rig.timing()).use { runtime ->
                    runtime.handle.refreshBondedHeadsets()
                    val state = withTimeout(2_000) { runtime.state.first { it.bondedHeadsets.isNotEmpty() } }
                    assertEquals(listOf(ADDRESS), state.bondedHeadsets.map { it.id })
                    assertEquals("My i5", state.bondedHeadsets.single().name)
                }
            }
        }
    }

    @Test
    fun scanDeliversAdvertisementsUntilStoppedAndReportsStartFailuresAsTransportErrors() = runBlocking<Unit> {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val scan = transport.startScan()
                rig.scans.trySend(KotlinAdvertisement(KotlinDeviceId(ADDRESS), "i5", -40, mapOf(0x5352 to byteArrayOf(0, 1))))
                val advertisement = withTimeout(2_000) { scan.nextAdvertisement() }
                assertEquals(ADDRESS, advertisement?.headsetId)
                assertEquals("i5", advertisement?.name)
                assertEquals((-40).toShort(), advertisement?.rssi)
                assertEquals(listOf<Byte>(0, 1), advertisement?.manufacturerData?.get(0x5352u.toUShort())?.toList())
                scan.stop()
                withTimeout(2_000) { while (!rig.scanStops.get()) delay(5) }
                assertNull(withTimeout(2_000) { scan.nextAdvertisement() })
            }
        }
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                rig.scanFailure = SecurityException("缺少 BLE 扫描权限")
                val scan = transport.startScan()
                assertFailsWith<DriverException.PermissionDenied> { withTimeout(2_000) { scan.nextAdvertisement() } }
            }
        }
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                rig.scanFailure = IOException("请在系统设置中开启定位服务")
                val scan = transport.startScan()
                val failure = assertFailsWith<DriverException.Terminal> { withTimeout(2_000) { scan.nextAdvertisement() } }
                assertEquals("请在系统设置中开启定位服务", failure.detail)
            }
        }
    }

    @Test
    fun sppLinkOpensSubscribesReadsWritesAndClosesThroughTheKotlinConnection() = runBlocking<Unit> {
        rig(ModelProfile.I7_RS_V2).use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val spp = transport.openSpp(ADDRESS, ADDRESS, "00001101-0000-1000-8000-00805f9b34fb")
                assertEquals(ADDRESS to "00001101-0000-1000-8000-00805f9b34fb", rig.sppRequest)
                assertEquals(260u, spp.maxWriteSize())
                val link = checkNotNull(rig.sppLink)
                spp.connect()
                assertTrue(link.opened && link.subscribed)
                link.deliver(1, 2, 3)
                assertEquals(listOf<Byte>(1, 2, 3), withTimeout(2_000) { spp.read() }.toList())
                spp.write(byteArrayOf(9, 8))
                assertEquals(listOf(listOf<Byte>(9, 8)), link.written.toList())
                spp.closeLink()
                assertTrue(link.closed)
            }
        }
    }

    @Test
    fun closingTheSppLinkUnblocksAPendingRead() = runBlocking<Unit> {
        rig(ModelProfile.I7_RS_V2).use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val spp = transport.openSpp(ADDRESS, ADDRESS, "uuid")
                spp.connect()
                val pending = async(Dispatchers.Default) { runCatching { spp.read() } }
                delay(50)
                assertFalse(pending.isCompleted)
                spp.closeLink()
                val failure = withTimeout(2_000) { pending.await() }.exceptionOrNull()
                assertIs<DriverException.Spp>(failure)
                assertEquals(SppFailureKind.DISCONNECTED, failure.kind)
            }
        }
    }

    @Test
    fun aRefusedSppCloseIsReportedSoTheCoreRetriesAndLaterSucceeds() = runBlocking<Unit> {
        rig(ModelProfile.I7_RS_V2).use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val spp = transport.openSpp(ADDRESS, ADDRESS, "uuid")
                val link = checkNotNull(rig.sppLink)
                spp.connect()
                link.closeFailure = IOException("socket still open")
                val refused = assertFailsWith<DriverException.Spp> { spp.closeLink() }
                assertEquals("socket still open", refused.detail)
                assertFalse(link.closed)
                link.closeFailure = null
                spp.closeLink()
                assertTrue(link.closed)
            }
        }
    }

    @Test
    fun sppFailuresKeepTheirKindAndRemoteLossEndsReads() = runBlocking<Unit> {
        rig(ModelProfile.I7_RS_V2).use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                val spp = transport.openSpp(ADDRESS, ADDRESS, "uuid")
                val link = checkNotNull(rig.sppLink)
                link.openFailure = SecurityException("no BLUETOOTH_CONNECT")
                assertEquals(SppFailureKind.PERMISSION, assertFailsWith<DriverException.Spp> { spp.connect() }.kind)
                link.openFailure = UnsupportedOperationException("classic Bluetooth unavailable")
                assertEquals(SppFailureKind.UNSUPPORTED, assertFailsWith<DriverException.Spp> { spp.connect() }.kind)
                link.openFailure = IOException("connect refused")
                assertEquals(SppFailureKind.DISCONNECTED, assertFailsWith<DriverException.Spp> { spp.connect() }.kind)
                link.openFailure = null
                spp.connect()
                link.writeFailure = IOException("broken pipe")
                assertEquals("broken pipe", assertFailsWith<DriverException.Spp> { spp.write(byteArrayOf(1)) }.detail)
                link.breakLink(IOException("SPP 连接已断开"))
                val lost = assertFailsWith<DriverException.Spp> { withTimeout(2_000) { spp.read() } }
                assertEquals(SppFailureKind.DISCONNECTED, lost.kind)
                assertEquals("SPP 连接已断开", lost.detail)
            }
        }
    }

    @Test
    fun sppFactoryFailuresAreTypedTransportErrors() {
        rig(ModelProfile.I7_RS_V2).use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                rig.sppFailure = IllegalArgumentException("invalid address")
                assertFailsWith<DriverException.InvalidArgument> { transport.openSpp("x", "x", "uuid") }
                rig.sppFailure = SecurityException("no BLUETOOTH_CONNECT")
                assertFailsWith<DriverException.PermissionDenied> { transport.openSpp(ADDRESS, ADDRESS, "uuid") }
                rig.sppFailure = UnsupportedOperationException("classic Bluetooth unavailable")
                assertFailsWith<DriverException.Unsupported> { transport.openSpp(ADDRESS, ADDRESS, "uuid") }
            }
        }
    }

    @Test
    fun recoverySupportComesFromTheDriverSource() {
        rig().use { rig ->
            KotlinCoreTransport(rig).use { transport ->
                assertFalse(transport.supportsInitializationRecovery())
                rig.supportsInitializationRecovery = true
                assertTrue(transport.supportsInitializationRecovery())
            }
        }
    }

    @Test
    fun closingTheTransportEndsItsBackgroundWork() = runBlocking<Unit> {
        rig().use { rig ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = KotlinCoreTransport(rig, scope)
            transport.openGatt(ADDRESS)
            transport.close()
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isCancelled)
            scope.cancel()
        }
    }

    private fun assertNotNullSnapshot(snapshot: uniffi.openrose_control.RawAudioSnapshot?): uniffi.openrose_control.RawAudioSnapshot {
        assertTrue(snapshot != null)
        return snapshot
    }
}
