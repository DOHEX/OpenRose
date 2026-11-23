package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.circulation.CirculationAudio
import java.util.concurrent.TimeUnit

/** Windows media routing is separate from the WinRT GATT session used to control the headset. */
class WindowsAudio internal constructor(
    private val call: (List<String>) -> List<String>,
    private val wait: () -> Unit = { Thread.sleep(250) },
    private val timeoutMillis: Long = 9_000,
) : CirculationAudio {
    constructor() : this(::audioCommand)

    private data class AudioState(val container: String, val render: Boolean, val capture: Boolean, val micInUse: Boolean)
    private data class Output(val id: String, val name: String, val container: String)

    private fun key(address: String) = address.replace(":", "")

    private fun state(target: String): AudioState {
        val fields = call(listOf("state", target)).single().split('/')
        return AudioState(container = fields[0], render = fields[1] == "1", capture = fields[2] == "1", micInUse = fields[3] == "1")
    }

    private fun defaults(): Map<String, Output> = call(listOf("defaults")).associate { line ->
        val fields = line.split('/')
        fields.first() to Output(id = fields[1], name = hex.parseHex(fields[2]).decodeToString(), container = fields[3])
    }

    fun currentOutput(): String? = defaults()["1"]?.name

    fun openSoundSettings() {
        ProcessBuilder("explorer.exe", "ms-settings:sound").start()
    }

    override fun isConnected(address: String): Boolean = state(key(address)).let { it.render || it.capture }

    override fun isMediaConnected(address: String): Boolean = state(key(address)).render

    override fun release(address: String) {
        val target = key(address)
        val previous = state(target)
        if (!previous.render && !previous.capture) return
        check(!previous.micInUse) { "耳机麦克风正在使用，无法让出" }
        call(listOf("disconnect", target))
        awaitStatus(target, "等待 Windows 让出耳机音频超时") { !it.render && !it.capture }
    }

    override fun acquire(address: String) {
        val target = key(address)
        val previous = state(target)
        if (previous.render) return
        val outputs = defaults()
        try {
            call(listOf("connect", target))
            awaitStatus(target, "等待 Windows 连接耳机音频超时") { it.render }
        } finally {
            // The Bluetooth driver can promote the new endpoint separately for multimedia, calls or console.
            // Only restore a role if it moved to this headset and had a previous output.
            val current = defaults()
            for ((role, original) in outputs) {
                val now = current[role]
                if (now?.container == previous.container && original.id != now.id) call(listOf("restore", role, original.id))
            }
        }
    }

    private fun awaitStatus(target: String, timeout: String, accepted: (AudioState) -> Boolean) {
        val started = System.nanoTime()
        while (!accepted(state(target))) {
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < timeoutMillis) { timeout }
            wait()
        }
    }
}

private fun audioCommand(args: List<String>): List<String> {
    val process = startWindowsHelper(listOf("audio") + args)
    try {
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Windows 音频接口响应超时" }
        val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        check(process.exitValue() == 0) { lines.firstOrNull()?.removePrefix("error\t") ?: "Windows 音频操作失败" }
        return lines
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
