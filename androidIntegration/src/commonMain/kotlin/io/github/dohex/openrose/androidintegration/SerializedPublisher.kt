package io.github.dohex.openrose.androidintegration

/**
 * The one ordered writer of a persisted value that has several triggers. The value is derived from the
 * source of truth while holding the lock that also covers the commit, so whichever caller commits last
 * commits the newest truth: a write that was requested earlier can never land after a later change.
 */
class SerializedPublisher<T>(private val commit: (T) -> Boolean) {
    private val lock = Any()

    /** Derives the value and commits it as one step; returns whether the commit was acknowledged. */
    fun publish(derive: () -> T): Boolean = synchronized(lock) { commit(derive()) }
}
