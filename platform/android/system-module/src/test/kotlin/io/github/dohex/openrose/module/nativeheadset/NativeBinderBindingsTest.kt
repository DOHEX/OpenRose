package io.github.dohex.openrose.module.nativeheadset

import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBinderBindingsTest {
    @Test
    fun bondedBindingAdmitsTheFirstSessionAndSessionBindingRejectsItsReplacement() {
        val directory = NativeHeadsetDirectory { 1_000L }
        val bindings = NativeBinderBindings(directory)
        val owner = Any()
        val address = "AA:BB:CC:DD:EE:FF"
        directory.publishBonded(setOf(address))
        bindings.rememberAddress(owner, address.lowercase())
        assertEquals(NativeBinderBindings.BoundHeadset(address, null), bindings.boundHeadset(owner))

        val current = NativeHeadsetSnapshot(
            hostEpoch = "host",
            deviceId = address,
            sessionId = 1,
            addresses = setOf(address),
            profile = ModelProfile.I5_ZT,
            capturedElapsedMillis = 1_000L,
            writableSettings = emptySet(),
            readableFields = emptySet(),
            noiseModes = emptySet(),
            ancLevel = null,
            transparencyLevel = null,
            fields = emptyMap(),
        )
        directory.publishSession(current)
        assertEquals(current, bindings.boundHeadset(owner)?.snapshot)
        bindings.rememberAddress(owner, address)
        directory.publishSession(current.copy(sessionId = 2))
        assertNull(bindings.boundHeadset(owner)?.snapshot)
        assertTrue(bindings.shouldSuppressManagedAction(owner, address))
        bindings.clear()
        assertNull(bindings.boundHeadset(owner))
    }
}
