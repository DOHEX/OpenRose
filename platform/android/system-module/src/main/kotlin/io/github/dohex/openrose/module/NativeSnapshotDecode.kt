package io.github.dohex.openrose.module

import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.decodeControlSnapshot

internal sealed interface NativeSnapshotDecode {
    data class Decoded(val native: NativeHeadsetSnapshot) : NativeSnapshotDecode

    data class Rejected(val reason: String) : NativeSnapshotDecode
}

internal fun decodeNativeSnapshot(frame: Bundle): NativeSnapshotDecode {
    val bytes = frame.getByteArray(BridgeWire.CORE_SNAPSHOT) ?: return NativeSnapshotDecode.Rejected("snapshot_core_missing")
    val decoded = systemIntegrationResult { decodeControlSnapshot(bytes) }.getOrElse {
        return NativeSnapshotDecode.Rejected("snapshot_core_unavailable")
    }
    return when (decoded) {
        is CoreDecodeResult.Error.Malformed -> NativeSnapshotDecode.Rejected("snapshot_core_malformed")

        is CoreDecodeResult.Error.UnsupportedVersion -> NativeSnapshotDecode.Rejected("snapshot_core_unsupported_version")

        is CoreDecodeResult.Ok -> {
            val native = NativeHeadsetSnapshot.from(
                decoded.value,
                frame.getString(BridgeWire.HOST_EPOCH).orEmpty(),
                frame.getLong(BridgeWire.CAPTURED_ELAPSED_MILLIS),
            )
            native?.let(NativeSnapshotDecode::Decoded) ?: NativeSnapshotDecode.Rejected("snapshot_core_session_missing")
        }
    }
}
