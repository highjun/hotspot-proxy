package dev.androidproxy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object Battery {
    fun isIgnoringOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** 배터리 최적화 앱 목록을 연다. 별도 권한 없이 열 수 있다. 열지 못하면 앱 정보 화면을 연다. */
    fun openOptimizationSettings(context: Context) {
        if (!tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppSettings(context)
    }

    /** 앱 정보 화면을 연다. Samsung 은 여기의 "배터리" 에서 제한 없음을 고른다. */
    fun openAppSettings(context: Context) {
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
