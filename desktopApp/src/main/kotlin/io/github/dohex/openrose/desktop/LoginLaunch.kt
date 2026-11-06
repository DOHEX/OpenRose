package io.github.dohex.openrose.desktop

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.core.runtime.ExecutableRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** “登录时启动” as the system records it; the app never keeps its own copy of this choice. */
internal sealed interface LoginLaunchState {
    data object Reading : LoginLaunchState
    data class Ready(val enabled: Boolean) : LoginLaunchState
    /** The user turned it off in system settings; only they can turn it back on there. */
    data object DisabledByUser : LoginLaunchState
    data class Unsupported(val reason: String) : LoginLaunchState
}

/** Reads and changes start-at-login off the UI thread; the backend may run subprocesses. */
internal class LoginLaunch(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<LoginLaunchState>(LoginLaunchState.Reading)
    private val mutex = Mutex()
    val state: StateFlow<LoginLaunchState> = mutableState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { mutex.withLock { mutableState.value = read() } }
    }

    fun set(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val result = runCatching { if (enabled) AutoLaunch.enable() else AutoLaunch.disable() }
                    .getOrDefault(AutoLaunchResult.ERROR)
                mutableState.value = when (result) {
                    AutoLaunchResult.UNSUPPORTED -> LoginLaunchState.Unsupported("当前系统不支持登录时启动")
                    else -> read()
                }
            }
        }
    }

    fun openSystemSettings() {
        scope.launch(Dispatchers.IO) { runCatching { AutoLaunch.openSystemSettings() } }
    }

    private fun read(): LoginLaunchState {
        // An unpackaged run would register the development JVM command as the login item.
        if (ExecutableRuntime.isDev()) return LoginLaunchState.Unsupported("仅安装版可设置登录时启动")
        return when (runCatching { AutoLaunch.state() }.getOrDefault(AutoLaunchState.UNSUPPORTED)) {
            AutoLaunchState.ENABLED -> LoginLaunchState.Ready(enabled = true)
            AutoLaunchState.DISABLED -> LoginLaunchState.Ready(enabled = false)
            AutoLaunchState.DISABLED_BY_USER -> LoginLaunchState.DisabledByUser
            AutoLaunchState.ENABLED_BY_POLICY, AutoLaunchState.DISABLED_BY_POLICY ->
                LoginLaunchState.Unsupported("登录时启动由系统策略控制")
            AutoLaunchState.UNSUPPORTED -> LoginLaunchState.Unsupported("当前系统不支持登录时启动")
        }
    }
}
