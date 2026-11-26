package io.github.dohex.openrose.module.nativecore

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import dalvik.system.BaseDexClassLoader
import io.github.dohex.openrose.ffi.RustControlResult
import io.github.dohex.openrose.ffi.RustControlRuntime
import io.github.dohex.openrose.ffi.kotlincore.KotlinCoreTransport
import io.github.dohex.openrose.ffi.kotlincore.toRust
import io.github.dohex.openrose.platform.androidCoreTransport
import io.github.dohex.openrose.platform.readPlatformState
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.openrose_control.ConnectionBlockReason
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlError
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HeadsetCandidate
import uniffi.openrose_headset.FieldReading
import uniffi.openrose_headset.HeadsetField
import java.io.File

internal fun androidNativeCoreTrial(module: XposedModule, context: Context): NativeCoreTrial {
    val log = TrialLog { priority, message, failure ->
        val text = if (failure == null) message else "$message\n${Log.getStackTraceString(failure)}"
        Log.println(priority, NativeCoreTrial.TAG, text)
    }
    return NativeCoreTrial(
        libraries = AndroidNativeLibraries(module, log),
        control = { startTrialSession(context.applicationContext ?: context, log) },
        log = log,
    )
}

private class AndroidNativeLibraries(private val module: XposedModule, private val log: TrialLog) : NativeCoreLibraries {
    private var prepared = false

    override fun load(name: String) {
        prepare()
        log.write(Log.INFO, "native_library_resolve library=$name path=${resolvedPath(name)}", null)
        System.loadLibrary(name)
    }

    private fun prepare() {
        if (prepared) return
        prepared = true
        System.setProperty("jna.nounpack", "true")
        System.setProperty("jna.debug_load", "true")
        val info = module.moduleApplicationInfo
        val extracted = info.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0
        log.write(
            Log.INFO,
            "environment module_source=${info.sourceDir} source_readable=${File(info.sourceDir).canRead()} " +
                "module_native_dir=${info.nativeLibraryDir} extract_native_libs=$extracted " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")} sdk=${Build.VERSION.SDK_INT} " +
                "uid=${Process.myUid()} pid=${Process.myPid()} selinux=${selinuxContext()} " +
                "classloader=${javaClass.classLoader}",
            null,
        )
    }

    private fun resolvedPath(name: String): String? = (javaClass.classLoader as? BaseDexClassLoader)?.findLibrary(name)

    private fun selinuxContext(): String = runCatching {
        File("/proc/self/attr/current").readText().trim { it.isWhitespace() || it == '\u0000' }
    }.getOrElse { "unreadable:${it.javaClass.simpleName}" }
}

private fun startTrialSession(context: Context, log: TrialLog): AutoCloseable {
    val transport = androidCoreTransport(context)
    var started = false
    try {
        val runtime = RustControlRuntime(
            transport = transport,
            initialPlatformState = context.readPlatformState(permissionRequested = true).toRust(),
        )
        return TrialSession(context, runtime, transport, log).also {
            it.start()
            started = true
        }
    } finally {
        if (!started) transport.close()
    }
}

private class TrialSession(
    private val context: Context,
    private val runtime: RustControlRuntime,
    private val transport: KotlinCoreTransport,
    private val log: TrialLog,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastConnection: String? = null
    private var lastError: ControlError? = null
    private var lastBlock: ConnectionBlockReason? = null
    private var lastReadings = emptyMap<HeadsetField, FieldReading>()

    fun start() {
        scope.launch { runtime.state.collect(::report) }
        scope.launch { connect() }
    }

    override fun close() {
        scope.cancel()
        runtime.close()
        transport.close()
    }

    private suspend fun connect() {
        val target = awaitBondedHeadset() ?: return
        log.write(Log.INFO, "connect_start headset=${target.id} name=${target.name} profile=${target.profile}", null)
        when (val result = runtime.connect(target.id)) {
            RustControlResult.Success -> log.write(Log.INFO, "connect_result headset=${target.id} result=success", null)

            is RustControlResult.Failure -> log.write(
                Log.ERROR,
                "connect_result headset=${target.id} result=failure code=${result.error.code} message=${result.error.message}",
                null,
            )
        }
    }

    private suspend fun awaitBondedHeadset(): HeadsetCandidate? {
        repeat(BONDED_ATTEMPTS) { attempt ->
            runtime.updatePlatformState(context.readPlatformState(permissionRequested = true).toRust())
            runtime.refreshBondedHeadsets()
            val snapshot = runtime.state.value
            if (snapshot.bondedHeadsets.isNotEmpty()) {
                log.write(Log.INFO, "bonded_headsets ids=${snapshot.bondedHeadsets.joinToString(",") { it.id }}", null)
                return snapshot.bondedHeadsets.first()
            }
            log.write(
                Log.WARN,
                "bonded_headset_missing attempt=${attempt + 1}/$BONDED_ATTEMPTS transport=${snapshot.platform.transport}",
                null,
            )
            delay(BONDED_RETRY_MILLIS)
        }
        log.write(Log.ERROR, "trial_gave_up reason=no_bonded_headset", null)
        return null
    }

    private fun report(snapshot: ControlSnapshot) {
        val connection = describe(snapshot.connection)
        if (connection != lastConnection) {
            lastConnection = connection
            log.write(Log.INFO, "state revision=${snapshot.revision} connection=$connection", null)
        }
        if (snapshot.lastError != lastError) {
            lastError = snapshot.lastError
            snapshot.lastError?.let { log.write(Log.WARN, "control_error code=${it.code} message=${it.message}", null) }
        }
        if (snapshot.connectionBlockReason != lastBlock) {
            lastBlock = snapshot.connectionBlockReason
            snapshot.connectionBlockReason?.let { log.write(Log.WARN, "connection_blocked reason=$it", null) }
        }
        val fields = snapshot.headset?.readings?.fields.orEmpty()
        fields.entries.sortedBy { it.key.name }
            .filter { lastReadings[it.key] != it.value }
            .forEach { (field, reading) -> log.write(Log.INFO, "reading field=$field value=$reading", null) }
        lastReadings = fields
    }

    private fun describe(state: ConnectionState): String = when (state) {
        ConnectionState.Disconnected -> "disconnected"
        is ConnectionState.Connecting -> "connecting headset=${state.headsetId} session=${state.sessionId} phase=${state.phase}"
        is ConnectionState.Connected -> "connected headset=${state.headsetId} session=${state.sessionId} profile=${state.profile}"
    }

    private companion object {
        const val BONDED_ATTEMPTS = 12
        const val BONDED_RETRY_MILLIS = 5_000L
    }
}
