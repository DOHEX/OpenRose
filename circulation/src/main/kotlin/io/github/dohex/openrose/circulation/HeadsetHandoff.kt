package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.protocol.ModelProfile
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

internal class PeerUnreachable(cause: IOException) : IOException("无法联系对端 · ${cause.message}", cause)

/** All commands target the classic address of the i5 selected by the receiving device. */
class HeadsetHandoff(private val audio: CirculationAudio, private val bonded: () -> List<String>) {
    constructor(audio: CirculationAudio, control: ControlRuntime) : this(audio, {
        control.state.value.bondedDevices.filter { it.profile == ModelProfile.I5_ZT }.map { it.classicAddress ?: it.deviceId.value }
    })

    private val operation = ReentrantLock()

    @Volatile var progress = ""
        private set

    internal fun release(address: String): Map<String, String> = exclusive {
        if (address !in bonded() || !audio.isConnected(address)) return@exclusive mapOf("status" to "idle")
        audio.release(address)
        check(!audio.isConnected(address)) { "原音源尚未让出耳机" }
        mapOf("status" to "released")
    }

    internal fun pull(node: CirculationNode, address: String): Map<String, String> = exclusive {
        check(address in bonded()) { "请先在系统蓝牙配对这副 i5" }
        val peers = node.peers.value
        check(peers.isNotEmpty()) { "请先完成流转配对" }
        if (audio.isConnected(address)) return@exclusive mapOf("message" to "本机已接入耳机")
        progress = "正在联系已配对的音源设备"
        try {
            val deliveries = peers.mapNotNull { peer ->
                val reply = try {
                    node.request(peer.id, mapOf("op" to "release", "address" to address))
                } catch (_: PeerUnreachable) {
                    return@mapNotNull null
                }
                val answer = reply["status"]
                check(answer == "idle" || answer == "released") { reply["error"] ?: "对端未确认让出耳机" }
                answer
            }
            val offline = deliveries.isEmpty()
            check(offline || "released" in deliveries || deliveries.size == peers.size) { "部分对端不可达，未能确认耳机持有状态" }
            progress = if (offline) "对端均不可达，正在尝试本机接入" else "正在接入耳机"
            audio.acquire(address)
            check(audio.isConnected(address)) { "未确认系统音频已接入" }
            mapOf("message" to if (offline) "对端均不可达，本机已接入耳机" else "本机已接入耳机")
        } catch (failure: IOException) {
            throw IOException("$progress：${failure.localizedMessage}", failure)
        } finally {
            progress = ""
        }
    }

    @Suppress("TooGenericExceptionCaught") // The platform may report a D-Bus error, Bluetooth Binder exception or audio server failure.
    private inline fun exclusive(action: () -> Map<String, String>): Map<String, String> {
        if (!operation.tryLock()) return mapOf("error" to "另一项耳机流转正在进行")
        return try {
            action()
        } catch (failure: Exception) {
            mapOf("error" to (failure.localizedMessage ?: "流转失败"))
        } finally {
            operation.unlock()
        }
    }
}
