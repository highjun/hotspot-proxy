package dev.androidproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 부팅이 끝나면, 설정이 켜져 있고 proxy 가 켜져 있었을 때 다시 켠다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = AppSettings(context)
        if (settings.startOnBoot && settings.proxyWanted) {
            ProxyService.start(context)
        }
    }
}
