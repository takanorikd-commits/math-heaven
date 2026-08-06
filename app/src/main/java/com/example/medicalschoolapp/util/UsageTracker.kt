package com.example.medicalschoolapp.util

import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.data.SettingsRepository
import kotlinx.coroutines.flow.first

object UsageTracker {

    suspend fun getTodayPlayUsageMs(context: Context, repository: SettingsRepository): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = TimeCalculator.getStartOfDayMs(now)

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        if (stats.isNullOrEmpty()) return 0L

        val categoriesMap = repository.appCategoriesFlow.first()
        val launcherPackages = if (repository is LocalSettingsRepository) repository.getLauncherPackages() else emptySet<String>()
        val defaultAllowed = LocalSettingsRepository.defaultAllowedPackages

        var totalPlayTimeMs = 0L
        val aggregatedStats = stats.groupBy { it.packageName }

        for ((packageName, packageStats) in aggregatedStats) {
            if (packageName == context.packageName) continue
            if (launcherPackages.contains(packageName) || defaultAllowed.contains(packageName)) continue

            val isPlayApp = categoriesMap[packageName] ?: run {
                !defaultAllowed.contains(packageName) && !launcherPackages.contains(packageName)
            }
            
            if (isPlayApp) {
                // Use the maximum totalTimeInForeground among stats for this package in this interval
                val timeInForeground = packageStats.maxOf { it.totalTimeInForeground }
                totalPlayTimeMs += timeInForeground
            }
        }
        
        val timePassedToday = now - startOfDay
        return totalPlayTimeMs.coerceAtMost(timePassedToday)
    }
}
