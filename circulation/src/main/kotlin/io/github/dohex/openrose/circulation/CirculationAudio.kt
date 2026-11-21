package io.github.dohex.openrose.circulation

/** Blocking platform operations on a locally recognised i5's Bluetooth Classic address. */
interface CirculationAudio {
    fun isConnected(address: String): Boolean

    /** Returns only after the source has left all audio profiles. Reports calls or capture as a refusal. */
    fun release(address: String)

    /** Returns after the media audio profile connects, independently of headset control and default output. */
    fun acquire(address: String)
}
