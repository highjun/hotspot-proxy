package dev.androidproxy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/** 핫스팟 상태. 켜져 있으면 핫스팟 쪽 폰 주소를 갖는다. */
data class HotspotInfo(val enabled: Boolean, val address: String?, val interfaceName: String?)

object Hotspot {
    /** 핫스팟(AP) interface 이름. Samsung 은 `swlan0` 를 쓴다. */
    private val apInterfaceName = Regex("^(swlan\\d+|ap\\d+|softap\\d+|wlan[1-9]\\d*)$")

    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val WIFI_AP_STATE_ENABLED = 13

    fun read(context: Context): HotspotInfo {
        val found = findApAddress()
        if (found != null) return HotspotInfo(true, found.second, found.first)
        return HotspotInfo(apStateEnabled(context), null, null)
    }

    private fun findApAddress(): Pair<String, String>? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && apInterfaceName.matches(it.name) }
            .firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                    ?.let { nif.name to it.hostAddress!! }
            }
    } catch (_: Exception) {
        null
    }

    /** 시스템이 남겨 둔(sticky) 핫스팟 상태 방송을 읽는다. */
    private fun apStateEnabled(context: Context): Boolean = try {
        val intent = context.registerReceiver(
            null,
            IntentFilter(ACTION_WIFI_AP_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        intent?.getIntExtra(EXTRA_WIFI_AP_STATE, -1) == WIFI_AP_STATE_ENABLED
    } catch (_: Exception) {
        false
    }

    /** 핫스팟 설정 화면을 연다. 기기마다 다른 길을 차례로 시도한다. */
    fun openSettings(context: Context) {
        val candidates = listOf(
            Intent("com.android.settings.WIFI_TETHER_SETTINGS"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
