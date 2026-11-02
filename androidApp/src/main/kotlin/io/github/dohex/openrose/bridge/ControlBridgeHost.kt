package io.github.dohex.openrose.bridge

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import io.github.dohex.openrose.core.BridgeIpcState
import io.github.dohex.openrose.core.BridgeSessionState
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionSnapshotResult
import io.github.dohex.openrose.core.SystemBridgeDiagnostics
import io.github.dohex.openrose.core.SystemEndpointDiagnostics
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.FusionSettingsDestination
import io.github.dohex.openrose.integration.NativeHeadsetSnapshot
import org.json.JSONObject
import java.util.Locale
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Authentication and leases are local; all device truth is read from the existing authority. */
class ControlBridgeHost(
    private val context: Context,
    private val runtime: ControlRuntime,
    private val onChanged: () -> Unit,
) {
    private val lock = Any()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val hostEpoch = UUID.randomUUID().toString()
    private val reads = AtomicLong()
    private var framework: XposedService? = null
    private var proofKey: String? = null
    private var facts = SystemBridgeDiagnostics(detail = "尚未收到兼容框架服务")
    private val leases = mutableMapOf<String, Lease>()

    private data class Lease(
        val id: String,
        val uid: Int,
        val pid: Int,
        val target: BridgeTarget,
        val processName: String,
        val client: IBinder,
        val death: IBinder.DeathRecipient,
        var touchedAt: Long,
        var awaiting: PendingRead? = null,
        var acknowledged: SessionReference? = null,
        var observer: IControlObserver? = null,
    )

    private data class PendingRead(val reference: SessionReference, val sequence: Long)

    fun start() {
        worker.launch {
            runtime.state.map { it.connection to it.presentation }.distinctUntilChanged().collect { (_, presentation) ->
                val reference = presentation.reference
                synchronized(lock) {
                    if (reportedReference != reference) {
                        reportedReference = reference
                        updateFacts { facts -> facts.copy(presentation = emptyMap()) }
                    }
                    if (presentation.connectionPrompt?.mode == IslandMode.OFFICIAL &&
                        leases.values.none { it.target == BridgeTarget.XIAOMI_BLUETOOTH }) {
                        updateFacts { facts -> facts.copy(presentation = facts.presentation +
                            (BridgePresentationWire.OFFICIAL_CONNECTION to "UNAVAILABLE · official_target_not_connected")) }
                    }
                }
                notifyReaders()
            }
        }
        worker.launch {
            runtime.state.map { state ->
                FusionStateSnapshot(
                    destination = state.settings.fusionSettingsDestination,
                    activeDeviceId = state.device?.deviceId?.value,
                    activeClassicAddress = state.device?.classicAddress,
                    targetDeviceId = state.connectionTarget?.takeIf { it.profile != null }?.deviceId?.value,
                    targetClassicAddress = state.connectionTarget?.takeIf { it.profile != null }?.classicAddress,
                    candidateAddresses = state.candidates.filter { it.profile != null }
                        .map { it.deviceId.value to it.classicAddress },
                    bondedAddresses = state.bondedDevices.filter { it.profile != null }
                        .map { it.deviceId.value to it.classicAddress },
                )
            }.distinctUntilChanged().collect { snapshot ->
                try {
                    publishFusionPreferences(snapshot = snapshot)
                } catch (failure: Throwable) {
                    android.util.Log.w(TAG, "Failed to publish fusion preferences", failure)
                }
            }
        }
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) { worker.launch { frameworkBound(service) } }
            override fun onServiceDied(service: XposedService) { worker.launch { frameworkDied(service) } }
        })
    }

    fun onUserUnlocked() { worker.launch {
        val service = synchronized(lock) { framework } ?: return@launch
        try {
            publishCredentials(service)
            publishFusionPreferences(service = service)
        } catch (_: Exception) { frameworkDied(service) }
    } }

    fun diagnostics(): SystemBridgeDiagnostics = synchronized(lock) { facts }

    fun availability(): ModuleAvailability = synchronized(lock) {
        if (facts.ipc == BridgeIpcState.AUTHENTICATED && leases.isNotEmpty()) ModuleAvailability.Available
        else ModuleAvailability.Unavailable(facts.detail ?: "系统桥接尚未就绪")
    }

    private fun frameworkBound(service: XposedService) {
        try {
            synchronized(lock) {
                revokeLeases()
                proofKey = null
                framework = service
            }
            val api = service.apiVersion
            updateFacts { SystemBridgeDiagnostics(
                frameworkName = service.frameworkName,
                frameworkVersion = service.frameworkVersion,
                frameworkApi = api,
                ipc = BridgeIpcState.WAITING,
                detail = if (api >= 102) "等待目标进程的受校验读取" else "框架服务 API 低于 102",
            ) }
            if (api >= 102) {
                publishCredentials(service)
                publishFusionPreferences(service = service)
            }
        } catch (_: Exception) {
            frameworkDied(service)
        }
    }

    private fun publishCredentials(service: XposedService) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            updateFacts { it.copy(detail = "用户解锁后初始化系统桥接") }
            return
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        synchronized(lock) {
            if (framework !== service || service.apiVersion < 102) return
            revokeLeases()
            proofKey = key
            updateFacts { it.copy(ipc = BridgeIpcState.WAITING, session = BridgeSessionState.NONE,
                detail = "等待目标进程的受校验读取") }
        }
        val stored = service.getRemotePreferences(BridgeWire.PREFERENCES_GROUP).edit()
            .putString(BridgeWire.HOST_EPOCH, hostEpoch).putString(BridgeWire.PROOF_KEY, key).commit()
        if (!stored) {
            synchronized(lock) { proofKey = null; revokeLeases() }
            updateFacts { it.copy(ipc = BridgeIpcState.DISCONNECTED, detail = "框架凭据发布失败") }
        }
    }

    private fun publishFusionPreferences(
        snapshot: FusionStateSnapshot? = null,
        service: XposedService? = null,
    ) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            return
        }
        val activeService = service ?: synchronized(lock) { framework } ?: return
        if (activeService.apiVersion < 102) return
        val current = snapshot ?: run {
            val state = runtime.state.value
            FusionStateSnapshot(
                destination = state.settings.fusionSettingsDestination,
                activeDeviceId = state.device?.deviceId?.value,
                activeClassicAddress = state.device?.classicAddress,
                targetDeviceId = state.connectionTarget?.takeIf { it.profile != null }?.deviceId?.value,
                targetClassicAddress = state.connectionTarget?.takeIf { it.profile != null }?.classicAddress,
                candidateAddresses = state.candidates.filter { it.profile != null }
                    .map { it.deviceId.value to it.classicAddress },
                bondedAddresses = state.bondedDevices.filter { it.profile != null }
                    .map { it.deviceId.value to it.classicAddress },
            )
        }
        val mappingJson = buildDeviceAddressMapping(current)
        val stored = runCatching {
            activeService.getRemotePreferences(BridgeWire.PREFERENCES_GROUP).edit()
                .putString(BridgeWire.FUSION_SETTINGS_DESTINATION, current.destination.name)
                .putString(BridgeWire.FUSION_DEVICE_MAPPING, mappingJson)
                .commit()
        }.getOrDefault(false)
        if (!stored) {
            android.util.Log.w(TAG, "RemotePreferences commit returned false for fusion preferences")
        }
    }

    private fun buildDeviceAddressMapping(snapshot: FusionStateSnapshot): String {
        val mapping = mutableMapOf<String, String>()
        fun register(rawAddress: String?, canonicalRaw: String?) {
            val key = NativeHeadsetSnapshot.normalizeBluetoothAddress(rawAddress) ?: return
            val canonical = NativeHeadsetSnapshot.normalizeBluetoothAddress(canonicalRaw) ?: return
            if (!mapping.containsKey(key)) {
                mapping[key] = canonical
            }
        }

        snapshot.activeDeviceId?.let { canonical ->
            register(canonical, canonical)
            snapshot.activeClassicAddress?.let { register(it, canonical) }
        }

        snapshot.targetDeviceId?.let { canonical ->
            register(canonical, canonical)
            snapshot.targetClassicAddress?.let { register(it, canonical) }
        }

        snapshot.candidateAddresses.forEach { (deviceId, classicAddress) ->
            register(deviceId, deviceId)
            classicAddress?.let { register(it, deviceId) }
        }

        snapshot.bondedAddresses.forEach { (deviceId, classicAddress) ->
            register(deviceId, deviceId)
            classicAddress?.let { register(it, deviceId) }
        }

        val json = JSONObject()
        mapping.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    private data class FusionStateSnapshot(
        val destination: FusionSettingsDestination,
        val activeDeviceId: String?,
        val activeClassicAddress: String?,
        val targetDeviceId: String?,
        val targetClassicAddress: String?,
        val candidateAddresses: List<Pair<String, String?>>,
        val bondedAddresses: List<Pair<String, String?>>,
    )

    private fun frameworkDied(service: XposedService) {
        synchronized(lock) {
            if (framework !== service) return
            framework = null
            proofKey = null
            revokeLeases()
        }
        updateFacts { it.copy(ipc = BridgeIpcState.DISCONNECTED, session = BridgeSessionState.NONE,
            detail = "框架服务已断开，普通客户端可继续使用") }
    }

    internal fun open(proof: Bundle, client: IBinder, uid: Int, pid: Int): Bundle {
        try {
            val (target, key) = synchronized(lock) {
                val key = proofKey ?: throw SecurityException("框架认证通道未就绪")
                requireIdentity(proof, key, uid, pid) to key
            }
            // PackageManager crosses Binder. Authenticate the proof before querying it, and never
            // block other readers while resolving package ownership or version information.
            if (context.packageManager.getPackageUid(target.packageName, 0) != uid) {
                throw SecurityException("读取端 UID 不匹配")
            }
            val info = context.packageManager.getPackageInfo(target.packageName, 0)
            return synchronized(lock) {
                if (proofKey != key) throw SecurityException("框架凭据已经更换")
                val processName = requireNotNull(proof.getString(BridgeWire.PROCESS))
                val endpointKey = "${target.packageName}@$processName"
                @Suppress("DEPRECATION")
                val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val resolution = requireNotNull(proof.getString(BridgeWire.RESOLUTION))
                if (resolution != BridgeWire.RESOLUTION_MATCHED) {
                    if (leases.values.any { it.target == target && it.processName == processName }) return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_REPORT)
                    updateFacts { it.copy(
                        targetPackage = target.packageName, targetProcess = processName,
                        componentVersion = "${info.versionName ?: "未知"} ($version)",
                        componentMatch = "${target.componentMatch} · $resolution",
                        ipc = if (leases.isEmpty()) BridgeIpcState.TARGET_INCOMPATIBLE else BridgeIpcState.AUTHENTICATED,
                        detail = "目标模块已报告组件入口不可用：$resolution",
                        endpoints = it.endpoints + (endpointKey to SystemEndpointDiagnostics(target.packageName, processName,
                            "${info.versionName ?: "未知"} ($version)", "${target.componentMatch} · $resolution", BridgeIpcState.TARGET_INCOMPATIBLE)),
                    ) }
                    return@synchronized BridgeWire.response(BridgeWire.STATUS_TARGET_INCOMPATIBLE)
                }
                leases.values.filter { it.uid == uid && it.pid == pid }.toList().forEach { removeLease(it.id) }
                if (leases.size >= 12) throw SecurityException("桥接读取端数量已达上限")
                val id = UUID.randomUUID().toString()
                val death = IBinder.DeathRecipient { readerDied(id) }
                client.linkToDeath(death, 0)
                leases[id] = Lease(id, uid, pid, target, processName, client, death, SystemClock.elapsedRealtime())
                updateFacts { it.copy(
                    targetPackage = target.packageName, targetProcess = processName,
                    componentVersion = "${info.versionName ?: "未知"} ($version)", componentMatch = target.componentMatch,
                    ipc = BridgeIpcState.AUTHENTICATED, detail = "目标模块已通过身份校验；尚未读取设备状态",
                    endpoints = it.endpoints + (endpointKey to SystemEndpointDiagnostics(target.packageName, processName,
                        "${info.versionName ?: "未知"} ($version)", target.componentMatch, BridgeIpcState.AUTHENTICATED)),
                ) }
                BridgeWire.response(BridgeWire.STATUS_OK).apply {
                    putString(BridgeWire.LEASE_ID, id)
                    putString(BridgeWire.HOST_EPOCH, hostEpoch)
                }
            }
        } catch (_: Exception) {
            // An untrusted caller cannot publish target/component or device/session facts.
            updateFacts { if (it.ipc == BridgeIpcState.AUTHENTICATED) it
                else it.copy(ipc = BridgeIpcState.REJECTED, detail = "桥接请求未通过身份校验") }
            throw SecurityException("桥接请求未通过身份校验")
        }
    }

    private fun requireIdentity(proof: Bundle, key: String, uid: Int, pid: Int): BridgeTarget {
        val processName = proof.getString(BridgeWire.PROCESS)
        val target = BridgeTarget.resolve(proof.getString(BridgeWire.PACKAGE), processName)
            ?: throw SecurityException("目标包或运行进程不在模块范围")
        if (proof.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION || proof.getString(BridgeWire.HOST_EPOCH) != hostEpoch ||
            proof.getString(BridgeWire.COMPONENT_MATCH_KEY) != target.componentMatch ||
            proof.getInt(BridgeWire.UID, -1) != uid || proof.getInt(BridgeWire.PID, -1) != pid || pid <= 0) {
            throw SecurityException("读取端身份不匹配")
        }
        val signature = BridgeWire.decodeSignature(proof.getString(BridgeWire.SIGNATURE)) ?: throw SecurityException("缺少有效证明")
        val resolution = proof.getString(BridgeWire.RESOLUTION)?.takeIf { it in BridgeWire.RESOLUTIONS }
            ?: throw SecurityException("缺少有效组件解析结果")
        val expected = BridgeWire.signature(key, hostEpoch, requireNotNull(processName), uid, pid, resolution, target)
        if (!MessageDigest.isEqual(signature, expected)) throw SecurityException("模块证明不匹配")
        return target
    }

    internal fun currentSession(leaseId: String, uid: Int, pid: Int): Bundle {
        synchronized(lock) {
            authenticate(leaseId, uid, pid)
            val state = runtime.state.value
            val connection = state.connection as? ConnectionState.Connected
            val device = state.device
            if (connection == null || device == null || device.deviceId != connection.deviceId || device.sessionId != connection.sessionId) {
                updateFacts { it.copy(session = BridgeSessionState.NONE, detail = "桥接已认证，当前没有就绪设备会话") }
                return BridgeWire.response(BridgeWire.STATUS_NO_SESSION)
            }
            return BridgeWire.response(BridgeWire.STATUS_OK).apply {
                putString(BridgeWire.HOST_EPOCH, hostEpoch)
                putString(BridgeWire.DEVICE_ID, connection.deviceId.value)
                putLong(BridgeWire.SESSION_ID, connection.sessionId)
            }
        }
    }

    internal fun read(request: Bundle, uid: Int, pid: Int): Bundle {
        synchronized(lock) {
            val lease = authenticate(request.getString(BridgeWire.LEASE_ID) ?: "", uid, pid)
            if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) return BridgeWire.response(BridgeWire.STATUS_UNSUPPORTED_VERSION)
            if (request.getString(BridgeWire.HOST_EPOCH) != hostEpoch) return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
            val deviceId = request.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotEmpty() && it.length <= 128 }
                ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            val result = runtime.readSession(SessionReference(DeviceId(deviceId), request.getLong(BridgeWire.SESSION_ID, -1)))
            return when (result) {
                is SessionSnapshotResult.Available -> {
                    val sequence = reads.incrementAndGet()
                    val reference = SessionReference(result.device.deviceId, result.device.sessionId)
                    lease.awaiting = PendingRead(reference, sequence)
                    if (lease.acknowledged != reference) {
                        updateFacts { it.copy(session = BridgeSessionState.WAITING_CONFIRMATION,
                            detail = "已生成当前会话快照，等待目标进程确认收到") }
                    }
                    BridgeWire.response(BridgeWire.STATUS_OK).apply {
                        putBundle(BridgeWire.SNAPSHOT, result.device.toBridgeSnapshot(hostEpoch, sequence).apply {
                            putBundle(BridgePresentationWire.PRESENTATION, result.presentation.toBridgePresentation())
                        })
                    }
                }
                is SessionSnapshotResult.Failure -> {
                    lease.awaiting = null
                    updateFacts { it.copy(session = BridgeSessionState.STALE, detail = "读取引用已失效，等待当前会话") }
                    BridgeWire.response(BridgeWire.STATUS_STALE_SESSION, result.error.message)
                }
            }
        }
    }

    internal fun acknowledge(receipt: Bundle, uid: Int, pid: Int): Bundle = synchronized(lock) {
        val lease = authenticate(receipt.getString(BridgeWire.LEASE_ID) ?: "", uid, pid)
        val pending = lease.awaiting ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_NO_PENDING_READ)
        if (receipt.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION || receipt.getString(BridgeWire.HOST_EPOCH) != hostEpoch ||
            receipt.getString(BridgeWire.DEVICE_ID) != pending.reference.deviceId.value ||
            receipt.getLong(BridgeWire.SESSION_ID, -1) != pending.reference.sessionId ||
            receipt.getLong(BridgeWire.READ_SEQUENCE, -1) != pending.sequence) {
            return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_RECEIPT)
        }
        lease.awaiting = null
        if (runtime.readSession(pending.reference) !is SessionSnapshotResult.Available) {
            updateFacts { it.copy(session = BridgeSessionState.STALE, detail = "目标确认的会话已经结束") }
            return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
        }
        lease.acknowledged = pending.reference
        updateFacts { it.copy(session = BridgeSessionState.READY, detail = "目标进程已确认收到当前会话快照") }
        BridgeWire.response(BridgeWire.STATUS_OK)
    }

    internal fun close(leaseId: String, uid: Int, pid: Int) {
        synchronized(lock) {
            authenticate(leaseId, uid, pid)
            readerDied(leaseId)
        }
    }

    internal fun watch(request: Bundle, observer: IControlObserver, uid: Int, pid: Int): Bundle = synchronized(lock) {
        val lease = authenticateRequest(request, uid, pid)
        lease.observer = observer
        BridgeWire.response(BridgeWire.STATUS_OK)
    }

    internal fun claimPrompt(request: Bundle, uid: Int, pid: Int): Bundle {
        val reference = synchronized(lock) {
            val lease = authenticateRequest(request, uid, pid)
            val mode = runtime.state.value.settings.islandMode
            if ((mode == IslandMode.MODULE && lease.target != BridgeTarget.BLUETOOTH) ||
                (mode == IslandMode.OFFICIAL && lease.target != BridgeTarget.XIAOMI_BLUETOOTH) || mode == IslandMode.OFF) {
                return BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "该系统入口不拥有当前提示策略")
            }
            request.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        }
        val id = request.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.length in 1..128 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val claimed = runBlocking { runtime.claimConnectionPrompt(reference, id) }
        return BridgeWire.response(if (claimed) BridgeWire.STATUS_OK else "STALE_PROMPT")
    }

    internal fun setNoise(request: Bundle, uid: Int, pid: Int): Bundle {
        val reference = synchronized(lock) {
            authenticateRequest(request, uid, pid)
            request.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        }
        val mode = request.getString(BridgePresentationWire.NOISE_MODE)?.takeIf { it.length in 1..64 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        // Validation and enqueue share the authority's lifecycle lock. A stale notification cannot
        // write to a subsequent connection, even if the device address is the same.
        return when (val result = runBlocking { runtime.set(reference, DeviceSetting.NOISE_MODE, FieldValue.Text(mode)) }) {
            OperationResult.Success -> BridgeWire.response(BridgeWire.STATUS_OK)
            is OperationResult.Failure -> BridgeWire.response(result.error.code.name, result.error.message)
        }
    }

    private var reportedReference: SessionReference? = null

    internal fun reportComponent(report: Bundle, uid: Int, pid: Int): Bundle = synchronized(lock) {
        val lease = authenticateRequest(report, uid, pid)
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val allowed = when (lease.target) {
            BridgeTarget.MILINK -> setOf(BridgePresentationWire.FUSION_CENTER)
            else -> setOf(BridgePresentationWire.SYSTEM_HEADSET, BridgePresentationWire.OFFICIAL_CONNECTION)
        }
        if (feature !in allowed) throw SecurityException("系统入口不能代其他组件报告状态")
        val status = report.getString(BridgeWire.STATUS)?.takeIf { it in BridgePresentationWire.COMPONENT_STATUSES }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val detail = report.getString(BridgePresentationWire.DETAIL)?.takeIf { it.length <= 512 }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val endpointKey = "${lease.target.packageName}@${lease.processName}"
        updateFacts { current ->
            val endpoint = current.endpoints[endpointKey] ?: return@updateFacts current
            current.copy(endpoints = current.endpoints + (endpointKey to endpoint.copy(features = endpoint.features + (feature to "$status · $detail"))))
        }
        BridgeWire.response(BridgeWire.STATUS_OK)
    }

    internal fun reportPresentation(report: Bundle, uid: Int, pid: Int): Bundle = synchronized(lock) {
        authenticateRequest(report, uid, pid)
        val reference = report.reference() ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        if (runtime.readSession(reference) !is SessionSnapshotResult.Available) {
            return@synchronized BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
        }
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val status = report.getString(BridgeWire.STATUS)?.takeIf { it in BridgePresentationWire.STATUSES }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val detail = report.getString(BridgePresentationWire.DETAIL)?.takeIf { it.length <= 512 }
            ?: return@synchronized BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        if (reportedReference != reference) {
            reportedReference = reference
            updateFacts { it.copy(presentation = emptyMap()) }
        }
        updateFacts { it.copy(presentation = it.presentation + (feature to "$status · $detail")) }
        BridgeWire.response(BridgeWire.STATUS_OK)
    }

    private fun authenticateRequest(request: Bundle, uid: Int, pid: Int): Lease {
        val lease = authenticate(request.getString(BridgeWire.LEASE_ID) ?: "", uid, pid)
        if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION || request.getString(BridgeWire.HOST_EPOCH) != hostEpoch) {
            throw SecurityException("展示请求版本或宿主代际不匹配")
        }
        return lease
    }

    private fun Bundle.reference(): SessionReference? {
        val id = getString(BridgeWire.DEVICE_ID)?.takeIf { it.length in 1..128 } ?: return null
        val sessionId = getLong(BridgeWire.SESSION_ID, -1).takeIf { it > 0 } ?: return null
        return SessionReference(DeviceId(id), sessionId)
    }

    private fun notifyReaders() {
        // Never invoke a remote Binder while holding the lease lock.
        val targets = synchronized(lock) { leases.values.mapNotNull { lease -> lease.observer?.let { lease.id to it } } }
        targets.forEach { (leaseId, observer) ->
            runCatching { observer.onChanged(Bundle().apply {
                putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
                putString(BridgeWire.HOST_EPOCH, hostEpoch)
                putString(BridgeWire.LEASE_ID, leaseId)
            }) }
        }
    }

    internal fun endpointUnbound() = synchronized(lock) {
        if (leases.isEmpty()) return@synchronized
        revokeLeases()
        updateFacts { it.copy(ipc = BridgeIpcState.DISCONNECTED, session = BridgeSessionState.NONE,
            detail = "目标进程读取通道已关闭") }
    }

    private fun authenticate(leaseId: String, uid: Int, pid: Int): Lease = synchronized(lock) {
        if (proofKey == null || framework == null) throw SecurityException("框架认证通道已结束")
        val lease = leases[leaseId] ?: throw SecurityException("读取凭据已失效")
        if (lease.uid != uid || lease.pid != pid) throw SecurityException("读取凭据与调用者不匹配")
        if (SystemClock.elapsedRealtime() - lease.touchedAt > 120_000) {
            readerDied(leaseId)
            throw SecurityException("读取凭据已过期")
        }
        lease.touchedAt = SystemClock.elapsedRealtime()
        lease
    }

    private fun readerDied(id: String) = synchronized(lock) {
        if (!removeLease(id)) return@synchronized
        if (leases.isEmpty()) updateFacts { it.copy(ipc = BridgeIpcState.DISCONNECTED, session = BridgeSessionState.NONE,
            detail = "目标进程读取通道已关闭") }
    }

    private fun removeLease(id: String): Boolean = leases.remove(id)?.let {
        runCatching { it.client.unlinkToDeath(it.death, 0) }
        val key = "${it.target.packageName}@${it.processName}"
        updateFacts { current ->
            val endpoint = current.endpoints[key] ?: return@updateFacts current
            current.copy(endpoints = current.endpoints + (key to endpoint.copy(ipc = BridgeIpcState.DISCONNECTED)))
        }
        true
    } ?: false

    private fun revokeLeases() { leases.keys.toList().forEach(::removeLease) }

    private fun updateFacts(change: (SystemBridgeDiagnostics) -> SystemBridgeDiagnostics) {
        val changed = synchronized(lock) { val next = change(facts); (next != facts).also { facts = next } }
        if (changed) onChanged()
    }

    private companion object {
        const val TAG = "ControlBridgeHost"
    }
}
