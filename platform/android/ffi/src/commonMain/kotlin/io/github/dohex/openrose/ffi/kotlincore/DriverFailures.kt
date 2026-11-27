package io.github.dohex.openrose.ffi.kotlincore

import uniffi.openrose_ffi.DriverException
import java.io.IOException

internal inline fun <T> translatingFailures(
    onSecurity: (SecurityException) -> DriverException,
    onUnsupported: (UnsupportedOperationException) -> DriverException,
    onIo: (IOException) -> DriverException,
    onInvalidArgument: (IllegalArgumentException) -> DriverException = { throw it },
    block: () -> T,
): T = try {
    block()
} catch (failure: IllegalArgumentException) {
    throw onInvalidArgument(failure).causedBy(failure)
} catch (failure: SecurityException) {
    throw onSecurity(failure).causedBy(failure)
} catch (failure: UnsupportedOperationException) {
    throw onUnsupported(failure).causedBy(failure)
} catch (failure: IOException) {
    throw onIo(failure).causedBy(failure)
}

internal fun <T : Throwable> T.causedBy(cause: Throwable): T = also { it.initCause(cause) }
