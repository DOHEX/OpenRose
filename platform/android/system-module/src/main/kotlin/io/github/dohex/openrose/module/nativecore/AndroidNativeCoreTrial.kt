package io.github.dohex.openrose.module.nativecore

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import dalvik.system.BaseDexClassLoader
import io.github.dohex.openrose.ffi.RustControlResult
import io.github.dohex.openrose.ffi.RustControlRuntime
import io.github.dohex.openrose.ffi.kotlincore.toRust
import io.github.dohex.openrose.platform.androidCoreTransport
import io.github.dohex.openrose.platform.readPlatformState
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.flow.StateFlow
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_headset.HeadsetId
import java.io.File

internal fun androidNativeCoreTrial(module: XposedModule, context: Context): NativeCoreTrial {
    val log = TrialLog { priority, message, failure ->
        val text = if (failure == null) message else "$message\n${Log.getStackTraceString(failure)}"
        Log.println(priority, NativeCoreTrial.TAG, text)
    }
    return NativeCoreTrial(
        libraries = AndroidNativeLibraries(module, log),
        control = { openAndroidTrialControl(context.applicationContext ?: context) },
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

private fun openAndroidTrialControl(context: Context): TrialControl {
    val transport = androidCoreTransport(context)
    var created = false
    try {
        val runtime = RustControlRuntime(
            transport = transport,
            initialPlatformState = context.readPlatformState(permissionRequested = true).toRust(),
        )
        created = true
        return object : TrialControl {
            override val state: StateFlow<ControlSnapshot> get() = runtime.state

            override fun updatePlatformState() {
                runtime.updatePlatformState(context.readPlatformState(permissionRequested = true).toRust())
            }

            override suspend fun refreshBondedHeadsets() {
                runtime.refreshBondedHeadsets()
            }

            override suspend fun connect(headsetId: HeadsetId): RustControlResult = runtime.connect(headsetId)

            override fun close() {
                runtime.close()
                transport.close()
            }
        }
    } finally {
        if (!created) transport.close()
    }
}
