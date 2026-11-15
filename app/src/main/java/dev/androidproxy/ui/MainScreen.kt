package dev.androidproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.androidproxy.AppSettings
import dev.androidproxy.Hotspot
import dev.androidproxy.HotspotInfo
import dev.androidproxy.ProxyService
import dev.androidproxy.ProxyState
import dev.androidproxy.ProxyStatus
import dev.androidproxy.R
import dev.androidproxy.formatBytes
import dev.androidproxy.formatRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val state by ProxyStatus.state.collectAsStateWithLifecycle()
    var hotspot by remember { mutableStateOf(HotspotInfo(false, null, null)) }

    // 화면이 보이는 동안 2초마다 핫스팟 상태를 읽는다.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                hotspot = withContext(Dispatchers.IO) { Hotspot.read(context) }
                delay(2_000)
            }
        }
    }

    MainContent(
        state = state,
        hotspot = hotspot,
        port = if (state.running) state.port else settings.port,
        http = if (state.running) state.httpEnabled else settings.httpEnabled,
        socks = if (state.running) state.socksEnabled else settings.socksEnabled,
        onToggle = {
            if (state.running) {
                ProxyService.stop(context)
            } else {
                ProxyService.start(context)
            }
        },
        onOpenSettings = onOpenSettings,
        onOpenHotspotSettings = { Hotspot.openSettings(context) },
    )
}

/** 상태를 받아 그리기만 하는 메인 화면. 스크린샷 테스트도 이것을 그린다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    state: ProxyState,
    hotspot: HotspotInfo,
    port: Int,
    http: Boolean,
    socks: Boolean,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "설정")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            PowerButton(
                on = state.running,
                onClick = onToggle,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (state.running) "proxy 켜짐 · 연결 ${state.connections}개" else "proxy 꺼짐",
                style = MaterialTheme.typography.titleMedium,
            )
            state.error?.takeIf { !state.running }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            HotspotCard(
                hotspot = hotspot,
                port = port,
                http = http,
                socks = socks,
                onOpenHotspotSettings = onOpenHotspotSettings,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TrafficCard(
                    label = "TX",
                    hint = "폰 → 인터넷",
                    total = state.traffic.txBytes,
                    rate = state.traffic.txRate,
                    modifier = Modifier.weight(1f),
                )
                TrafficCard(
                    label = "RX",
                    hint = "인터넷 → 폰",
                    total = state.traffic.rxBytes,
                    rate = state.traffic.rxRate,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(200.dp),
        colors = if (on) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
    ) {
        Text(if (on) "ON" else "OFF", fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HotspotCard(
    hotspot: HotspotInfo,
    port: Int,
    http: Boolean,
    socks: Boolean,
    onOpenHotspotSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hotspot.enabled) {
                Text("핫스팟 켜짐", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                if (hotspot.address != null) {
                    Text(
                        "${hotspot.address}:$port",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    val protocols = listOfNotNull("HTTP".takeIf { http }, "SOCKS5".takeIf { socks })
                    Text(
                        if (protocols.isEmpty()) "켜 둔 protocol 이 없습니다" else protocols.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("핫스팟 주소를 찾는 중입니다", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text("핫스팟 꺼짐", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onOpenHotspotSettings) { Text("핫스팟 설정 열기") }
            }
        }
    }
}

@Composable
private fun TrafficCard(label: String, hint: String, total: Long, rate: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(formatBytes(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(formatRate(rate), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
