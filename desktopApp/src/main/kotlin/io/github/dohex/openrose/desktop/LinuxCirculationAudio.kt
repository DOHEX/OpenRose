package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.circulation.CirculationAudio
import io.github.dohex.openrose.transport.bluez.BluezAudioProfiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal interface DesktopCirculationAudio : CirculationAudio {
    fun currentOutput(): String?
    fun openSoundSettings()
}

internal class LinuxCirculationAudio(private val profiles: BluezAudioProfiles) : DesktopCirculationAudio {
    override fun isConnected(address: String) = runBlocking { profiles.isConnected(address) }

    override fun release(address: String) {
        val sources = pactl("list", "short", "sources").lineSequence().map { it.split('\t') }
            .filter { it.size >= 2 && matchesHeadset(it[1], "input", address) }.map { it.first() }.toSet()
        val capturing = pactl("list", "source-outputs").split("Source Output #").any { stream ->
            stream.lineSequence().any { it.trim() == "Corked: no" } &&
                stream.lineSequence().any { it.trim().removePrefix("Source: ") in sources }
        }
        check(!capturing) { "耳机麦克风正在使用，无法让出" }
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
            if (matchesHeadset(pactl("get-default-sink").trim(), "output", address)) pactl("set-default-sink", previous)
        }
    }

    override fun currentOutput(): String {
        val name = pactl("get-default-sink").trim()
        val sink = pactl("list", "sinks").split("Sink #").firstOrNull { block ->
            block.lineSequence().any { it.trim() == "Name: $name" }
        }
        return sink?.lineSequence()?.map(String::trim)?.firstOrNull { it.startsWith("Description: ") }
            ?.removePrefix("Description: ") ?: name
    }

    override fun openSoundSettings() {
        val commands = listOf(listOf("pavucontrol"), listOf("gnome-control-center", "sound"), listOf("systemsettings", "kcm_pulseaudio"))
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        val command = commands.firstOrNull { argv -> path.any { File(it, argv.first()).canExecute() } }
        checkNotNull(command) { "未找到系统声音设置程序" }
        ProcessBuilder(command).start()
    }

    private fun matchesHeadset(name: String, kind: String, address: String): Boolean {
        val prefix = "bluez_${kind}.${address.replace(':', '_')}"
        val device = name.replace(':', '_')
        return device == prefix || device.startsWith("$prefix.")
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
