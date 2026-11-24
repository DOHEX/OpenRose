package io.github.dohex.openrose.desktop

import androidx.compose.ui.graphics.Color
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.ui.RemoteHeadset
import io.github.dohex.openrose.ui.TraySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopTrayTest {
    @Test
    fun selectsConnectedThenRemoteThenDisconnectedIconState() {
        val localConnected = summary(connected = true)
        val localDisconnected = summary(connected = false)
        val remote = remoteHeadset()

        assertEquals(TrayIconState.CONNECTED, trayIconState(localConnected, remote))
        assertEquals(TrayIconState.CONNECTED, trayIconState(localConnected, null))
        assertEquals(TrayIconState.REMOTE, trayIconState(localDisconnected, remote))
        assertEquals(TrayIconState.DISCONNECTED, trayIconState(localDisconnected, null))
    }

    @Test
    fun formatsRemoteTooltipWithBatteryStatusFallbackAndLocalPriority() {
        val localConnected = summary(connected = true, title = "EarFree i5", compactBattery = "L 90% · R 85% · C 100%")
        val localDisconnected = summary(connected = false, title = "未连接")
        val remoteWithBattery = remoteHeadset(left = 80, right = 75, case = 60)
        val remoteRecovering = remoteHeadset(status = RemoteControlStatus.RECOVERING)
        val remoteReadyWithoutBattery = remoteHeadset(status = RemoteControlStatus.READY)
        val remoteSingleUnit = remoteHeadset(case = 88, layout = BatteryLayout.SINGLE_UNIT)

        assertEquals("OpenRose", trayTooltip(started = false, localDisconnected, remoteWithBattery))
        assertEquals(
            "EarFree i5\nL 90% · R 85% · C 100%",
            trayTooltip(started = true, localConnected, remoteWithBattery),
        )
        assertEquals(
            "EarFree i5 · Xiaomi 14\nL 80% · R 75% · C 60%",
            trayTooltip(started = true, localDisconnected, remoteWithBattery),
        )
        assertEquals(
            "EarFree i5 · Xiaomi 14\n耳机已接入，控制暂不可用",
            trayTooltip(started = true, localDisconnected, remoteRecovering),
        )
        assertEquals(
            "EarFree i5 · Xiaomi 14",
            trayTooltip(started = true, localDisconnected, remoteReadyWithoutBattery),
        )
        assertEquals(
            "EarFree i5 · Xiaomi 14\n电量 88%",
            trayTooltip(started = true, localDisconnected, remoteSingleUnit),
        )
    }

    @Test
    fun overridesTrayIconTintOnlyOnUnknownLinuxDesktop() {
        assertEquals(
            Color.Black,
            trayIconTint(Platform.Linux, LinuxDesktopEnvironment.Unknown, systemDark = false),
        )
        assertEquals(
            Color.White,
            trayIconTint(Platform.Linux, LinuxDesktopEnvironment.Unknown, systemDark = true),
        )
        assertNull(trayIconTint(Platform.Linux, LinuxDesktopEnvironment.Gnome, systemDark = false))
        assertNull(trayIconTint(Platform.Linux, LinuxDesktopEnvironment.KDE, systemDark = false))
        assertNull(trayIconTint(Platform.Windows, LinuxDesktopEnvironment.Unknown, systemDark = false))
    }

    private fun summary(
        connected: Boolean,
        title: String = "EarFree i5",
        compactBattery: String? = null,
    ) = TraySummary(
        connected = connected,
        title = title,
        deviceName = title.takeIf { connected },
        sourceStatus = null,
        battery = compactBattery,
        compactBattery = compactBattery,
        noiseModes = emptyList(),
        reconnectTarget = null,
        reconnectLabel = "重新连接",
        addDevice = false,
        connectionHint = null,
    )

    private fun remoteHeadset(
        name: String = "Xiaomi 14",
        deviceName: String = "EarFree i5",
        status: RemoteControlStatus = RemoteControlStatus.READY,
        left: Int? = null,
        right: Int? = null,
        case: Int? = null,
        layout: BatteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    ) = RemoteHeadset(
        name = name,
        reading = RemoteReading(
            status = status,
            deviceName = deviceName,
            left = left,
            right = right,
            case = case,
            batteryLayout = layout,
        ),
        busy = false,
        feedback = null,
        setSetting = {},
    )
}
