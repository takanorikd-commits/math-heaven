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

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: SettingsRepository
    private var monitorJob: Job? = null
    private var currentForegroundPackage: String? = null

    companion object {
        private const val TAG = "AppMonitorService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        repository = LocalSettingsRepository(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            if (packageName == "com.android.systemui" || packageName == applicationContext.packageName) {
                return
            }

            if (packageName != currentForegroundPackage) {
                Log.d(TAG, "Foreground app changed to: $packageName")
                currentForegroundPackage = packageName
                handleAppChange(packageName)
            }
        }
    }

    private fun handleAppChange(packageName: String) {
        monitorJob?.cancel()
        
        serviceScope.launch {
            val isPlay = repository.isAppPlayCategory(packageName)
            Log.d(TAG, "App $packageName categorized as Play: $isPlay")
            
            if (isPlay) {
                // Initial check
                checkAndBlockIfTimeUp(packageName)

                // Periodic check
                monitorJob = launch {
                    while (isActive) {
                        delay(5000) // check more frequently for better blocking
                        checkAndBlockIfTimeUp(packageName)
                    }
                }
            }
        }
    }

    private suspend fun checkAndBlockIfTimeUp(packageName: String) {
        val usedTimeMs = repository.getTodayPlayUsageMs()
        val startDateMs = repository.startDateFlow.first()
        val stats = repository.dailyUsageStatsFlow.first()
        val manualBaseMins = repository.baseTimeFlow.first()
        val extendedTimeMins = stats.second
        val now = System.currentTimeMillis()

        val baseAllowedMins = manualBaseMins ?: TimeCalculator.getBaseAllowedMinutes(startDateMs, now)

        val remainingMs = TimeCalculator.getRemainingTimeTodayMs(
            startDateMs = startDateMs,
            currentDateMs = now,
            usedTimeMs = usedTimeMs,
            extendedTimeMins = extendedTimeMins,
            baseAllowedMins = baseAllowedMins
        )

        Log.d(TAG, "Checking $packageName: used=${usedTimeMs/1000}s, remaining=${remainingMs/1000}s")

        // Update cache
        repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(now), usedTimeMs)

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
