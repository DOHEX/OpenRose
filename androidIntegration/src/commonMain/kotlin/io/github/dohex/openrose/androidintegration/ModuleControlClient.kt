package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class ClientTiming(
    val retryMillis: Long = 3_000,
    val statusPollMillis: Long = 2_000,
    val releaseTimeoutMillis: Long = 10_000,
    val acquireTimeoutMillis: Long = 10_000,
    /** Must outlast the owner's own connect and confirmation deadlines. */
    val commandTimeoutMillis: Long = 45_000,
    /** How often an owned host is asked for its status; frames follow device changes, so their silence proves nothing. */
    val probeIntervalMillis: Long = 5_000,
    val probeTimeoutMillis: Long = 2_000,
    /** Consecutive unanswered probes after which the host counts as unresponsive. */
    val probeMisses: Int = 3,
)

/** What the application knows about the module owner right now; never more than the host has told it. */
sealed interface ModuleView {
    data object Stopped : ModuleView
    data object Connecting : ModuleView
    data class Unavailable(val stage: OwnerFailureStage, val reason: String) : ModuleView

    /** Authorised, but the host currently has no owner. */
    data class Inactive(val status: HostStatus.Inactive) : ModuleView

    /** The host owner holds the session; [frame] is its latest published state. */
    data class Active(val frame: HostFrame) : ModuleView

    /** The host process is reachable but stopped answering; its last reading is withdrawn rather than shown as current. */
    data object Unresponsive : ModuleView
}

internal const val UNRESPONSIVE_REASON = "模块宿主未响应"

/** What may be relied on to say the module owner no longer holds the session. */
sealed interface ReleaseProof {
    /** The owner confirmed this exact request after closing its connection. */
    data class Receipt(val receipt: ReleaseReceipt) : ReleaseProof

    /** The host process of [incarnation] ended, which closes every connection it owned. */
    data class ProcessEnded(val incarnation: String) : ReleaseProof

    /** A timeout, lost link or unknown remote state is not proof. */
    data class Unproven(val stage: OwnerFailureStage, val reason: String) : ReleaseProof
}

sealed interface AcquireOutcome {
    data class Acquired(val generation: OwnerGeneration, val holder: OwnerHolder) : AcquireOutcome
    data class Failed(val stage: OwnerFailureStage, val reason: String) : AcquireOutcome
}

