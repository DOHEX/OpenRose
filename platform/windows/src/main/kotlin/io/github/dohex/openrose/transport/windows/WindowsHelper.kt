package io.github.dohex.openrose.transport.windows

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private val executable by lazy {
    val file = Files.createTempFile("openrose-winrt-", ".exe").toFile().apply { deleteOnExit() }
    requireNotNull(WindowsControlTransport::class.java.getResourceAsStream("/openrose-winrt.exe")) { "缺少 OpenRose Windows 蓝牙组件" }
        .use { source -> file.outputStream().use(source::copyTo) }
    file
}

internal fun startWindowsHelper(arguments: List<String>): Process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

@OptIn(FlowPreview::class)
internal fun windowsSnapshots(): Flow<String> = helperLines { startWindowsHelper(listOf("facts")) }
    .timeout(15.seconds)
    .retryWhen { cause, _ ->
        emit("unavailable\tWindows 蓝牙状态不可用：${cause.message}")
        delay(2_000)
        true
    }

internal fun helperLines(start: () -> Process): Flow<String> = channelFlow {
    val process = start()
    val reader = launch {
        process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
            while (true) send(input.readLine() ?: break)
        }
        throw IOException("Windows 蓝牙组件已退出")
    }
    awaitClose {
        process.destroyForcibly()
        reader.cancel()
    }
}.flowOn(Dispatchers.IO)
