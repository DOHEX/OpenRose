package io.github.dohex.openrose.ffi

import uniffi.openrose_control.CodecException
import uniffi.openrose_control.ControlCommand
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_ffi.decodeCommand
import uniffi.openrose_ffi.decodeSnapshot
import uniffi.openrose_ffi.encodeCommand
import uniffi.openrose_ffi.encodeSnapshot
import uniffi.openrose_ffi.wireFormatVersion

sealed interface CoreDecodeResult<out T> {
    data class Ok<T>(val value: T) : CoreDecodeResult<T>

    sealed interface Error : CoreDecodeResult<Nothing> {
        data class Malformed(val detail: String) : Error

        data class UnsupportedVersion(val expected: UByte, val actual: UByte) : Error
    }
}

fun coreWireFormatVersion(): UByte = wireFormatVersion()

fun ControlSnapshot.encodeToBytes(): ByteArray = encodeSnapshot(this)

fun decodeControlSnapshot(bytes: ByteArray): CoreDecodeResult<ControlSnapshot> = decodeCore { decodeSnapshot(bytes) }

fun ControlCommand.encodeToBytes(): ByteArray = encodeCommand(this)

fun decodeControlCommand(bytes: ByteArray): CoreDecodeResult<ControlCommand> = decodeCore { decodeCommand(bytes) }

private inline fun <T> decodeCore(decode: () -> T): CoreDecodeResult<T> = try {
    CoreDecodeResult.Ok(decode())
} catch (error: CodecException.Malformed) {
    CoreDecodeResult.Error.Malformed(error.detail)
} catch (error: CodecException.UnsupportedVersion) {
    CoreDecodeResult.Error.UnsupportedVersion(error.expected, error.actual)
}
