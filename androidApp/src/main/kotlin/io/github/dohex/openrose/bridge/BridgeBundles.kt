package io.github.dohex.openrose.bridge

import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgeWire

/** Bundle builders for the bridge's hello and reply envelopes; the keys and values are [BridgeWire]'s. */
internal object BridgeBundles {
    fun hello(processName: String?, target: BridgeTarget?, resolution: String = BridgeWire.RESOLUTION_MATCHED): Bundle = Bundle().apply {
        putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
        target?.let {
            putString(BridgeWire.PACKAGE, it.packageName)
            putString(BridgeWire.COMPONENT_MATCH_KEY, it.componentMatch)
        }
        processName?.let { putString(BridgeWire.PROCESS, it) }
        putString(BridgeWire.RESOLUTION, resolution)
    }

    fun response(status: String, message: String? = null): Bundle = Bundle().apply {
        putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
        putString(BridgeWire.STATUS, status)
        message?.let { putString(BridgeWire.MESSAGE, it) }
    }

    fun payload(status: String, text: String): Bundle = response(status).apply { putString(BridgeWire.PAYLOAD, text) }
}
