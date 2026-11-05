package io.github.dohex.openrose.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GattControlConnectionTest {
    @Test
    fun missingServicesAreRetriedTwiceBeforeTheConnectionCanWrite() = runTest {
        val driver = FakeGattDriver().apply { missingDiscoveries = 2 }
        val connection = GattControlConnection.open(driver, endpoint) {}
        connection.subscribe()
        connection.write(byteArrayOf(0x12, 0x34))
        assertEquals(listOf("1234"), driver.writes)
        assertEquals(3, driver.discoveries)
        connection.close()
    }

    @Test
    fun negotiatedMtuBoundsCompletePacketsAndFallsBackWhenUnavailable() = runTest {
        for ((mtu, capacity) in listOf(null to 20, 22 to 20, 23 to 20, 185 to 182, 517 to 512)) {
            val driver = FakeGattDriver().apply { negotiatedMtu = mtu }
            val connection = GattControlConnection.open(driver, endpoint) {}
            assertEquals(capacity, connection.maxWriteSize)
            connection.write(ByteArray(capacity) { 1 })
            kotlin.test.assertFailsWith<IllegalArgumentException> { connection.write(ByteArray(capacity + 1)) }
            connection.close()
        }
    }

    @Test
    fun subscriptionDeadlineRollsBackRemoteAndLocalDeliveryBeforeFailing() = runTest {
        val driver = FakeGattDriver().apply { subscriptionGate = CompletableDeferred() }
        val connection = GattControlConnection.open(driver, endpoint) {}
        val subscribing = async { runCatching { connection.subscribe() } }
        runCurrent()
        assertTrue(driver.routing)
        advanceTimeBy(4_999)
        assertFalse(subscribing.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(subscribing.isCompleted, "Subscription must fail at 5 seconds")
        val failure = assertIs<RecoverableTransportException>(subscribing.await().exceptionOrNull())
        assertEquals(TransportRecoveryReason.SUBSCRIPTION, failure.reason)
        assertFalse(driver.routing)
        assertEquals(GattSubscription.DISABLED, driver.remoteSubscription)
        connection.close()
    }

    @Test
    fun writeFailuresOnlyRecoverForExplicitlyTransientNativeErrors() = runTest {
        val expected = mapOf(
            GattFailureKind.TRANSIENT to RecoverableTransportException::class,
            GattFailureKind.PERMISSION to SecurityException::class,
            GattFailureKind.UNSUPPORTED to UnsupportedOperationException::class,
            GattFailureKind.INVALID_VALUE to IllegalArgumentException::class,
            GattFailureKind.DISCONNECTED to TerminalTransportException::class,
            GattFailureKind.UNKNOWN to TerminalTransportException::class,
        )
        for ((kind, type) in expected) {
            val driver = FakeGattDriver().apply { writeFailure = GattException(kind, "native failure") }
            val connection = GattControlConnection.open(driver, endpoint) {}
            val failure = runCatching { connection.write(byteArrayOf(1)) }.exceptionOrNull()
            assertTrue(type.isInstance(failure), "Expected $type for $kind, got $failure")
            if (failure is RecoverableTransportException) assertEquals(TransportRecoveryReason.WRITE_FAILED, failure.reason)
            connection.close()
        }
    }

    @Test
    fun servicesChangedDuringDiscoveryDiscardTheSnapshotAndLaterInvalidateTheLink() = runTest {
        val discovery = CompletableDeferred<Unit>()
        val driver = FakeGattDriver().apply { discoveryGate = discovery }
        val opening = async { GattControlConnection.open(driver, endpoint) {} }
        runCurrent()
        driver.incoming.send(GattEvent.ServicesChanged)
        runCurrent()
        discovery.complete(Unit)
        val connection = opening.await()
        assertEquals(2, driver.discoveries)
        val receiving = async { runCatching { connection.notifications.toList() } }
        driver.incoming.send(GattEvent.ServicesChanged)
        runCurrent()
        assertTrue(receiving.isCompleted, "A changed service database must end the old connection")
        assertEquals(TransportRecoveryReason.SERVICES_CHANGED,
            assertIs<RecoverableTransportException>(receiving.await().exceptionOrNull()).reason)
        val failure = runCatching { connection.write(byteArrayOf(1)) }.exceptionOrNull()
        assertEquals(TransportRecoveryReason.SERVICES_CHANGED, assertIs<RecoverableTransportException>(failure).reason)
        assertEquals(emptyList(), driver.writes)
        connection.close()
    }

    @Test
    fun concurrentCommandsWaitForNativeCompletionInSubmissionOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val driver = FakeGattDriver().apply { writeGate = gate }
        val connection = GattControlConnection.open(driver, endpoint) {}
        val first = async { connection.write(byteArrayOf(1)) }
        runCurrent()
        val second = async { connection.write(byteArrayOf(2)) }
        runCurrent()
        assertEquals(listOf("01"), driver.writes)
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("01", "02"), driver.writes)
        connection.close()
    }

    @Test
    fun exhaustedDiscoveryClosesTheDriverAndPreservesTheRecoveryReason() = runTest {
        val driver = FakeGattDriver().apply { missingDiscoveries = 10 }
        val failure = runCatching { GattControlConnection.open(driver, endpoint) {} }.exceptionOrNull()
        assertEquals(TransportRecoveryReason.SERVICES_UNAVAILABLE, assertIs<RecoverableTransportException>(failure).reason)
        assertEquals(3, driver.discoveries)
        assertTrue(driver.closed)
    }

    @Test
    fun mtuTimeoutFallsBackWithoutBlockingSubscriptionOrSmallPackets() = runTest {
        val driver = FakeGattDriver().apply { mtuGate = CompletableDeferred() }
        val connection = GattControlConnection.open(driver, endpoint) {}
        assertEquals(1_500, testScheduler.currentTime)
        assertEquals(20, connection.maxWriteSize)
        connection.subscribe()
        connection.write(ByteArray(20) { 1 })
        assertTrue(driver.routing)
        assertEquals(GattSubscription.NOTIFY, driver.remoteSubscription)
        connection.close()
    }
    @Test
    fun serviceChangeDuringPendingWritePreservesTheSharedFailure() = runTest {
        val driver = FakeGattDriver().apply { writeGate = CompletableDeferred() }
        val connection = GattControlConnection.open(driver, endpoint) {}
        val writing = async { runCatching { connection.write(byteArrayOf(1)) } }
        runCurrent()
        driver.incoming.send(GattEvent.ServicesChanged)
        runCurrent()
        val failure = writing.await().exceptionOrNull()
        assertEquals(TransportRecoveryReason.SERVICES_CHANGED, assertIs<RecoverableTransportException>(failure).reason)
        connection.close()
    }
    @Test
    fun notificationsRetainCharacteristicIdentityWhenServicesReuseUuid() = runTest {
        val selected = GattCharacteristic("notify", canNotify = true)
        val unrelated = GattCharacteristic("notify", canNotify = true)
        val driver = FakeGattDriver().apply {
            discoveredServices = listOf(
                GattService("service", listOf(GattCharacteristic("write", canWrite = true), selected)),
                GattService("another-service", listOf(unrelated)),
            )
        }
        val connection = GattControlConnection.open(driver, endpoint) {}
        connection.subscribe()
        val next = async { connection.notifications.first() }
        driver.incoming.send(GattEvent.Notification(unrelated, byteArrayOf(0x55)))
        driver.incoming.send(GattEvent.Notification(selected, byteArrayOf(0x12)))
        kotlin.test.assertContentEquals(byteArrayOf(0x12), next.await())
        connection.close()
    }



    private class FakeGattDriver : GattDriver {
        val incoming = Channel<GattEvent>(Channel.BUFFERED)
        override val events = incoming.receiveAsFlow()
        var missingDiscoveries = 0
        var discoveries = 0
        var negotiatedMtu: Int? = 185
        var discoveredServices: List<GattService> = services
        val writes = mutableListOf<String>()
        var writeFailure: GattException? = null
        var discoveryGate: CompletableDeferred<Unit>? = null
        var subscriptionGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var mtuGate: CompletableDeferred<Unit>? = null
        var closed = false
        var routing = false
        var remoteSubscription = GattSubscription.DISABLED
        override suspend fun connect() = Unit
        override suspend fun discoverServices(): List<GattService> {
            discoveries++
            discoveryGate?.await()
            return if (discoveries <= missingDiscoveries) emptyList() else discoveredServices
        }
        override suspend fun requestMtu(preferred: Int): Int? {
            mtuGate?.await()
            return negotiatedMtu
        }
        override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) { routing = enabled }
        override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) {
            check(routing) { "Local routing must precede remote configuration" }
            remoteSubscription = subscription
            if (subscription != GattSubscription.DISABLED) subscriptionGate?.await()
        }
        override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
            writeFailure?.let { throw it }
            writes += value.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            writeGate?.await()
        }
        override suspend fun close() {
            closed = true
            writeGate?.completeExceptionally(GattException(GattFailureKind.DISCONNECTED, "closed"))
            incoming.close()
        }
    }

    private companion object {
        val endpoint = TransportEndpoint.BleGatt("service", "write", "notify")
        val services = listOf(GattService("service", listOf(
            GattCharacteristic("write", canWrite = true),
            GattCharacteristic("notify", canNotify = true),
        )))
    }
}
