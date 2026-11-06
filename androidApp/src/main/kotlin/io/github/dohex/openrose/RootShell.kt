package io.github.dohex.openrose

import io.github.dohex.openrose.androidintegration.scope.RootLaunchException
import io.github.dohex.openrose.androidintegration.scope.RootResult
import java.io.IOException

/** Runs [script] in a Root shell, forwarding each output line; launch failures surface as [RootLaunchException]. */
internal fun runRootShell(script: String, onLine: (String) -> Unit): RootResult = try {
    val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
    val output = StringBuilder()
    process.inputStream.bufferedReader().use { reader ->
        reader.forEachLine { line ->
            output.appendLine(line)
            onLine(line)
        }
    }
    RootResult(process.waitFor(), output.toString())
} catch (error: IOException) {
    throw RootLaunchException(error.message ?: "su 不可用", error)
} catch (error: SecurityException) {
    throw RootLaunchException(error.message ?: "权限被拒绝", error)
}
