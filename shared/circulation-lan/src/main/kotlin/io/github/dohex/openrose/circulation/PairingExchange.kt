package io.github.dohex.openrose.circulation

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger

internal fun authenticatePairing(localId: String, remoteId: String, code: String, input: DataInputStream, output: DataOutputStream) {
    val participant = JPAKEParticipant(localId, code.toCharArray())
    val first = participant.createRound1PayloadToSend()
    output.writeNumbers(listOf(first.gx1, first.gx2) + first.knowledgeProofForX1 + first.knowledgeProofForX2)
    participant.validateRound1PayloadReceived(
        JPAKERound1Payload(remoteId, input.number(), input.number(), input.proof(), input.proof()),
    )
    val second = participant.createRound2PayloadToSend()
    output.writeNumbers(listOf(second.a) + second.knowledgeProofForX2s)
    participant.validateRound2PayloadReceived(JPAKERound2Payload(remoteId, input.number(), input.proof()))
    val key = participant.calculateKeyingMaterial()
    output.writeNumbers(listOf(participant.createRound3PayloadToSend(key).macTag))
    participant.validateRound3PayloadReceived(JPAKERound3Payload(remoteId, input.number()), key)
}

private fun DataOutputStream.writeNumbers(numbers: List<BigInteger>) {
    numbers.forEach {
        val bytes = it.toByteArray()
        writeShort(bytes.size)
        write(bytes)
    }
    flush()
}

private fun DataInputStream.number(): BigInteger {
    val size = readUnsignedShort()
    require(size in 1..385)
    return BigInteger(ByteArray(size).also(::readFully))
}

private fun DataInputStream.proof(): Array<BigInteger> = arrayOf(number(), number())
