package io.github.dohex.openrose.androidintegration

class SystemRequestGate(
    private val clock: () -> Long,
    private val windowMillis: Long = 250,
) {
    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    private class Reservation(val token: Long, var expiresAt: Long)

    private val lock = Any()
    private var sequence = 0L
    private val reservations = mutableMapOf<Any, Reservation>()

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
