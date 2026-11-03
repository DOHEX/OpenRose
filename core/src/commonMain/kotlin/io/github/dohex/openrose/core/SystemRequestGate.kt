package io.github.dohex.openrose.core

/**
 * Coalesces the several synchronous vendor entry points one system UI tap can reach into a single user
 * operation. It owns no queue: a request is "the same operation" while one for the same [key] is still being
 * handed over, and for [windowMillis] after it was accepted. A refused hand-over releases the key at once.
 */
class SystemRequestGate(
    private val clock: () -> Long,
    private val windowMillis: Long = 250,
) {
    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    private class Reservation(val token: Long, var expiresAt: Long)

    private val lock = Any()
    private var sequence = 0L
    private val reservations = mutableMapOf<Any, Reservation>()

    /** [enqueue] reports whether the work was locally accepted; it is never run for a duplicate. */
    fun submit(key: Any, enqueue: () -> Boolean): Result {
        val token = synchronized(lock) {
            val now = clock()
            reservations.entries.removeAll { it.value.expiresAt <= now }
            if (key in reservations) return Result.DUPLICATE
            sequence += 1
            reservations[key] = Reservation(sequence, Long.MAX_VALUE)
            sequence
        }
        val accepted = runCatching(enqueue).getOrDefault(false)
        synchronized(lock) {
            val reservation = reservations[key]
            if (reservation?.token == token) {
                if (accepted) reservation.expiresAt = clock() + windowMillis else reservations.remove(key)
            }
        }
        return if (accepted) Result.ACCEPTED else Result.REJECTED
    }
}
