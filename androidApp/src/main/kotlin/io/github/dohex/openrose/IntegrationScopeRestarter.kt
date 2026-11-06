package io.github.dohex.openrose

import io.github.dohex.openrose.ui.ScopeRestartPhase
import io.github.dohex.openrose.ui.ScopeRestartResult
import io.github.dohex.openrose.ui.ScopeRestartStatus
import io.github.dohex.openrose.ui.ScopeRestartUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Runs one Root shell for the chosen scopes, so authorization is requested only after the user confirms.
 * All chosen scopes are signalled together and share one wait of [waitSeconds]; `onUpdate` receives the
 * phase once Root is granted and each second of the wait, and each result the moment its line arrives.
 */
internal class IntegrationScopeRestarter(
    private val waitSeconds: Int = 8,
    private val runRoot: (script: String, onLine: (String) -> Unit) -> RootResult = ::runRootShell,
) {
    suspend fun restart(
        requested: List<String> = TARGETS,
        onUpdate: (ScopeRestartUpdate) -> Unit = {},
    ): List<ScopeRestartResult> = withContext(Dispatchers.IO) {
        val targets = TARGETS.filter { it in requested }
        if (targets.isEmpty()) return@withContext emptyList()
        fun failed(reason: String) = targets.map { ScopeRestartResult(it, ScopeRestartStatus.FAILED, reason) }.onEach(onUpdate)
        if (!restartMutex.tryLock()) return@withContext failed("重启操作正在进行")
        try {
            val reported = HashMap<String, ScopeRestartResult>()
            val response = try {
                runRoot(restartScript(targets, waitSeconds)) { line ->
                    when {
                        line == ROOT_OK -> onUpdate(ScopeRestartPhase.Signalling)

                        line.startsWith(WAITING_PREFIX) ->
                            line.removePrefix(WAITING_PREFIX).toIntOrNull()?.let { onUpdate(ScopeRestartPhase.Waiting(it)) }

                        else -> parseResult(line, targets)?.let { if (reported.putIfAbsent(it.target, it) == null) onUpdate(it) }
                    }
                }
            } catch (error: IOException) {
                return@withContext failed("无法启动 Root：${error.message ?: "su 不可用"}")
            } catch (error: SecurityException) {
                return@withContext failed("无法启动 Root：${error.message ?: "权限被拒绝"}")
            }
            if (!response.output.lineSequence().any { it == ROOT_OK }) {
                val reason = response.output.trim().take(500).ifEmpty { "Root 不可用或授权被拒绝" }
                return@withContext failed("Root 不可用或授权被拒绝：$reason")
            }
            targets.map { target ->
                reported[target] ?: ScopeRestartResult(
                    target,
                    ScopeRestartStatus.FAILED,
                    "未收到目标执行结果${if (response.exitCode != 0) "（退出码 ${response.exitCode}）" else ""}",
                ).also(onUpdate)
            }
        } finally {
            restartMutex.unlock()
        }
    }

    private companion object {
        val restartMutex = Mutex()
    }
}

internal data class RootResult(val exitCode: Int, val output: String)

private fun runRootShell(script: String, onLine: (String) -> Unit): RootResult {
    val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
    val output = StringBuilder()
    process.inputStream.bufferedReader().use { reader ->
        reader.forEachLine { line ->
            output.appendLine(line)
            onLine(line)
        }
    }
    return RootResult(process.waitFor(), output.toString())
}

private const val ROOT_OK = "OPENROSE_ROOT_OK"
private const val WAITING_PREFIX = "OPENROSE_WAITING:"
private const val RESULT_PREFIX = "OPENROSE_RESULT:"

private fun parseResult(line: String, targets: List<String>): ScopeRestartResult? {
    if (!line.startsWith(RESULT_PREFIX)) return null
    val rest = line.removePrefix(RESULT_PREFIX)
    val target = targets.firstOrNull { rest.startsWith("$it:") } ?: return null
    val status = rest.removePrefix("$target:")
    return when {
        status == "RESTARTED" -> ScopeRestartResult(target, ScopeRestartStatus.RESTARTED)

        status == "TERMINATED" -> ScopeRestartResult(target, ScopeRestartStatus.TERMINATED)

        status == "NOT_RUNNING" -> ScopeRestartResult(target, ScopeRestartStatus.NOT_RUNNING)

        status.startsWith("FAILED:") ->
            ScopeRestartResult(target, ScopeRestartStatus.FAILED, status.removePrefix("FAILED:").ifBlank { "终止一个或多个目标进程失败" })

        else -> null
    }
}

