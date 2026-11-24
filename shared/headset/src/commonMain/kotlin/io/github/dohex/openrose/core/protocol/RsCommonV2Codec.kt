package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelProtocol
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

internal class RsCommonV2Codec(
    override val profile: ModelProfile,
    private val protocol: ModelProtocol.RsCommonV2,
    private val localTime: () -> LocalDateTime = { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) },
) : ProtocolCodec {
    private var sequence = 0
    private var buffer = byteArrayOf()

    private val infoTypes = (protocol.infoIds ?: "010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649").bytes()

    override fun prepareSetting(
        setting: DeviceSetting,
        value: FieldValue,
        firmware: FieldValue.Firmware?,
        currentFields: Map<DeviceField, FieldReading>,
    ): ProtocolSetting {
        val capabilities = CapabilityFacts(currentFields, firmware).resolve(profile)
        if (setting == DeviceSetting.CUSTOM_EQ) return customEqualizer(value, capabilities)
        val (type, payload, values) = rsV2Setting(capabilities, setting, value)
        val write = frame(byteArrayOf(type) + payload)
        val followUp = protocol.spatialFollowUp(setting, value, currentFields)
        if (followUp != null) {
            val (body, field, expectedValue) = followUp
            return ProtocolSetting(
                listOf(write, frame(body)),
                query(byteArrayOf(type), values.keys),
                values + (field to expectedValue),
                listOf(query(byteArrayOf(body.first()), setOf(field))),
            )
        }
        val readback = query(byteArrayOf(type), values.keys)
        val refreshEq = setting == DeviceSetting.GAME_MODE && protocol.refreshEqualizerAfterGameMode
        return ProtocolSetting(listOf(write), readback, values, if (refreshEq) listOf(query(byteArrayOf(0x2a))) else emptyList())
    }

    private fun customEqualizer(value: FieldValue, capabilities: DeviceCapabilities): ProtocolSetting {
        val payload = customCurveBytes(value, capabilities.equalizer?.custom)
        val writes = listOf(frame(byteArrayOf(0x2a, 4)), frame(byteArrayOf(0x3e) + payload))
        val modeReadback = query(byteArrayOf(0x2a))
        val curveReadback = queryCurve()
        return ProtocolSetting(
            writes,
            modeReadback,
            mapOf(DeviceField.EQ to FieldValue.Text("custom"), DeviceField.CUSTOM_EQ to value),
            listOf(curveReadback),
        )
    }

    private fun frame(body: ByteArray): ByteArray {
        val prefix = byteArrayOf(0xff.toByte(), sequence.toByte(), body.size.toByte()) + body
        sequence = (sequence + 1) and 255
        return prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte(), 0xaa.toByte())
    }

    private fun query(ids: ByteArray, expectedFields: Set<DeviceField>? = null): ProtocolQuery {
        val fields = expectedFields ?: profile.capabilities().readableFields
        val keys = ids.map { byteArrayOf(it).hex() }.filter { id -> rsCommonV2Fields[id].orEmpty().any { it in fields } }.toSet()
        return ProtocolQuery(
            "fa" + ids.hex(),
            frame(byteArrayOf(0xfa.toByte()) + ids),
            keys,
            expectedFields ?: keys.flatMap { rsCommonV2Fields.getValue(it) }.intersect(fields),
        )
    }

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 7) { "RS Common V2 queries require at least a 7-byte write capacity" }
        val groups = infoTypes.toList().chunked(maxWriteSize - 6)
        return buildList {
            groups.forEach { add(query(it.toByteArray())) }
            if (protocol.synchronizeClockOnConnect) {
                val now = localTime()
                val body = byteArrayOf(
                    0x35,
                    (now.year / 100).toByte(),
                    (now.year % 100).toByte(),
                    now.month.number.toByte(),
                    now.day.toByte(),
                    now.hour.toByte(),
                    now.minute.toByte(),
                    now.second.toByte(),
                )
                add(ProtocolQuery("35", frame(body), emptySet(), emptySet()))
            }
            if (profile.capabilities().equalizer?.custom != null) add(queryCurve())
        }
    }

    override fun firmwareInitQueries(firmware: FieldValue.Firmware?): List<ProtocolQuery> {
        val unlocked = profile.capabilities().equalizer?.custom == null && profile.capabilities(firmware).equalizer?.custom != null
        return if (unlocked) listOf(queryCurve()) else emptyList()
    }

    private fun queryCurve() = query(byteArrayOf(0x3e), setOf(DeviceField.CUSTOM_EQ))

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        buffer += bytes
        val replies = mutableListOf<ProtocolReply>()
        if (buffer.size > 8192) {
            replies += ProtocolReply(buffer.hex(), warnings = listOf("接收缓冲区超过 8192 字节，已清空"))
            buffer = byteArrayOf()
            return replies
        }
        while (buffer.isNotEmpty()) {
            val start = buffer.indexOf(0xdd.toByte())
            if (start < 0) {
                replies += ProtocolReply(buffer.hex(), warnings = listOf("没有 DD 接收帧头"))
                buffer = byteArrayOf()
            } else {
                if (start > 0) {
                    replies += ProtocolReply(buffer.copyOfRange(0, start).hex(), warnings = listOf("忽略帧前数据"))
                    buffer = buffer.copyOfRange(start, buffer.size)
                }
                val (end, reply) = findComplete() ?: break
                replies += reply
                buffer = buffer.copyOfRange(end, buffer.size)
            }
        }
        return replies
    }

    private fun findComplete(): Pair<Int, ProtocolReply>? {
        for (end in 5 until buffer.size) {
            if (buffer[end].u() == 0xaa) {
                parseFrame(buffer.copyOfRange(0, end + 1))?.let { return (end + 1) to it }
            }
        }
        return null
    }

    private fun parseFrame(raw: ByteArray): ProtocolReply? {
        val checksum = raw[raw.size - 2].u()
        val prefixMatches = (raw.dropLast(2).sumOf { it.u() } and 255) == checksum
        val recordsMatch = (raw.slice(2 until raw.size - 2).sumOf { it.u() } and 255) == checksum
        if (!prefixMatches && !recordsMatch) return null
        val readings = linkedMapOf<DeviceField, FieldReading>()
        val keys = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var position = 2
        val stop = raw.size - 2
        while (position + 1 < stop) {
            val length = raw[position].u()
            val end = if (length == 0) stop else position + length + 1
            if (end > stop) return null
            val key = byteArrayOf(raw[position + 1]).hex()
            val value = raw.copyOfRange(position + 2, end)
            if (length == 0) warnings += "L00 兼容字段；客户端仍验证校验字节"
            val decoded = readRsV2Record(key, profile, value)
            if (decoded.isNotEmpty()) {
                keys += key
                readings += decoded
            } else {
                warnings += "未更新字段 $key=${value.hex()}"
            }
            position = end
        }
        return if (position == stop) ProtocolReply(raw.hex(), keys, readings, warnings) else null
    }

    override fun reset() {
        sequence = 0
        buffer = byteArrayOf()
    }
}

private fun ModelProtocol.RsCommonV2.spatialFollowUp(
    setting: DeviceSetting,
    value: FieldValue,
    currentFields: Map<DeviceField, FieldReading>,
): Triple<ByteArray, DeviceField, FieldValue>? {
    if (!spatialNeedsEarAdaptive) return null
    fun confirmed(field: DeviceField) = (currentFields[field] as? FieldReading.Known)?.value
    return when {
        setting == DeviceSetting.SPATIAL_MODE && value != FieldValue.Text("off") &&
            confirmed(DeviceField.EAR_ADAPTIVE) != FieldValue.Toggle(true) ->
            Triple(byteArrayOf(0x3a, 1), DeviceField.EAR_ADAPTIVE, FieldValue.Toggle(true))

        setting == DeviceSetting.EAR_ADAPTIVE && value == FieldValue.Toggle(false) &&
            confirmed(DeviceField.SPATIAL_MODE) != FieldValue.Text("off") ->
            Triple(byteArrayOf(0x38, 0), DeviceField.SPATIAL_MODE, FieldValue.Text("off"))

        else -> null
    }
}
