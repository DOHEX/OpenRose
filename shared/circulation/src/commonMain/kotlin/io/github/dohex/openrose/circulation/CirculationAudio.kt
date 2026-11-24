package io.github.dohex.openrose.circulation

interface CirculationAudio {
    fun isConnected(address: String): Boolean
    fun isMediaConnected(address: String): Boolean

    fun release(address: String)

    fun acquire(address: String)
}
