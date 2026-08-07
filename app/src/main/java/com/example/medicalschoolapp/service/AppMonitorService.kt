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
    private var lastCheckTimeMs = 0L
    private var accumulatedSessionMs = 0L

    companion object {
        private const val TAG = "AppMonitorService"
        private const val CHANNEL_ID = "monitor_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
            .setContentTitle("医学部合格アプリ：稼働中")
            .setContentText("利用時間を秒単位で計測しています")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != currentForegroundPackage) {
            currentForegroundPackage = pkg
            lastCheckTimeMs = System.currentTimeMillis()
        }
    }

    private fun startPersistentMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            lastCheckTimeMs = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastCheckTimeMs
                lastCheckTimeMs = now

                val foreground = (rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage ?: "").trim()
                
                if (foreground.isNotEmpty() && !isSystemWhitelisted(foreground)) {
                    val isPlay = repository.isAppPlayCategory(foreground)
                    if (isPlay) {
                        // 1秒ごとに手動加算
                        accumulatedSessionMs += elapsed
                        if (accumulatedSessionMs >= 2000) {
                            repository.addUsageMs(accumulatedSessionMs)
                            accumulatedSessionMs = 0
                        }
                    }
                    
                    if (!repository.isRecentlyUnlocked()) {
                        checkAndBlock(foreground, isPlay)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun isSystemWhitelisted(pkg: String): Boolean = 
        pkg == applicationContext.packageName || pkg == "com.android.systemui" || LocalSettingsRepository.defaultAllowedPackages.contains(pkg)

    private suspend fun checkAndBlock(packageName: String, isPlay: Boolean) {
        // アンインストール防止 (設定画面等)
        if (packageName == "com.android.settings" || packageName.contains("packageinstaller")) {
            checkAndBlockUninstallAttempt()
            return
        }

        // 勉強時間・疑似制限
        if ((repository.isStudyTimeNow() || repository.isPseudoRestrictionFlow.first()) && isPlay) {
            launchBlockActivity()
            return
        }

        // 時間切れ判定
        if (isPlay && calculateRemainingMs() < 60000L) {
            launchBlockActivity()
        }
    }

    private fun checkAndBlockUninstallAttempt() {
        val root = rootInActiveWindow ?: return
        val appName = getString(com.example.medicalschoolapp.R.string.app_name)
        val dangerous = listOf("アンインストール", "削除", "無効", "停止", "解除", "Uninstall", "Delete", "Disable", "Force stop")
        var isTarget = root.findAccessibilityNodeInfosByText(appName).isNotEmpty()
        if (!isTarget) { for (k in dangerous) { if (root.findAccessibilityNodeInfosByText(k).isNotEmpty()) { isTarget = true; break } } }
        if (isTarget) launchBlockActivity()
    }

    private suspend fun calculateRemainingMs(): Long {
        val stats = repository.dailyUsageStatsFlow.first()
        val base = repository.baseTimeFlow.first()
        val start = repository.startDateFlow.first()
        val initial = repository.initialBaseTimeFlow.first()
        val baseline = repository.usageBaselineFlow.first()
        val systemUsedMs = repository.getTodayPlayUsageMs()
        
        val baseMins = base ?: initial
        // 手動計測(stats.first)とシステム計測の差分の大きい方を採用
        val actualUsedMs = maxOf(stats.first, (systemUsedMs - baseline).coerceAtLeast(0L))
        return (baseMins + stats.second) * 60000L - actualUsedMs
    }

    private fun launchBlockActivity() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); serviceScope.cancel() }
}
