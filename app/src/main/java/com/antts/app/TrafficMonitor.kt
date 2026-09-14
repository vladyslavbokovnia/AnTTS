package com.antts.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import java.util.Locale

class TrafficMonitor(private val context: Context) {
    fun monthlyText(): String {
        val start = AppSettings(context).periodStartMillis()
        val now = System.currentTimeMillis()
        val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) queryMobileBytes(start, now) else null
        return format(total ?: fallbackBytes())
    }

    private fun queryMobileBytes(start: Long, end: Long): Long? = runCatching {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end).use { bucket ->
            var total = 0L
            val data = NetworkStats.Bucket()
            while (bucket.hasNextBucket()) {
                bucket.getNextBucket(data)
                total += data.rxBytes + data.txBytes
            }
            total
        }
    }.getOrNull()

    /** Device-wide fallback keeps the indicator useful when usage access is not granted. */
    private fun fallbackBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0L && tx >= 0L) rx + tx else 0L
    }

    private fun format(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        return when {
            value < 1_000_000.0 -> String.format(Locale.US, "%.0f КБ", value / 1_000.0)
            value < 1_000_000_000.0 -> String.format(Locale.US, "%.1f МБ", value / 1_000_000.0)
            else -> String.format(Locale.US, "%.2f ГБ", value / 1_000_000_000.0)
        }
    }
}
