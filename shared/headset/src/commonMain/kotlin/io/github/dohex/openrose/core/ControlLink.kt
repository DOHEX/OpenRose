package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.transportOperation
import kotlinx.collections.immutable.mutate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Owns connection attempts, including every native link opened during initialization recovery. */
internal class ControlLink(
    private val state: MutableStateFlow<ControlState>,
    private val transport: ControlTransport?,
    private val scope: CoroutineScope,
    private val timing: SessionTiming,
    private val settings: SettingQueue,
    sessionIdBase: Long,
    private val timeSource: TimeSource,
) {
    internal val lifecycle = Mutex()
    private var generation = sessionIdBase

    private val connections = OwnedConnections()
    private var released = false
    internal var active: ConnectionAttempt? = null
        private set

    suspend fun connect(
        candidate: DeviceCandidate,
        cancelWithCaller: Boolean,
        isSuperseded: () -> Boolean,
        onStarted: () -> Unit,
        onAttemptFailed: () -> Unit,
    ): OperationResult {
        val deviceId = candidate.deviceId
        var ownsAttempt = false
        val attempt = lifecycle.withLock {
            if (released) return OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, "控制所有者已释放"))
            if (isSuperseded()) return@withLock null
            active?.takeIf { it.candidate.deviceId == deviceId && state.value.connection !is ConnectionState.Disconnected }
                ?.let { return@withLock it }
            if (!clearActive()) return OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, "之前的控制连接尚未确认释放"))
            onStarted()
            val next = ConnectionAttempt(++generation, candidate, state.value.settings.forceBle)
            ownsAttempt = true
            state.update {
                it.copy(
                    connection = ConnectionState.Connecting(deviceId, next.id, ConnectionPhase.CONNECTING),
                    device = candidate.loadingDevice(next.id),
                    lastError = null,
                    transportSelection = null,
                    connectionRecovery = null,
                )
            }
            active = next
            next.task = scope.async(start = CoroutineStart.LAZY) { openAndRead(next) }
            next
        } ?: return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))
        val result = awaitAttempt(attempt, cancelWithCaller && ownsAttempt)
        // Revalidating an old add-flow success must not clear a newer audio-driven session.
        if (result !is OperationResult.Success) onAttemptFailed()
        if (cancelWithCaller && result is OperationResult.Success) {
            return lifecycle.withLock {
                if (active === attempt && state.value.readySession == SessionReference(deviceId, attempt.id)) {
                    result
                } else {
                    OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "本次连接已被替换"))
                }
            }
        }
        return result
    }

    suspend fun disconnect(retire: Boolean = false): Boolean = lifecycle.withLock {
        val closed = clearActive()
        if (retire && closed) released = true
        state.update { it.copy(connection = ConnectionState.Disconnected, device = null, lastError = null, connectionRecovery = null) }
        closed
    }

    internal fun ensureActive(attempt: ConnectionAttempt) {
        if (!attempt.matches(state.value) || attempt.progress.value.ended) throw LinkEnded()
    }

    /** Returns the notification revisions from immediately before the write, for fresh setting readbacks. */
    internal suspend fun writePaced(attempt: ConnectionAttempt, link: ControlConnection, bytes: ByteArray): ReadProgress {
        check(bytes.size <= link.maxWriteSize) { "完整协议报文超出传输写入容量" }
        val interval = (timing.writeIntervalMillis ?: requireNotNull(attempt.candidate.profile).spec.writeIntervalMillis).milliseconds
        if (attempt.hasWritten) delay(attempt.nextWriteAt?.let { -it.elapsedNow() } ?: interval)
        ensureActive(attempt)
        val baseline = attempt.progress.value
        attempt.hasWritten = true
        attempt.nextWriteAt = null
        withTimeout(timing.writeTimeoutMillis) { link.write(bytes.copyOf()) }
        val current = state.value
        if (attempt.candidate.profile == ModelProfile.I5_ZT && current.platform.hostPlatform.isDesktop && current.deviceHasSystemAudio) {
            attempt.nextWriteAt = timeSource.markNow() + interval
        }
        return baseline
    }

    private suspend fun awaitAttempt(attempt: ConnectionAttempt, cancelOnCallerExit: Boolean): OperationResult = try {
        attempt.task.await()
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            if (cancelOnCallerExit) {
                withContext(NonCancellable) {
                    lifecycle.withLock {
                        if (active === attempt && !attempt.ready) {
                            clearActive()
                            state.update { it.copy(lastError = null, connectionRecovery = null) }
                        }
                    }
                }
            }
            throw cancelled
        }
        // Disconnect cancels the owned operation, without cancelling another caller's scope.
        OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接会话已结束"))
    }

    private suspend fun openAndRead(attempt: ConnectionAttempt): OperationResult {
        fun fail(error: ControlError): OperationResult.Failure {
            attempt.update(state) { it.copy(connection = ConnectionState.Disconnected, lastError = error, transportSelection = null) }
            return OperationResult.Failure(error)
        }
        return try {
            transportOperation(onFailure = { fail(connectionFailure(it)) }) {
                val selected = attempt.selectTransport(requireNotNull(transport), timing.connectTimeoutMillis)
                attempt.update(state) { it.copy(transportSelection = selected) }
                Initialization(attempt, selected).run()
            }
        } catch (_: TimeoutCancellationException) {
            fail(ControlError(ControlErrorCode.TIMEOUT, "蓝牙连接或读取超时"))
        } catch (cancelled: CancellationException) {
            attempt.update(state) {
                it.copy(
                    connection = ConnectionState.Disconnected,
                    device = null,
                    lastError = ControlError(ControlErrorCode.DISCONNECTED, "控制会话已结束"),
                    transportSelection = null,
                )
            }
            throw cancelled
        } finally {
            if (!attempt.ready) {
                closeAttemptLink(attempt)
                withContext(NonCancellable) {
                    lifecycle.withLock {
                        if (active === attempt) {
                            active = null
                            state.update { it.copy(transportSelection = null) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun closeAttemptLink(attempt: ConnectionAttempt) = withContext(NonCancellable) {
        val (oldConnection, oldObserver) = lifecycle.withLock {
            attempt.linkEpoch.value += 1
            settings.end(attempt)
            val oldConnection = attempt.connection
            val oldObserver = attempt.observer
            attempt.connection = null
            attempt.observer = null
            oldConnection to oldObserver
        }
        oldObserver?.cancel()
        oldConnection?.let { connections.close(it) }
        oldObserver?.join()
    }

    /**
     * Caller holds lifecycle. Invalidate before closing so old callbacks cannot update a new session. Returns
     * whether every connection this session ever opened is confirmed closed: a close the platform refused is
     * remembered and tried again on the next call instead of being forgotten.
     */
    internal suspend fun clearActive(): Boolean {
        val previous = active
        active = null
        generation++
        previous?.let {
            it.linkEpoch.value += 1
        }
        state.update { it.copy(connection = ConnectionState.Disconnected, device = null, transportSelection = null) }
        previous?.let { settings.end(it) }
        previous?.task?.cancel()
        previous?.observer?.cancel()
        return connections.closeAll()
    }

    internal fun platformError(): ControlError? = platformError(state.value, transport)

    private inner class Initialization(private val attempt: ConnectionAttempt, private val selection: TransportSelection) {
        suspend fun run(): OperationResult {
            val epoch = beginLink()
            return try {
                openAndReadLink(epoch)
            } catch (cause: RecoverableTransportException) {
                if (!selection.canRetry(attempt, transport, cause)) throw cause
                prepareRecovery(cause, epoch)
                openAndReadLink(beginLink())
            }
        }

        private suspend fun beginLink(): Long = lifecycle.withLock {
            if (active !== attempt) throw CancellationException("Stale connection")
            attempt.linkEpoch.value += 1
            attempt.codec = createProtocolCodec(requireNotNull(attempt.candidate.profile))
            attempt.progress = MutableStateFlow(ReadProgress())
            attempt.hasWritten = false
            attempt.nextWriteAt = null
            attempt.initializationQueryIndex = null
            attempt.connection = null
            attempt.observer = null
            attempt.ready = false
            attempt.linkEpoch.value
        }

        private suspend fun openAndReadLink(epoch: Long): OperationResult {
            val candidate = attempt.candidate
            val progress = attempt.progress
            val opened = withTimeout(timing.connectTimeoutMillis) {
                connections.open {
                    requireNotNull(transport).connect(candidate.deviceId, selection.endpoint) { nextPhase ->
                        attempt.phase(state, epoch, nextPhase)
                    }
                }.also { attempt.connection = it }
            }
            lifecycle.withLock {
                if (active !== attempt || attempt.linkEpoch.value != epoch) throw CancellationException("Stale connection")
                attempt.connection = opened
            }
            observe(opened, epoch)
            attempt.phase(state, epoch, ConnectionPhase.SUBSCRIBING)
            withTimeout(timing.connectTimeoutMillis) { opened.subscribe() }
            throwProgressFailure(progress)
            attempt.phase(state, epoch, ConnectionPhase.INITIALIZING)
            readInitialQueries(epoch, opened)
            val device = state.value.device?.takeIf { it.sessionId == attempt.id }
            if (device == null || state.value.connection is ConnectionState.Disconnected) throw LinkEnded()
            if (!progress.value.meaningful) {
                val timeout = ControlError(ControlErrorCode.TIMEOUT, "没有收到可用的设备状态")
                attempt.update(state, epoch) {
                    it.copy(connection = ConnectionState.Disconnected, lastError = timeout, transportSelection = null)
                }
                return OperationResult.Failure(timeout)
            }
            commitReady(epoch)
            settings.start(this@ControlLink, attempt)
            return OperationResult.Success
        }

        private suspend fun commitReady(epoch: Long) {
            val candidate = attempt.candidate
            val progress = attempt.progress
            val committedReady = lifecycle.withLock {
                val live = active === attempt && attempt.linkEpoch.value == epoch && attempt.matches(state.value)
                if (!live || progress.value.ended) {
                    false
                } else {
                    val ready = ConnectionState.Connected(candidate.deviceId, attempt.id, requireNotNull(candidate.profile))
                    state.update { it.copy(connection = ready) }
                    attempt.ready = (state.value.connection as? ConnectionState.Connected)?.sessionId == attempt.id
                    attempt.ready
                }
            }
            if (!committedReady) throw progress.value.failure ?: LinkEnded()
        }

        private suspend fun writeInitialQuery(
            connection: ControlConnection,
            bytes: ByteArray,
            canRecoverQuery: Boolean,
        ) {
            try {
                transportOperation(onFailure = { cause ->
                    throw initialWriteFailure(cause, canRecoverQuery)
                }) { writePaced(attempt, connection, bytes) }
            } catch (timeout: TimeoutCancellationException) {
                throw initialWriteFailure(timeout, canRecoverQuery && currentCoroutineContext().isActive)
            }
        }

        private fun throwProgressFailure(progress: MutableStateFlow<ReadProgress>) {
            val current = progress.value
            if (current.ended) throw current.failure ?: LinkEnded()
        }

        private suspend fun prepareRecovery(
            failure: RecoverableTransportException,
            failedEpoch: Long,
        ) {
            val (oldConnection, oldObserver) = lifecycle.withLock {
                if (active !== attempt || attempt.linkEpoch.value != failedEpoch) throw CancellationException("Stale recovery")
                // Invalidate callbacks before cancellation so an old finally cannot clear the new link.
                attempt.linkEpoch.value += 1
                settings.end(attempt)
                attempt.settings = SettingQueue.Pending()
                val oldConnection = attempt.connection
                val oldObserver = attempt.observer
                attempt.connection = null
                attempt.observer = null
                oldConnection to oldObserver
            }
            withContext(NonCancellable) {
                oldObserver?.cancel()
                try {
                    check(oldConnection == null || connections.close(oldConnection)) { "旧控制连接尚未确认释放" }
                } finally {
                    oldObserver?.join()
                }
            }
            lifecycle.withLock {
                if (active !== attempt) throw CancellationException("Stale recovery")
                state.update { current ->
                    if (current.device?.sessionId != attempt.id || current.connection is ConnectionState.Disconnected) {
                        current
                    } else {
                        current.copy(
                            connection = ConnectionState.Connecting(attempt.candidate.deviceId, attempt.id, ConnectionPhase.CONNECTING),
                            device = attempt.candidate.loadingDevice(attempt.id),
                            lastError = null,
                            transportSelection = selection,
                            connectionRecovery = ConnectionRecovery(attempt.id, failure.reason),
                        )
                    }
                }
            }
        }

        private fun observe(link: ControlConnection, epoch: Long) {
            val progress = attempt.progress
            attempt.observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var failure: Throwable? = null
                var error = ControlError(ControlErrorCode.DISCONNECTED, "设备已断开")
                try {
                    transportOperation(onFailure = { cause ->
                        failure = cause
                        error = when (cause) {
                            is UnsupportedOperationException, is IllegalArgumentException ->
                                ControlError(ControlErrorCode.UNSUPPORTED, cause.message ?: "不支持该连接")

                            is SecurityException -> ControlError(ControlErrorCode.PERMISSION_REQUIRED, cause.message ?: "蓝牙权限不足")

                            else -> ControlError(ControlErrorCode.DISCONNECTED, cause.message ?: "设备已断开")
                        }
                    }) {
                        link.notifications.collect { bytes ->
                            lifecycle.withLock {
                                if (active === attempt && attempt.linkEpoch.value == epoch) attempt.receiveNotification(state, bytes)
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        lifecycle.withLock {
                            // Publish link termination under the same lock as the READY commit so a
                            // final notification cannot race into a dead Connected state.
                            progress.update { it.copy(ended = true, failure = failure) }
                            if (active === attempt && attempt.linkEpoch.value == epoch) {
                                val setupRecovery = failure is RecoverableTransportException && !attempt.ready &&
                                    selection.endpoint is TransportEndpoint.BleGatt
                                if (!setupRecovery) {
                                    settings.end(attempt)
                                    active = null
                                    attempt.update(state, epoch) {
                                        it.copy(
                                            connection = ConnectionState.Disconnected,
                                            device = null,
                                            lastError = error,
                                            transportSelection = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    connections.close(link)
                }
            }
        }

        private suspend fun readInitialQueries(epoch: Long, opened: ControlConnection) {
            val codec = attempt.codec
            val progress = attempt.progress
            val queries = sequence {
                yieldAll(codec.initQueries(opened.maxWriteSize))
                val firmware = state.value.device?.knownValue(DeviceField.FIRMWARE) as? FieldValue.Firmware
                yieldAll(codec.firmwareInitQueries(firmware))
            }
            for ((index, query) in queries.withIndex()) {
                attempt.initializationQueryIndex = index
                throwProgressFailure(progress)
                // A mode change queued during the inter-write gap gets this slot before the next initialization query.
                attempt.nextWriteAt?.let { delay(-it.elapsedNow()) }
                settings.runNextDuringInitialization(this@ControlLink, attempt)
                delay(query.delayBeforeMillis)
                throwProgressFailure(progress)
                val canRecoverQuery = selection.canRecoverFirstQuery(index, transport)
                writeInitialQuery(opened, query.bytes, canRecoverQuery)
                val waitForMeaningfulState = canRecoverQuery && query.expectedFields.isNotEmpty()
                val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                    progress.first {
                        it.ended || (it.keys.containsAll(query.responseKeys) && (!waitForMeaningfulState || it.meaningful))
                    }
                }
                if (observed?.ended == true) throw observed.failure ?: LinkEnded()
                if (observed == null) {
                    if (canRecoverQuery && !progress.value.meaningful) {
                        throw RecoverableTransportException(TransportRecoveryReason.NO_INITIAL_RESPONSE, "首条初始化读取没有收到有效设备状态")
                    }
                    val timeout = ControlError(ControlErrorCode.TIMEOUT, "${query.key} 部分字段读取超时")
                    attempt.update(state, epoch) { current ->
                        val old = requireNotNull(current.device)
                        val missing = query.expectedFields.filter { old.diagnostics.fields[it] is FieldReading.Loading }.toSet()
                        val errors = old.diagnostics.fieldErrors.mutate { values -> missing.forEach { values[it] = timeout } }
                        current.copy(
                            device = old.copy(diagnostics = old.diagnostics.copy(fieldErrors = errors)),
                            lastError = timeout,
                        )
                    }
                }
            }
        }
    }
}
