package io.github.dohex.openrose.module

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The only initial system hook: attach the application-owned bridge after Android Bluetooth's
 * AdapterService has completed creation. This module never creates or controls a ControlSession.
 */
class OpenRoseBluetoothModule : XposedModule() {
    private val bindingLock = Any()
    @Volatile private var loadedProcessName: String? = null
    private var acceptingBindings = true
    private var currentBinding: BridgeBinding? = null
    @Volatile private var targetContext: Context? = null
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    private var activeHookCalls = 0
    private var hookDrain = CountDownLatch(0)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        loadedProcessName = param.processName
        log(Log.INFO, TAG, "module_loaded process=${param.processName} api=$apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return

        val processName = loadedProcessName
        if (processName != TARGET_PROCESS) {
            log(
                Log.WARN,
                TAG,
                "hook_skipped package=${param.packageName} process=${processName ?: "unknown"}; " +
                    "expected_main_process=$TARGET_PROCESS",
            )
            return
        }

        val adapterService = try {
            param.classLoader.loadClass(ADAPTER_SERVICE_CLASS)
        } catch (failure: Throwable) {
            log(Log.WARN, TAG, "target_class_unavailable class=$ADAPTER_SERVICE_CLASS", failure)
            installAttachDiagnosticHook(RESOLUTION_CLASS_MISSING)
            return
        }

        val onCreate = resolveOnCreate(adapterService)
        if (onCreate == null) {
            installAttachDiagnosticHook(RESOLUTION_METHOD_MISSING)
            return
        }
        try {
            hook(onCreate)
                .setId(HOOK_ID)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(::afterAdapterServiceCreate)
            log(
                Log.INFO,
                TAG,
                "hook_installed class=${onCreate.declaringClass.name} method=${onCreate.name} " +
                    "process=$processName",
            )
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "hook_install_failed class=$ADAPTER_SERVICE_CLASS method=onCreate", failure)
            installAttachDiagnosticHook(RESOLUTION_HOOK_FAILED)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val drain = synchronized(bindingLock) {
            acceptingBindings = false
            if (activeHookCalls == 0) null else hookDrain
        }
        val hooksStopped = try {
            drain == null || drain.await(RELOAD_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val context = targetContext?.let { it.applicationContext ?: it }
        if (context != null) {
            if (context.packageName != TARGET_PACKAGE || context.javaClass.classLoader === javaClass.classLoader) {
                log(Log.ERROR, TAG, "hot_reload_context_rejected package=${context.packageName}")
                param.setSavedInstanceState(null)
            } else {
                param.setSavedInstanceState(context)
            }
        } else {
            param.setSavedInstanceState(null)
        }
        val resourcesStopped = closeModuleResources()
        return hooksStopped && resourcesStopped
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val processName = param.processName
        val savedContext = param.savedInstanceState as? Context
        val oldHandles = param.oldHookHandles
        synchronized(bindingLock) {
            loadedProcessName = processName
            acceptingBindings = processName == TARGET_PROCESS
            targetContext = null
        }

        val validSavedContext = savedContext?.takeIf {
            it.packageName == TARGET_PACKAGE && it.javaClass.classLoader !== javaClass.classLoader
        }
        val targetOnCreate = when {
            processName != TARGET_PROCESS -> null
            validSavedContext != null -> resolveOnCreateFrom(validSavedContext)
            savedContext == null -> oldHandles
                .asSequence()
                .mapNotNull { it.executable as? Method }
                .firstOrNull(::isAdapterServiceOnCreate)
                ?.let { resolveOnCreate(it.declaringClass) }
            else -> null
        }
        val attachMethod = if (processName == TARGET_PROCESS) resolveApplicationAttach() else null

        var replacedMatchedHook = false
        var replacedDiagnosticResolution: String? = null
        for (oldHandle in oldHandles) {
            val executable = oldHandle.executable
            if (processName == TARGET_PROCESS && targetOnCreate != null &&
                isAdapterServiceOnCreate(executable) && executable == targetOnCreate
            ) {
                try {
                    oldHandle.replaceHook(::afterAdapterServiceCreate)
                    replacedMatchedHook = true
                } catch (failure: Throwable) {
                    oldHandle.unhook()
                    log(Log.ERROR, TAG, "hot_reload_hook_replace_failed", failure)
                }
            } else if (processName == TARGET_PROCESS && attachMethod != null && executable == attachMethod) {
                val resolution = resolutionForDiagnosticHook(executable, oldHandle.id)
                if (resolution == null) {
                    oldHandle.unhook()
                    log(Log.ERROR, TAG, "hot_reload_diagnostic_hook_identity_unavailable")
                    continue
                }
                try {
                    oldHandle.replaceHook { chain -> afterApplicationAttach(chain, resolution) }
                    replacedDiagnosticResolution = resolution
                } catch (failure: Throwable) {
                    oldHandle.unhook()
                    log(Log.ERROR, TAG, "hot_reload_diagnostic_hook_replace_failed", failure)
                }
            } else {
                oldHandle.unhook()
            }
        }

        if (replacedMatchedHook && replacedDiagnosticResolution == null && validSavedContext != null) {
            targetContext = validSavedContext
            bindControlBridge(validSavedContext, bindingAttempt = 0)
            log(Log.INFO, TAG, "hot_reload_bridge_rebound process=$processName")
        } else if (replacedMatchedHook && replacedDiagnosticResolution == null) {
            log(Log.INFO, TAG, "hot_reload_hook_replaced awaiting_adapter_service_onCreate")
        } else if (replacedDiagnosticResolution != null && validSavedContext != null) {
            targetContext = validSavedContext
            bindControlBridge(
                validSavedContext,
                bindingAttempt = 0,
                resolution = replacedDiagnosticResolution,
                reportOnly = true,
            )
            log(Log.INFO, TAG, "hot_reload_diagnostic_rebound resolution=$replacedDiagnosticResolution")
        } else if (replacedDiagnosticResolution != null) {
            log(Log.INFO, TAG, "hot_reload_diagnostic_hook_replaced awaiting_application_attach")
        } else {
            log(
                Log.WARN,
                TAG,
                "hot_reload_hook_unavailable process=$processName " +
                    "matched_replaced=$replacedMatchedHook diagnostic=$replacedDiagnosticResolution " +
                    "context_available=${savedContext != null}; package_lifecycle_is_not_replayed",
            )
        }
    }

    private fun resolveOnCreateFrom(context: Context): Method? {
        val targetClass = try {
            context.classLoader.loadClass(ADAPTER_SERVICE_CLASS)
        } catch (failure: Throwable) {
            log(Log.WARN, TAG, "hot_reload_target_class_unavailable class=$ADAPTER_SERVICE_CLASS", failure)
            return null
        }
        return resolveOnCreate(targetClass)
    }

    private fun installAttachDiagnosticHook(resolution: String) {
        if (resolution == RESOLUTION_MATCHED || resolution !in BridgeWire.RESOLUTIONS) return
        val attach = resolveApplicationAttach() ?: run {
            return
        }
        try {
            hook(attach)
                .setId(diagnosticHookId(resolution))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> afterApplicationAttach(chain, resolution) }
            log(Log.INFO, TAG, "diagnostic_hook_installed resolution=$resolution")
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "diagnostic_hook_install_failed resolution=$resolution", failure)
        }
    }

