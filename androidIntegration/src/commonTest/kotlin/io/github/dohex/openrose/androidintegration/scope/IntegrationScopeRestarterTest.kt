package io.github.dohex.openrose.androidintegration.scope

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationScopeRestarterTest {
    @Test
    fun deniedRootReportsFailureForEveryScope() = runTest {
        val reported = mutableListOf<ScopeRestartUpdate>()
        val results = IntegrationScopeRestarter { _, _ -> RootResult(1, "permission denied") }.restart(onUpdate = { reported += it })
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ScopeRestartStatus.FAILED && it.detail!!.contains("permission denied") })
        assertEquals<List<ScopeRestartUpdate>>(results, reported)
    }

    @Test
    fun restartsOnlyTheChosenScopes() = runTest {
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
    fun theScriptNamesOnlyTheChosenScopesAndCarriesTheWaitBudget() = runTest {
        var script = ""
        IntegrationScopeRestarter(waitSeconds = 5) { sent, _ ->
            script = sent
            RootResult(1, "")
        }.restart(listOf("com.milink.service"))
        assertTrue("targets=\"com.milink.service\"" in script)
        assertTrue("-e com.milink.service" in script)
        assertTrue("com.xiaomi.bluetooth" !in script)
        assertTrue("+ 5))" in script)
    }

    @Test
    fun aRunnerThatCannotLaunchRootFailsEveryScopeAndReleasesTheOperation() = runTest {
        val reported = mutableListOf<ScopeRestartUpdate>()
        val refused = IntegrationScopeRestarter { _, _ -> throw RootLaunchException("su 不可用") }
        val results = refused.restart(onUpdate = { reported += it })
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ScopeRestartStatus.FAILED && it.detail == "无法启动 Root：su 不可用" })
        assertEquals<List<ScopeRestartUpdate>>(results, reported)
        // The failed launch must not leave the operation locked.
        assertEquals(
            ScopeRestartStatus.NOT_RUNNING,
            scripted("OPENROSE_ROOT_OK", "OPENROSE_RESULT:com.milink.service:NOT_RUNNING")
                .restart(listOf("com.milink.service")).single().status,
        )
    }

    @Test
    fun aShellThatNeverGrantsRootReportsItsOutputOrAFallbackReason() = runTest {
        val silent = IntegrationScopeRestarter { _, _ -> RootResult(1, "  \n") }.restart(listOf("com.milink.service"))
        assertEquals("Root 不可用或授权被拒绝：Root 不可用或授权被拒绝", silent.single().detail)

        val noisy = IntegrationScopeRestarter { _, _ -> RootResult(1, "x".repeat(600)) }.restart(listOf("com.milink.service"))
        assertEquals("Root 不可用或授权被拒绝：" + "x".repeat(500), noisy.single().detail)
    }

    @Test
    fun scopesWithoutAResultFailWithTheExitCodeOnlyWhenItIsNonZero() = runTest {
        val reported = mutableListOf<ScopeRestartUpdate>()
        val crashed = IntegrationScopeRestarter { _, onLine ->
            onLine("OPENROSE_ROOT_OK")
            RootResult(3, "OPENROSE_ROOT_OK\n")
        }.restart(onUpdate = { reported += it })
        assertTrue(crashed.all { it.status == ScopeRestartStatus.FAILED && it.detail == "未收到目标执行结果（退出码 3）" })
        assertEquals(listOf<ScopeRestartUpdate>(ScopeRestartPhase.Signalling) + crashed, reported)

        val silent = IntegrationScopeRestarter { _, _ -> RootResult(0, "OPENROSE_ROOT_OK\n") }.restart(listOf("com.milink.service"))
        assertEquals("未收到目标执行结果", silent.single().detail)
    }

    @Test
    fun outputLinesAreParsedStrictlyAndEachScopeIsReportedOnce() = runTest {
        val reported = mutableListOf<ScopeRestartUpdate>()
        val results = scripted(
            "OPENROSE_ROOT_OK",
            "OPENROSE_WAITING:8",
            "OPENROSE_WAITING:soon",
            "OPENROSE_RESULT:com.xiaomi.bluetooth.extra:RESTARTED",
            "OPENROSE_RESULT:com.xiaomi.bluetooth:BOGUS",
            "OPENROSE_RESULT:com.xiaomi.bluetooth",
            "OPENROSE_RESULT:com.android.bluetooth:FAILED:",
            "OPENROSE_RESULT:com.android.bluetooth:RESTARTED",
            "OPENROSE_RESULT:unknown.scope:RESTARTED",
            "OPENROSE_RESULT:com.milink.service:FAILED:进程未退出",
            "com.milink.service:RESTARTED",
        ).restart(onUpdate = { reported += it })

        assertEquals(
            listOf(
                ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.FAILED, "终止一个或多个目标进程失败"),
                ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.FAILED, "未收到目标执行结果"),
                ScopeRestartResult("com.milink.service", ScopeRestartStatus.FAILED, "进程未退出"),
            ),
            results,
        )
        // Updates arrive in output order, the first result of a scope wins, and unmatched scopes are reported last.
        assertEquals(
            listOf(
                ScopeRestartPhase.Signalling,
                ScopeRestartPhase.Waiting(8),
                results[0],
                results[2],
                results[1],
            ),
            reported,
        )
    }

    private fun scripted(vararg lines: String) = IntegrationScopeRestarter { _, onLine ->
        lines.forEach(onLine)
        RootResult(0, lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
