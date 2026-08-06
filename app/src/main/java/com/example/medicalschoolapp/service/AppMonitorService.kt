package com.example.medicalschoolapp.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.example.medicalschoolapp.BlockActivity
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.data.SettingsRepository
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: SettingsRepository
    private var monitorJob: Job? = null
    private var currentForegroundPackage: String? = null

    companion object {
        private const val TAG = "AppMonitorService"
        private const val CHANNEL_ID = "monitor_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        repository = LocalSettingsRepository(applicationContext)
        setupForegroundNotification()
        startPersistentMonitor()
    }

    private fun setupForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "アプリ監視サービス", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("制限機能が稼働中")
            .setContentText("お子様のスマホ利用を安全に守っています")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 全ての関連イベント（ウィンドウ変更、フォーカス変更等）に対してチェック
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != currentForegroundPackage) {
            currentForegroundPackage = packageName
        }
    }

    private fun startPersistentMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val foregroundPackage = (rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage ?: "").trim()
                
                if (foregroundPackage.isNotEmpty() && foregroundPackage != applicationContext.packageName) {
                    if (repository.isRecentlyUnlocked()) {
                        Log.d(TAG, "GRACE PERIOD: Skipping block for $foregroundPackage")
                    } else if (isSecurityRiskPackage(foregroundPackage)) {
                        checkAndBlockUninstallAttempt()
                    } else if (foregroundPackage != "com.android.systemui") {
                        val isPlay = repository.isAppPlayCategory(foregroundPackage)
                        if (isPlay) {
                            checkAndBlockIfTimeUp(foregroundPackage)
                        }
                    }
                }
                delay(1500)
            }
        }
    }

    private fun isSecurityRiskPackage(packageName: String): Boolean {
        return packageName == "com.android.settings" || 
               packageName == "com.android.packageinstaller" || 
               packageName == "com.google.android.packageinstaller" || 
               packageName == "com.samsung.android.packageinstaller"
    }

    private fun checkAndBlockUninstallAttempt() {
        val root = rootInActiveWindow ?: return
        val appName = getString(com.example.medicalschoolapp.R.string.app_name)
        val hasAppName = root.findAccessibilityNodeInfosByText(appName).isNotEmpty()
        val dangerousKeywords = listOf("アンインストール", "削除", "無効", "停止", "解除", "Uninstall", "Delete", "Disable", "Force stop")
        var isTargetingThisApp = hasAppName
        if (!isTargetingThisApp) {
            for (keyword in dangerousKeywords) {
                if (root.findAccessibilityNodeInfosByText(keyword).isNotEmpty()) {
                    isTargetingThisApp = true
                    break
                }
            }
        }
        if (isTargetingThisApp) {
            serviceScope.launch {
                if (repository.isStudyTimeNow() || repository.isPseudoRestrictionFlow.first() || calculateRemainingMs() < 60000L) {
                    launchBlockActivity()
                }
            }
        }
    }

    private suspend fun calculateRemainingMs(): Long {
        val now = System.currentTimeMillis()
        val usedTimeMs = repository.getTodayPlayUsageMs()
        val startDateMs = repository.startDateFlow.first()
        val initialBaseMins = repository.initialBaseTimeFlow.first()
        val stats = repository.dailyUsageStatsFlow.first()
        val manualBaseMins = repository.baseTimeFlow.first()
        val baselineMs = repository.usageBaselineFlow.first()
        
        val effectiveUsedMs = (usedTimeMs - baselineMs).coerceAtLeast(0L)
        val baseAllowedMins = manualBaseMins ?: TimeCalculator.getBaseAllowedMinutes(startDateMs, now, initialBaseMins)
        val totalAllowedMs = (baseAllowedMins.toLong() + stats.second.toLong()) * 60000L
        val remainingMs = totalAllowedMs - effectiveUsedMs
        
        // 詳細な計算ログを出力
        Log.d(TAG, "TIME CHECK: Base=$baseAllowedMins, Bonus=${stats.second}, Used=${usedTimeMs/60000}m, Baseline=${baselineMs/60000}m -> Remaining=${remainingMs/1000}s")
        
        return remainingMs
    }

    private suspend fun checkAndBlockIfTimeUp(packageName: String): Boolean {
        // 解除直後の猶予期間中かチェック
        if (repository.isRecentlyUnlocked()) {
            Log.d(TAG, "Restriction skipped: Within 5min grace period after unlock")
            return false
        }

        // 1. まず勉強時間・疑似制限をチェック
        val isStudyTime = repository.isStudyTimeNow()
        val isPseudoActive = repository.isPseudoRestrictionFlow.first()
        
        if (isStudyTime || isPseudoActive) {
            val actualForeground = rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage
            if (actualForeground != applicationContext.packageName && actualForeground != "com.android.systemui") {
                Log.w(TAG, "BLOCKING $packageName due to Schedule/Pseudo")
                launchBlockActivity()
                return true
            }
            return false
        }

        // 2. 残り時間をチェック
        val remainingMs = calculateRemainingMs()
        
        // UIに反映させるためキャッシュを更新
        repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(System.currentTimeMillis()), repository.getTodayPlayUsageMs())

        if (remainingMs < 60000L) { // 1分未満でブロック
            val actualForeground = rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage
            if (actualForeground != applicationContext.packageName && 
                actualForeground != "com.android.systemui" && 
                actualForeground != "com.android.settings") {
                Log.w(TAG, "BLOCKING $packageName: Time Up (${remainingMs/1000}s remaining)")
                launchBlockActivity()
                return true
            }
        }
        return false
    }

    private fun launchBlockActivity() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
