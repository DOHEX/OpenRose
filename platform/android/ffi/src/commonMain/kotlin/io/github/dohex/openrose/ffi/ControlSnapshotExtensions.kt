package io.github.dohex.openrose.ffi

import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HeadsetCandidate
import uniffi.openrose_control.SessionReference
import uniffi.openrose_ffi.ControlFailure
import uniffi.openrose_ffi.currentHeadset
import uniffi.openrose_ffi.decodeSnapshot
import uniffi.openrose_ffi.encodeSnapshot
import uniffi.openrose_ffi.headsetHasSystemAudio
import uniffi.openrose_ffi.readySession
import uniffi.openrose_ffi.sessionFor
import uniffi.openrose_headset.HeadsetSetting

fun ControlSnapshot.currentHeadset(): HeadsetCandidate? = currentHeadset(this)

fun ControlSnapshot.readySession(): SessionReference? = readySession(this)

fun ControlSnapshot.sessionFor(setting: HeadsetSetting): SessionReference? = sessionFor(this, setting)

fun ControlSnapshot.hasSystemAudio(): Boolean = headsetHasSystemAudio(this)

fun ControlSnapshot.encodeToBytes(): ByteArray = encodeSnapshot(this)

fun decodeControlSnapshot(bytes: ByteArray): Result<ControlSnapshot> = try {
    Result.success(decodeSnapshot(bytes))
} catch (failure: ControlFailure.Failed) {
    Result.failure(failure)
}
