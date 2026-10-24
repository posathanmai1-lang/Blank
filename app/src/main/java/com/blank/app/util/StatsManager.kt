package com.blank.app.util

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

object StatsManager {

    private const val PREFS_NAME = "blank_stats"
    private const val KEY_TOTAL_QUERIES = "total_queries"
    private const val KEY_BLOCKED_ADS = "blocked_ads"

    private lateinit var prefs: SharedPreferences
    val totalQueries = AtomicLong(0)
    val blockedAds = AtomicLong(0)

    var onStatsUpdated: (() -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        totalQueries.set(prefs.getLong(KEY_TOTAL_QUERIES, 0L))
        blockedAds.set(prefs.getLong(KEY_BLOCKED_ADS, 0L))
    }

    fun incrementBlocked() {
        val count = blockedAds.incrementAndGet()
        totalQueries.incrementAndGet()
        // Save to local storage asynchronously
        prefs.edit().putLong(KEY_BLOCKED_ADS, count).apply()
        onStatsUpdated?.invoke()
    }

    fun incrementAllowed() {
        val count = totalQueries.incrementAndGet()
        prefs.edit().putLong(KEY_TOTAL_QUERIES, count).apply()
        onStatsUpdated?.invoke()
    }

    fun resetStats() {
        totalQueries.set(0)
        blockedAds.set(0)
        prefs.edit().clear().apply()
        onStatsUpdated?.invoke()
    }
}
