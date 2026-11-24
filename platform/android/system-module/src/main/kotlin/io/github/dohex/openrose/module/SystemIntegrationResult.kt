package io.github.dohex.openrose.module

import java.util.concurrent.CancellationException

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> systemIntegrationResult(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
} catch (incompatibleHost: LinkageError) {
    Result.failure(incompatibleHost)
}
