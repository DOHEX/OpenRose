package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.ControlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** One secure RFCOMM connection carrying the same V2 byte stream as the BLE control service. */
@SuppressLint("MissingPermission") // The session gates Bluetooth permission; revocation fails the operation.
internal class AndroidSppConnection(
    private val adapter: BluetoothAdapter,
    private val address: String,
    serviceUuid: String,
) : ControlConnection {
    private val device: BluetoothDevice = adapter.getRemoteDevice(address)
    private val serviceId: UUID = UUID.fromString(serviceUuid)
    private val incoming = Channel<ByteArray>(INCOMING_CAPACITY)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val collectorAttached = AtomicBoolean(false)
    private val subscribed = AtomicBoolean(false)
    private val writeLock = Mutex()

    @Volatile private var socket: BluetoothSocket? = null

    @Volatile private var readerJob: Job? = null

    override val notifications: Flow<ByteArray> = flow {
        if (!collectorAttached.compareAndSet(false, true)) {
            throw IllegalStateException("SPP 通知流只允许一个读取者")
        }
        try {
            for (chunk in incoming) emit(chunk)
        } finally {
            close()
        }
    }

    override val maxWriteSize: Int = MAX_WRITE_SIZE

    suspend fun open(onPhase: (ConnectionPhase) -> Unit) {
        checkOpen()
        val client = device.createRfcommSocketToServiceRecord(serviceId)
        socket = client
        onPhase(ConnectionPhase.DISCOVERING)
        performBlockingIo("连接") {
            adapter.cancelDiscovery()
            client.connect()
        }
        checkOpen()
    }

    override suspend fun subscribe() {
        checkOpen()
        if (!collectorAttached.get()) throw IOException("SPP 通知读取器尚未启动")
        if (!subscribed.compareAndSet(false, true)) return
        readerJob = ioScope.launch { readLoop() }
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        checkOpen()
        require(bytes.isNotEmpty() && bytes.size <= maxWriteSize) { "协议报文必须适配完整 SPP 写入容量" }
        val client = requireSocket()
        performBlockingIo("写入") {
            client.outputStream.write(bytes)
            client.outputStream.flush()
        }
    }

    override suspend fun close() {
        stopIo(IOException("SPP 连接已关闭"))
        releaseSocket()
        val reader = readerJob
        if (reader != null && reader != kotlinx.coroutines.currentCoroutineContext()[Job]) {
            withContext(NonCancellable) { reader.cancelAndJoin() }
        }
    }

    private suspend fun performBlockingIo(action: String, operation: () -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val ioJob = ioScope.launch {
                try {
                    operation()
                    continuation.resume(Unit) { _, _, _ -> terminate(IOException("SPP ${action}结果已取消")) }
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
            ioJob.invokeOnCompletion { cause ->
                if (cause != null && continuation.isActive) {
                    continuation.resumeWith(Result.failure(IOException("SPP ${action}操作已结束", cause)))
                }
            }
            continuation.invokeOnCancellation {
                // Closing the socket interrupts a blocked RFCOMM operation; cancelling the coroutine alone does not.
                terminate(IOException("SPP ${action}已取消"))
                ioJob.cancel()
            }
        }
    }

    private fun readLoop() {
        try {
            val client = requireSocket()
            val input = client.inputStream
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (!closed.get()) {
                val length = input.read(buffer)
                if (length < 0) throw IOException("SPP 连接已断开")
                if (length == 0) continue
                if (incoming.trySend(buffer.copyOf(length)).isFailure) {
                    throw IOException("SPP 通知接收缓冲区已满或已关闭")
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) terminate(if (error is IOException) error else IOException("SPP 读取失败", error))
        }
    }

    private fun requireSocket(): BluetoothSocket = socket?.takeUnless { closed.get() }
        ?: throw IOException("SPP 连接不可用")

    private fun checkOpen() {
        if (closed.get()) throw IOException("SPP 连接已关闭")
    }

    private fun stopIo(error: Throwable) {
        if (closed.compareAndSet(false, true)) {
            incoming.close(error)
            ioScope.cancel()
        }
    }

    private fun terminate(error: Throwable) {
        stopIo(error)
        // A cancellation callback cannot deliver a close failure. Explicit close retries the same socket.
        runCatching { releaseSocket() }
    }

    @Synchronized
    private fun releaseSocket() {
        socket?.close()
        socket = null
    }

    private companion object {
        const val MAX_WRITE_SIZE = 260
        const val READ_BUFFER_SIZE = 1_024
        const val INCOMING_CAPACITY = 32
    }
}