/**
 * The application's client of the module host: keeps one authorised connection while module control
 * is selected, mirrors the host's published state and forwards commands. It never creates a
 * session and treats a command as done only when the host's owner reports the device-confirmed result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModuleControlClient(
    private val link: ModuleHostLink,
    private val scope: CoroutineScope,
    private val timing: ClientTiming = ClientTiming(),
) {
    private val mutableView = MutableStateFlow<ModuleView>(ModuleView.Stopped)
    val view: StateFlow<ModuleView> = mutableView.asStateFlow()

    private val lifecycle = Mutex()
    private var loop: Job? = null
    private val currentConnection = MutableStateFlow<HostConnection?>(null)

    /** The latest module owner this client has seen; used to name the owner a release must cover. */
    var lastObservedGeneration: OwnerGeneration? = null
        private set

    fun start() {
        if (loop?.isActive == true) return
        mutableView.value = ModuleView.Connecting
        loop = scope.launch { run() }
    }

    suspend fun stop() = lifecycle.withLock {
        loop?.cancelAndJoin()
        loop = null
        currentConnection.value?.close()
        currentConnection.value = null
        mutableView.value = ModuleView.Stopped
    }

    suspend fun execute(command: HostCommand): HostCallResult {
        val active = view.value as? ModuleView.Active
            ?: return HostCallResult.Rejected(HostRejection.NO_OWNER, "模块控制尚未就绪")
        val connection = currentConnection.value
            ?: return HostCallResult.Rejected(HostRejection.NO_OWNER, "模块控制尚未就绪")
        return try {
            withTimeout(timing.commandTimeoutMillis) { connection.execute(active.frame.generation, command) }
        } catch (e: TimeoutCancellationException) {
            HostCallResult.Operation(OperationResult.Failure(ControlError(ControlErrorCode.TIMEOUT, "模块宿主未在期限内确认结果")))
        } catch (e: HostLinkException) {
            HostCallResult.Operation(OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "与模块宿主的连接中断，结果未确认")))
        }
    }

    /**
     * Asks the module owner to release for [request]. Only the owner's receipt for exactly this request and
     * the exact owner generation the request names, or the observed end of the process that held that owner,
     * counts; everything else is [ReleaseProof.Unproven]. A refusal never makes the client name another
     * generation by itself: the owner the host reports is remembered in [lastObservedGeneration] and the
     * caller must ask again naming it.
     */
    suspend fun release(request: SwitchRequest): ReleaseProof = withConnection(
        { ReleaseProof.Unproven(it.stage, it.reason) },
    ) { connection ->
        val result = try {
            withTimeout(timing.releaseTimeoutMillis) { connection.release(request) }
        } catch (e: TimeoutCancellationException) {
            return@withConnection ReleaseProof.Unproven(OwnerFailureStage.RELEASE, "模块宿主未在期限内确认释放")
        } catch (e: HostLinkException) {
            return@withConnection lostLinkProof(connection, request, e.message)
        }
        when (result) {
            is ReleaseResult.Released -> receiptProof(connection, request, result.receipt)

            is ReleaseResult.Refused -> {
                (result.current as? HostStatus.Active)?.let { lastObservedGeneration = it.generation }
                ReleaseProof.Unproven(OwnerFailureStage.RELEASE, result.message)
            }
        }
    }

    private fun receiptProof(connection: HostConnection, request: SwitchRequest, receipt: ReleaseReceipt): ReleaseProof {
        val named = request.fromGeneration
        val coversNamedOwner = receipt.generation == named ||
            (receipt.predecessorEnded && receipt.generation == null && named != null && named.incarnation != receipt.incarnation)
        val matches = receipt.requestId == request.id && receipt.incarnation == connection.incarnation && coversNamedOwner
        return if (matches) {
            ReleaseProof.Receipt(receipt)
        } else {
            ReleaseProof.Unproven(OwnerFailureStage.RELEASE, "释放回执与本次切换请求或所有者代际不匹配")
        }
    }

    suspend fun acquire(request: SwitchRequest, settings: HostSettings): AcquireOutcome = withConnection(
        { AcquireOutcome.Failed(it.stage, it.reason) },
    ) { connection ->
        try {
            when (val result = withTimeout(timing.acquireTimeoutMillis) { connection.acquire(request, settings) }) {
                is AcquireResult.Acquired -> {
                    lastObservedGeneration = result.generation
                    AcquireOutcome.Acquired(result.generation, result.holder)
                }

                is AcquireResult.Refused -> AcquireOutcome.Failed(OwnerFailureStage.ACTIVATION, result.message)
            }
        } catch (e: TimeoutCancellationException) {
            AcquireOutcome.Failed(OwnerFailureStage.ACTIVATION, "模块宿主未在期限内响应")
        } catch (e: HostLinkException) {
            AcquireOutcome.Failed(OwnerFailureStage.CONNECTION, e.message ?: "与模块宿主的连接中断")
        }
    }

    private fun lostLinkProof(connection: HostConnection, request: SwitchRequest, detail: String?): ReleaseProof {
        val end = connection.ended.takeIf { it.isCompleted }?.getCompleted()
        val named = request.fromGeneration
        // The death of a process only proves the owners that process held, so it must be the one the request names.
        if (end != null && end.processDied && (named == null || named.incarnation == connection.incarnation)) {
            return ReleaseProof.ProcessEnded(connection.incarnation)
        }
        return ReleaseProof.Unproven(OwnerFailureStage.CONNECTION, detail ?: "与模块宿主失联，无法确认已释放")
    }

    private suspend fun <T : Any> withConnection(
        onFailed: (LinkOpenResult.Failed) -> T,
        block: suspend (HostConnection) -> T,
    ): T {
        currentConnection.value?.takeIf { !it.ended.isCompleted }?.let { return block(it) }
        return when (val opened = link.open()) {
            is LinkOpenResult.Open -> try {
                block(opened.connection)
            } finally {
                if (currentConnection.value !== opened.connection) opened.connection.close()
            }

            is LinkOpenResult.Failed -> onFailed(opened)
        }
    }

    private suspend fun run() {
        while (scope.isActive) {
            mutableView.value = ModuleView.Connecting
            when (val opened = link.open()) {
                is LinkOpenResult.Failed -> mutableView.value = ModuleView.Unavailable(opened.stage, opened.reason)

                is LinkOpenResult.Open -> {
                    val connection = opened.connection
                    currentConnection.value = connection
                    try {
                        observe(connection)
                    } finally {
                        currentConnection.value = null
                        connection.close()
                    }
                    if (scope.isActive) {
                        // A lost link must not leave the last battery or noise reading looking current.
                        mutableView.value = ModuleView.Unavailable(OwnerFailureStage.CONNECTION, "与模块宿主的连接已断开")
                    }
                }
            }
            delay(timing.retryMillis)
        }
    }

    private suspend fun observe(connection: HostConnection) {
        try {
            coroutineScope {
                launch {
                    connection.ended.await()
                    this@coroutineScope.cancel(CancellationException("host connection ended"))
                }
                var status = connection.initialStatus
                while (true) {
                    when (status) {
                        is HostStatus.Active -> {
                            lastObservedGeneration = status.generation
                            observeActive(connection, status.generation)
                            // The generation ended: nothing from it is current any more.
                            mutableView.value = ModuleView.Connecting
                        }

                        is HostStatus.Inactive -> {
                            mutableView.value = ModuleView.Inactive(status)
                            delay(timing.statusPollMillis)
                        }
                    }
                    status = connection.status()
                }
            }
        } catch (e: CancellationException) {
            if (scope.isActive && connection.ended.isCompleted) return
            throw e
        } catch (e: HostLinkException) {
            return
        }
    }

    /**
     * Mirrors the owner's frames until the generation ends while probing the host. A host that stops
     * answering [HostConnection.status] withdraws the reading; once it answers again the frames are
     * subscribed afresh, so nothing published before the stall is shown as current.
     */
    private suspend fun observeActive(connection: HostConnection, generation: OwnerGeneration) = coroutineScope {
        val mirroring = launch {
            connection.frames(generation).collect { frame ->
                if (frame.generation == generation) mutableView.value = ModuleView.Active(frame)
            }
        }
        val probing = launch {
            var misses = 0
            while (true) {
                delay(timing.probeIntervalMillis)
                val answered = try {
                    withTimeout(timing.probeTimeoutMillis) { connection.status() }
                    true
                } catch (e: TimeoutCancellationException) {
                    false
                }
                if (answered) {
                    misses = 0
                    if (mutableView.value == ModuleView.Unresponsive) {
                        mirroring.cancel()
                        return@launch
                    }
                } else if (++misses >= timing.probeMisses) {
                    mutableView.update { if (it is ModuleView.Active) ModuleView.Unresponsive else it }
                }
            }
        }
        mirroring.join()
        probing.cancel()
    }
}
