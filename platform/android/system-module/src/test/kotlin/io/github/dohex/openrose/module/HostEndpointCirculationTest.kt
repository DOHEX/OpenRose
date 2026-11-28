package io.github.dohex.openrose.module

import android.app.Application
import android.os.Binder
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.circulationWireFormatVersionNumber
import io.github.dohex.openrose.ffi.decodeCirculationReply
import io.github.dohex.openrose.ffi.encodeToBytes
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.openrose_circulation.CirculationCommand
import uniffi.openrose_circulation.CirculationReply
import uniffi.openrose_circulation.RemoteError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class HostEndpointCirculationTest {
    private class Served(val endpoint: HostEndpoint, val request: (ByteArray?) -> Bundle)

    private suspend fun serve(scope: CoroutineScope, nodeScope: CoroutineScope): Served {
        var owner: OwnerCirculation? = null
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { runtimeScope, settings, platform, base -> coreRuntime(runtimeScope, settings, platform, base) },
            scope,
            { "lease" },
            onOwnerStarted = { generation, runtimeScope, control ->
                val core = control as CoreControlRuntime
                val supervisor = CirculationSupervisor(nodeScope, create = core::isolatedCirculationNode)
                owner = OwnerCirculation(generation, runtimeScope, supervisor::execute)
            },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val generation = assertIs<HostStatus.Active>(host.status.value).generation
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), scope) { owner }
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(null, null), Binder()).getString(BridgeWire.LEASE_ID))
        return Served(endpoint) { bytes ->
            Bundle().apply {
                putString(BridgeWire.LEASE_ID, lease)
                putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
                putString(BridgeWire.PAYLOAD, HostIdentityWire.encode(generation).render())
                bytes?.let { putByteArray(BridgeWire.CIRCULATION, it) }
            }
        }
    }

    private fun Served.call(command: CirculationCommand): CirculationReply {
        repeat(READY_ATTEMPTS) {
            val response = endpoint.circulation(request(command.encodeToBytes()))
            if (response.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK) {
                val decoded = decodeCirculationReply(assertNotNull(response.getByteArray(BridgeWire.CIRCULATION)))
                val reply = assertIs<CoreDecodeResult.Ok<CirculationReply>>(decoded).value
                if (reply != CirculationReply.Failed(RemoteError.ServiceClosed)) return reply
            }
            Thread.sleep(READY_POLL_MILLIS)
        }
        error("circulation node never became ready")
    }

    @Test
    fun commandBytesAreExecutedByTheOwnersRustNodeAndAnswerWithReplyBytes() = runTest {
        val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val served = serve(backgroundScope, nodeScope)

            val directory = assertIs<CirculationReply.Directory>(served.call(CirculationCommand.Directory))
            assertEquals(emptyList(), directory.paired)
            assertEquals(null, directory.offer)

            val offer = assertIs<CirculationReply.Offer>(served.call(CirculationCommand.Offer)).offer
            assertEquals(6, offer.code.length)
            assertEquals(offer.code, assertIs<CirculationReply.Directory>(served.call(CirculationCommand.Directory)).offer?.code)

            val unpaired = served.call(CirculationCommand.Read("not-paired"))
            assertEquals(CirculationReply.Failed(RemoteError.NotPaired), unpaired)
        } finally {
            nodeScope.cancel()
        }
    }

    @Test
    fun malformedAndWrongVersionBytesGetTypedStatuses() = runTest {
        val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val served = serve(backgroundScope, nodeScope)
            val valid = CirculationCommand.Directory.encodeToBytes()
            val versionIndex = 3
            val wrongVersion = valid.copyOf().also { it[versionIndex] = (circulationWireFormatVersionNumber().toInt() + 1).toByte() }

            assertEquals(BridgeWire.STATUS_INVALID_ARGUMENT, served.endpoint.circulation(served.request(null)).getString(BridgeWire.STATUS))
            assertEquals(
                BridgeWire.STATUS_INVALID_ARGUMENT,
                served.endpoint.circulation(served.request(byteArrayOf(1, 2, 3))).getString(BridgeWire.STATUS),
            )
            assertEquals(
                BridgeWire.STATUS_UNSUPPORTED_VERSION,
                served.endpoint.circulation(served.request(wrongVersion)).getString(BridgeWire.STATUS),
            )
        } finally {
            nodeScope.cancel()
        }
    }

    private companion object {
        const val READY_ATTEMPTS = 100
        const val READY_POLL_MILLIS = 50L
    }
}
