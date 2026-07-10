package com.example.medicalschoolapp.util

import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.medicalschoolapp.data.SettingsRepository
import kotlinx.coroutines.flow.first

object UsageTracker {

    suspend fun getTodayPlayUsageMs(context: Context, repository: SettingsRepository): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = TimeCalculator.getStartOfDayMs(now)

        val stats = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
        if (stats.isNullOrEmpty()) return 0L

        // Get app categories map to check efficiently
        val categoriesMap = repository.appCategoriesFlow.first()
        var totalPlayTimeMs = 0L

        for ((packageName, usageStats) in stats) {
            // Self package is not play app
            if (packageName == context.packageName) continue
            // System packages (like launcher) might need exclusion, but for MVP let's just stick to the default rule.
            // A better MVP would exclude common system UI packages, but we'll let the user categorize them.
            val isPlayApp = categoriesMap[packageName] ?: true // Default is restricted (Play)
            if (isPlayApp) {
                totalPlayTimeMs += usageStats.totalTimeInForeground
            }
        }
        return totalPlayTimeMs
    }
}
