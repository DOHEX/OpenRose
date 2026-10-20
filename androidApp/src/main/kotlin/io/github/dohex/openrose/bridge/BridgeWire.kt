package io.github.dohex.openrose.bridge

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Versioned app/module wire format; it never contains protocol commands or a second session. */
internal object BridgeWire {
    const val VERSION = 1
    const val KEY_VERSION = "version"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val PACKAGE = "package"
    const val PROCESS = "process"
    const val UID = "uid"
    const val PID = "pid"
    const val COMPONENT_MATCH_KEY = "component_match"
    const val RESOLUTION = "resolution"
    const val SIGNATURE = "signature"
    const val LEASE_ID = "lease_id"
    const val SNAPSHOT = "snapshot"
    const val DEVICE_ID = "device_id"
    const val SESSION_ID = "session_id"
    const val READ_SEQUENCE = "read_sequence"
    const val STATUS_OK = "OK"
    const val STATUS_NO_SESSION = "NO_SESSION"
    const val STATUS_TARGET_INCOMPATIBLE = "TARGET_INCOMPATIBLE"
    const val STATUS_STALE_REPORT = "STALE_REPORT"
    const val STATUS_STALE_HOST = "STALE_HOST"
    const val STATUS_STALE_SESSION = "STALE_SESSION"
    const val STATUS_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val STATUS_INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val STATUS_NO_PENDING_READ = "NO_PENDING_READ"
    const val STATUS_STALE_RECEIPT = "STALE_RECEIPT"
    const val RESOLUTION_MATCHED = "MATCHED"
    const val RESOLUTION_CLASS_MISSING = "CLASS_MISSING"
    const val RESOLUTION_METHOD_MISSING = "METHOD_MISSING"
    const val RESOLUTION_HOOK_FAILED = "HOOK_FAILED"
    const val APP_PACKAGE = "io.github.dohex.openrose"
    const val SERVICE_CLASS = "$APP_PACKAGE.bridge.ControlBridgeService"
    const val TARGET_PACKAGE = "com.android.bluetooth"
    const val TARGET_PROCESS = TARGET_PACKAGE
    const val TARGET_COMPONENT_CLASS = "com.android.bluetooth.btservice.AdapterService"
    const val TARGET_COMPONENT_METHOD = "onCreate"
    const val COMPONENT_MATCH = "$TARGET_COMPONENT_CLASS#$TARGET_COMPONENT_METHOD():void"
    const val PREFERENCES_GROUP = "control_bridge_v1"
    const val HOST_EPOCH = "host_epoch"
    const val PROOF_KEY = "proof_key"
    val RESOLUTIONS = setOf(RESOLUTION_MATCHED, RESOLUTION_CLASS_MISSING, RESOLUTION_METHOD_MISSING, RESOLUTION_HOOK_FAILED)

    fun proof(keyHex: String, hostEpoch: String, processName: String, uid: Int, pid: Int, resolution: String = RESOLUTION_MATCHED): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(HOST_EPOCH, hostEpoch)
        putString(PACKAGE, TARGET_PACKAGE)
        putString(PROCESS, processName)
        putInt(UID, uid)
        putInt(PID, pid)
        putString(COMPONENT_MATCH_KEY, COMPONENT_MATCH)
        putString(RESOLUTION, resolution)
        putString(SIGNATURE, signature(keyHex, hostEpoch, processName, uid, pid, resolution).hex())
    }

    fun signature(keyHex: String, hostEpoch: String, processName: String, uid: Int, pid: Int, resolution: String): ByteArray {
        require(keyHex.length == 64 && keyHex.all { it.isHexDigit() })
        require(hostEpoch.length in 1..64 && processName.length in 1..128)
        require(resolution in RESOLUTIONS)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(hostEpoch)
                out.writeUTF(TARGET_PACKAGE)
                out.writeUTF(processName)
                out.writeInt(uid)
                out.writeInt(pid)
                out.writeUTF(COMPONENT_MATCH)
                out.writeUTF(resolution)
            }
            buffer.toByteArray()
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256"))
            doFinal(bytes)
        }
    }

    fun decodeSignature(value: String?): ByteArray? = value?.takeIf {
        it.length == 64 && it.all { char -> char.isHexDigit() }
    }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    fun response(status: String, message: String? = null): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(STATUS, status)
        message?.let { putString(MESSAGE, it) }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
