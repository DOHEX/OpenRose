package io.github.dohex.openrose.circulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

internal data class PeerGrant(val peer: PairedPeer, val token: String)

internal class PeerTrust(directory: File) {
    private val file = File(directory, "peers.properties")
    private val saved = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    private val mutablePeers = MutableStateFlow(listPeers())
    val peers = mutablePeers.asStateFlow()

    @Synchronized
    fun grant(id: String): PeerGrant? = saved.getProperty("token.$id")?.let { PeerGrant(PairedPeer(id, saved.getProperty("name.$id")), it) }

    @Synchronized
    fun add(grant: PeerGrant) {
        update {
            setProperty("token.${grant.peer.id}", grant.token)
            setProperty("name.${grant.peer.id}", grant.peer.name)
        }
    }

    @Synchronized
    fun remove(id: String) {
        update {
            remove("token.$id")
            remove("name.$id")
        }
    }

    private fun update(change: Properties.() -> Unit) {
        val next = Properties().apply { putAll(saved); change() }
        file.replaceContents { next.store(it, null) }
        saved.clear()
        saved.putAll(next)
        mutablePeers.value = listPeers()
    }

    private fun listPeers() = saved.stringPropertyNames().filter { it.startsWith("token.") }
        .mapNotNull { grant(it.removePrefix("token."))?.peer }.sortedBy { it.name }
}
