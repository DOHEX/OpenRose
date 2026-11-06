package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolCodec
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TerminalTransportException
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportFacts
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Owns connection attempts, including every native link opened during initialization recovery. */
internal class ControlLink(
    private val state: MutableStateFlow<ControlState>,
    private val transport: ControlTransport?,
    private val scope: CoroutineScope,
    private val timing: SessionTiming,
    private val settings: SettingQueue,
    sessionIdBase: Long,
) {
    internal val lifecycle = Mutex()
    private var generation = sessionIdBase

    /** Connections whose close the platform refused, retried on every release. */
    private val unclosed = MutableStateFlow<PersistentList<ControlConnection>>(persistentListOf())
    internal var active: Attempt? = null
        private set

    internal class Attempt(val id: Long, val candidate: DeviceCandidate, val forceBle: Boolean) {
        lateinit var task: Deferred<OperationResult>
        var connection: ControlConnection? = null
        var observer: Job? = null
        var codec: ProtocolCodec = createProtocolCodec(requireNotNull(candidate.profile))
        var progress = MutableStateFlow(ReadProgress())
        val linkEpoch = MutableStateFlow(0L)
        var selection: TransportSelection? = null
        var initializationQueryIndex: Int? = null
        val settings = SettingQueue.Pending()
        var hasWritten = false
        var recoveryUsed = false
        var ready = false
    }

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
            if (isSuperseded()) return@withLock null
            active?.takeIf { it.candidate.deviceId == deviceId && state.value.connection !is ConnectionState.Disconnected }
                ?.let { return@withLock it }
            clearActive()
            onStarted()
            val next = Attempt(++generation, candidate, state.value.settings.forceBle)
            ownsAttempt = true
            val profile = requireNotNull(candidate.profile)
            state.update {
                it.copy(
                    connection = ConnectionState.Connecting(deviceId, next.id, ConnectionPhase.CONNECTING),
                    device = DeviceState(
                        deviceId,
                        next.id,
                        profile,
                        deviceCapabilities(profile, null),
                        profile.supportedFields.associateWith { FieldReading.Loading }.toPersistentMap(),
                        classicAddress = candidate.classicAddress,
                    ),
                    lastError = null,
                    transportSelection = null,
                    connectionRecovery = null,
                )
            }
            active = next
            next.task = scope.async(start = CoroutineStart.LAZY) { openAndRead(next) }
            next
        } ?: return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))
        val result = try {
            attempt.task.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                if (cancelWithCaller && ownsAttempt) {
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
        // Revalidating an old add-flow success must not clear a newer audio-driven session.
        if (result !is OperationResult.Success) onAttemptFailed()
        if (cancelWithCaller && result is OperationResult.Success) {
            return lifecycle.withLock {
                val current = state.value.connection as? ConnectionState.Connected
                if (active === attempt && attempt.ready && current?.deviceId == deviceId &&
                    current.sessionId == attempt.id && current.phase == ConnectionPhase.READY
                ) {
                    result
                } else {
                    OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "本次连接已被替换"))
                }
            }
        }
        return result
    }

    suspend fun disconnect(): Boolean = lifecycle.withLock {
        val closed = clearActive()
        state.update { it.copy(connection = ConnectionState.Disconnected, device = null, lastError = null, connectionRecovery = null) }
        closed
    }

    internal fun ensureActive(attempt: Attempt) {
        if (state.value.device?.sessionId != attempt.id || state.value.connection is ConnectionState.Disconnected || attempt.progress.value.ended) throw LinkEnded()
    }

    internal suspend fun pace(attempt: Attempt) {
        if (attempt.hasWritten) delay(if (attempt.candidate.profile == ModelProfile.I5_ZT) timing.i5WriteIntervalMillis else timing.i7WriteIntervalMillis)
        attempt.hasWritten = true
    }

    internal suspend fun writePaced(attempt: Attempt, link: ControlConnection, bytes: ByteArray) {
        check(bytes.size <= link.maxWriteSize) { "完整协议报文超出传输写入容量" }
        pace(attempt)
        ensureActive(attempt)
        withTimeout(timing.queryTimeoutMillis) { link.write(bytes.copyOf()) }
    }

    private suspend fun openAndRead(attempt: Attempt): OperationResult {
        try {
            val selected = attempt.selection ?: selectTransport(attempt).also { attempt.selection = it }
            updateAttempt(attempt) { it.copy(transportSelection = selected) }
            while (true) {
                val epoch = beginLink(attempt)
                try {
                    return openAndReadLink(attempt, selected, epoch)
                } catch (cause: RecoverableTransportException) {
                    if (attempt.recoveryUsed || !canRetry(attempt, selected, cause)) throw cause
                    attempt.recoveryUsed = true
                    prepareRecovery(attempt, selected, cause, epoch)
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) {
                updateAttempt(attempt) {
                    it.copy(
                        connection = ConnectionState.Disconnected,
                        device = null,
                        lastError = ControlError(ControlErrorCode.DISCONNECTED, "控制会话已结束"),
                        transportSelection = null,
                    )
                }
                throw cancelled
            }
            val error = ControlError(ControlErrorCode.TIMEOUT, "蓝牙连接或读取超时")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error, transportSelection = null) }
            return OperationResult.Failure(error)
        } catch (cause: Exception) {
            val code = when {
                cause is UnsupportedOperationException || cause is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED
                cause is SecurityException -> ControlErrorCode.PERMISSION_REQUIRED
                cause is RecoverableTransportException && cause.cause is UnsupportedOperationException -> ControlErrorCode.UNSUPPORTED
                cause is RecoverableTransportException && cause.cause is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED
                cause is RecoverableTransportException && cause.cause is SecurityException -> ControlErrorCode.PERMISSION_REQUIRED
                cause is RecoverableTransportException && cause.reason == TransportRecoveryReason.NO_INITIAL_RESPONSE -> ControlErrorCode.TIMEOUT
                cause is RecoverableTransportException && cause.cause is TimeoutCancellationException -> ControlErrorCode.TIMEOUT
                else -> ControlErrorCode.DISCONNECTED
            }
            val error = ControlError(code, cause.message ?: "设备连接已结束")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error, transportSelection = null) }
            return OperationResult.Failure(error)
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

    private suspend fun beginLink(attempt: Attempt): Long = lifecycle.withLock {
        if (active !== attempt) throw CancellationException("Stale connection")
        attempt.linkEpoch.value += 1
        attempt.codec = createProtocolCodec(requireNotNull(attempt.candidate.profile))
        attempt.progress = MutableStateFlow(ReadProgress())
        attempt.hasWritten = false
        attempt.initializationQueryIndex = null
        attempt.connection = null
        attempt.observer = null
        attempt.ready = false
        attempt.linkEpoch.value
    }

    private suspend fun openAndReadLink(attempt: Attempt, selection: TransportSelection, epoch: Long): OperationResult {
        val candidate = attempt.candidate
        val profile = requireNotNull(candidate.profile)
        val codec = attempt.codec
        val progress = attempt.progress
        val opened = withTimeout(timing.connectTimeoutMillis) {
            requireNotNull(transport).connect(candidate.deviceId, selection.endpoint) { nextPhase ->
                phase(attempt, epoch, nextPhase)
            }.also { attempt.connection = it }
        }
        lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != epoch) throw CancellationException("Stale connection")
            attempt.connection = opened
        }
        val link = opened
        attempt.observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            var error = ControlError(ControlErrorCode.DISCONNECTED, "设备已断开")
            try {
                link.notifications.collect { bytes ->
                    if (attempt.linkEpoch.value != epoch) return@collect
                    val parsed = codec.feedNotification(bytes.copyOf())
                    val readings = parsed.flatMap { it.readings.entries }.associate { it.toPair() }
                    lifecycle.withLock {
                        updateLinkAttempt(attempt, epoch) { current ->
                            val old = requireNotNull(current.device)
                            val firmware = ((readings[DeviceField.FIRMWARE] ?: old.fields[DeviceField.FIRMWARE]) as? FieldReading.Known)?.value as? FieldValue.Firmware
                            current.copy(
                                device = old.copy(
                                    fields = old.fields.putAll(readings),
                                    capabilities = deviceCapabilities(profile, firmware),
                                    fieldErrors = readings.keys.fold(old.fieldErrors) { errors, field -> errors.remove(field) },
                                    rawNotifications = old.rawNotifications.appendRecent(listOf(bytes.hex())),
                                    unknownNotifications = old.unknownNotifications.appendRecent(parsed.filter { !it.accepted }.map { it.rawHex }),
                                    warnings = old.warnings.appendRecent(parsed.flatMap { it.warnings }),
                                ),
                            )
                        }
                    }
                    progress.update { previous ->
                        val keys = parsed.flatMap { it.responseKeys }.toSet()
                        val fields = parsed.flatMap { it.readings.keys }.toSet()
                        previous.copy(
                            keys = previous.keys.addAll(keys),
                            meaningful = previous.meaningful || parsed.any { it.accepted },
                            keyRevisions = previous.keyRevisions.mutate { revisions -> keys.forEach { revisions[it] = (revisions[it] ?: 0) + 1 } },
                            fieldRevisions = previous.fieldRevisions.mutate { revisions -> fields.forEach { revisions[it] = (revisions[it] ?: 0) + 1 } },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                failure = cause
                error = when (cause) {
                    is UnsupportedOperationException, is IllegalArgumentException -> ControlError(ControlErrorCode.UNSUPPORTED, cause.message ?: "不支持该连接")
                    is SecurityException -> ControlError(ControlErrorCode.PERMISSION_REQUIRED, cause.message ?: "蓝牙权限不足")
                    else -> ControlError(ControlErrorCode.DISCONNECTED, cause.message ?: "设备已断开")
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
                                updateLinkAttempt(attempt, epoch) {
                                    if (it.connection is ConnectionState.Disconnected) {
                                        it
                                    } else {
                                        it.copy(connection = ConnectionState.Disconnected, device = null, lastError = error, transportSelection = null)
                                    }
                                }
                            }
                        }
                    }
                }
                closeOrRemember(link)
            }
        }
        phase(attempt, epoch, ConnectionPhase.SUBSCRIBING)
        withTimeout(timing.connectTimeoutMillis) { opened.subscribe() }
        throwProgressFailure(progress)
        phase(attempt, epoch, ConnectionPhase.INITIALIZING)
        val queries = codec.initQueries(opened.maxWriteSize)
        for ((index, query) in queries.withIndex()) {
            attempt.initializationQueryIndex = index
            throwProgressFailure(progress)
            writeInitialQuery(attempt, selection, opened, query.bytes, index)
            val waitForMeaningfulState = index == 0 && selection.endpoint is TransportEndpoint.BleGatt &&
                requireNotNull(transport).supportsInitializationRecovery
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                progress.first {
                    it.ended || (it.keys.containsAll(query.responseKeys) && (!waitForMeaningfulState || it.meaningful))
                }
            }
            if (observed?.ended == true) throw observed.failure ?: LinkEnded()
            if (observed == null) {
                if (index == 0 && !progress.value.meaningful && selection.endpoint is TransportEndpoint.BleGatt &&
                    requireNotNull(transport).supportsInitializationRecovery
                ) {
                    throw RecoverableTransportException(TransportRecoveryReason.NO_INITIAL_RESPONSE, "首条初始化读取没有收到有效设备状态")
                }
                val timeout = ControlError(ControlErrorCode.TIMEOUT, "${query.key} 部分字段读取超时")
                updateLinkAttempt(attempt, epoch) { current ->
                    val old = requireNotNull(current.device)
                    val missing = query.expectedFields.filter { old.fields[it] is FieldReading.Loading }.toSet()
                    current.copy(device = old.copy(fieldErrors = old.fieldErrors.mutate { errors -> missing.forEach { errors[it] = timeout } }), lastError = timeout)
                }
            }
        }
        val device = state.value.device?.takeIf { it.sessionId == attempt.id }
        if (device == null || state.value.connection is ConnectionState.Disconnected) throw LinkEnded()
        if (!progress.value.meaningful) {
            val timeout = ControlError(ControlErrorCode.TIMEOUT, "没有收到可用的设备状态")
            updateLinkAttempt(attempt, epoch) { it.copy(connection = ConnectionState.Disconnected, lastError = timeout, transportSelection = null) }
            return OperationResult.Failure(timeout)
        }
        val committedReady = lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != epoch || progress.value.ended ||
                state.value.device?.sessionId != attempt.id || state.value.connection is ConnectionState.Disconnected
            ) {
                false
            } else {
                state.update { it.copy(connection = ConnectionState.Connected(candidate.deviceId, attempt.id, profile)) }
                attempt.ready = (state.value.connection as? ConnectionState.Connected)?.sessionId == attempt.id
                attempt.ready
            }
        }
        if (!committedReady) throw progress.value.failure ?: LinkEnded()
        settings.start(this, attempt)
        return OperationResult.Success
    }

    private suspend fun writeInitialQuery(
        attempt: Attempt,
        selection: TransportSelection,
        connection: ControlConnection,
        bytes: ByteArray,
        queryIndex: Int,
    ) {
        try {
            writePaced(attempt, connection, bytes)
        } catch (cause: CancellationException) {
            if (cause is TimeoutCancellationException && currentCoroutineContext().isActive &&
                queryIndex == 0 && canRecoverUnclassified(attempt, selection)
            ) {
                throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "首条初始化读取写入超时", cause)
            }
            throw cause
        } catch (cause: Exception) {
            if (queryIndex == 0 && canRecoverUnclassified(attempt, selection) && cause !is SecurityException &&
                cause !is IllegalArgumentException && cause !is UnsupportedOperationException && cause !is LinkEnded &&
                cause !is RecoverableTransportException && cause !is TerminalTransportException
            ) {
                throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "首条初始化读取写入失败", cause)
            }
            throw cause
        }
    }

    private fun canRecoverUnclassified(attempt: Attempt, selection: TransportSelection): Boolean = selection.endpoint is TransportEndpoint.BleGatt && requireNotNull(transport).supportsInitializationRecovery

    private fun canRetry(
        attempt: Attempt,
        selection: TransportSelection,
        failure: RecoverableTransportException,
    ): Boolean {
        if (selection.endpoint !is TransportEndpoint.BleGatt) return false
        if (failure.cause is SecurityException || failure.cause is IllegalArgumentException ||
            failure.cause is UnsupportedOperationException || failure.cause is TerminalTransportException
        ) {
            return false
        }
        return when (failure.reason) {
            TransportRecoveryReason.WRITE_FAILED, TransportRecoveryReason.NO_INITIAL_RESPONSE ->
                attempt.initializationQueryIndex == 0 && canRecoverUnclassified(attempt, selection)

            TransportRecoveryReason.SERVICE_DISCOVERY,
            TransportRecoveryReason.SERVICES_UNAVAILABLE,
            TransportRecoveryReason.SUBSCRIPTION,
            TransportRecoveryReason.SERVICES_CHANGED,
            -> true
        }
    }

    private fun throwProgressFailure(progress: MutableStateFlow<ReadProgress>) {
        val current = progress.value
        if (current.ended) throw current.failure ?: LinkEnded()
    }

    private suspend fun prepareRecovery(
        attempt: Attempt,
        selection: TransportSelection,
        failure: RecoverableTransportException,
        failedEpoch: Long,
    ) {
        val (oldConnection, oldObserver) = lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != failedEpoch) throw CancellationException("Stale recovery")
            // Invalidate callbacks before cancellation so an old finally cannot clear the new link.
            attempt.linkEpoch.value += 1
            val oldConnection = attempt.connection
            val oldObserver = attempt.observer
            attempt.connection = null
            attempt.observer = null
            oldConnection to oldObserver
        }
        withContext(NonCancellable) {
            oldObserver?.cancel()
            try {
                oldConnection?.close()
            } finally {
                oldObserver?.join()
            }
        }
        lifecycle.withLock {
            if (active !== attempt) throw CancellationException("Stale recovery")
            attempt.codec = createProtocolCodec(requireNotNull(attempt.candidate.profile))
            attempt.progress = MutableStateFlow(ReadProgress())
            attempt.hasWritten = false
            attempt.initializationQueryIndex = null
            attempt.ready = false
            val profile = requireNotNull(attempt.candidate.profile)
            state.update { current ->
                if (current.device?.sessionId != attempt.id || current.connection is ConnectionState.Disconnected) {
                    current
                } else {
                    current.copy(
                        connection = ConnectionState.Connecting(attempt.candidate.deviceId, attempt.id, ConnectionPhase.CONNECTING),
                        device = DeviceState(
                            attempt.candidate.deviceId,
                            attempt.id,
                            profile,
                            deviceCapabilities(profile, null),
                            profile.supportedFields.associateWith { FieldReading.Loading }.toPersistentMap(),
                            classicAddress = attempt.candidate.classicAddress,
                        ),
                        lastError = null,
                        transportSelection = selection,
                        connectionRecovery = ConnectionRecovery(attempt.id, failure.reason),
                    )
                }
            }
        }
    }

    private suspend fun closeAttemptLink(attempt: Attempt) = withContext(NonCancellable) {
        val (oldConnection, oldObserver) = lifecycle.withLock {
            attempt.linkEpoch.value += 1
            val oldConnection = attempt.connection
            val oldObserver = attempt.observer
            attempt.connection = null
            attempt.observer = null
            oldConnection to oldObserver
        }
        oldObserver?.cancel()
        oldConnection?.let { closeOrRemember(it) }
        oldObserver?.join()
    }

    private suspend fun selectTransport(attempt: Attempt): TransportSelection {
        val profile = requireNotNull(attempt.candidate.profile)
        val gatt = profile.gatt
        val ble = TransportEndpoint.BleGatt(gatt.serviceUuid, gatt.writeUuid, gatt.notifyUuid)
        if (profile != ModelProfile.I7_RS_V2) return TransportSelection(ble, TransportSelectionReason.MODEL_BLE_ONLY)
        if (attempt.forceBle) return TransportSelection(ble, TransportSelectionReason.FORCED_BLE)
        val address = attempt.candidate.classicAddress
            ?: return TransportSelection(ble, TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE)

        val facts = try {
            withTimeout(timing.connectTimeoutMillis) {
                requireNotNull(transport).connectionFacts(attempt.candidate.deviceId, address)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TransportFacts()
        }
        if (!facts.sppAvailable) return TransportSelection(ble, TransportSelectionReason.SPP_UNAVAILABLE)
        return when (facts.audioProfile) {
            AudioProfileEvidence.UNKNOWN ->
                TransportSelection(ble, TransportSelectionReason.AUDIO_PROFILE_UNKNOWN)

            AudioProfileEvidence.LE_AUDIO ->
                TransportSelection(ble, TransportSelectionReason.LE_AUDIO)

            AudioProfileEvidence.CLASSIC ->
                TransportSelection(TransportEndpoint.SppRfcomm(address, I7_CONTROL_SPP_UUID), TransportSelectionReason.CLASSIC_AUDIO)
        }
    }

    internal data class ReadProgress(
        val keys: PersistentSet<String> = persistentSetOf(),
        val meaningful: Boolean = false,
        val ended: Boolean = false,
        val keyRevisions: PersistentMap<String, Long> = persistentMapOf(),
        val fieldRevisions: PersistentMap<DeviceField, Long> = persistentMapOf(),
        val failure: Throwable? = null,
    )

    internal class LinkEnded : Exception("设备连接已结束")

    private fun phase(attempt: Attempt, epoch: Long, phase: ConnectionPhase) = updateLinkAttempt(attempt, epoch) {
        val current = it.connection as? ConnectionState.Connecting
        if (current != null && phase != ConnectionPhase.READY && phase.ordinal >= current.phase.ordinal) {
            it.copy(connection = current.copy(phase = phase))
        } else {
            it
        }
    }

    private fun updateLinkAttempt(attempt: Attempt, epoch: Long, update: (ControlState) -> ControlState) {
        state.update {
            if (attempt.linkEpoch.value == epoch && it.device?.sessionId == attempt.id && it.connection !is ConnectionState.Disconnected) update(it) else it
        }
    }

    private fun updateAttempt(attempt: Attempt, update: (ControlState) -> ControlState) {
        state.update {
            if (it.device?.sessionId == attempt.id && it.connection !is ConnectionState.Disconnected) update(it) else it
        }
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
        previous?.connection?.let { connection -> unclosed.update { if (connection in it) it else it.add(connection) } }
        unclosed.getAndUpdate { persistentListOf() }.forEach { closeOrRemember(it) }
        return unclosed.value.isEmpty()
    }

    private suspend fun closeOrRemember(connection: ControlConnection): Boolean {
        val closed = closeSafely(connection)
        if (!closed) unclosed.update { if (connection in it) it else it.add(connection) }
        return closed
    }

    private suspend fun closeSafely(connection: ControlConnection): Boolean = withContext(NonCancellable) {
        try {
            connection.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun platformError(): ControlError? = platformError(state.value, transport)

    private fun PersistentList<String>.appendRecent(values: Iterable<String>): PersistentList<String> = mutate {
        it.addAll(values)
        while (it.size > 128) it.removeAt(0)
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private companion object {
        const val I7_CONTROL_SPP_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
    }
}
