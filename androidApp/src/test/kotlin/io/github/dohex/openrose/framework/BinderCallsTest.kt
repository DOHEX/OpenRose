package io.github.dohex.openrose.framework

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class BinderCallsTest {
    @Test
    fun anUnresponsiveTransactionReleasesItsWaiterButKeepsItsSlot() = runBlocking {
        val calls = BinderCalls(1)
        val started = CompletableDeferred<Unit>()
        val abandoned = CompletableDeferred<Int>()
        val release = CountDownLatch(1)
        try {
            val wait = launch {
                calls.run<Int>(onAbandoned = { abandoned.complete(it) }) {
                    started.complete(Unit)
                    release.await()
                    1
                }
            }
            withTimeout(5_000) {
                started.await()
                wait.cancelAndJoin()
            }
            var ran = false
            assertNull(withTimeoutOrNull(50) { calls.run { ran = true } })
            assertFalse(ran)
            release.countDown()
            assertEquals(1, withTimeout(5_000) { abandoned.await() })
            assertEquals(2, withTimeout(5_000) { calls.run { 2 } })
        } finally {
            release.countDown()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun leavingDuringDiscoveryUnbindsThePendingConnection() = runTest {
        var bindings = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindings++
                return true
            }
            override fun unbindService(connection: ServiceConnection) {
                bindings--
            }
        }
        val discovering = launch { ModuleHostBinderLink(context).open() }
        runCurrent()
        assertEquals(1, bindings)
        discovering.cancelAndJoin()
        assertEquals(0, bindings)
    }
}
