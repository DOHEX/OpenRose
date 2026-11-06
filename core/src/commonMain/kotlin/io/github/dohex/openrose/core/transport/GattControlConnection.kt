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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Shared control protocol policy over one platform GATT connection. */
class GattControlConnection private constructor(
    private val driver: GattDriver,
    private val endpoint: TransportEndpoint.BleGatt,
    private val eventScope: CoroutineScope,
) : ControlConnection {
    private data class Services(val writer: GattCharacteristic, val notifier: GattCharacteristic)

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
                driver.events.collect { event ->
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

                        is GattEvent.Disconnected -> end(classify(event.failure, TransportRecoveryReason.SERVICE_DISCOVERY))
                    }
                }
                end(TerminalTransportException("蓝牙事件流已结束"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                end(if (error is GattException) classify(error, TransportRecoveryReason.SERVICE_DISCOVERY) else error)
            } finally {
                // A failed release remains retryable through close(), as required by ControlLink.
                withContext(NonCancellable) { runCatching { releaseDriver() } }
            }
        }
    }

    private suspend fun initialize(onPhase: (ConnectionPhase) -> Unit) {
        native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.connect() }
        onPhase(ConnectionPhase.DISCOVERING)
        var exhausted: Exception = RecoverableTransportException(TransportRecoveryReason.SERVICES_UNAVAILABLE, "重新发现后仍未找到此型号要求的服务或特征")
        for (attempt in 0..2) {
            val before = lifecycle.withLock { generation }
            val found = try {
                native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.discoverServices() }
            } catch (error: RecoverableTransportException) {
                exhausted = error
                continue
            }
            val service = found.firstOrNull { it.uuid.equals(endpoint.serviceUuid, ignoreCase = true) }
            val writer = service?.characteristics?.firstOrNull { it.uuid.equals(endpoint.writeUuid, ignoreCase = true) }
            val notifier = service?.characteristics?.firstOrNull { it.uuid.equals(endpoint.notifyUuid, ignoreCase = true) }
            val accepted = lifecycle.withLock {
                checkOpen()
                if (before != generation) {
                    exhausted = RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "蓝牙服务在发现过程中发生变化")
                    false
                } else if (writer == null || notifier == null) {
                    exhausted = RecoverableTransportException(TransportRecoveryReason.SERVICES_UNAVAILABLE, "未找到此型号要求的服务或特征")
                    false
                } else {
                    if ((!writer.canWrite && !writer.canWriteWithoutResponse) || (!notifier.canNotify && !notifier.canIndicate)) {
                        throw UnsupportedOperationException("设备特征不提供所需的写入或订阅能力")
                    }
                    services.value = Services(writer, notifier)
                    true
                }
            }
            if (accepted) {
                val mtu = try {
                    withTimeoutOrNull(MTU_TIMEOUT_MS) { native(TransportRecoveryReason.SERVICE_DISCOVERY) { driver.requestMtu(185) } }
                } catch (_: RecoverableTransportException) {
                    null
                } catch (_: UnsupportedOperationException) {
                    null
                }
                checkOpen()
                capacity.value = ((mtu ?: 23).coerceAtLeast(23) - 3).coerceAtMost(512)
                return
            }
        }
        throw exhausted
    }

    override suspend fun subscribe() = operations.withLock {
        checkOpen()
        val notifier = requireNotNull(services.value).notifier
        try {
            val subscribed = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                native(TransportRecoveryReason.SUBSCRIPTION) {
                    driver.setNotificationRouting(notifier, true)
                    driver.configureSubscription(notifier, if (notifier.canNotify) GattSubscription.NOTIFY else GattSubscription.INDICATE)
                }
                true
            } ?: false
            if (!subscribed) throw RecoverableTransportException(TransportRecoveryReason.SUBSCRIPTION, "通知订阅超时")
            checkOpen()
        } catch (error: Exception) {
            withContext(NonCancellable) {
                // Even if an outstanding native CCCD write prevents remote rollback, always undo local routing.
                try {
                    withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                        runCatching { driver.configureSubscription(notifier, GattSubscription.DISABLED) }
                    }
                } finally {
                    withTimeoutOrNull(OPERATION_TIMEOUT_MS) { runCatching { driver.setNotificationRouting(notifier, false) } }
                }
            }
            if (error !is CancellationException) end(error)
            throw error
        }
    }

    override suspend fun write(bytes: ByteArray) = operations.withLock {
        checkOpen()
        require(bytes.isNotEmpty() && bytes.size <= maxWriteSize) { "协议报文必须完整适配当前写入容量" }
        val writer = requireNotNull(services.value).writer
        try {
            val written = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                native(TransportRecoveryReason.WRITE_FAILED) { driver.write(writer, bytes, withResponse = !writer.canWriteWithoutResponse) }
                true
            } ?: false
            if (!written) throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "蓝牙写入超时")
            checkOpen()
        } catch (error: Exception) {
            if (error !is CancellationException) end(error)
            throw error
        }
    }

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
            // close() releases pending native calls, so no extra coroutine is needed per packet.
            block().also { checkOpen() }
        } catch (error: GattException) {
            checkOpen()
            throw classify(error, reason)
        }
    }

    private fun classify(error: GattException, reason: TransportRecoveryReason): Exception = when (error.kind) {
        GattFailureKind.TRANSIENT -> RecoverableTransportException(reason, error.message ?: "GATT 操作失败", error)
        GattFailureKind.PERMISSION -> SecurityException(error.message, error)
        GattFailureKind.UNSUPPORTED -> UnsupportedOperationException(error.message, error)
        GattFailureKind.INVALID_VALUE -> IllegalArgumentException(error.message, error)
        GattFailureKind.DISCONNECTED, GattFailureKind.UNKNOWN -> TerminalTransportException(error.message ?: "GATT 连接不可用", error)
    }

    companion object {
        private const val MTU_TIMEOUT_MS = 1_500L
        private const val OPERATION_TIMEOUT_MS = 5_000L

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
            connection.observe()
            try {
                connection.initialize(onPhase)
                return connection
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        connection.close()
                    } catch (closing: Exception) {
                        error.addSuppressed(closing)
                    }
                }
                throw error
            }
        }
    }
}
