package com.antts.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.util.Locale

class TrafficMonitor(private val context: Context) {
    fun monthlyText(): String {
        val start = AppSettings(context).periodStartMillis()
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "—"
        return format(queryDeviceBytes(start, now) ?: return "—")
    }

    private fun queryDeviceBytes(start: Long, end: Long): Long? = runCatching {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val bucket = manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
        bucket.rxBytes.coerceAtLeast(0L) + bucket.txBytes.coerceAtLeast(0L)
    }.getOrNull()

    private fun format(bytes: Long): String {
        return String.format(Locale.getDefault(), "%.2f", bytes.coerceAtLeast(0L) / 1_000_000_000.0)
    }
}
