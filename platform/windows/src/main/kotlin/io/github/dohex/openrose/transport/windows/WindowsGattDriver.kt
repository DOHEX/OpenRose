package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class WindowsGattDriver(private val process: Process, private val serviceUuid: String) : GattDriver {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<List<String>>>()
    private val sequence = AtomicLong()
    private val incoming = Channel<GattEvent>(Channel.BUFFERED)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handles = ConcurrentHashMap<GattCharacteristic, Int>()
    override val events = incoming.receiveAsFlow()

    @Volatile private var mtu: Int? = null

    init {
        scope.launch {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) receive(line.split('\t'))
                }
            } catch (error: IOException) {
                if (process.isAlive) incoming.close(error)
            } finally {
                val failure = GattException(GattFailureKind.DISCONNECTED, "Windows GATT 连接已结束")
                incoming.close(failure)
                pending.values.forEach { it.completeExceptionally(failure) }
            }
        }
    }

    private suspend fun receive(fields: List<String>) {
        when (fields.first()) {
            "value" -> {
                val characteristic = handles.entries.firstOrNull { it.value == fields[1].toInt() }?.key ?: return
                incoming.send(GattEvent.Notification(characteristic, hex.parseHex(fields[2])))
            }

            "changed" -> incoming.send(GattEvent.ServicesChanged)

            "gone" -> incoming.send(GattEvent.Disconnected(GattException(GattFailureKind.DISCONNECTED, "Windows 蓝牙设备已断开")))

            else -> {
                val reply = pending[fields.first().toLong()] ?: return
                if (fields[1] == "ok") {
                    reply.complete(fields.drop(2))
                } else {
                    val kind = GattFailureKind.valueOf(fields[2])
                    reply.completeExceptionally(GattException(kind, fields[3]))
                }
            }
        }
    }

    private suspend fun request(command: String): List<String> {
        val id = sequence.incrementAndGet()
        val result = CompletableDeferred<List<String>>()
        pending[id] = result
        try {
            withContext(Dispatchers.IO) {
                synchronized(writer) {
                    check(process.isAlive) { "Windows GATT 连接已结束" }
                    writer.write("$id\t$command\n")
                    writer.flush()
                }
            }
            return result.await()
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun connect() {
        request("connect")
    }

    override suspend fun discoverServices(): List<GattService> {
        val fields = request("discover")
        handles.clear()
        if (fields.isEmpty()) return emptyList()
        mtu = fields.first().toIntOrNull()
        val characteristics = fields.drop(1).map { entry ->
            val (id, uuid, flags) = entry.split('/')
            GattCharacteristic(uuid, 'w' in flags, 'c' in flags, 'n' in flags, 'i' in flags).also { handles[it] = id.toInt() }
        }
        return listOf(GattService(serviceUuid, characteristics))
    }

    override suspend fun requestMtu(preferred: Int): Int? = mtu

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) {
        request("route\t${handles.getValue(characteristic)}\t$enabled")
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) {
        request("subscribe\t${handles.getValue(characteristic)}\t${subscription.name}")
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
        request("write\t${handles.getValue(characteristic)}\t$withResponse\t${hex.formatHex(value)}")
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { process.destroyForcibly().waitFor() }
        scope.cancel()
    }
}
