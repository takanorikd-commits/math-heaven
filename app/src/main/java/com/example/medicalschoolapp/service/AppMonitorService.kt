package com.example.medicalschoolapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.medicalschoolapp.BlockActivity
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.data.SettingsRepository
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

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
        
        // 常時監視ループを開始
        startPersistentMonitor()
    }

    private fun setupForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "アプリ監視サービス",
                NotificationManager.IMPORTANCE_LOW
            )
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            if (packageName != currentForegroundPackage) {
                Log.d(TAG, "Foreground app changed (Event): $packageName")
                currentForegroundPackage = packageName
            }
        }
    }

    private fun startPersistentMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                // 現在最前面にいるアプリのパッケージ名を取得
                val foregroundPackage = rootInActiveWindow?.packageName?.toString() 
                    ?: currentForegroundPackage 
                    ?: ""
                
                if (foregroundPackage.isNotEmpty() && foregroundPackage != applicationContext.packageName) {
                    
                    // パスワードで解除直後の場合は、一切のブロックを一時停止する
                    if (repository.isRecentlyUnlocked()) {
                        Log.d(TAG, "Restriction suspended due to recent unlock")
                        return@launch
                    }

                    // システムUIは監視対象外
                    if (foregroundPackage == "com.android.systemui") {
                        // skip
                    } else if (isSecurityRiskPackage(foregroundPackage)) {
                        // 1. アンインストール防止チェック
                        // ただし、現在はデバッグを容易にするため、設定画面自体の起動は許可し、
                        // 特定の危険操作のみをチェックする（必要最小限のガード）
                        checkAndBlockUninstallAttempt()
                    } else {
                        // 2. 通常の遊びアプリ制限チェック
                        val isPlay = repository.isAppPlayCategory(foregroundPackage)
                        if (isPlay) {
                            checkAndBlockIfTimeUp(foregroundPackage)
                        }
                    }
                }
                
                delay(2000) // 2秒ごとにチェック
            }
        }
    }

    private fun isSecurityRiskPackage(packageName: String): Boolean {
        // 設定画面も含めて制限対象とする（お子様が勝手に設定を変えないように）
        return packageName == "com.android.settings" || 
               packageName == "com.android.packageinstaller" || 
               packageName == "com.google.android.packageinstaller" || 
               packageName == "com.samsung.android.packageinstaller"
    }

    private fun checkAndBlockUninstallAttempt() {
        val root = rootInActiveWindow ?: return
        
        // アプリ名または「ユーザー補助」「デバイス管理」などのキーワードが含まれているかチェック
        val appName = getString(com.example.medicalschoolapp.R.string.app_name)
        val hasAppName = root.findAccessibilityNodeInfosByText(appName).isNotEmpty()
        
        // 設定画面自体が保護対象なので、設定画面が開かれただけでチェックを開始する
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
            // 制限中であれば、設定/削除画面をブロックする
            serviceScope.launch {
                val remainingMs = calculateRemainingMs()
                val isPseudoActive = repository.isPseudoRestrictionFlow.first()
                val isStudyTime = repository.isStudyTimeNow()
                
                if (isPseudoActive || isStudyTime || remainingMs <= 0) {
                    Log.w(TAG, "BLOCKING Access to Settings/Uninstall")
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
        val extendedTimeMins = stats.second

        // 設定時点の累積使用量を差し引いて実質的な使用量を計算
        val effectiveUsedMs = (usedTimeMs - baselineMs).coerceAtLeast(0L)

        val baseAllowedMins = manualBaseMins ?: TimeCalculator.getBaseAllowedMinutes(startDateMs, now, initialBaseMins)
        return (baseAllowedMins + extendedTimeMins) * 60 * 1000L - effectiveUsedMs
    }

    /**
     * @return ブロックを実行した場合は true
     */
    private suspend fun checkAndBlockIfTimeUp(packageName: String): Boolean {
        val remainingMs = calculateRemainingMs()
        val now = System.currentTimeMillis()
        
        // キャッシュ更新（使用統計用）
        repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(now), repository.getTodayPlayUsageMs())

        val isPseudoActive = repository.isPseudoRestrictionFlow.first()
        val isStudyTime = repository.isStudyTimeNow()
        
        if (isPseudoActive || isStudyTime || remainingMs <= 0) {
            // 現在の本当の最前面を確認して、自アプリでなければブロック
            val actualForeground = rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage
            if (actualForeground != applicationContext.packageName && actualForeground != "com.android.systemui") {
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

    override fun onInterrupt() {
        monitorJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
