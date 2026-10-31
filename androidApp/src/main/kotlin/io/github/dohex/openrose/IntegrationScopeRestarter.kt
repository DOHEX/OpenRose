package io.github.dohex.openrose

import io.github.dohex.openrose.ui.ScopeRestartResult
import io.github.dohex.openrose.ui.ScopeRestartStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException

/** Runs one Root shell, so authorization is requested only after the user confirms. */
internal class IntegrationScopeRestarter(
    private val runRoot: (String) -> RootResult = ::runRootShell,
) {
    suspend fun restart(): List<ScopeRestartResult> = withContext(Dispatchers.IO) {
        if (!restartMutex.tryLock()) return@withContext failed("重启操作正在进行")
        try {
            val response = try {
                runRoot(RESTART_SCRIPT)
            } catch (error: IOException) {
                return@withContext failed("无法启动 Root：${error.message ?: "su 不可用"}")
            } catch (error: SecurityException) {
                return@withContext failed("无法启动 Root：${error.message ?: "权限被拒绝"}")
            }
            val lines = response.output.lineSequence().toList()
            if ("OPENROSE_ROOT_OK" !in lines) {
                val reason = response.output.trim().take(500).ifEmpty { "Root 不可用或授权被拒绝" }
                return@withContext failed("Root 不可用或授权被拒绝：$reason")
            }
            TARGETS.map { target ->
                val prefix = "OPENROSE_RESULT:$target:"
                val status = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
                when (status) {
                    "EXECUTED" -> ScopeRestartResult(target, ScopeRestartStatus.EXECUTED)
                    "NOT_RUNNING" -> ScopeRestartResult(target, ScopeRestartStatus.NOT_RUNNING)
                    else -> if (status?.startsWith("FAILED:") == true) {
                        ScopeRestartResult(target, ScopeRestartStatus.FAILED, status.removePrefix("FAILED:").ifBlank { "终止一个或多个目标进程失败" })
                    } else {
                        ScopeRestartResult(target, ScopeRestartStatus.FAILED, "未收到目标执行结果${if (response.exitCode != 0) "（退出码 ${response.exitCode}）" else ""}")
                    }
                }
            }
        } finally {
            restartMutex.unlock()
        }
    }

    private companion object {
        val restartMutex = Mutex()
    }

    private fun failed(reason: String) = TARGETS.map { ScopeRestartResult(it, ScopeRestartStatus.FAILED, reason) }
}

internal data class RootResult(val exitCode: Int, val output: String)

private fun runRootShell(script: String): RootResult {
    val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return RootResult(process.waitFor(), output)
}

private val TARGETS = listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service")

// Read the first NUL-delimited argv value: package name alone for Bluetooth, package or
// package:child for the other scopes, matching BridgeTarget.acceptsProcess. Never force-stop.
private val RESTART_SCRIPT = """
    if [ "${'$'}(id -u)" != 0 ]; then exit 1; fi
    echo OPENROSE_ROOT_OK
    for target in com.android.bluetooth com.xiaomi.bluetooth com.milink.service; do
        found=0
        failed=0
        reason=
        for entry in /proc/[0-9]*; do
            [ -r "${'$'}entry/cmdline" ] || continue
            name=${'$'}(tr '\000' '\n' < "${'$'}entry/cmdline" 2>/dev/null | head -n 1)
            case "${'$'}name" in
                "${'$'}target") ;;
                "${'$'}target":*) [ "${'$'}target" != com.android.bluetooth ] || continue ;;
                *) continue ;;
            esac
            found=1
            pid=${'$'}{entry##*/}
            error=${'$'}(kill -TERM "${'$'}pid" 2>&1) || { failed=1; reason=${'$'}error; }
        done
        if [ "${'$'}failed" = 1 ]; then result="FAILED:${'$'}reason"
        elif [ "${'$'}found" = 1 ]; then result=EXECUTED
        else result=NOT_RUNNING
        fi
        echo "OPENROSE_RESULT:${'$'}target:${'$'}result"
    done
""".trimIndent()
