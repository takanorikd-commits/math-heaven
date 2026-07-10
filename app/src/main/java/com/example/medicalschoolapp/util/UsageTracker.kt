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

        // Using queryUsageStats for better control over aggregation
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        if (stats.isNullOrEmpty()) return 0L

        val categoriesMap = repository.appCategoriesFlow.first()
        val launcherPackages = if (repository is LocalSettingsRepository) {
            repository.getLauncherPackages()
        } else {
            emptySet<String>()
        }
        val defaultAllowed = LocalSettingsRepository.defaultAllowedPackages
        val pm = context.packageManager

        var totalPlayTimeMs = 0L

        // Group by package to handle multiple entries if they exist
        val aggregatedStats = stats.groupBy { it.packageName }

        for ((packageName, packageStats) in aggregatedStats) {
            if (packageName == context.packageName) continue
            
            // Only count apps that the user can actually launch
            val hasLaunchIntent = try {
                pm.getLaunchIntentForPackage(packageName) != null
            } catch (e: Exception) {
                false
            }
            if (!hasLaunchIntent) continue
            
            val isPlayApp = categoriesMap[packageName] ?: run {
                !defaultAllowed.contains(packageName) && !launcherPackages.contains(packageName)
            }
            
            if (isPlayApp) {
                // Sum foreground time for this package in the interval
                totalPlayTimeMs += packageStats.sumOf { it.totalTimeInForeground }
            }
        }
        
        // Safety cap: usage cannot exceed time passed since start of day
        val timePassedToday = now - startOfDay
        return totalPlayTimeMs.coerceAtMost(timePassedToday)
    }
}