private val TARGETS = listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service")

// scan() prints pid:target for every process of a chosen scope. One grep over all cmdlines finds the candidates,
// so only processes that mention a scope are read in full; the first NUL-delimited argv value must then be the
// package name alone for Bluetooth, package or package:child for the other scopes, matching
// BridgeTarget.acceptsProcess. Every scope is snapshotted, then all are signalled with SIGTERM (never force-stop)
// and share one deadline. A scope counts as restarted when a pid that was not there before appears; pids that are
// still alive at the deadline mean the process ignored the signal. The seconds left are printed whenever they drop.
// Targets are only ever members of TARGETS, so they are safe to inline into the script.
private fun restartScript(targets: List<String>, waitSeconds: Int) = """
    if [ "${'$'}(id -u)" != 0 ]; then exit 1; fi
    echo $ROOT_OK
    targets="${targets.joinToString(" ")}"
    scan() {
        for file in ${'$'}(grep -l -F ${targets.joinToString(" ") { "-e $it" }} /proc/[0-9]*/cmdline 2>/dev/null); do
            pid=${'$'}{file#/proc/}
            pid=${'$'}{pid%/cmdline}
            name=${'$'}(tr '\000' '\n' < "${'$'}file" 2>/dev/null | head -n 1)
            for target in ${'$'}targets; do
                case "${'$'}name" in
                    "${'$'}target") ;;
                    "${'$'}target":*) [ "${'$'}target" != com.android.bluetooth ] || continue ;;
                    *) continue ;;
                esac
                echo "${'$'}pid:${'$'}target"
            done
        done
    }
    of() { for entry in ${'$'}2; do [ "${'$'}{entry#*:}" != "${'$'}1" ] || echo "${'$'}{entry%%:*}"; done; }
    before=" ${'$'}(scan | tr '\n' ' ')"
    pending=
    for target in ${'$'}targets; do
        old=${'$'}(of "${'$'}target" "${'$'}before")
        if [ -z "${'$'}old" ]; then echo "$RESULT_PREFIX${'$'}target:NOT_RUNNING"; continue; fi
        failed=0
        reason=
        for pid in ${'$'}old; do
            error=${'$'}(kill -TERM "${'$'}pid" 2>&1) || { failed=1; reason=${'$'}error; }
        done
        if [ "${'$'}failed" = 1 ]; then echo "$RESULT_PREFIX${'$'}target:FAILED:${'$'}reason"; else pending="${'$'}pending ${'$'}target"; fi
    done
    if [ -n "${'$'}pending" ]; then
        end=${'$'}((${'$'}(date +%s) + $waitSeconds))
        shown=
        while :; do
            seconds=${'$'}((end - ${'$'}(date +%s)))
            if [ "${'$'}seconds" -gt 0 ] && [ "${'$'}seconds" != "${'$'}shown" ]; then echo "$WAITING_PREFIX${'$'}seconds"; shown=${'$'}seconds; fi
            now=" ${'$'}(scan | tr '\n' ' ')"
            rest=
            for target in ${'$'}pending; do
                restarted=0
                for pid in ${'$'}(of "${'$'}target" "${'$'}now"); do
                    case "${'$'}before" in *" ${'$'}pid:${'$'}target "*) ;; *) restarted=1 ;; esac
                done
                if [ "${'$'}restarted" = 1 ]; then echo "$RESULT_PREFIX${'$'}target:RESTARTED"; else rest="${'$'}rest ${'$'}target"; fi
            done
            pending=${'$'}rest
            if [ -z "${'$'}pending" ] || [ "${'$'}seconds" -le 0 ]; then break; fi
            sleep 0.2
        done
        for target in ${'$'}pending; do
            if [ -n "${'$'}(of "${'$'}target" "${'$'}now")" ]; then echo "$RESULT_PREFIX${'$'}target:FAILED:进程未退出"
            else echo "$RESULT_PREFIX${'$'}target:TERMINATED"; fi
        done
    fi
""".trimIndent()
