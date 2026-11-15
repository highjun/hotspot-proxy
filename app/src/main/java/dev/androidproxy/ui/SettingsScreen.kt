package dev.androidproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.androidproxy.AppSettings
import dev.androidproxy.Battery
import dev.androidproxy.ProxyService
import dev.androidproxy.ProxyStatus
import dev.androidproxy.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var http by remember { mutableStateOf(settings.httpEnabled) }
    var socks by remember { mutableStateOf(settings.socksEnabled) }
    var boot by remember { mutableStateOf(settings.startOnBoot) }
    var batteryExempt by remember { mutableStateOf(Battery.isIgnoringOptimizations(context)) }

    // 시스템 창에서 돌아오면 배터리 예외 상태를 다시 읽는다.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            batteryExempt = Battery.isIgnoringOptimizations(context)
        }
    }

    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in AppSettings.MIN_PORT..AppSettings.MAX_PORT

    /** 켜져 있으면 바뀐 설정으로 다시 켠다. */
    fun applyIfRunning() {
        if (ProxyStatus.state.value.running) ProxyService.restart(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("proxy")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
                    label = { Text("포트") },
                    singleLine = true,
                    isError = !portValid,
                    supportingText = { Text("${AppSettings.MIN_PORT}–${AppSettings.MAX_PORT}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    enabled = portValid && portValue != settings.port,
                    onClick = {
                        settings.port = portValue!!
                        applyIfRunning()
                    },
                ) { Text("적용") }
            }
            SwitchRow("HTTP proxy", "HTTP 요청과 CONNECT(HTTPS)를 받습니다", http) {
                http = it
                settings.httpEnabled = it
                applyIfRunning()
            }
            SwitchRow("SOCKS5 proxy", "인증 없는 SOCKS5 CONNECT 를 받습니다", socks) {
                socks = it
                settings.socksEnabled = it
                applyIfRunning()
            }
            Text(
                "켜져 있을 때 바꾸면 proxy 를 다시 켭니다. TX/RX 는 0 부터 다시 셉니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("자동 시작")
            SwitchRow("부팅할 때 자동 시작", "꺼지기 전에 proxy 가 켜져 있었으면 다시 켭니다", boot) {
                boot = it
                settings.startOnBoot = it
            }

            HorizontalDivider()
            SectionTitle("배터리")
            Text(
                if (batteryExempt) "배터리 최적화 예외: 허용됨" else "배터리 최적화 예외: 허용 안 됨",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                onClick = { Battery.requestIgnoreOptimizations(context) },
                enabled = !batteryExempt,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("배터리 최적화 예외 요청") }
            OutlinedButton(
                onClick = { Battery.openAppSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("앱 배터리 설정 열기") }
            Text(
                "Samsung 기기는 설정 > 배터리 > 백그라운드 사용 제한 > \"절전 예외 앱\" 에 Android Proxy 를 더하세요. " +
                    "앱 정보 > 배터리에서 \"제한 없음\" 을 고르면 화면이 꺼져도 proxy 가 멈추지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
