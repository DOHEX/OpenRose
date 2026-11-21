package io.github.dohex.openrose.androidintegration

import kotlin.test.Test
import kotlin.test.assertEquals

/** One tap in the system UI must become one user operation, however many vendor entry points it reaches. */
class SystemRequestGateTest {
    private var now = 1_000L
    private val gate = SystemRequestGate({ now }, windowMillis = 250)
    private val delivered = mutableListOf<Any>()

    private fun submit(key: Any, accept: Boolean = true) = gate.submit(key) {
        delivered += key
        accept
    }

    @Test
    fun theSameOperationReachedTwiceIsHandedOverOnce() {
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:anc"))
        now += 100
        assertEquals(SystemRequestGate.Result.DUPLICATE, submit("gen#1:rose-a:1:anc"))
        assertEquals(listOf<Any>("gen#1:rose-a:1:anc"), delivered)
    }

    @Test
    fun aLaterTapOfTheSameOperationIsANewUserOperation() {
        submit("gen#1:rose-a:1:anc")
        now += 250
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:anc"))
        assertEquals(listOf<Any>("gen#1:rose-a:1:anc", "gen#1:rose-a:1:anc"), delivered)
    }

    @Test
    fun aDifferentModeSessionOrGenerationIsNeverCoalesced() {
        submit("gen#1:rose-a:1:anc")
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:1:transparency"))
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#1:rose-a:2:anc"))
        assertEquals(SystemRequestGate.Result.ACCEPTED, submit("gen#2:rose-a:1:anc"))
        assertEquals(
            listOf<Any>("gen#1:rose-a:1:anc", "gen#1:rose-a:1:transparency", "gen#1:rose-a:2:anc", "gen#2:rose-a:1:anc"),
            delivered,
        )
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
