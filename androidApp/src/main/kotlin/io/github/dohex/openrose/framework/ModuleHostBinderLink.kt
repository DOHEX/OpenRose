package io.github.dohex.openrose.framework

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
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.LinkEnd
import io.github.dohex.openrose.androidintegration.LinkOpenResult
import io.github.dohex.openrose.androidintegration.ModuleHostLink
import io.github.dohex.openrose.androidintegration.OwnerFailureStage
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HandoffWire
import io.github.dohex.openrose.androidintegration.wire.HostFrameWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.Wire
import io.github.dohex.openrose.androidintegration.wire.WireFormatException
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlFrames
import io.github.dohex.openrose.bridge.circulationReply
import io.github.dohex.openrose.bridge.toBundle
import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationFailure
import io.github.dohex.openrose.circulation.CirculationReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

class ModuleHostBinderLink(
    context: Context,
    private val bindTimeoutMillis: Long = 5_000,
) : ModuleHostLink {
    private val context = context.applicationContext ?: context

    private val connection = AtomicReference<BinderConnection?>()

    suspend fun circulation(command: CirculationCommand): CirculationReply {
        val active = connection.get() ?: return CirculationFailure("模块控制尚未就绪")
        return active.circulation(command)
    }

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
        val released = AtomicBoolean(false)
        fun unbind() {
            if (released.compareAndSet(false, true)) runCatching { context.unbindService(binding) }
        }

        var handedOff = false
        try {
            val opened = try {
                if (context.bindService(intent, binding, Context.BIND_AUTO_CREATE)) {
                    awaitAuthorization(connected, ended, ::unbind)
                } else {
                    LinkOpenResult.Failed(
                        OwnerFailureStage.DISCOVERY,
                        "无法绑定模块宿主服务（模块未启用、作用域未包含 ${BridgeWire.TARGET_PACKAGE}，或服务不可达）",
                    )
                }
            } catch (failure: SecurityException) {
                LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "系统不允许绑定宿主服务：${failure.message}")
            }
            handedOff = opened is LinkOpenResult.Open
            return opened
        } finally {
            if (!handedOff) unbind()
        }
    }

    private suspend fun awaitAuthorization(
        connected: Deferred<IBinder?>,
        ended: CompletableDeferred<LinkEnd>,
        unbind: () -> Unit,
    ): LinkOpenResult {
        val binder = withTimeoutOrNull(bindTimeoutMillis) { connected.await() }
            ?: return LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "宿主没有提供控制端点")
        return withTimeoutOrNull(bindTimeoutMillis) {
            calls.run(onAbandoned = { (it as? LinkOpenResult.Open)?.connection?.close() }) {
                authorize(binder, ended, unbind)
            }
        } ?: LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "宿主未在期限内响应")
    }

    private fun authorize(binder: IBinder, ended: CompletableDeferred<LinkEnd>, unbind: () -> Unit): LinkOpenResult {
        val bridge = IControlBridge.Stub.asInterface(binder)
        val token = Binder()
        val opened = try {
            bridge.open(BridgeBundles.hello(null, null), token)
        } catch (failure: RemoteException) {
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "与宿主通信失败：${failure.javaClass.simpleName}")
        } catch (failure: SecurityException) {
            return LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, "宿主拒绝了此调用者：${failure.message}")
        } catch (reloading: IllegalStateException) {
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "模块宿主暂不可用：${reloading.message}")
        }
        val status = opened.getString(BridgeWire.STATUS)
        if (status == BridgeWire.STATUS_UNAUTHORIZED) {
            return LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, opened.getString(BridgeWire.MESSAGE) ?: "宿主拒绝了此调用者")
        }
        if (status == BridgeWire.STATUS_UNSUPPORTED_VERSION) {
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "应用与模块宿主版本不匹配，请重启系统集成作用域以加载当前模块")
        }
        if (status == BridgeWire.STATUS_UNAVAILABLE) {
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, opened.getString(BridgeWire.MESSAGE) ?: "宿主暂不可用，等待重新发现")
        }
        val result = try {
            if (status != BridgeWire.STATUS_OK) {
                null
            } else {
                HostIdentityWire.decodeOpenResult(parseWire(opened.getString(BridgeWire.PAYLOAD).orEmpty()))
            }
        } catch (_: WireFormatException) {
            null
        }
        if (result !is HostOpenResult.Opened) {
            val reason = (result as? HostOpenResult.Refused)?.message ?: "宿主返回了无法识别的授权结果（$status）"
            return LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, reason)
        }
        val death = IBinder.DeathRecipient { ended.complete(LinkEnd(processDied = true, reason = "宿主进程已结束")) }
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            return LinkOpenResult.Failed(OwnerFailureStage.CONNECTION, "宿主进程已不存在")
        }
        val active = BinderConnection(bridge, binder, token, unbind, death, result.lease, result.status, ended)
        connection.set(active)
        return LinkOpenResult.Open(active)
    }

    private inner class BinderConnection(
        private val bridge: IControlBridge,
        private val binder: IBinder,
        @Suppress("unused") private val token: IBinder,
        private val unbind: () -> Unit,
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

        @Suppress("TooGenericExceptionCaught")
        private suspend fun transact(block: () -> Bundle): Bundle {
            val failure = try {
                return calls.run(call = block)
            } catch (ended: DeadObjectException) {
                HostLinkException("宿主进程已结束", ended)
            } catch (remote: RemoteException) {
                HostLinkException("与宿主通信失败", remote)
            } catch (denied: SecurityException) {
                HostLinkException("宿主拒绝了请求：${denied.message}", denied)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (reloading: IllegalStateException) {
                HostLinkException("模块宿主暂不可用，等待重新发现", reloading)
            } catch (rejected: RuntimeException) {
                HostLinkException("与宿主通信失败：${rejected.message ?: rejected.javaClass.simpleName}", rejected)
            }
            throw failure
        }

        private suspend fun <T> call(block: () -> Bundle, decode: (Wire) -> T): T {
            val reply = transact(block)
            if (reply.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                throw HostLinkException("宿主返回 ${reply.getString(BridgeWire.STATUS)}")
            }
            return try {
                decode(parseWire(reply.getString(BridgeWire.PAYLOAD).orEmpty()))
            } catch (failure: WireFormatException) {
                throw HostLinkException("宿主返回了无法识别的内容", failure)
            }
        }

        suspend fun circulation(command: CirculationCommand): CirculationReply {
            val active = status() as? HostStatus.Active ?: return CirculationFailure("模块控制尚未就绪")
            val query = request().apply {
                putString(BridgeWire.HOST_EPOCH, active.generation.toString())
                putBundle(BridgeWire.PAYLOAD, command.toBundle())
            }
            val reply = calls.run { bridge.circulation(query) }
            val data = reply.getBundle(BridgeWire.PAYLOAD) ?: return CirculationFailure(reply.getString(BridgeWire.MESSAGE) ?: "互联服务不可用")
            return data.circulationReply(command)
        }

        override suspend fun status(): HostStatus = call({ bridge.ownerStatus(request()) }, HostIdentityWire::decodeStatus)

        override suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult = call(
            {
                bridge.execute(
                    request(HostIdentityWire.encode(generation)).apply {
                        putByteArray(BridgeWire.COMMAND, HostWire.encode(command))
                    },
                )
            },
            HostWire::decodeCallResult,
        )

        override suspend fun release(request: SwitchRequest): ReleaseResult = call(
            { bridge.release(request(HandoffWire.encode(request))) },
            HandoffWire::decodeReleaseResult,
        )

        override suspend fun acquire(request: SwitchRequest): AcquireResult = call(
            { bridge.acquire(request(HandoffWire.encode(request))) },
            HandoffWire::decodeAcquireResult,
        )

        override fun frames(generation: OwnerGeneration): Flow<HostFrame> = callbackFlow {
            val receiver = object : IControlFrames.Stub() {
                override fun onFrame(frame: Bundle) {
                    try {
                        trySend(HostFrameWire.decodeFrame(parseWire(frame.getString(BridgeWire.PAYLOAD).orEmpty())))
                    } catch (failure: WireFormatException) {
                        this@callbackFlow.close(HostLinkException("宿主返回了无法识别的状态帧", failure))
                    }
                }

                override fun onEnded(end: Bundle) {
                    this@callbackFlow.close()
                }
            }
            val reply = transact { bridge.watchFrames(request(HostIdentityWire.encode(generation)), receiver) }
            if (reply.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                throw HostLinkException("宿主拒绝订阅状态")
            }
            awaitClose()
        }

        override fun close() {
            connection.compareAndSet(this, null)
            closeDispatcher.dispatch(EmptyCoroutineContext) { runCatching { bridge.close(lease.id) } }
            runCatching { binder.unlinkToDeath(death, 0) }
            unbind()
        }
    }

    private companion object {
        val calls = BinderCalls(4)
        val closeDispatcher = Dispatchers.IO.limitedParallelism(2)
    }
}
