package com.antts.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("antts_settings", Context.MODE_PRIVATE)

    var progressAlpha: Int
        get() = prefs.getInt("progress_alpha", 90)
        set(value) = prefs.edit().putInt("progress_alpha", value.coerceIn(10, 100)).apply()

    var backgroundAlpha: Int
        get() = prefs.getInt("background_alpha", 82)
        set(value) = prefs.edit().putInt("background_alpha", value.coerceIn(0, 100)).apply()

    var barHeightDp: Int
        get() = prefs.getInt("bar_height_dp", 28)
        set(value) = prefs.edit().putInt("bar_height_dp", value.coerceIn(16, 64)).apply()
    var trafficStartDay: Int
        get() = prefs.getInt("traffic_start_day", 1).coerceIn(1, 31)
        set(value) = prefs.edit().putInt("traffic_start_day", value.coerceIn(1, 31)).apply()
    var progressColor: Int
        get() = prefs.getInt("progress_color", 0xFFFFFFFF.toInt())
        set(value) = prefs.edit().putInt("progress_color", value).apply()

    var scrollMode: String
        get() = prefs.getString("scroll_mode", "smooth") ?: "smooth"
        set(value) = prefs.edit().putString("scroll_mode", value).apply()

    fun periodStartMillis(): Long {
        val now = System.currentTimeMillis()
        val cycle = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_MONTH, minOf(trafficStartDay, getActualMaximum(java.util.Calendar.DAY_OF_MONTH)))
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (now < cycle.timeInMillis) cycle.add(java.util.Calendar.MONTH, -1)
        return cycle.timeInMillis
    }

    private fun monthStart(): Long {
        val now = java.util.Calendar.getInstance()
        now.set(java.util.Calendar.DAY_OF_MONTH, 1)
        now.set(java.util.Calendar.HOUR_OF_DAY, 0)
        now.set(java.util.Calendar.MINUTE, 0)
        now.set(java.util.Calendar.SECOND, 0)
        now.set(java.util.Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
