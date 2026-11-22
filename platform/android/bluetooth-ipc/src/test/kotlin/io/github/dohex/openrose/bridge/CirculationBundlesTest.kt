package io.github.dohex.openrose.bridge

import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.transport.DeviceId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CirculationBundlesTest {
    private val session = RemoteSessionReference("laptop", DeviceId("earphones"), "owner/12")
    private val ticket = PairingTicket("001234", "qr", 10_000L)

    @Test
    fun commandAndReplySurviveTheBinderBoundary() {
        val reading = RemoteReading(
            RemoteControlStatus.READY,
            "EarFree i5",
            session,
            NoiseMode.WIND,
            listOf(NoiseMode.WIND, NoiseMode.TRANSPARENCY),
            left = 0,
            right = 100,
            batteryLayout = BatteryLayout.EARBUDS_ONLY,
        )
        val ok = CirculationResult("已确认")
        val exchanges = mapOf(
            CirculationCommand.Directory to PeerDirectory(mapOf("phone" to "手机"), mapOf("laptop" to "电脑"), ticket),
            CirculationCommand.Offer to PairingReply(ticket),
            CirculationCommand.Pair("laptop", "001234") to ok,
            CirculationCommand.PairQr("qr") to ok,
            CirculationCommand.Forget("laptop") to ok,
            CirculationCommand.Read("laptop") to reading,
            CirculationCommand.SetNoise(session, NoiseMode.TRANSPARENCY) to reading,
            CirculationCommand.Handoff(null) to HandoffStatus(available = true, offline = true),
            CirculationCommand.Pull("address") to ok,
        )
        for ((command, reply) in exchanges) {
            assertEquals(command, command.toBundle().circulationCommand())
            assertEquals(reply, reply.toBundle().circulationReply(command))
            val failure = CirculationFailure("不可用")
            assertEquals(failure, failure.toBundle().circulationReply(command))
        }
    }

    @Test
    fun aReadingFromAPeerWithoutABatteryLayoutStaysUnspecified() {
        val command = CirculationCommand.Read("laptop")
        val reading = RemoteReading(RemoteControlStatus.READY, "EarFree i5", session, left = 80, batteryLayout = BatteryLayout.SINGLE_UNIT)
        assertEquals(BatteryLayout.SINGLE_UNIT, reading.toBundle().circulationReply(command).let { (it as RemoteReading).batteryLayout })
        val old = reading.toBundle().apply { remove("batteryLayout") }
        assertNull((old.circulationReply(command) as RemoteReading).batteryLayout)
        val future = reading.toBundle().apply { putString("batteryLayout", "NEW_LAYOUT") }
        assertNull((future.circulationReply(command) as RemoteReading).batteryLayout)
    }

    @Test
    fun remoteWritesCannotOmitThePeerDeviceOrOwnerSession() {
        val command = CirculationCommand.SetNoise(session, NoiseMode.TRANSPARENCY)
        for (key in listOf("peer", "device", "session", "mode")) {
            val incomplete = command.toBundle().apply { remove(key) }
            assertFailsWith<IllegalArgumentException> { incomplete.circulationCommand() }
        }
    }
}
