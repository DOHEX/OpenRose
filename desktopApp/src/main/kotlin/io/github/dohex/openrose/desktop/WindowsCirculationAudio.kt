package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.transport.windows.WindowsAudio

internal class WindowsCirculationAudio(private val audio: WindowsAudio = WindowsAudio()) : DesktopCirculationAudio {
    override fun isConnected(address: String): Boolean = audio.isConnected(address)
    override fun isMediaConnected(address: String): Boolean = audio.isMediaConnected(address)
    override fun release(address: String) = audio.release(address)
    override fun acquire(address: String) = audio.acquire(address)
    override fun currentOutput(): String? = audio.currentOutput()
    override fun openSoundSettings() {
        ProcessBuilder("explorer.exe", "ms-settings:sound").start()
    }
}
