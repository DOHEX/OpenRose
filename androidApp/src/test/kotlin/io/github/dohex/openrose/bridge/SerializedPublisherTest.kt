package io.github.dohex.openrose.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedPublisherTest {
    @Volatile private var persisted = "MODULE"
    private val committed = CopyOnWriteArrayList<String>()

    @Test
    fun aWriteStartedBeforeALaterChangeNeverOutlivesIt() {
        val firstCommitting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val publisher = SerializedPublisher<String> { value ->
            if (committed.isEmpty()) {
                firstCommitting.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            committed += value
            true
        }

        val early = thread { publisher.publish { persisted } }
        assertTrue(firstCommitting.await(10, TimeUnit.SECONDS))
        // The user's later choice is saved and published while the earlier commit is still in flight.
        persisted = "ORDINARY"
        val late = thread { publisher.publish { persisted } }
        releaseFirst.countDown()
        early.join(10_000)
        late.join(10_000)

        assertEquals("ORDINARY", committed.last())
        assertEquals(listOf("MODULE", "ORDINARY"), committed.toList())
    }

    @Test
    fun theValueIsDerivedOnlyOnceTheEarlierCommitIsDone() {
        val firstCommitting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val publisher = SerializedPublisher<String> { value ->
            committed += value
            if (committed.size == 1) {
                firstCommitting.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            true
        }
        val early = thread { publisher.publish { persisted } }
        assertTrue(firstCommitting.await(10, TimeUnit.SECONDS))
        persisted = "ORDINARY"
        val derivedWhileFirstRunning = CountDownLatch(1)
        val late = thread {
            publisher.publish {
                derivedWhileFirstRunning.countDown()
                persisted
            }
        }

        assertFalse(derivedWhileFirstRunning.await(300, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        early.join(10_000)
        late.join(10_000)
        assertEquals("ORDINARY", committed.last())
    }

    @Test
    fun theCommitResultIsReturned() {
        assertTrue(SerializedPublisher<String> { true }.publish { "x" })
        assertFalse(SerializedPublisher<String> { false }.publish { "x" })
    }
}
