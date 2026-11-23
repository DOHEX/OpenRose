package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.ReleaseReceipt
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest

object HandoffWire {
    fun encode(request: SwitchRequest): Wire = obj(
        "id" to str(request.id),
        "from" to Wire.Str(request.from.name),
        "to" to Wire.Str(request.to.name),
        "generation" to request.fromGeneration?.let(HostIdentityWire::encode),
    )

    fun decodeRequest(wire: Wire): SwitchRequest = wire.asObj().let {
        SwitchRequest(
            it.str("id"),
            it.field("from").asEnum<ControlWay>(),
            it.field("to").asEnum<ControlWay>(),
            it.optional("generation")?.let(HostIdentityWire::decodeGeneration),
        )
    }

    fun encode(receipt: ReleaseReceipt): Wire = obj(
        "request" to str(receipt.requestId),
        "incarnation" to str(receipt.incarnation),
        "generation" to receipt.generation?.let(HostIdentityWire::encode),
        "predecessorEnded" to bool(receipt.predecessorEnded),
    )

    private fun decodeReceipt(wire: Wire): ReleaseReceipt = wire.asObj().let {
        ReleaseReceipt(
            it.str("request"),
            it.str("incarnation"),
            it.optional("generation")?.let(HostIdentityWire::decodeGeneration),
            it.bool("predecessorEnded"),
        )
    }

    fun encode(result: ReleaseResult): Wire = when (result) {
        is ReleaseResult.Released -> obj("t" to str("released"), "receipt" to encode(result.receipt))

        is ReleaseResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
            "current" to result.current?.let(HostIdentityWire::encode),
        )
    }

    fun decodeReleaseResult(wire: Wire): ReleaseResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "released" -> ReleaseResult.Released(decodeReceipt(node.field("receipt")))

            "refused" -> ReleaseResult.Refused(
                node.field("rejection").asEnum<HostRejection>(),
                node.str("message"),
                node.optional("current")?.let(HostIdentityWire::decodeStatus),
            )

            else -> throw WireFormatException("未知释放结果 $kind")
        }
    }

    fun encode(result: AcquireResult): Wire = when (result) {
        is AcquireResult.Acquired -> obj(
            "t" to str("acquired"),
            "generation" to HostIdentityWire.encode(result.generation),
            "holder" to HostIdentityWire.encode(result.holder),
        )

        is AcquireResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeAcquireResult(wire: Wire): AcquireResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "acquired" -> AcquireResult.Acquired(
                HostIdentityWire.decodeGeneration(node.field("generation")),
                HostIdentityWire.decodeHolder(node.field("holder")),
            )

            "refused" -> AcquireResult.Refused(node.field("rejection").asEnum<HostRejection>(), node.str("message"))

            else -> throw WireFormatException("未知获取结果 $kind")
        }
    }
}
