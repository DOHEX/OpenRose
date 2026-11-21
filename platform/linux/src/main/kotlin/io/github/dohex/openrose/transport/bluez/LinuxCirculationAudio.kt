package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.circulation.CirculationAudio
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LinuxCirculationAudio(private val profiles: BluezAudioProfiles) : CirculationAudio {
    override fun isConnected(address: String) = runBlocking { profiles.isConnected(address) }

    override fun isMediaConnected(address: String) = runBlocking { profiles.isMediaConnected(address) }

    override fun release(address: String) {
        check(!headsetMicActive(address, pactl("list", "short", "sources"), pactl("list", "source-outputs"))) { "耳机麦克风正在使用，无法让出" }
        runBlocking { profiles.release(address) }
    }

    override fun acquire(address: String) {
        val previous = pactl("get-default-sink").trim()
        check(previous.isNotEmpty()) { "无法读取当前系统输出" }
        // WirePlumber may auto-select a newly connected A2DP sink. Keep the user's current choice.
        pactl("set-default-sink", previous)
        try {
            runBlocking { profiles.acquire(address) }
        } finally {
            val current = pactl("get-default-sink").trim()
            if (matchesPulseEndpoint(current, address, "bluez_output", "bluez_sink")) pactl("set-default-sink", previous)
        }
    }

    fun currentOutput(): String {
        val name = pactl("get-default-sink").trim()
        val sink = pactl("list", "sinks").split("Sink #").firstOrNull { block ->
            block.lineSequence().any { it.trim() == "Name: $name" }
        }
        return sink?.lineSequence()?.map(String::trim)?.firstOrNull { it.startsWith("Description: ") }
            ?.removePrefix("Description: ") ?: name
    }

    fun openSoundSettings() {
        val commands = listOf(listOf("pavucontrol"), listOf("gnome-control-center", "sound"), listOf("systemsettings", "kcm_pulseaudio"))
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        val command = commands.firstOrNull { argv -> path.any { File(it, argv.first()).canExecute() } }
        checkNotNull(command) { "未找到系统声音设置程序" }
        ProcessBuilder(command).start()
    }

    private fun pactl(vararg args: String): String {
        val process = ProcessBuilder(listOf("pactl") + args).apply { environment()["LC_ALL"] = "C" }.redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            check(process.waitFor(3, TimeUnit.SECONDS)) { "读取系统音频服务超时" }
            val text = output.get(1, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { text.trim().ifEmpty { "系统音频服务不可用" } }
            return text
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal fun headsetMicActive(address: String, sourceList: String, streams: String): Boolean {
    val sources = sourceList.lineSequence().map { it.split('\t') }
        .filter { it.size >= 2 && matchesPulseEndpoint(it[1], address, "bluez_input", "bluez_source") }.map { it.first() }.toSet()
    return streams.split("Source Output #").any { stream ->
        stream.lineSequence().any { it.trim() == "Corked: no" } &&
            stream.lineSequence().any { it.trim().removePrefix("Source: ") in sources }
    }
}

private fun matchesPulseEndpoint(name: String, address: String, vararg prefixes: String): Boolean {
    val device = name.replace(':', '_')
    val mac = address.replace(':', '_')
    return prefixes.any {
        val expected = "$it.$mac"
        device == expected || device.startsWith("$expected.")
    }
}
