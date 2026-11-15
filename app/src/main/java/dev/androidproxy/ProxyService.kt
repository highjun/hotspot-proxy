package dev.androidproxy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.androidproxy.proxy.ProxyServer
import dev.androidproxy.proxy.TrafficSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * proxy 를 돌리는 foreground service(specialUse).
 * 도는 동안 partial wake lock 과 Wi-Fi lock 을 쥔다.
 */
class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: AppSettings
    private var server: ProxyServer? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.proxyWanted = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                goForeground()
                stopProxy()
                startProxy()
            }
            else -> {
                // intent 가 null 이면 시스템이 START_STICKY 로 다시 띄운 것이다.
                if (intent == null && !settings.proxyWanted) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                goForeground()
                if (server == null) startProxy()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!settings.proxyWanted) return
        // 최근 앱 목록에서 지워도 돈다. 기기가 process 를 죽이는 때를 위해 1초 뒤 다시 띄우기를 걸어 둔다.
        val restart = PendingIntent.getForegroundService(
            this,
            REQUEST_RESTART,
            Intent(this, ProxyService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_000, restart)
        } catch (e: Exception) {
            Log.w(TAG, "재시작 알람을 걸지 못했다", e)
        }
    }

    override fun onDestroy() {
        stopProxy()
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        stopProxy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProxy() {
        val config = settings.proxyConfig()
        val proxy = ProxyServer(config)
        try {
            proxy.start()
        } catch (e: IOException) {
            Log.w(TAG, "proxy 를 켜지 못했다", e)
            ProxyStatus.update {
                ProxyState(running = false, port = config.port, error = "포트 ${config.port} 를 열지 못했습니다: ${e.message}")
            }
            settings.proxyWanted = false
            shutdown()
            return
        }
        server = proxy
        acquireLocks()
        ProxyStatus.update {
            ProxyState(running = true, port = config.port, httpEnabled = config.httpEnabled, socksEnabled = config.socksEnabled)
        }
        proxy.counter.sample()
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                val snapshot = proxy.counter.sample()
                ProxyStatus.update { it.copy(traffic = snapshot, connections = proxy.connectionCount) }
                // 알림은 2초마다 고친다. 시스템이 너무 잦은 갱신을 버린다.
                if (tick++ % 2 == 0) notifyStatus(snapshot)
            }
        }
        notifyStatus(TrafficSnapshot(0, 0, 0, 0))
    }

    private fun stopProxy() {
        ticker?.cancel()
        ticker = null
        server?.stop()
        server = null
        releaseLocks()
        ProxyStatus.update { it.copy(running = false, connections = 0, traffic = it.traffic.copy(txRate = 0, rxRate = 0)) }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidProxy:proxy")
                .apply {
                    setReferenceCounted(false)
                    // proxy 를 끌 때까지 쥔다. 끄면 releaseLocks 가 놓는다.
                    @Suppress("WakelockTimeout")
                    acquire()
                }
        }
        if (wifiLock == null) {
            // Android 14 부터 HIGH_PERF 는 효과가 없어 LOW_LATENCY 를 쓴다.
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "AndroidProxy:proxy")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun goForeground() {
        val notification = buildNotification(ProxyStatus.state.value.traffic.copy(txRate = 0, rxRate = 0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // Android 13 에는 specialUse 종류가 없다.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyStatus(traffic: TrafficSnapshot) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(traffic))
    }

    private fun buildNotification(traffic: TrafficSnapshot): Notification {
        val state = ProxyStatus.state.value
        val running = server != null
        val protocols = listOfNotNull("HTTP".takeIf { state.httpEnabled }, "SOCKS5".takeIf { state.socksEnabled })
            .joinToString(" · ")
        val title = if (running) "proxy 켜짐 · 포트 ${settings.port} · $protocols" else "proxy 켜는 중"
        val text = "TX ${formatBytes(traffic.txBytes)} (${formatRate(traffic.txRate)}) · " +
            "RX ${formatBytes(traffic.rxBytes)} (${formatRate(traffic.rxRate)})"
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build())
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "proxy 상태", NotificationManager.IMPORTANCE_LOW).apply {
            description = "proxy 가 켜져 있는 동안 보이는 알림"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_RESTART = 3

        const val ACTION_START = "dev.androidproxy.action.START"
        const val ACTION_STOP = "dev.androidproxy.action.STOP"
        const val ACTION_RESTART = "dev.androidproxy.action.RESTART"

        fun start(context: Context) {
            AppSettings(context).proxyWanted = true
            context.startForegroundService(Intent(context, ProxyService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            AppSettings(context).proxyWanted = false
            context.startService(Intent(context, ProxyService::class.java).setAction(ACTION_STOP))
        }

        /** 설정이 바뀌면 다시 켠다. 켜져 있을 때만 부른다. TX/RX 는 0 부터 다시 센다. */
        fun restart(context: Context) {
            context.startForegroundService(Intent(context, ProxyService::class.java).setAction(ACTION_RESTART))
        }
    }
}
