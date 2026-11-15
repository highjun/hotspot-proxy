package dev.androidproxy.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.androidproxy.HotspotInfo
import dev.androidproxy.ProxyState
import dev.androidproxy.proxy.TrafficSnapshot
import org.junit.Rule
import org.junit.Test

/** Play 스토어 휴대전화 스크린샷(1080×1920). 기기 없이 JVM 에서 그린다. */
class StoreScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 1920, locale = "ko"),
        useDeviceResolution = true,
    )

    @Test
    fun screenshot1MainOn() {
        paparazzi.snapshot {
            AppTheme {
                MainContent(
                    state = ProxyState(
                        running = true,
                        port = 8080,
                        connections = 7,
                        traffic = TrafficSnapshot(
                            txBytes = (12.4 * MB).toLong(),
                            rxBytes = 186 * MB,
                            txRate = 320 * KB,
                            rxRate = (2.1 * MB).toLong(),
                        ),
                    ),
                    hotspot = HotspotInfo(true, "192.168.43.1", "swlan0"),
                    port = 8080,
                    http = true,
                    socks = true,
                    onToggle = {},
                    onOpenSettings = {},
                    onOpenHotspotSettings = {},
                )
            }
        }
    }

    @Test
    fun screenshot2MainOff() {
        paparazzi.snapshot {
            AppTheme {
                MainContent(
                    state = ProxyState(),
                    hotspot = HotspotInfo(false, null, null),
                    port = 8080,
                    http = true,
                    socks = true,
                    onToggle = {},
                    onOpenSettings = {},
                    onOpenHotspotSettings = {},
                )
            }
        }
    }

    @Test
    fun screenshot3Settings() {
        paparazzi.snapshot {
            AppTheme {
                SettingsContent(
                    portText = "8080",
                    savedPort = 8080,
                    http = true,
                    socks = true,
                    boot = true,
                    batteryExempt = true,
                    onPortTextChange = {},
                    onApplyPort = {},
                    onHttpChange = {},
                    onSocksChange = {},
                    onBootChange = {},
                    onOpenOptimizationSettings = {},
                    onOpenAppSettings = {},
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}
