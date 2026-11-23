package io.github.dohex.openrose.androidintegration

class SerializedPublisher<T>(private val commit: (T) -> Boolean) {
    private val lock = Any()

    fun publish(derive: () -> T): Boolean = synchronized(lock) { commit(derive()) }
}