    private fun resolveApplicationAttach(): Method? {
        val attach = try {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "diagnostic_hook_unavailable method=Application.attach(Context)", failure)
            return null
        }
        if (!isApplicationAttach(attach)) {
            log(Log.ERROR, TAG, "diagnostic_hook_signature_mismatch method=Application.attach(Context)")
            return null
        }
        return attach
    }

    private fun isApplicationAttach(executable: Executable): Boolean =
        executable is Method &&
            executable.declaringClass == Application::class.java &&
            executable.name == "attach" &&
            executable.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
            executable.returnType == Void.TYPE &&
            !Modifier.isStatic(executable.modifiers)

    private fun resolutionForDiagnosticHook(executable: Executable, hookId: String?): String? {
        if (!isApplicationAttach(executable)) return null
        val resolution = hookId?.removePrefix(DIAGNOSTIC_HOOK_ID_PREFIX) ?: return null
        return resolution.takeIf { it in BridgeWire.RESOLUTIONS && it != RESOLUTION_MATCHED }
    }

    private fun diagnosticHookId(resolution: String): String = "$DIAGNOSTIC_HOOK_ID_PREFIX$resolution"

    private fun resolveOnCreate(adapterService: Class<*>): Method? {
        val method = try {
            adapterService.getDeclaredMethod(BridgeWire.TARGET_COMPONENT_METHOD)
        } catch (failure: Throwable) {
            log(
                Log.WARN,
                TAG,
                "target_method_unavailable class=${adapterService.name} method=onCreate()",
                failure,
            )
            return null
        }

        if (!isAdapterServiceOnCreate(method)) {
            log(
                Log.WARN,
                TAG,
                "target_method_signature_mismatch class=${method.declaringClass.name} " +
                    "method=${method.name} params=${method.parameterTypes.contentToString()} " +
                    "return=${method.returnType.name}",
            )
            return null
        }
        log(Log.INFO, TAG, "target_method_resolved class=${adapterService.name} method=onCreate()")
        return method
    }

    private fun isAdapterServiceOnCreate(executable: Executable): Boolean =
        executable is Method &&
            executable.declaringClass.name == ADAPTER_SERVICE_CLASS &&
            executable.name == "onCreate" &&
            executable.parameterTypes.isEmpty() &&
            executable.returnType == Void.TYPE &&
            !Modifier.isStatic(executable.modifiers)

    private fun afterAdapterServiceCreate(chain: XposedInterface.Chain): Any? {
        val tracked = beginTrackedHookCall()
        try {
            val result = chain.proceed()
            if (tracked) {
                val serviceContext = chain.thisObject as? Context
                if (serviceContext == null || serviceContext.packageName != TARGET_PACKAGE) {
                    log(
                        Log.ERROR,
                        TAG,
                        "bridge_bind_skipped reason=unexpected_service_context " +
                            "package=${serviceContext?.packageName ?: "unknown"}",
                    )
                } else {
                    targetContext = serviceContext
                    bindControlBridge(serviceContext, bindingAttempt = 0)
                }
            }
            return result
        } finally {
            if (tracked) endTrackedHookCall()
        }
    }

    private fun afterApplicationAttach(chain: XposedInterface.Chain, resolution: String): Any? {
        val tracked = beginTrackedHookCall()
        try {
            val result = chain.proceed()
            if (!tracked) return result

            val context = chain.getArg(0) as? Context
            if (context == null || context.packageName != TARGET_PACKAGE ||
                context.javaClass.classLoader === javaClass.classLoader ||
                loadedProcessName != TARGET_PROCESS
            ) {
                log(
                    Log.ERROR,
                    TAG,
                    "diagnostic_report_skipped reason=unexpected_application_context " +
                        "package=${context?.packageName ?: "unknown"} process=${loadedProcessName ?: "unknown"}",
                )
                return result
            }
            targetContext = context.applicationContext ?: context
            bindControlBridge(context, bindingAttempt = 0, resolution = resolution, reportOnly = true)
            return result
        } finally {
            if (tracked) endTrackedHookCall()
        }
    }

    private fun beginTrackedHookCall(): Boolean = synchronized(bindingLock) {
        if (!acceptingBindings) {
            false
        } else {
            if (activeHookCalls == 0) hookDrain = CountDownLatch(1)
            activeHookCalls += 1
            true
        }
    }

    private fun endTrackedHookCall() {
        synchronized(bindingLock) {
            activeHookCalls -= 1
            if (activeHookCalls == 0) hookDrain.countDown()
        }
    }

    private fun bindControlBridge(
        serviceContext: Context,
        bindingAttempt: Int,
        resolution: String = RESOLUTION_MATCHED,
        reportOnly: Boolean = resolution != RESOLUTION_MATCHED,
    ) {
        val binding: BridgeBinding
        synchronized(bindingLock) {
            if (!acceptingBindings || loadedProcessName != TARGET_PROCESS) {
                log(Log.INFO, TAG, "bridge_bind_skipped reason=module_generation_inactive")
                return
            }
            if (currentBinding != null) return
            binding = BridgeBinding(serviceContext, bindingAttempt, resolution, reportOnly)
            currentBinding = binding
            readerHandlerLocked().post(binding.pollRunnable)
        }

        val intent = Intent().setComponent(
            ComponentName(BridgeWire.APP_PACKAGE, BridgeWire.SERVICE_CLASS),
        )
        val bound = try {
            serviceContext.bindService(intent, binding, Context.BIND_AUTO_CREATE)
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "bridge_bind_failed component=${BridgeWire.SERVICE_CLASS}", failure)
            false
        }

        var unbind = false
        synchronized(bindingLock) {
            binding.isBound = bound
            if (!bound) {
                if (currentBinding === binding) currentBinding = null
                binding.isClosed = true
            } else if (binding.isClosed || currentBinding !== binding) {
                binding.isClosed = true
                binding.isBound = false
                unbind = true
            }
        }

        if (unbind) unbindQuietly(binding)
        if (bound && !unbind) {
            log(Log.INFO, TAG, "bridge_binding_requested component=${BridgeWire.SERVICE_CLASS}")
        } else if (!bound) {
            log(Log.WARN, TAG, "bridge_bind_unavailable component=${BridgeWire.SERVICE_CLASS}")
        }
    }

    private fun readerHandlerLocked(): Handler {
        readerHandler?.let { return it }
        val thread = HandlerThread(READER_THREAD_NAME).apply { start() }
        readerThread = thread
        return Handler(thread.looper).also { readerHandler = it }
    }

    private fun schedulePoll(binding: BridgeBinding, delayMillis: Long = 0) {
        synchronized(bindingLock) {
            if (!acceptingBindings || currentBinding !== binding || binding.isClosed) return
            val handler = readerHandler ?: return
            handler.removeCallbacks(binding.pollRunnable)
            handler.postDelayed(binding.pollRunnable, delayMillis)
        }
    }

    private fun pollBridge(binding: BridgeBinding) {
        try {
            if (!isCurrentBinding(binding)) return
            val bridge = binding.remoteBridge ?: return
            val preferences = getRemotePreferences(BridgeWire.PREFERENCES_GROUP)
            attachPreferencesListener(binding, preferences)

            val hostEpoch = preferences.getString(BridgeWire.HOST_EPOCH, null)
            val proofKey = preferences.getString(BridgeWire.PROOF_KEY, null)
            if (hostEpoch.isNullOrBlank() || proofKey.isNullOrBlank()) {
                forgetCredentials(binding, bridge)
                recordBridgeStatus(binding, "waiting_for_host_credentials")
                return
            }
            if (hostEpoch.length > 64 || proofKey.length != 64 || !proofKey.all { it.isHexDigit() }) {
                forgetCredentials(binding, bridge)
                recordBridgeStatus(binding, "invalid_host_credentials")
                return
            }

            if (binding.hostEpoch != hostEpoch || binding.proofKey != proofKey) {
                closeLease(binding, bridge)
                binding.hostEpoch = hostEpoch
                binding.proofKey = proofKey
                binding.rejectedCredential = null
                binding.reportCompleted = false
            }

            if (binding.reportOnly) {
                reportResolution(binding, bridge, hostEpoch, proofKey)
                return
            }

            if (binding.leaseId == null) {
                val fingerprint = "$hostEpoch:$proofKey"
                if (binding.rejectedCredential == fingerprint) return
                val proof = BridgeWire.proof(
                    keyHex = proofKey,
                    hostEpoch = hostEpoch,
                    processName = TARGET_PROCESS,
                    uid = Process.myUid(),
                    pid = Process.myPid(),
                    resolution = binding.resolution,
                )
                val opened = bridge.open(proof, binding.clientToken)
                val status = opened.getString(STATUS) ?: STATUS_MISSING
                val lease = opened.getString(LEASE_ID)
                val openedEpoch = opened.getString(BridgeWire.HOST_EPOCH)
                if (!hasWireVersion(opened) || status != STATUS_OK || lease.isNullOrBlank() || openedEpoch != hostEpoch) {
                    if (!lease.isNullOrBlank()) runCatching { bridge.close(lease) }
                    if (status == STATUS_STALE_HOST || openedEpoch != hostEpoch) {
                        binding.rejectedCredential = fingerprint
                    }
                    recordBridgeStatus(binding, if (hasWireVersion(opened)) "open_$status" else "open_invalid_version")
                    return
                }
                binding.leaseId = lease
                binding.lastReadIdentity = null
                recordBridgeStatus(binding, "authenticated")
            }

            readCurrentSnapshot(binding, bridge, hostEpoch)
        } catch (failure: SecurityException) {
            binding.rejectedCredential = binding.hostEpoch?.let { "$it:${binding.proofKey}" }
            closeLease(binding, binding.remoteBridge)
            if (recordBridgeStatus(binding, "rejected_by_host")) {
                log(Log.ERROR, TAG, "bridge_request_rejected", failure)
            }
        } catch (failure: Throwable) {
            if (recordBridgeStatus(binding, "request_failed_${failure.javaClass.simpleName}")) {
                log(Log.WARN, TAG, "bridge_request_failed", failure)
            }
        } finally {
            schedulePoll(binding, POLL_INTERVAL_MILLIS)
        }
    }

    private fun readCurrentSnapshot(binding: BridgeBinding, bridge: IControlBridge, hostEpoch: String) {
        val lease = binding.leaseId ?: return
        val current = bridge.currentSession(lease)
        if (!hasWireVersion(current)) {
            recordBridgeStatus(binding, "session_invalid_version")
            return
        }
        val currentStatus = current.getString(STATUS) ?: STATUS_MISSING
        if (currentStatus == STATUS_NO_SESSION) {
            binding.lastReadIdentity = null
            recordBridgeStatus(binding, "no_session")
            return
        }
        if (currentStatus != STATUS_OK) {
            if (currentStatus == STATUS_STALE_HOST || currentStatus == STATUS_STALE_SESSION) {
                closeLease(binding, bridge)
            }
            recordBridgeStatus(binding, "session_$currentStatus")
            return
        }

        val currentEpoch = current.getString(BridgeWire.HOST_EPOCH)
        val deviceId = current.getString(DEVICE_ID)
        val sessionId = current.getLong(SESSION_ID, INVALID_SESSION_ID)
        if (currentEpoch != hostEpoch || deviceId.isNullOrBlank() || sessionId == INVALID_SESSION_ID) {
            recordBridgeStatus(binding, "session_identity_mismatch")
            return
        }

        val request = Bundle().apply {
            putInt(VERSION, BridgeWire.VERSION)
            putString(LEASE_ID, lease)
            putString(BridgeWire.HOST_EPOCH, hostEpoch)
            putString(DEVICE_ID, deviceId)
            putLong(SESSION_ID, sessionId)
        }
        val response = bridge.read(request)
        if (!hasWireVersion(response)) {
            recordBridgeStatus(binding, "read_invalid_version")
            return
        }
        val responseStatus = response.getString(STATUS) ?: STATUS_MISSING
        if (responseStatus != STATUS_OK) {
            if (responseStatus == STATUS_STALE_HOST || responseStatus == STATUS_STALE_SESSION) {
                closeLease(binding, bridge)
            }
            recordBridgeStatus(binding, "read_$responseStatus")
            return
        }

        val snapshot = response.getBundle(SNAPSHOT)
        if (
            snapshot == null ||
            !hasWireVersion(snapshot) ||
            snapshot.getString(BridgeWire.HOST_EPOCH) != hostEpoch ||
            snapshot.getString(DEVICE_ID) != deviceId ||
            snapshot.getLong(SESSION_ID, INVALID_SESSION_ID) != sessionId
        ) {
            recordBridgeStatus(binding, "snapshot_identity_mismatch")
            return
        }

        val readSequence = snapshot.getLong(READ_SEQUENCE, INVALID_READ_SEQUENCE)
        if (readSequence <= 0) {
            recordBridgeStatus(binding, "snapshot_read_sequence_missing")
            return
        }
        val receipt = Bundle().apply {
            putInt(VERSION, BridgeWire.VERSION)
            putString(LEASE_ID, lease)
            putString(BridgeWire.HOST_EPOCH, hostEpoch)
            putString(DEVICE_ID, deviceId)
            putLong(SESSION_ID, sessionId)
            putLong(READ_SEQUENCE, readSequence)
        }
        val acknowledged = bridge.acknowledge(receipt)
        if (!hasWireVersion(acknowledged) || acknowledged.getString(STATUS) != STATUS_OK) {
            binding.lastReadIdentity = null
            val ackStatus = if (hasWireVersion(acknowledged)) {
                acknowledged.getString(STATUS) ?: STATUS_MISSING
            } else {
                "INVALID_VERSION"
            }
            recordBridgeStatus(binding, "ack_$ackStatus")
            return
        }

        val identity = "$hostEpoch:$deviceId:$sessionId"
        if (binding.lastReadIdentity != identity) {
            log(Log.INFO, TAG, "snapshot_acknowledged session=$sessionId")
            binding.lastReadIdentity = identity
        }
        recordBridgeStatus(binding, "snapshot_acknowledged")
    }

    private fun reportResolution(
        binding: BridgeBinding,
        bridge: IControlBridge,
        hostEpoch: String,
        proofKey: String,
    ) {
        if (binding.reportCompleted) return
        val fingerprint = "$hostEpoch:$proofKey"
        if (binding.rejectedCredential == fingerprint) return
        val proof = BridgeWire.proof(
            keyHex = proofKey,
            hostEpoch = hostEpoch,
            processName = TARGET_PROCESS,
            uid = Process.myUid(),
            pid = Process.myPid(),
            resolution = binding.resolution,
        )
        val response = bridge.open(proof, binding.clientToken)
        val status = response.getString(STATUS) ?: STATUS_MISSING
        if (!hasWireVersion(response)) {
            binding.reportCompleted = true
            recordBridgeStatus(binding, "diagnostic_invalid_response_version")
            return
        }
        when (status) {
            STATUS_TARGET_INCOMPATIBLE -> {
                binding.reportCompleted = true
                recordBridgeStatus(binding, "target_incompatibility_reported_${binding.resolution}")
            }
            STATUS_STALE_REPORT -> {
                binding.reportCompleted = true
                recordBridgeStatus(binding, "diagnostic_report_blocked_by_active_reader")
            }
            STATUS_OK -> {
                response.getString(LEASE_ID)?.let { runCatching { bridge.close(it) } }
                binding.reportCompleted = true
                recordBridgeStatus(binding, "diagnostic_unexpected_lease")
            }
            STATUS_STALE_HOST -> {
                binding.rejectedCredential = fingerprint
                recordBridgeStatus(binding, "diagnostic_waiting_for_current_host")
            }
            else -> {
                binding.rejectedCredential = fingerprint
                recordBridgeStatus(binding, "diagnostic_report_$status")
            }
        }
    }

    private fun attachPreferencesListener(binding: BridgeBinding, preferences: SharedPreferences) {
        if (binding.remotePreferences === preferences) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == BridgeWire.HOST_EPOCH || key == BridgeWire.PROOF_KEY) {
                schedulePoll(binding)
            }
        }
        binding.remotePreferences?.let { old ->
            binding.preferencesListener?.let(old::unregisterOnSharedPreferenceChangeListener)
        }
        binding.remotePreferences = preferences
        binding.preferencesListener = listener
        runCatching { preferences.registerOnSharedPreferenceChangeListener(listener) }
            .onFailure { log(Log.WARN, TAG, "remote_preferences_listener_unavailable", it) }
    }

    private fun closeLease(binding: BridgeBinding, bridge: IControlBridge?) {
        val lease = binding.leaseId
        binding.leaseId = null
        binding.lastReadIdentity = null
        if (lease != null && bridge != null) {
            try {
                bridge.close(lease)
                log(Log.INFO, TAG, "bridge_lease_closed")
            } catch (failure: Throwable) {
                log(Log.WARN, TAG, "bridge_lease_close_failed", failure)
            }
        }
    }

    private fun forgetCredentials(binding: BridgeBinding, bridge: IControlBridge) {
        closeLease(binding, bridge)
        binding.hostEpoch = null
        binding.proofKey = null
        binding.rejectedCredential = null
        binding.reportCompleted = false
    }

    private fun recordBridgeStatus(binding: BridgeBinding, status: String): Boolean {
        if (binding.lastStatus == status) return false
        binding.lastStatus = status
        log(Log.INFO, TAG, "bridge_status=$status")
        return true
    }

    private fun hasWireVersion(bundle: Bundle): Boolean =
        bundle.getInt(VERSION, INVALID_WIRE_VERSION) == BridgeWire.VERSION

    private fun isCurrentBinding(binding: BridgeBinding): Boolean = synchronized(bindingLock) {
        acceptingBindings && currentBinding === binding && !binding.isClosed
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun closeModuleResources(): Boolean {
        val binding: BridgeBinding?
        val handler: Handler?
        val thread: HandlerThread?
        synchronized(bindingLock) {
            acceptingBindings = false
            binding = currentBinding
            currentBinding = null
            binding?.isClosed = true
            handler = readerHandler
            thread = readerThread
        }

        var cleanupComplete = true
        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null)
            val completed = CountDownLatch(1)
            val posted = handler.post {
                try {
                    binding?.let {
                        closeLease(it, it.remoteBridge)
                        detachPreferencesListener(it)
                    }
                } catch (failure: Throwable) {
                    cleanupComplete = false
                    log(Log.ERROR, TAG, "hot_reload_worker_cleanup_failed", failure)
                } finally {
                    completed.countDown()
                }
            }
            val workerCleanupFinished = try {
                posted && completed.await(RELOAD_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            cleanupComplete = cleanupComplete && workerCleanupFinished
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            val threadStopped = try {
                if (Thread.currentThread() !== thread) thread.join(RELOAD_CLEANUP_TIMEOUT_MILLIS)
                !thread.isAlive
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            cleanupComplete = cleanupComplete && threadStopped
        } else {
            try {
                binding?.let {
                    closeLease(it, it.remoteBridge)
                    detachPreferencesListener(it)
                }
            } catch (failure: Throwable) {
                cleanupComplete = false
                log(Log.ERROR, TAG, "hot_reload_cleanup_failed", failure)
            }
        }

        binding?.let { unbindQuietly(it) }
        synchronized(bindingLock) {
            readerHandler = null
            readerThread = null
            targetContext = null
        }
        log(
            if (cleanupComplete) Log.INFO else Log.ERROR,
            TAG,
            "hot_reload_cleanup_complete=$cleanupComplete",
        )
        return cleanupComplete
    }

    private fun detachPreferencesListener(binding: BridgeBinding) {
        val preferences = binding.remotePreferences
        val listener = binding.preferencesListener
        var detached = true
        if (preferences != null && listener != null) {
            runCatching { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
                .onFailure {
                    detached = false
                    log(Log.WARN, TAG, "remote_preferences_listener_remove_failed", it)
                }
        }
        binding.remotePreferences = null
        binding.preferencesListener = null
        if (!detached) throw IllegalStateException("Could not remove remote preferences listener")
    }

    private fun unbindQuietly(binding: BridgeBinding) {
        val shouldUnbind = synchronized(bindingLock) {
            if (!binding.isBound) false else {
                binding.isBound = false
                true
            }
        }
        if (!shouldUnbind) return
        try {
            binding.context.unbindService(binding)
        } catch (failure: IllegalArgumentException) {
            log(Log.WARN, TAG, "bridge_unbind_failed reason=not_registered", failure)
        } catch (failure: Throwable) {
            log(Log.ERROR, TAG, "bridge_unbind_failed", failure)
        }
    }

    private inner class BridgeBinding(
        val context: Context,
        val bindingAttempt: Int,
        val resolution: String,
        val reportOnly: Boolean,
    ) : ServiceConnection {
        val clientToken = Binder()
        val pollRunnable = Runnable { pollBridge(this) }
        @Volatile var remoteBridge: IControlBridge? = null
        var isBound: Boolean = false
        var isClosed: Boolean = false
        var remotePreferences: SharedPreferences? = null
        var preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        var hostEpoch: String? = null
        var proofKey: String? = null
        var leaseId: String? = null
        var rejectedCredential: String? = null
        var reportCompleted: Boolean = false
        var lastReadIdentity: String? = null
        var lastStatus: String? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!isCurrentBinding(this)) return
            remoteBridge = IControlBridge.Stub.asInterface(service)
            log(Log.INFO, TAG, "bridge_service_connected component=${name.flattenToShortString()}")
            schedulePoll(this)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!isCurrentBinding(this)) return
            remoteBridge = null
            recordBridgeStatus(this, "service_disconnected")
            log(Log.WARN, TAG, "bridge_service_disconnected component=${name.flattenToShortString()}")
        }

        override fun onBindingDied(name: ComponentName) {
            if (!isCurrentBinding(this)) return
            log(Log.ERROR, TAG, "bridge_binding_died component=${name.flattenToShortString()}")
            val shouldRetry = synchronized(bindingLock) {
                if (currentBinding !== this || isClosed) false else {
                    currentBinding = null
                    isClosed = true
                    true
                }
            }
            unbindQuietly(this)
            if (shouldRetry && bindingAttempt < MAX_BINDING_RETRIES) {
                bindControlBridge(context, bindingAttempt + 1, resolution, reportOnly)
            }
        }

        override fun onNullBinding(name: ComponentName) {
            if (!isCurrentBinding(this)) return
            log(Log.ERROR, TAG, "bridge_null_binding component=${name.flattenToShortString()}")
            synchronized(bindingLock) {
                if (currentBinding === this) currentBinding = null
                isClosed = true
            }
            unbindQuietly(this)
        }
    }

    private companion object {
        const val TAG = "OpenRoseBluetoothModule"
        const val TARGET_PACKAGE = BridgeWire.TARGET_PACKAGE
        const val TARGET_PROCESS = BridgeWire.TARGET_PROCESS
        const val ADAPTER_SERVICE_CLASS = BridgeWire.TARGET_COMPONENT_CLASS
        const val HOOK_ID = "openrose.bluetooth.AdapterService.onCreate"
        const val DIAGNOSTIC_HOOK_ID_PREFIX = "openrose.bluetooth.Application.attach."
        const val READER_THREAD_NAME = "OpenRoseBridgeReader"
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val RELOAD_CLEANUP_TIMEOUT_MILLIS = 3_000L
        const val MAX_BINDING_RETRIES = 1

        const val VERSION = BridgeWire.KEY_VERSION
        const val STATUS = BridgeWire.STATUS
        const val STATUS_OK = BridgeWire.STATUS_OK
        const val STATUS_NO_SESSION = BridgeWire.STATUS_NO_SESSION
        const val STATUS_TARGET_INCOMPATIBLE = BridgeWire.STATUS_TARGET_INCOMPATIBLE
        const val STATUS_STALE_REPORT = BridgeWire.STATUS_STALE_REPORT
        const val STATUS_STALE_HOST = BridgeWire.STATUS_STALE_HOST
        const val STATUS_STALE_SESSION = BridgeWire.STATUS_STALE_SESSION
        const val STATUS_MISSING = "MISSING_STATUS"
        const val LEASE_ID = BridgeWire.LEASE_ID
        const val SNAPSHOT = BridgeWire.SNAPSHOT
        const val DEVICE_ID = BridgeWire.DEVICE_ID
        const val SESSION_ID = BridgeWire.SESSION_ID
        const val READ_SEQUENCE = BridgeWire.READ_SEQUENCE
        const val INVALID_WIRE_VERSION = -1
        const val INVALID_READ_SEQUENCE = -1L
        const val INVALID_SESSION_ID = Long.MIN_VALUE
        const val RESOLUTION_MATCHED = BridgeWire.RESOLUTION_MATCHED
        const val RESOLUTION_CLASS_MISSING = BridgeWire.RESOLUTION_CLASS_MISSING
        const val RESOLUTION_METHOD_MISSING = BridgeWire.RESOLUTION_METHOD_MISSING
        const val RESOLUTION_HOOK_FAILED = BridgeWire.RESOLUTION_HOOK_FAILED
    }
}
