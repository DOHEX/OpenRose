package io.github.dohex.openrose

import io.github.dohex.openrose.ui.ScopeRestartPhase
import io.github.dohex.openrose.ui.ScopeRestartResult
import io.github.dohex.openrose.ui.ScopeRestartStatus
import io.github.dohex.openrose.ui.ScopeRestartUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class IntegrationScopeRestarterTest {
    @Test
    fun deniedRootReportsFailureForEveryScope() = runBlocking {
        val reported = mutableListOf<ScopeRestartUpdate>()
        val results = IntegrationScopeRestarter { _, _ -> RootResult(1, "permission denied") }.restart(onUpdate = { reported += it })
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ScopeRestartStatus.FAILED && it.detail!!.contains("permission denied") })
        assertEquals<List<ScopeRestartUpdate>>(results, reported)
    }

    @Test
    fun restartsOnlyTheChosenScopes() = runBlocking {
        val results = IntegrationScopeRestarter { _, onLine ->
            val output = "OPENROSE_ROOT_OK\nOPENROSE_RESULT:com.milink.service:NOT_RUNNING\n"
            output.lineSequence().filter { it.isNotEmpty() }.forEach(onLine)
            RootResult(0, output)
        }.restart(listOf("com.milink.service", "not.a.scope"))
        assertEquals(listOf("com.milink.service"), results.map { it.target })
        assertEquals(ScopeRestartStatus.NOT_RUNNING, results.single().status)
        assertTrue(IntegrationScopeRestarter { _, _ -> error("no Root without a chosen scope") }.restart(emptyList()).isEmpty())
    }

    @Test
    fun resultsAreReportedWhileTheScriptIsStillRunning() = runBlocking {
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reported = mutableListOf<ScopeRestartUpdate>()
        val restarter = IntegrationScopeRestarter { _, onLine ->
            onLine("OPENROSE_ROOT_OK")
            onLine("OPENROSE_RESULT:com.android.bluetooth:RESTARTED")
            first.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            onLine("OPENROSE_RESULT:com.xiaomi.bluetooth:TERMINATED")
            onLine("OPENROSE_RESULT:com.milink.service:NOT_RUNNING")
            RootResult(0, "OPENROSE_ROOT_OK\n")
        }
        val running = async(Dispatchers.Default) { restarter.restart(onUpdate = { synchronized(reported) { reported += it } }) }
        try {
            assertTrue(first.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (synchronized(reported) { reported.none { it is ScopeRestartResult } } && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(
                listOf(ScopeRestartPhase.Signalling, ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.RESTARTED)),
                synchronized(reported) { reported.toList() },
            )
        } finally {
            release.countDown()
        }
        val results = running.await()
        assertEquals(listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service"), results.map { it.target })
        assertEquals(listOf(ScopeRestartStatus.RESTARTED, ScopeRestartStatus.TERMINATED, ScopeRestartStatus.NOT_RUNNING), results.map { it.status })
        assertEquals(listOf<ScopeRestartUpdate>(ScopeRestartPhase.Signalling) + results, reported)
    }

    @Test
    fun concurrentRequestsDoNotStartAnotherRootOperation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restarter = IntegrationScopeRestarter { _, _ ->
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
    fun matchesOnlyAllowedProcessesAndReportsEachOutcome() = withFixtures { dir, start ->
        val bluetooth = start("com.android.bluetooth")
        val bluetoothChild = start("com.android.bluetooth:unrelated")
        val xiaomi = start("com.xiaomi.bluetooth:service")
        val xiaomiLookalike = start("com.xiaomi.bluetooth.extra")
        val signals = dir.resolve("signals")
        val results = stubbedRestarter(signals, "FAIL_PID" to bluetooth, "RESPAWN_PID" to xiaomi, "RESPAWN_NAME" to "com.xiaomi.bluetooth:service").restart()
        assertEquals(listOf(ScopeRestartStatus.FAILED, ScopeRestartStatus.RESTARTED, ScopeRestartStatus.NOT_RUNNING), results.map { it.status })
        assertEquals("permission denied", results.first().detail)
        val signalled = Files.readAllLines(signals).toSet()
        assertTrue(bluetooth in signalled)
        assertTrue(xiaomi in signalled)
        assertTrue(bluetoothChild !in signalled)
        assertTrue(xiaomiLookalike !in signalled)
    }

    @Test
    fun aProcessThatDoesNotComeBackIsTerminatedNotFailed() = withFixtures { dir, start ->
        start("com.milink.service")
        val results = stubbedRestarter(dir.resolve("signals")).restart(listOf("com.milink.service"))
        assertEquals(listOf(ScopeRestartStatus.TERMINATED), results.map { it.status })
    }

    @Test
    fun scopesAreRestartedTogetherAndReportedAsTheyFinish() = withFixtures { dir, start ->
        start("com.android.bluetooth")
        val xiaomi = start("com.xiaomi.bluetooth")
        val reported = mutableListOf<ScopeRestartUpdate>()
        val results = stubbedRestarter(dir.resolve("signals"), "RESPAWN_PID" to xiaomi, "RESPAWN_NAME" to "com.xiaomi.bluetooth")
            .restart(onUpdate = { reported += it })
        // Bluetooth uses up the whole shared wait, yet Xiaomi Bluetooth, signalled at the same time, is reported first.
        assertEquals(ScopeRestartPhase.Signalling, reported.first())
        assertEquals(
            listOf(
                ScopeRestartResult("com.milink.service", ScopeRestartStatus.NOT_RUNNING),
                ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.RESTARTED),
                ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.TERMINATED),
            ),
            reported.filterIsInstance<ScopeRestartResult>(),
        )
        // The shared wait counts down within its length, each value once, and starts before the early result.
        val countdown = reported.filterIsInstance<ScopeRestartPhase.Waiting>().map { it.secondsLeft }
        assertTrue(countdown.isNotEmpty())
        assertEquals(countdown.sortedDescending().distinct(), countdown)
        assertTrue(countdown.all { it in 1..2 })
        assertTrue(reported.indexOfFirst { it is ScopeRestartPhase.Waiting } < reported.indexOfFirst { it is ScopeRestartResult && it.target == "com.xiaomi.bluetooth" })
        assertEquals(listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service"), results.map { it.target })
    }

    @Test
    fun aProcessThatIgnoresTheSignalFails() = withFixtures { dir, start ->
        val milink = start("com.milink.service")
        val results = stubbedRestarter(dir.resolve("signals"), "IGNORE_PID" to milink).restart(listOf("com.milink.service"))
        assertEquals(ScopeRestartStatus.FAILED, results.single().status)
        assertEquals("进程未退出", results.single().detail)
    }

    private fun stubbedRestarter(signals: Path, vararg env: Pair<String, String>) = IntegrationScopeRestarter(waitSeconds = 2) { script, onLine ->
        // Same script, with Root and signalling replaced: kill really signals unless the scenario says otherwise,
        // and a respawn starts a fresh process under the old name.
        val stub = """
            id() { echo 0; }
            kill() {
                printf '%s\n' "${'$'}2" >> "${'$'}SIGNAL_LOG"
                if [ "${'$'}2" = "${'$'}FAIL_PID" ]; then echo 'permission denied' >&2; return 1; fi
                if [ "${'$'}2" = "${'$'}IGNORE_PID" ]; then return 0; fi
                command kill "${'$'}@" || return 1
                if [ "${'$'}2" = "${'$'}RESPAWN_PID" ]; then
                    bash -c 'exec -a "${'$'}0" sleep 5' "${'$'}RESPAWN_NAME" >/dev/null 2>&1 </dev/null &
                fi
            }
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", "$stub\n$script").redirectErrorStream(true).apply {
            environment()["SIGNAL_LOG"] = signals.toString()
            env.forEach { (key, value) -> environment()[key] = value }
        }.start()
        val output = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { output.appendLine(it); onLine(it) }
        RootResult(process.waitFor(), output.toString())
    }

    private fun withFixtures(body: suspend (dir: Path, start: (String) -> String) -> Unit) = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        val dir = Files.createTempDirectory("openrose-scopes")
        val processes = mutableListOf<Process>()
        fun start(name: String): String {
            val pidFile = dir.resolve("pid${processes.size}")
            val process = ProcessBuilder("bash", "-c", "echo \$\$ > \"\$2\"; exec -a \"\$1\" sleep 60", "fixture", name, pidFile.toString()).start()
            processes += process
            repeat(100) {
                if (Files.exists(pidFile)) {
                    val pid = Files.readString(pidFile).trim()
                    val path = Path.of("/proc/$pid/cmdline")
                    if (Files.exists(path) && Files.readString(path).startsWith(name + '\u0000')) return pid
                }
                Thread.sleep(10)
            }
            error("fixture process did not start: $name")
        }
        try {
            body(dir, ::start)
        } finally {
            processes.forEach { it.destroyForcibly(); it.waitFor() }
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
}
