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
        startForegroundService()
    }

    private fun startForegroundService() {
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
            
            if (packageName == "com.android.systemui") {
                return
            }

            if (packageName != currentForegroundPackage) {
                Log.d(TAG, "Foreground app changed to: $packageName")
                currentForegroundPackage = packageName

                // 自アプリが最前面に来た場合は、制限対象アプリの監視を停止する
                if (packageName == applicationContext.packageName) {
                    monitorJob?.cancel()
                    return
                }

                handleAppChange(packageName)
            }
        }
    }

    private fun handleAppChange(packageName: String) {
        monitorJob?.cancel()
        
        serviceScope.launch {
            val isPlay = repository.isAppPlayCategory(packageName)
            val isPseudoActive = repository.isPseudoRestrictionFlow.first()
            
            Log.d(TAG, "App $packageName: isPlay=$isPlay, isPseudoActive=$isPseudoActive")
            
            if (isPlay || isPseudoActive) {
                // Initial check
                checkAndBlockIfTimeUp(packageName)

                // Periodic check
                monitorJob = launch {
                    while (isActive) {
                        delay(2000) // 2秒ごとにチェック（より頻繁に）
                        checkAndBlockIfTimeUp(packageName)
                    }
                }
            }
        }
    }

    private suspend fun checkAndBlockIfTimeUp(packageName: String) {
        val now = System.currentTimeMillis()
        val usedTimeMs = repository.getTodayPlayUsageMs()
        val startDateMs = repository.startDateFlow.first()
        val stats = repository.dailyUsageStatsFlow.first()
        val manualBaseMins = repository.baseTimeFlow.first()
        val extendedTimeMins = stats.second

        val baseAllowedMins = manualBaseMins ?: TimeCalculator.getBaseAllowedMinutes(startDateMs, now)

        val totalAllowedMins = baseAllowedMins + extendedTimeMins
        val totalAllowedMs = totalAllowedMins * 60 * 1000L
        val remainingMs = totalAllowedMs - usedTimeMs

        Log.d(TAG, "Checking $packageName: used=${usedTimeMs/1000}s, totalAllowed=${totalAllowedMs/1000}s, remaining=${remainingMs/1000}s")

        // Update cache
        repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(now), usedTimeMs)

        val isPseudoActive = repository.isPseudoRestrictionFlow.first()
        val isStudyTime = repository.isStudyTimeNow()
        
        if (isPseudoActive || isStudyTime) {
            Log.w(TAG, "BLOCKING $packageName - Restriction active (Pseudo: $isPseudoActive, Study: $isStudyTime)")
            launchBlockActivity()
            return
        }

        if (remainingMs <= 0) {
            Log.w(TAG, "BLOCKING $packageName - Time is up!")
            launchBlockActivity()
        }
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
