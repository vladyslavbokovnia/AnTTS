package com.antts.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.util.Locale

class TrafficMonitor(private val context: Context) {
    fun monthlyText(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ""
        return runCatching {
            val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val start = AppSettings(context).periodStartMillis()
            val bucket = manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis())
            var total = 0L
            val data = NetworkStats.Bucket()
            while (bucket.hasNextBucket()) { bucket.getNextBucket(data); total += data.rxBytes + data.txBytes }
            bucket.close()
            format(total)
        }.getOrDefault("")
    }

    private fun format(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb < 1000) String.format(Locale.US, "%.1f", mb) else String.format(Locale.US, "%.1f", mb / 1000.0)
    }
}
