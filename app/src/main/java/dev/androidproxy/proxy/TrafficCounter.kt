package dev.androidproxy.proxy

import java.util.concurrent.atomic.AtomicLong

/** 누적량과 속도. 속도의 단위는 byte/초다. */
data class TrafficSnapshot(
    val txBytes: Long,
    val rxBytes: Long,
    val txRate: Long,
    val rxRate: Long,
)

/**
 * proxy 가 주고받은 byte 를 센다.
 * TX 는 폰에서 인터넷(원격 서버)으로 보낸 byte, RX 는 원격 서버에서 받은 byte 다.
 */
class TrafficCounter {
    private val tx = AtomicLong()
    private val rx = AtomicLong()

    private var lastNanos = 0L
    private var lastTx = 0L
    private var lastRx = 0L
    private var txRate = 0L
    private var rxRate = 0L

    fun addTx(bytes: Int) {
        tx.addAndGet(bytes.toLong())
    }

    fun addRx(bytes: Int) {
        rx.addAndGet(bytes.toLong())
    }

    val txBytes: Long get() = tx.get()
    val rxBytes: Long get() = rx.get()

    /**
     * 누적량과 속도를 낸다. 속도는 지난 sample 뒤 지난 시간으로 나눈 값이다.
     * 1초마다 부르면 1초 창의 속도가 된다.
     */
    @Synchronized
    fun sample(nowNanos: Long = System.nanoTime()): TrafficSnapshot {
        val curTx = tx.get()
        val curRx = rx.get()
        if (lastNanos != 0L) {
            val elapsed = nowNanos - lastNanos
            if (elapsed > 0) {
                txRate = (curTx - lastTx) * 1_000_000_000L / elapsed
                rxRate = (curRx - lastRx) * 1_000_000_000L / elapsed
            }
        }
        lastNanos = nowNanos
        lastTx = curTx
        lastRx = curRx
        return TrafficSnapshot(curTx, curRx, txRate, rxRate)
    }
}
