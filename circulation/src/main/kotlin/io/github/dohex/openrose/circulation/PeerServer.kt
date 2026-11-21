package io.github.dohex.openrose.circulation

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal class PeerServer(private val tls: SSLContext, private val accept: (SSLSocket) -> Unit) : Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        Thread(it, "OpenRose-peer").apply { isDaemon = true }
    }

    @Volatile private var listener: SSLServerSocket? = null
    val running get() = listener != null

    fun start(): Int {
        check(!workers.isShutdown && !running)
        val server = (tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
        listener = server
        thread(isDaemon = true, name = "OpenRose-discovery-listener") {
            try {
                while (running) dispatch(server.accept() as SSLSocket)
            } catch (_: IOException) {
                // Closing the server socket wakes accept.
            }
        }
        return server.localPort
    }

    private fun dispatch(socket: SSLSocket) {
        sockets.add(socket)
        try {
            workers.execute {
                try {
                    socket.use {
                        it.soTimeout = RESPONSE_TIMEOUT_MS
                        accept(it)
                    }
                } catch (_: Exception) {
                    // Invalid credentials, partial frames and disconnected clients end only their own transaction.
                } finally {
                    sockets.remove(socket)
                }
            }
        } catch (_: RejectedExecutionException) {
            sockets.remove(socket)
            socket.close()
        }
    }

    override fun close() {
        val server = listener
        listener = null
        server?.close()
        workers.shutdownNow()
        sockets.forEach { runCatching { it.close() } }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val RESPONSE_TIMEOUT_MS = 18_000
    }
}
