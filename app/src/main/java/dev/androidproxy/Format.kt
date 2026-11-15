package dev.androidproxy

import java.util.Locale

/** byte 수를 사람이 읽는 꼴로 쓴다(1024 단위). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unit])
}

fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
