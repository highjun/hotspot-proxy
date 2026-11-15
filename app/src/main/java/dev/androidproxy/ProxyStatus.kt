package dev.androidproxy

import dev.androidproxy.proxy.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** service 가 화면에 알리는 proxy 상태. */
data class ProxyState(
    val running: Boolean = false,
    val port: Int = 0,
    val httpEnabled: Boolean = true,
    val socksEnabled: Boolean = true,
    val connections: Int = 0,
    val traffic: TrafficSnapshot = TrafficSnapshot(0, 0, 0, 0),
    val error: String? = null,
)

object ProxyStatus {
    private val mutable = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = mutable

    internal fun update(transform: (ProxyState) -> ProxyState) {
        while (true) {
            val current = mutable.value
            if (mutable.compareAndSet(current, transform(current))) return
        }
    }
}
