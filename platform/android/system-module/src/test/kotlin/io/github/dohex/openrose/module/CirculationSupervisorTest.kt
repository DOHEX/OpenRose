package io.github.dohex.openrose.module

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.openrose_circulation.CirculationCommand
import uniffi.openrose_circulation.CirculationReply
import uniffi.openrose_circulation.RemoteError
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class CirculationSupervisorTest {
    @Test
    fun aFailedCreationIsRetriedAfterTheBackoffAndTheNodeThenAnswers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = hostedCoreRuntime(scope)
        val attempts = AtomicInteger()
        try {
            val supervisor = CirculationSupervisor(scope, backoffMillis = 20L) {
                if (attempts.incrementAndGet() == 1) error("discovery unavailable")
                control.isolatedCirculationNode()
            }
            assertEquals(CirculationReply.Failed(RemoteError.ServiceClosed), supervisor.execute(CirculationCommand.Directory))
            var reply: CirculationReply = CirculationReply.Forgotten
            repeat(100) {
                reply = supervisor.execute(CirculationCommand.Directory)
                if (reply !is CirculationReply.Failed) return@repeat
                Thread.sleep(50)
            }
            assertIs<CirculationReply.Directory>(reply)
            assertTrue(attempts.get() >= 2)
        } finally {
            scope.cancel()
        }
    }
}
