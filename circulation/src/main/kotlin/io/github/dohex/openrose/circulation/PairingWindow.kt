package io.github.dohex.openrose.circulation

import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class PairingWindow(private val id: String) {
    private val random = SecureRandom()
    private var code: String? = null
    private var expires: Long = 0
    private var attempts = 0

    @Synchronized
    fun open(): PairingOffer {
        val digits = "%06d".format(Locale.ROOT, random.nextInt(1_000_000))
        code = digits
        expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        attempts = 0
        return PairingOffer(digits, "openrose://pair/$id/$digits")
    }

    @Synchronized
    fun tryPairing(): String {
        check(++attempts <= 5 && System.nanoTime() < expires) { "请重新显示配对码" }
        return checkNotNull(code) { "请先在电脑显示配对码" }
    }

    @Synchronized
    fun finish(digits: String) {
        check(code == digits && System.nanoTime() < expires) { "配对码已失效" }
        code = null
    }
}
