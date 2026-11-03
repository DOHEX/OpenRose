package io.github.dohex.openrose.module

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.dohex.openrose.bridge.BridgePresentationWire
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.integration.AuthenticatedNativeHeadsetCallbacks
import io.github.dohex.openrose.integration.MiLinkNativeHeadsetAdapter
import io.github.dohex.openrose.integration.NativeHeadsetBinderAdapter
import io.github.dohex.openrose.integration.NativeHeadsetSnapshot
import io.github.dohex.openrose.presentation.OfficialConnectionPresenter
import io.github.dohex.openrose.presentation.PresentationCallbacks
import io.github.dohex.openrose.core.SystemHeadsetMapping
import io.github.dohex.openrose.presentation.SystemPresentationRenderer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Scoped system module. In `com.android.bluetooth` it hosts the single module-controlled session
 * ([ModuleHostRuntime]); every scoped target, including that one, consumes it through the
 * authorised bridge client. No Bluetooth control connection is opened by the consumers.
 */
class OpenRoseBluetoothModule : XposedModule() {
    private val lifecycle = Any()
    private val entryHooks = mutableListOf<XposedInterface.HookHandle>()
    private val pendingReports = linkedMapOf<String, Pair<String, String>>()
    private val consumedOfficialPrompts = mutableSetOf<String>()
    private val actions = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(task, "OpenRose-system-action").apply { isDaemon = true }
    }
    @Volatile private var retired = false
    @Volatile private var processName: String? = null
    @Volatile private var target: BridgeTarget? = null
    @Volatile private var targetContext: Context? = null
    @Volatile private var client: AuthenticatedBridgeClient? = null
    @Volatile private var nativeSnapshot: NativeHeadsetSnapshot? = null
    private var binderAdapter: NativeHeadsetBinderAdapter? = null
    private var fusionAdapter: MiLinkNativeHeadsetAdapter? = null
    private var renderer: SystemPresentationRenderer? = null
    private var official: OfficialConnectionPresenter? = null
    private var lastOfficialIdentity: String? = null
    private var entryResolution = BridgeWire.RESOLUTION_MATCHED
    @Volatile private var hostRuntime: ModuleHostRuntime? = null
    private var hostStarted = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(Log.INFO, TAG, "module_loaded process=${param.processName} api=$apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val actualProcess = processName ?: return
        val selected = BridgeTarget.resolve(param.packageName, actualProcess) ?: return
        if (retired) return
        synchronized(lifecycle) {
            if (target != null) return
            target = selected
        }
        if (selected == BridgeTarget.BLUETOOTH) {
            // Discovery must exist before the entry hook can start the host owner.
            hostRuntime = ModuleHostRuntime(this, param.classLoader).also { it.installDiscovery() }
        }
        installNativeAdapters(selected, param.classLoader)
        val entry = if (selected == BridgeTarget.BLUETOOTH) {
            val type = runCatching { param.classLoader.loadClass(BridgeWire.TARGET_COMPONENT_CLASS) }.getOrNull()
            entryResolution = if (type == null) BridgeWire.RESOLUTION_CLASS_MISSING else BridgeWire.RESOLUTION_METHOD_MISSING
            runCatching { type?.getDeclaredMethod("onCreate") }.getOrNull()
                ?.takeIf { it.returnType == Void.TYPE && it.parameterTypes.isEmpty() }
        } else {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }
        if (entry == null) {
            log(Log.WARN, TAG, "entry_unavailable target=$selected process=$actualProcess")
            installEntry(Application::class.java.getDeclaredMethod("attach", Context::class.java), selected, contextArgument = true)
            return
        }
        entryResolution = BridgeWire.RESOLUTION_MATCHED
        installEntry(entry, selected)
    }

    private fun installEntry(method: Method, selected: BridgeTarget, contextArgument: Boolean = selected != BridgeTarget.BLUETOOTH) {
        try {
            val handle = hook(method).setId("openrose.entry.${selected.name}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    if (!retired) {
                        val context = if (contextArgument) chain.getArg(0) as? Context else chain.thisObject as? Context
                        if (context?.packageName == selected.packageName) attach(context, selected)
                    }
                    result
                }
            synchronized(lifecycle) { entryHooks += handle }
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "entry_hook_failed target=$selected", failure)
        }
    }

    private fun installNativeAdapters(selected: BridgeTarget, loader: ClassLoader) {
        val callbacks = object : AuthenticatedNativeHeadsetCallbacks {
            override fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String): Boolean {
                if (retired || !snapshot.isFresh() || !snapshot.supportsNoise(mode) || nativeSnapshot?.reference != snapshot.reference) return false
                val connectedClient = client ?: return false
                val frame = latestFrame ?: return false
                return runCatching {
                    actions.execute {
                        if (retired || nativeSnapshot?.reference != snapshot.reference) return@execute
                        val result = connectedClient.setNoise(frame, mode)
                        if (!retired && nativeSnapshot?.reference == snapshot.reference) {
                            // Only the earphones' confirmation is a success; every refusal keeps its own status.
                            val outcome = SystemHeadsetMapping.commandReport(
                                mode, result.getString(BridgeWire.STATUS), result.getString(BridgeWire.MESSAGE),
                            )
                            componentReport(featureFor(selected), outcome.status, outcome.detail)
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            override fun report(feature: String, status: String, detail: String) = componentReport(featureFor(selected), status, detail)
            override fun fusionEntryDevice(address: String): String? {
                val connectedClient = client?.takeIf { !retired } ?: return null
                val ask = java.util.concurrent.FutureTask { connectedClient.fusionEntryDevice(address) }
                return try {
                    actions.execute(ask)
                    ask.get(FUSION_ENTRY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (failure: Throwable) {
                    ask.cancel(true)
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    null
                }
            }
        }
        when (selected) {
            BridgeTarget.BLUETOOTH, BridgeTarget.XIAOMI_BLUETOOTH -> {
                binderAdapter = NativeHeadsetBinderAdapter(this, loader, callbacks).also { it.install() }
            }
            BridgeTarget.MILINK -> fusionAdapter = MiLinkNativeHeadsetAdapter(
                this, loader, callbacks, contextSupplier = { targetContext },
            ).also { it.install() }
        }
    }

    @Volatile private var latestFrame: Bundle? = null

    private fun attach(context: Context, selected: BridgeTarget) {
        synchronized(lifecycle) {
            if (retired || client != null || target != selected) return
            targetContext = context.applicationContext ?: context
            val actualProcess = processName ?: return
            if (selected == BridgeTarget.BLUETOOTH) startHost(context)
            val created = AuthenticatedBridgeClient(context, selected, actualProcess,
                onSnapshot = ::publish,
                onClear = ::clearConsumers,
                onAuthenticated = { authenticated ->
                    val reports = synchronized(pendingReports) { pendingReports.toMap() }
                    reports.forEach { (feature, value) -> authenticated.reportComponent(feature, value.first, value.second) }
                },
                onClosing = ::closeConsumers,
                resolution = entryResolution,
            )
            client = created
            created.start()
        }
    }

    /** Starts the host owner once per runtime, before the in-process consumer client exists. */
    private fun startHost(context: Context) {
        if (hostStarted) return
        val runtime = hostRuntime ?: return
        hostStarted = true
        runtime.start(context)
    }

    private fun presentationCallbacks(connected: AuthenticatedBridgeClient) = object : PresentationCallbacks {
        override fun claimPrompt(snapshot: Bundle, promptId: String): Boolean = connected.claimPrompt(snapshot, promptId)
        override fun setNoise(snapshot: Bundle, mode: String): Bundle = connected.setNoise(snapshot, mode)
        override fun report(snapshot: Bundle, feature: String, status: String, detail: String) =
            connected.reportPresentation(snapshot, feature, status, detail)
    }

    private fun publish(snapshot: Bundle) {
        if (retired) return
        val connected = client ?: return
        val context = targetContext ?: return
        val native = NativeHeadsetSnapshot.fromBundle(snapshot)
        nativeSnapshot = native
        latestFrame = Bundle(snapshot)
        binderAdapter?.publish(native)
        fusionAdapter?.publish(native)
        when (target) {
            BridgeTarget.BLUETOOTH -> {
                val output = renderer ?: SystemPresentationRenderer(context, connected.handler, presentationCallbacks(connected))
                    .also { renderer = it }
                output.render(snapshot)
            }
            BridgeTarget.XIAOMI_BLUETOOTH -> {
                val output = official ?: OfficialConnectionPresenter(context, connected.handler, presentationCallbacks(connected))
                    .also { official = it }
                val spec = snapshot.getBundle(BridgePresentationWire.PRESENTATION)
                val prompt = spec?.getBundle(BridgePresentationWire.PROMPT)
                val id = prompt?.getString(BridgePresentationWire.PROMPT_ID)
                val identity = "${snapshot.getString(BridgeWire.HOST_EPOCH)}:${snapshot.getString(BridgeWire.DEVICE_ID)}:${snapshot.getLong(BridgeWire.SESSION_ID)}"
                if (lastOfficialIdentity != identity) { output.clear(); consumedOfficialPrompts.clear(); lastOfficialIdentity = identity }
                if (spec?.getString(BridgePresentationWire.MODE) != BridgePresentationWire.OFFICIAL ||
                    !spec.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS) || id == null) {
                    output.clear()
                    return
                }
                if (id !in consumedOfficialPrompts) {
                    consumedOfficialPrompts += id
                    if (!prompt.getBoolean(BridgePresentationWire.CLAIMED)) output.show(snapshot, id)
                }
            }
            else -> Unit
        }
    }

    private fun clearConsumers() {
        nativeSnapshot = null
        latestFrame = null
        binderAdapter?.publish(null)
        fusionAdapter?.publish(null)
        renderer?.clear()
        official?.clear()
    }

    private fun componentReport(feature: String, status: String, detail: String) {
        synchronized(pendingReports) { pendingReports[feature] = status to detail.take(512) }
        val connected = client ?: return
        connected.handler.post { if (!retired) connected.reportComponent(feature, status, detail.take(512)) }
    }

    private fun closeConsumers(): Boolean {
        clearConsumers()
        binderAdapter?.close(); binderAdapter = null
        fusionAdapter?.close(); fusionAdapter = null
        val output = renderer
        output?.close(); renderer = null
        official?.close(); official = null
        actions.shutdownNow()
        return (output == null || output.awaitActionTermination(1_000)) && try {
            actions.awaitTermination(1_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // Release the host owner first so no consumer outlives or races its source.
        val hostClosed = hostRuntime?.close() ?: true
        hostRuntime = null
        retired = true
        // Only framework/target-owned values cross the generation boundary.
        targetContext?.let { param.setSavedInstanceState(it) }
        synchronized(lifecycle) { entryHooks.forEach { runCatching { it.unhook() } }; entryHooks.clear() }
        return (client?.close() ?: closeConsumers()) && hostClosed
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        processName = param.processName
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        val context = param.savedInstanceState as? Context
        if (context == null) { log(Log.WARN, TAG, "hot_reload_context_unavailable"); return }
        val selected = BridgeTarget.resolve(context.packageName, param.processName) ?: return
        target = selected
        if (selected == BridgeTarget.BLUETOOTH) {
            val runtime = ModuleHostRuntime(this, context.classLoader)
            hostRuntime = runtime
            hostStarted = false
            runtime.installDiscovery()
            startHost(context)
        }
        installNativeAdapters(selected, context.classLoader)
        attach(context, selected)
    }

    private fun featureFor(selected: BridgeTarget): String = if (selected == BridgeTarget.MILINK) BridgePresentationWire.FUSION_CENTER else BridgePresentationWire.SYSTEM_HEADSET

    private companion object {
        const val TAG = "OpenRoseSystemModule"
        /** A settings-entry tap waits this long for the host before the system's own page runs. */
        const val FUSION_ENTRY_TIMEOUT_MILLIS = 750L
    }
}
