package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelProtocol

internal class ZtCodec(override val profile: ModelProfile, private val protocol: ModelProtocol.Zt) : ProtocolCodec {
    override fun prepareSetting(
        setting: DeviceSetting,
        value: FieldValue,
        firmware: FieldValue.Firmware?,
        currentFields: Map<DeviceField, FieldReading>,
    ): ProtocolSetting {
        if (setting == DeviceSetting.CUSTOM_EQ) return customEqualizer(value, firmware)
        val (writeKey, readKey, payload) = protocol.settingFrame(profile, setting, value, firmware)
        val readback = query(readKey)
        val expectedValues = if (readKey == "0801" && !protocol.independentMultipoint) {
            val multipoint = payload.single().u() == 0
            mapOf(
                DeviceField.AUDIO_MODE to FieldValue.Text(if (multipoint) "aac_sbc" else "ldac"),
                DeviceField.MULTIPOINT to FieldValue.Toggle(multipoint),
            )
        } else {
            mapOf(readback.expectedFields.single() to value)
        }
        return ProtocolSetting(
            listOf(frame(writeKey, payload)),
            readback,
            expectedValues,
        )
    }

    private fun customEqualizer(value: FieldValue, firmware: FieldValue.Firmware?): ProtocolSetting {
        val payload = customCurveBytes(value, profile.capabilities(firmware).equalizer?.custom)
        val slot = protocol.eqPresets.indexOf("custom")
        require(slot >= 0) { "该型号的自定义均衡器没有可选的预设槽位" }
        return ProtocolSetting(
            listOf(frame("0281", byteArrayOf(slot.toByte())), frame("0282", payload)),
            query("0201"),
            mapOf(DeviceField.EQ to FieldValue.Text("custom"), DeviceField.CUSTOM_EQ to value),
            listOf(query("0202")),
        )
    }

    private fun frame(key: String, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = payload.size + 10
        val prefix = "08ee000000$key".bytes() + byteArrayOf(length.toByte(), (length shr 8).toByte()) + payload
        return prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte())
    }

    private fun query(key: String): ProtocolQuery {
        val fields = when {
            key == "0101" && profile.batteryLayout == BatteryLayout.SINGLE_UNIT -> emptySet()
            key == "0801" && protocol.independentMultipoint -> setOf(DeviceField.AUDIO_MODE)
            else -> ZtReply.commands.getValue(key).fields
        }
        val responseKeys = if (key == "0101" && profile.batteryLayout == BatteryLayout.SINGLE_UNIT) emptySet() else setOf(key)
        return ProtocolQuery(key, frame(key), responseKeys, fields)
    }

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 10) { "ZT queries require a 10-byte write capacity" }
        val keys = protocol.initKeys.ifEmpty {
            listOf("0101", "0602", "0201", "0603", "0801", "0d01", "0401", "0a01", "0605", "0c01", "0607", "0604", "0e03")
        }
        return keys.map(::query)
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> = listOf(ZtReply.read(bytes, profile, protocol))

    override fun reset() = Unit
}
