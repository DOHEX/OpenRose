package io.github.dohex.openrose.core.transport

import io.github.dohex.openrose.core.ConnectionPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class GattControlConnection private constructor(
    private val driver: GattDriver,
    private val endpoint: TransportEndpoint.BleGatt,
    private val eventScope: CoroutineScope,
) : ControlConnection {
    private data class Services(val writer: GattCharacteristic, val notifier: GattCharacteristic) {
        fun requireControlFeatures() {
            val supportsWrites = writer.canWrite || writer.canWriteWithoutResponse
            val supportsSubscriptions = notifier.canNotify || notifier.canIndicate
            if (!supportsWrites || !supportsSubscriptions) throw UnsupportedOperationException("设备特征不提供所需的写入或订阅能力")
        }
    }

    private val operations = Mutex()
    private val lifecycle = Mutex()
    private val release = Mutex()
    private var released = false
    private var generation = 0
    private val services = MutableStateFlow<Services?>(null)
    private val capacity = MutableStateFlow(20)
    private val failure = MutableStateFlow<Exception?>(null)
    private val received = Channel<ByteArray>(Channel.BUFFERED)
    override val notifications: Flow<ByteArray> = received.receiveAsFlow()
    override val maxWriteSize: Int get() = capacity.value

    private fun observe() {
        eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                driver.events.catch { failure ->
                    when (failure) {
                        is GattException -> end(failure.classify(TransportRecoveryReason.SERVICE_DISCOVERY))
                        is CancellationException -> throw failure
                        is Exception -> end(failure)
                        else -> throw failure
                    }
                }.collect { event ->
                    when (event) {
                        is GattEvent.Notification -> {
                            if (event.characteristic === services.value?.notifier &&
                                received.trySend(event.value).isFailure
                            ) {
                                end(TerminalTransportException("通知接收缓冲区已满或已关闭"))
                            }
                        }

                        GattEvent.ServicesChanged -> {
                            val invalidated = lifecycle.withLock {
                                generation++
                                services.value != null
                            }
                            if (invalidated) {
                                end(RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "蓝牙服务数据库已变化；需要重新建立连接"))
                            }
                        }

                        is GattEvent.Disconnected -> end(event.failure.classify(TransportRecoveryReason.SERVICE_DISCOVERY))
                    }
                }
                end(TerminalTransportException("蓝牙事件流已结束"))
            } finally {
                withContext(NonCancellable) { runCatching { releaseDriver() } }
            }
        }
    }

    private suspend fun initialize(onPhase: (ConnectionPhase) -> Unit) {
        native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.connect() }
        onPhase(ConnectionPhase.DISCOVERING)
        for (attempt in 1..3) {
            try {
                discoverServices()
                break
            } catch (exhausted: RecoverableTransportException) {
                if (attempt == 3) throw exhausted
            }
        }
        val mtu = try {
            withTimeoutOrNull(MTU_TIMEOUT_MS) { native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.requestMtu(185) } }
        } catch (_: RecoverableTransportException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        checkOpen()
        capacity.value = ((mtu ?: 23).coerceAtLeast(23) - 3).coerceAtMost(512)
    }

    private suspend fun discoverServices() {
        val before = lifecycle.withLock { generation }
        val found = native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.discoverServices() }
        val service = found.firstOrNull { it.uuid.equals(endpoint.serviceUuid, ignoreCase = true) }
        val writer = service?.characteristics?.firstOrNull { it.uuid.equals(endpoint.writeUuid, ignoreCase = true) }
        val notifier = service?.characteristics?.firstOrNull { it.uuid.equals(endpoint.notifyUuid, ignoreCase = true) }
        lifecycle.withLock {
            checkOpen()
            if (before != generation) {
                throw RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "蓝牙服务在发现过程中发生变化")
            }
            if (writer == null || notifier == null) {
                throw RecoverableTransportException(TransportRecoveryReason.SERVICES_UNAVAILABLE, "未找到此型号要求的服务或特征")
            }
            services.value = Services(writer, notifier).also { it.requireControlFeatures() }
        }
    }

    override suspend fun subscribe() = operations.withLock {
        checkOpen()
        val notifier = requireNotNull(services.value).notifier
        terminalOnFailure {
            var subscribed = false
            try {
                subscribed = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) {
                    native(TransportRecoveryReason.SUBSCRIPTION) {
                        driver.setNotificationRouting(notifier, true)
                        val subscription = if (notifier.canNotify) GattSubscription.NOTIFY else GattSubscription.INDICATE
                        driver.configureSubscription(notifier, subscription)
                    }
                    checkOpen()
                    true
                } ?: false
                if (!subscribed) throw RecoverableTransportException(TransportRecoveryReason.SUBSCRIPTION, "通知订阅超时")
            } finally {
                if (!subscribed) driver.undoSubscription(notifier)
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = operations.withLock {
        checkOpen()
        val writer = requireNotNull(services.value).writer
        terminalOnFailure {
            val written = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) {
                native(TransportRecoveryReason.WRITE_FAILED) { driver.write(writer, bytes, withResponse = !writer.canWriteWithoutResponse) }
                true
            } ?: false
            if (!written) throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "蓝牙写入超时")
            checkOpen()
        }
    }

    private inline fun terminalOnFailure(operation: () -> Unit) = transportOperation({ failure ->
        end(failure)
        throw failure
    }, operation)

    override suspend fun close() {
        failure.compareAndSet(null, TerminalTransportException("蓝牙连接已关闭"))
        received.close()
        eventScope.cancel()
        withContext(NonCancellable) { releaseDriver() }
    }

    private suspend fun releaseDriver() = release.withLock {
        if (!released) {
            driver.close()
            released = true
        }
    }

    private fun end(error: Exception) {
        if (failure.compareAndSet(null, error)) {
            received.close(error)
            eventScope.cancel()
        }
    }

    private fun checkOpen() {
        failure.value?.let { throw it }
    }

    private suspend fun <T> native(reason: TransportRecoveryReason, block: suspend () -> T): T {
        checkOpen()
        return try {
            block().also { checkOpen() }
        } catch (error: GattException) {
            checkOpen()
            throw error.classify(reason)
        }
    }

    companion object {
        private const val MTU_TIMEOUT_MS = 1_500L

        suspend fun open(
            driver: GattDriver,
            endpoint: TransportEndpoint.BleGatt,
            onPhase: (ConnectionPhase) -> Unit,
        ): GattControlConnection {
            val connection = GattControlConnection(
                driver,
                endpoint,
                CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob()),
            )
            return connection.openSafely {
                observe()
                initialize(onPhase)
            }
        }
    }
}

private const val GATT_OPERATION_TIMEOUT_MS = 5_000L

private fun GattException.classify(reason: TransportRecoveryReason): Exception = when (kind) {
    GattFailureKind.TRANSIENT -> RecoverableTransportException(reason, message ?: "GATT 操作失败", this)
    GattFailureKind.PERMISSION -> SecurityException(message, this)
    GattFailureKind.UNSUPPORTED -> UnsupportedOperationException(message, this)
    GattFailureKind.INVALID_VALUE -> IllegalArgumentException(message, this)
    GattFailureKind.DISCONNECTED, GattFailureKind.UNKNOWN -> TerminalTransportException(message ?: "GATT 连接不可用", this)
}

private suspend fun GattDriver.undoSubscription(notifier: GattCharacteristic) = withContext(NonCancellable) {
    try {
        withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) { runCatching { configureSubscription(notifier, GattSubscription.DISABLED) } }
    } finally {
        withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) { runCatching { setNotificationRouting(notifier, false) } }
    }
}
