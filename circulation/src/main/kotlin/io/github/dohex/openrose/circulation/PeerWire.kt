package io.github.dohex.openrose.circulation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.net.ssl.SSLSocket

/** One bounded transaction per authenticated TLS socket. No command is retried after delivery. */
internal class PeerWire(socket: SSLSocket) {
    val input = DataInputStream(socket.inputStream.buffered())
    val output = DataOutputStream(socket.outputStream.buffered())

    fun send(fields: Map<String, String>) {
        require(fields.size <= MAX_FIELDS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            for ((key, value) in fields) {
                data.writeUTF(key)
                data.writeUTF(value)
                require(bytes.size() <= MAX_FRAME)
            }
        }
        output.writeInt(bytes.size())
        bytes.writeTo(output)
        output.flush()
    }

    fun receive(): Map<String, String> {
        val length = input.readInt()
        if (length !in 0..MAX_FRAME) throw IOException("互联报文长度无效")
        val frame = DataInputStream(ByteArrayInputStream(ByteArray(length).also(input::readFully)))
        return buildMap {
            while (frame.available() > 0) {
                if (size >= MAX_FIELDS || put(frame.readUTF(), frame.readUTF()) != null) throw IOException("互联报文无效")
            }
        }
    }

    companion object {
        private const val MAX_FRAME = 16_384
        private const val MAX_FIELDS = 64
    }
}
