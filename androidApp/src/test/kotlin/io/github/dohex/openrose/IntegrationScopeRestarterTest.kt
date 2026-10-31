package io.github.dohex.openrose

import io.github.dohex.openrose.ui.ScopeRestartStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class IntegrationScopeRestarterTest {
    @Test
    fun deniedRootReportsFailureForEveryScope() = runBlocking {
        val results = IntegrationScopeRestarter { RootResult(1, "permission denied") }.restart()
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ScopeRestartStatus.FAILED && it.detail!!.contains("permission denied") })
    }

    @Test
    fun concurrentRequestsDoNotStartAnotherRootOperation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restarter = IntegrationScopeRestarter {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            RootResult(1, "permission denied")
        }
        val first = async(Dispatchers.Default) { restarter.restart() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = restarter.restart()
            assertTrue(second.all { it.status == ScopeRestartStatus.FAILED && it.detail == "重启操作正在进行" })
        } finally {
            release.countDown()
            first.await()
        }
    }

    @Test
    fun matchesOnlyAllowedProcessesAndContinuesAfterFailure() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        val dir = Files.createTempDirectory("openrose-scopes")
        val log = dir.resolve("signals")
        val processes = mutableListOf<Process>()
        fun start(name: String): String {
            val pidFile = dir.resolve("pid${processes.size}")
            val process = ProcessBuilder("bash", "-c", "echo \$\$ > \"\$2\"; exec -a \"\$1\" sleep 60", "fixture", name, pidFile.toString()).start()
            processes += process
            repeat(100) {
                if (Files.exists(pidFile)) {
                    val pid = Files.readString(pidFile).trim()
                    val path = java.nio.file.Path.of("/proc/$pid/cmdline")
                    if (Files.exists(path) && Files.readString(path).startsWith(name + '\u0000')) return pid
                }
                Thread.sleep(10)
            }
            error("fixture process did not start: $name")
        }
        try {
            val bluetooth = start("com.android.bluetooth")
            val bluetoothChild = start("com.android.bluetooth:unrelated")
            val xiaomi = start("com.xiaomi.bluetooth:service")
            val xiaomiLookalike = start("com.xiaomi.bluetooth.extra")
            val restarter = IntegrationScopeRestarter { script ->
                val stub = """
                    id() { echo 0; }
                    kill() { printf '%s\n' "${'$'}2" >> "${'$'}SIGNAL_LOG"; if [ "${'$'}2" = "${'$'}FAIL_PID" ]; then echo 'permission denied' >&2; return 1; fi; }
                """.trimIndent()
                val process = ProcessBuilder("sh", "-c", "$stub\n$script").redirectErrorStream(true).apply {
                    environment()["SIGNAL_LOG"] = log.toString()
                    environment()["FAIL_PID"] = bluetooth
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                RootResult(process.waitFor(), output)
            }
            val results = restarter.restart()
            assertEquals(listOf(ScopeRestartStatus.FAILED, ScopeRestartStatus.EXECUTED, ScopeRestartStatus.NOT_RUNNING), results.map { it.status })
            assertEquals("permission denied", results.first().detail)
            val signalled = Files.readAllLines(log).toSet()
            assertTrue(bluetooth in signalled)
            assertTrue(xiaomi in signalled)
            assertTrue(bluetoothChild !in signalled)
            assertTrue(xiaomiLookalike !in signalled)
        } finally {
            processes.forEach { it.destroyForcibly(); it.waitFor() }
            Files.deleteIfExists(log)
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
}
