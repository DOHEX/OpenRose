package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TerminalTransportException
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import kotlinx.coroutines.TimeoutCancellationException

internal fun TransportSelection.canRecoverFirstQuery(attempt: ConnectionAttempt, index: Int?): Boolean =
    index == 0 && endpoint is TransportEndpoint.BleGatt && attempt.transport.supportsInitializationRecovery

internal fun isTerminalTransportFailure(cause: Throwable?): Boolean = when (cause) {
    is SecurityException, is IllegalArgumentException, is UnsupportedOperationException, is TerminalTransportException -> true
    else -> false
}

internal fun TransportSelection.canRetry(attempt: ConnectionAttempt, failure: RecoverableTransportException): Boolean {
    if (endpoint !is TransportEndpoint.BleGatt) return false
    if (isTerminalTransportFailure(failure.cause)) return false
    return when (failure.reason) {
        TransportRecoveryReason.WRITE_FAILED, TransportRecoveryReason.NO_INITIAL_RESPONSE ->
            canRecoverFirstQuery(attempt, attempt.initializationQueryIndex)

        TransportRecoveryReason.SERVICE_DISCOVERY,
        TransportRecoveryReason.SERVICES_UNAVAILABLE,
        TransportRecoveryReason.SUBSCRIPTION,
        TransportRecoveryReason.SERVICES_CHANGED,
        -> true
    }
}

internal fun connectionFailure(cause: Exception): ControlError {
    val native = if (cause is RecoverableTransportException) cause.cause else cause
    val code = when {
        native is UnsupportedOperationException || native is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED
        native is SecurityException -> ControlErrorCode.PERMISSION_REQUIRED
        cause is RecoverableTransportException && cause.reason == TransportRecoveryReason.NO_INITIAL_RESPONSE -> ControlErrorCode.TIMEOUT
        native is TimeoutCancellationException -> ControlErrorCode.TIMEOUT
        else -> ControlErrorCode.DISCONNECTED
    }
    return ControlError(code, cause.message ?: "设备连接已结束")
}

internal fun initialWriteFailure(cause: Exception, recoverable: Boolean): Exception {
    if (!recoverable) return cause
    val message = when {
        cause is TimeoutCancellationException -> "首条初始化读取写入超时"
        cause is LinkEnded || cause is RecoverableTransportException || isTerminalTransportFailure(cause) -> return cause
        else -> "首条初始化读取写入失败"
    }
    return RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, message, cause)
}
