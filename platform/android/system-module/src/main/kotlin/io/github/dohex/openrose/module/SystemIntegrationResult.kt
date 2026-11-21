package io.github.dohex.openrose.module

import java.util.concurrent.CancellationException

/** Optional ROM calls and snapshot consumers report failures to their caller; cancellation and fatal VM errors propagate. */
@Suppress("TooGenericExceptionCaught") // Vendor callbacks, media providers and notification services have no common exception type.
internal inline fun <T> systemIntegrationResult(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
} catch (incompatibleHost: LinkageError) {
    Result.failure(incompatibleHost)
}
