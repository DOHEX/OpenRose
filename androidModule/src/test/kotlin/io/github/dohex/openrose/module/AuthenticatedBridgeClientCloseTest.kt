package io.github.dohex.openrose.module

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.dohex.openrose.bridge.BridgeTarget
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class AuthenticatedBridgeClientCloseTest {
    @Test
    fun aLaterReloadSeesCompletedCleanupAfterTheFirstWaitTimedOut() {
        val target = BridgeTarget.MILINK
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = target.packageName
        }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = AuthenticatedBridgeClient(context, target, target.packageName, {}, {}, {}, {}, onClosing = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertFalse(client.close(timeoutMillis = 20))
            assertTrue(started.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(client.close())
        assertTrue(client.close(timeoutMillis = 20))
    }
}
