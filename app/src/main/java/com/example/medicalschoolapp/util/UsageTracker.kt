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
        // val pm = context.packageManager (unused)

        var totalPlayTimeMs = 0L

        // Group by package to handle multiple entries if they exist
        val aggregatedStats = stats.groupBy { it.packageName }

        for ((packageName, packageStats) in aggregatedStats) {
            if (packageName == context.packageName) continue
            
            // ホーム画面（ランチャー）や電話、設定などは計算から除外する
            if (launcherPackages.contains(packageName) || defaultAllowed.contains(packageName)) {
                continue
            }

            // デフォルトの判定ロジック:
            // 1. 親が明示的に設定している場合はそれに従う
            // 2. 設定されていない場合は、ランチャーやシステム許可リストに含まれていなければ「遊び」とみなす
            val isPlayApp = categoriesMap[packageName] ?: run {
                !defaultAllowed.contains(packageName) && !launcherPackages.contains(packageName)
            }
            
            if (isPlayApp) {
                // そのアプリの今日のフォアグラウンド時間を合計
                val timeInForeground = packageStats.maxByOrNull { it.lastTimeUsed }?.totalTimeInForeground ?: 0L
                totalPlayTimeMs += timeInForeground
            }
        }
        
        // Safety cap: usage cannot exceed time passed since start of day
        val timePassedToday = now - startOfDay
        return totalPlayTimeMs.coerceAtMost(timePassedToday)
    }
}
