package dev.androidproxy

import android.content.Context
import dev.androidproxy.proxy.ProxyConfig

/** 앱 설정. SharedPreferences 하나에 둔다. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, ProxyConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var httpEnabled: Boolean
        get() = prefs.getBoolean(KEY_HTTP, true)
        set(value) = prefs.edit().putBoolean(KEY_HTTP, value).apply()

    var socksEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOCKS, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCKS, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    /** 사용자가 proxy 를 켜 두었는지. 부팅 자동 시작과 service 재시작이 본다. */
    var proxyWanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) {
            // 바로 뒤에 process 가 죽어도 남도록 곧바로 쓴다.
            prefs.edit().putBoolean(KEY_WANTED, value).commit()
        }

    fun proxyConfig() = ProxyConfig(port = port, httpEnabled = httpEnabled, socksEnabled = socksEnabled)

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_HTTP = "http_enabled"
        private const val KEY_SOCKS = "socks_enabled"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_WANTED = "proxy_wanted"

        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
