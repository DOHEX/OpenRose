package io.github.dohex.openrose.androidintegration

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val POLL_MILLIS = 5L
private const val QUIET_ROUNDS = 4
private const val QUIET_ROUND_MILLIS = 25L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.eventually(what: String, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + 5.seconds
    runCurrent()
    while (!condition()) {
        check(deadline.hasNotPassedNow()) { "timed out waiting for $what" }
        blockFor(POLL_MILLIS)
        runCurrent()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.quiesce() {
    repeat(QUIET_ROUNDS) {
        runCurrent()
        blockFor(QUIET_ROUND_MILLIS)
    }
    runCurrent()
}
