package io.github.dohex.openrose.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostConnection
import io.github.dohex.openrose.androidintegration.HostFrame
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostLinkException
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.LinkEnd
import io.github.dohex.openrose.androidintegration.LinkOpenResult
import io.github.dohex.openrose.androidintegration.ModuleHostLink
import io.github.dohex.openrose.androidintegration.OwnerFailureStage
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.Wire
import io.github.dohex.openrose.androidintegration.wire.WireFormatException
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Binder binding from the application to the module host in `com.android.bluetooth`. Discovery binds an
 * existing host service for [BridgeWire.ACTION_CONTROL_HOST]; authorisation is a separate `open` that the
 * host decides from this app's verified uid. Every failure names the stage it happened at.
 */
class ModuleHostBinderLink(
    context: Context,
    private val bindTimeoutMillis: Long = 5_000,
) : ModuleHostLink {
    private val context = context.applicationContext ?: context

    override suspend fun open(): LinkOpenResult {
        val intent = Intent(BridgeWire.ACTION_CONTROL_HOST)
            .setComponent(ComponentName(BridgeWire.TARGET_PACKAGE, BridgeWire.HOST_SERVICE_CLASS))
        val ended = CompletableDeferred<LinkEnd>()
        val connected = CompletableDeferred<IBinder?>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                // The binding is gone; only a Binder death notification proves the process ended.
                ended.complete(LinkEnd(processDied = false, reason = "宿主服务绑定已断开"))
            }
            override fun onNullBinding(name: ComponentName) {
                connected.complete(null)
            }
            override fun onBindingDied(name: ComponentName) {
                ended.complete(LinkEnd(processDied = false, reason = "宿主服务绑定已失效"))
                connected.complete(null)
            }
        }
        val bound = try {
            context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
        } catch (failure: SecurityException) {
            context.unbindService(binding)
            return LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "系统不允许绑定宿主服务：${failure.message}")
        }
        if (!bound) {
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(
                OwnerFailureStage.DISCOVERY,
                "无法绑定模块宿主服务（模块未启用、作用域未包含 ${BridgeWire.TARGET_PACKAGE}，或服务不可达）",
            )
        }
        val binder = withTimeoutOrNull(bindTimeoutMillis) { connected.await() }
        if (binder == null) {
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "宿主没有提供控制端点")
        }
        val bridge = IControlBridge.Stub.asInterface(binder)
        val token = Binder()
        val opened = try {
            withContext(Dispatchers.IO) { bridge.open(BridgeBundles.hello(null, null), token) }
        } catch (failure: Exception) {
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "与宿主通信失败：${failure.javaClass.simpleName}")
        }
        val status = opened.getString(BridgeWire.STATUS)
        if (status == BridgeWire.STATUS_UNAUTHORIZED) {
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, opened.getString(BridgeWire.MESSAGE) ?: "宿主拒绝了此调用者")
        }
        if (status == BridgeWire.STATUS_UNAVAILABLE) {
            // A reloaded or stopped host instance still answers on its old Binder; rebind to find the new one.
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, opened.getString(BridgeWire.MESSAGE) ?: "宿主暂不可用，等待重新发现")
        }
        val result = try {
            if (status != BridgeWire.STATUS_OK) {
                null
            } else {
                HostWire.decodeOpenResult(parseWire(opened.getString(BridgeWire.PAYLOAD).orEmpty()))
            }
        } catch (_: WireFormatException) {
            null
        }
        if (result !is HostOpenResult.Opened) {
            runCatching { context.unbindService(binding) }
            val reason = (result as? HostOpenResult.Refused)?.message ?: "宿主返回了无法识别的授权结果（$status）"
            return LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, reason)
        }
        val death = IBinder.DeathRecipient { ended.complete(LinkEnd(processDied = true, reason = "宿主进程已结束")) }
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            runCatching { context.unbindService(binding) }
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "宿主进程已不存在")
        }
        return LinkOpenResult.Open(BinderConnection(context, bridge, binder, token, binding, death, result.lease, result.status, ended))
    }

    private class BinderConnection(
        private val context: Context,
        private val bridge: IControlBridge,
        private val binder: IBinder,
        @Suppress("unused") private val token: IBinder,
        private val binding: ServiceConnection,
        private val death: IBinder.DeathRecipient,
        private val lease: HostLease,
        override val initialStatus: HostStatus,
        override val ended: Deferred<LinkEnd>,
    ) : HostConnection {
        override val incarnation: String get() = lease.incarnation

        private fun request(payload: Wire? = null): Bundle = Bundle().apply {
            putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
            putString(BridgeWire.LEASE_ID, lease.id)
            payload?.let { putString(BridgeWire.PAYLOAD, it.render()) }
        }

        private suspend fun <T> call(block: () -> Bundle, decode: (Wire) -> T): T {
            val reply = try {
                withContext(Dispatchers.IO) { block() }
            } catch (failure: DeadObjectException) {
                throw HostLinkException("宿主进程已结束", failure)
            } catch (failure: RemoteException) {
                throw HostLinkException("与宿主通信失败", failure)
            } catch (failure: SecurityException) {
                throw HostLinkException("宿主拒绝了请求：${failure.message}", failure)
            }
            if (reply.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                throw HostLinkException("宿主返回 ${reply.getString(BridgeWire.STATUS)}")
            }
            return try {
                decode(parseWire(reply.getString(BridgeWire.PAYLOAD).orEmpty()))
            } catch (failure: WireFormatException) {
                throw HostLinkException("宿主返回了无法识别的内容", failure)
            }
        }

        override suspend fun status(): HostStatus = call({ bridge.ownerStatus(request()) }, HostWire::decodeStatus)

        override suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult = call(
            { bridge.execute(request(HostWire.encodeCall(generation, command))) },
            HostWire::decodeCallResult,
        )

        override suspend fun release(request: SwitchRequest): ReleaseResult = call(
            { bridge.release(request(HostWire.encode(request))) },
            HostWire::decodeReleaseResult,
        )

        override suspend fun acquire(request: SwitchRequest, settings: HostSettings): AcquireResult = call(
            { bridge.acquire(request(HostWire.encodeAcquire(request, settings))) },
            HostWire::decodeAcquireResult,
        )

        override fun frames(generation: OwnerGeneration): Flow<HostFrame> = callbackFlow {
            val receiver = object : IControlFrames.Stub() {
                override fun onFrame(frame: Bundle) {
                    // A frame that cannot be decoded ends the stream rather than showing unverifiable state.
                    val decoded = runCatching { HostWire.decodeFrame(parseWire(frame.getString(BridgeWire.PAYLOAD).orEmpty())) }.getOrNull()
                    if (decoded == null) this@callbackFlow.close() else trySend(decoded)
                }

                override fun onEnded(end: Bundle) {
                    this@callbackFlow.close()
                }
            }
            val reply = try {
                withContext(Dispatchers.IO) { bridge.watchFrames(request(HostWire.encode(generation)), receiver) }
            } catch (failure: Exception) {
                this@callbackFlow.close(HostLinkException("无法订阅宿主状态", failure))
                null
            }
            if (reply != null && reply.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                this@callbackFlow.close(HostLinkException("宿主拒绝订阅状态"))
            }
            awaitClose()
        }

        override fun close() {
            runCatching { bridge.close(lease.id) }
            runCatching { binder.unlinkToDeath(death, 0) }
            runCatching { context.unbindService(binding) }
        }
    }
}
