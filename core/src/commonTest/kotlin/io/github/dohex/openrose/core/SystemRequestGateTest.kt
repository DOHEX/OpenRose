package io.github.dohex.openrose.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** One tap in the system UI must become one user operation, however many vendor entry points it reaches. */
class SystemRequestGateTest {
    private var now = 1_000L
    private val gate = SystemRequestGate({ now }, windowMillis = 250)
    private var handedOver = 0

    private fun submit(key: Any, accept: Boolean = true) = gate.submit(key) { handedOver++; accept }

    @Test
    fun theSameOperationReachedTwiceIsHandedOverOnce() {
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:anc"))
        now += 100
        assertEquals(SystemRequestGate.Result.DUPLICATE, submit("gen#1:rose-a:1:anc"))
        assertEquals(1, handedOver)
    }

    @Test
    fun aLaterTapOfTheSameOperationIsANewUserOperation() {
        submit("gen#1:rose-a:1:anc")
        now += 250
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:anc"))
        assertEquals(2, handedOver)
    }

    @Test
    fun aDifferentModeSessionOrGenerationIsNeverCoalesced() {
        submit("gen#1:rose-a:1:anc")
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:transparency"))
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:2:anc"))
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#2:rose-a:1:anc"))
        assertEquals(4, handedOver)
    }

    @Test
    fun aRefusedHandOverReleasesTheOperationImmediately() {
        assertEquals(SystemRequestGate.Result.REJECTED, submit("k", accept = false))
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("k"))
    }

    @Test
    fun aRepeatWhileTheFirstIsStillBeingHandedOverIsADuplicate() {
        var inner: SystemRequestGate.Result? = null
        gate.submit("k") {
            inner = submit("k")
            true
        }
        assertEquals(SystemRequestGate.Result.DUPLICATE, inner)
    }
}
