package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder

object HostIdentityWire {
    fun encode(generation: OwnerGeneration): Wire = obj(
        "incarnation" to str(generation.incarnation),
        "sequence" to num(generation.sequence),
    )
    fun decodeGeneration(wire: Wire): OwnerGeneration = wire.asObj().let { OwnerGeneration(it.str("incarnation"), it.long("sequence")) }

    fun encode(holder: OwnerHolder): Wire = obj(
        "way" to Wire.Str(holder.way.name),
        "generation" to encode(holder.generation),
        "package" to str(holder.packageName),
        "process" to str(holder.processName),
        "uid" to holder.uid?.let(::num),
        "pid" to holder.pid?.let(::num),
    )

    fun decodeHolder(wire: Wire): OwnerHolder = wire.asObj().let {
        OwnerHolder(
            it.field("way").asEnum<ControlWay>(),
            decodeGeneration(it.field("generation")),
            it.str("package"),
            it.str("process"),
            it.optional("uid")?.asInt(),
            it.optional("pid")?.asInt(),
        )
    }

    fun encode(status: HostStatus): Wire = when (status) {
        is HostStatus.Active -> obj("t" to str("active"), "generation" to encode(status.generation), "holder" to encode(status.holder))

        is HostStatus.Inactive -> obj(
            "t" to str("inactive"),
            "incarnation" to str(status.incarnation),
            "last" to status.lastGeneration?.let(::encode),
            "reason" to str(status.reason),
        )
    }

    fun decodeStatus(wire: Wire): HostStatus {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "active" -> HostStatus.Active(decodeGeneration(node.field("generation")), decodeHolder(node.field("holder")))
            "inactive" -> HostStatus.Inactive(node.str("incarnation"), node.optional("last")?.let(::decodeGeneration), node.str("reason"))
            else -> throw WireFormatException("未知宿主状态 $kind")
        }
    }

    fun encode(result: HostOpenResult): Wire = when (result) {
        is HostOpenResult.Opened -> obj(
            "t" to str("opened"),
            "lease" to str(result.lease.id),
            "role" to Wire.Str(result.lease.role.name),
            "incarnation" to str(result.lease.incarnation),
            "status" to encode(result.status),
        )

        is HostOpenResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeOpenResult(wire: Wire): HostOpenResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "opened" -> HostOpenResult.Opened(
                HostLease(node.str("lease"), node.field("role").asEnum<ClientRole>(), node.str("incarnation")),
                decodeStatus(node.field("status")),
            )

            "refused" -> HostOpenResult.Refused(node.field("rejection").asEnum<HostRejection>(), node.str("message"))

            else -> throw WireFormatException("未知授权结果 $kind")
        }
    }
}
