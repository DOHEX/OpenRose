package io.github.dohex.openrose.module

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.asWireMap
import io.github.dohex.openrose.module.nativeheadset.AuthenticatedNativeHeadsetCallbacks
import io.github.dohex.openrose.module.nativeheadset.MiLinkNativeHeadsetAdapter
import io.github.dohex.openrose.module.nativeheadset.NativeHeadsetBinderAdapter
import io.github.dohex.openrose.module.presentation.OfficialConnectionPresenter
import io.github.dohex.openrose.module.presentation.PresentationCallbacks
import io.github.dohex.openrose.module.presentation.SystemPresentationRenderer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Method
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class OpenRoseBluetoothModule : XposedModule() {
    private val lifecycle = Any()
    private val presentation = HeadsetPresentation()
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

    @Volatile private var binderAdapter: NativeHeadsetBinderAdapter? = null

    @Volatile private var fusionAdapter: MiLinkNativeHeadsetAdapter? = null
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
            hostRuntime = ModuleHostRuntime(this, param.classLoader).also { it.installDiscovery() }
        }
        installNativeAdapters(selected, param.classLoader)
        val entry = if (selected == BridgeTarget.BLUETOOTH) {
            bluetoothServiceEntry(param.classLoader)
        } else {
            applicationAttach()
        }
        if (entry == null) {
            log(Log.WARN, TAG, "entry_unavailable target=$selected process=$actualProcess")
            installEntry(applicationAttach(), selected, contextArgument = true)
            return
        }
        entryResolution = BridgeWire.RESOLUTION_MATCHED
        installEntry(entry, selected)
    }

    @SuppressLint("PrivateApi")
    private fun bluetoothServiceEntry(loader: ClassLoader): Method? {
        val type = runCatching { loader.loadClass(BridgeWire.TARGET_COMPONENT_CLASS) }.getOrNull()
        entryResolution = if (type == null) BridgeWire.RESOLUTION_CLASS_MISSING else BridgeWire.RESOLUTION_METHOD_MISSING
        return runCatching { type?.getDeclaredMethod("onCreate") }.getOrNull()
            ?.takeIf { it.returnType == Void.TYPE && it.parameterTypes.isEmpty() }
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
        } catch (failure: IllegalArgumentException) {
            log(Log.ERROR, TAG, "entry_hook_failed target=$selected", failure)
        } catch (failure: HookFailedError) {
            log(Log.ERROR, TAG, "entry_hook_failed target=$selected", failure)
        }
    }

    private fun installNativeAdapters(selected: BridgeTarget, loader: ClassLoader) {
        val callbacks = object : AuthenticatedNativeHeadsetCallbacks {
            override fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String): Boolean {
                if (retired || nativeSnapshot?.reference != snapshot.reference) return false
                val connectedClient = client ?: return false
                val frame = latestFrame ?: return false
                return try {
                    actions.execute {
                        if (retired || nativeSnapshot?.reference != snapshot.reference) return@execute
                        val result = connectedClient.calls.setNoise(frame, mode)
                        if (!retired && nativeSnapshot?.reference == snapshot.reference) {
                            val outcome = SystemHeadsetMapping.commandReport(
                                mode,
                                result.getString(BridgeWire.STATUS),
                                result.getString(BridgeWire.MESSAGE),
                            )
                            presentation.componentReport(featureFor(selected), outcome.status, outcome.detail)
                        }
                    }
                    true
                } catch (_: RejectedExecutionException) {
                    false
                }
            }
            override fun report(feature: String, status: String, detail: String) {
                presentation.componentReport(featureFor(selected), status, detail)
            }
            override fun fusionEntryDevice(address: String): String? {
                val connectedClient = client?.takeIf { !retired } ?: return null
                val ask = java.util.concurrent.FutureTask { connectedClient.calls.fusionEntryDevice(address) }
                return try {
                    actions.execute(ask)
                    ask.get(FUSION_ENTRY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    null
                } catch (_: TimeoutException) {
                    null
                } catch (_: ExecutionException) {
                    null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } finally {
                    if (!ask.isDone) ask.cancel(true)
                }
            }
        }
        when (selected) {
            BridgeTarget.BLUETOOTH, BridgeTarget.XIAOMI_BLUETOOTH -> {
                binderAdapter = NativeHeadsetBinderAdapter(this, loader, callbacks).also { it.install() }
            }

            BridgeTarget.MILINK -> fusionAdapter = MiLinkNativeHeadsetAdapter(
                this,
                loader,
                callbacks,
                contextSupplier = { targetContext },
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
            val created = AuthenticatedBridgeClient(
                context,
                selected,
                actualProcess,
                onSnapshot = presentation::publish,
                onSnapshotFresh = presentation::refreshSnapshot,
                onClear = presentation::clearConsumers,
                onBonded = presentation::publishBonded,
                onAuthenticated = { authenticated ->
                    val reports = synchronized(pendingReports) { pendingReports.toMap() }
                    reports.forEach { (feature, value) -> authenticated.calls.reportComponent(feature, value.first, value.second) }
                },
                onClosing = presentation::closeConsumers,
                resolution = entryResolution,
            )
            client = created
            created.start()
        }
    }

    private fun startHost(context: Context) {
        if (hostStarted) return
        val runtime = hostRuntime ?: return
        hostStarted = true
        runtime.start(context)
    }

    private inner class HeadsetPresentation {
        private fun presentationCallbacks(connected: AuthenticatedBridgeClient) = object : PresentationCallbacks {
            override fun claimPrompt(snapshot: Bundle, promptId: String): Boolean = connected.calls.claimPrompt(snapshot, promptId)
            override fun setNoise(snapshot: Bundle, mode: String): Bundle = connected.calls.setNoise(snapshot, mode)
            override fun report(snapshot: Bundle, feature: String, status: String, detail: String) = connected.calls
                .reportPresentation(snapshot, feature, status, detail)
        }

        fun publish(snapshot: Bundle) {
            if (retired) return
            val connected = client ?: return
            val context = targetContext ?: return
            val native = NativeHeadsetSnapshot.fromWire(snapshot.asWireMap())
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
                    publishOfficialPrompt(snapshot, output)
                }

                else -> Unit
            }
        }

        private fun publishOfficialPrompt(snapshot: Bundle, output: OfficialConnectionPresenter) {
            val identity = "${snapshot.getString(BridgeWire.HOST_EPOCH)}:" +
                "${snapshot.getString(BridgeWire.DEVICE_ID)}:" +
                "${snapshot.getLong(BridgeWire.SESSION_ID)}"
            if (lastOfficialIdentity != identity) {
                output.clear()
                consumedOfficialPrompts.clear()
                lastOfficialIdentity = identity
            }
            val spec = snapshot.getBundle(BridgePresentationWire.PRESENTATION)
            if (spec?.getString(BridgePresentationWire.MODE) != BridgePresentationWire.OFFICIAL ||
                !spec.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS)
            ) {
                output.clear()
                return
            }
            val prompt = spec.getBundle(BridgePresentationWire.PROMPT)
            val id = prompt?.getString(BridgePresentationWire.PROMPT_ID) ?: return
            if (consumedOfficialPrompts.add(id) && !prompt.getBoolean(BridgePresentationWire.CLAIMED)) output.show(snapshot, id)
        }

        fun refreshSnapshot(capturedAt: Long) {
            if (retired) return
            val current = nativeSnapshot ?: return
            val fresh = current.copy(capturedElapsedMillis = capturedAt)
            nativeSnapshot = fresh
            binderAdapter?.publish(fresh)
            fusionAdapter?.publish(fresh)
        }

        fun publishBonded(addresses: Set<String>) {
            if (retired) return
            binderAdapter?.publishBonded(addresses)
            fusionAdapter?.publishBonded(addresses)
        }

        fun clearConsumers() {
            nativeSnapshot = null
            latestFrame = null
            binderAdapter?.publish(null)
            fusionAdapter?.publish(null)
            renderer?.clear()
            official?.clear()
        }

        fun componentReport(feature: String, status: String, detail: String) {
            synchronized(pendingReports) { pendingReports[feature] = status to detail }
            val connected = client ?: return
            connected.handler.post { if (!retired) connected.calls.reportComponent(feature, status, detail) }
        }

        fun closeConsumers() {
            clearConsumers()
            renderer?.close()
            official?.close()
            actions.shutdownNow()
        }

        fun consumersTerminated(): Boolean = renderer?.awaitActionTermination(1_000) != false && try {
            actions.awaitTermination(1_000, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun closeNativeAdapters() {
        binderAdapter?.close()
        binderAdapter = null
        fusionAdapter?.close()
        fusionAdapter = null
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val runtime = hostRuntime
        if (runtime?.close() == false) return false
        retired = true
        val readerClosed = client?.close() ?: run {
            presentation.closeConsumers()
            true
        }
        if (!readerClosed || !presentation.consumersTerminated()) return false

        hostRuntime = null
        renderer = null
        official = null
        targetContext?.let { context ->
            param.setSavedInstanceState(
                HashMap<String, Any>().apply {
                    put(SAVED_CONTEXT, context)
                    runtime?.let { put(SAVED_ENDPOINT_SLOT, it.endpointSlot) }
                },
            )
        }
        synchronized(lifecycle) {
            entryHooks.forEach { runCatching { it.unhook() } }
            entryHooks.clear()
        }
        closeNativeAdapters()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        processName = param.processName
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        val saved = param.savedInstanceState as? Map<*, *>
        val context = (saved?.get(SAVED_CONTEXT) ?: param.savedInstanceState) as? Context
        if (context == null) {
            log(Log.WARN, TAG, "hot_reload_context_unavailable")
            return
        }
        val selected = BridgeTarget.resolve(context.packageName, param.processName) ?: return
        target = selected
        if (selected == BridgeTarget.BLUETOOTH) {
            @Suppress("UNCHECKED_CAST")
            val slot = saved?.get(SAVED_ENDPOINT_SLOT) as? AtomicReference<IBinder?>
            if (slot == null) log(Log.WARN, TAG, "hot_reload_endpoint_slot_unavailable")
            val runtime = slot?.let { ModuleHostRuntime(this, context.classLoader, it) } ?: ModuleHostRuntime(this, context.classLoader)
            hostRuntime = runtime
            hostStarted = false
            runtime.installDiscovery()
            startHost(context)
        }
        installNativeAdapters(selected, context.classLoader)
        attach(context, selected)
    }

    private fun featureFor(selected: BridgeTarget): String = if (selected == BridgeTarget.MILINK) {
        BridgePresentationWire.FUSION_CENTER
    } else {
        BridgePresentationWire.SYSTEM_HEADSET
    }

    private companion object {
        const val TAG = "OpenRoseSystemModule"

        const val FUSION_ENTRY_TIMEOUT_MILLIS = 750L

        const val SAVED_CONTEXT = "context"
        const val SAVED_ENDPOINT_SLOT = "endpointSlot"
    }
}

@SuppressLint("DiscouragedPrivateApi")
private fun applicationAttach(): Method = Application::class.java.getDeclaredMethod("attach", Context::class.java)
