package com.example.medicalschoolapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = LocalSettingsRepository(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // Ignore system UI and self
            if (packageName == "com.android.systemui" || packageName == applicationContext.packageName) {
                return
            }

            if (packageName != currentForegroundPackage) {
                currentForegroundPackage = packageName
                handleAppChange(packageName)
            }
        }
    }

    private fun handleAppChange(packageName: String) {
        monitorJob?.cancel()
        
        serviceScope.launch {
            val categoriesMap = repository.appCategoriesFlow.first()
            val isPlayApp = categoriesMap[packageName] ?: true // Default is restricted
            
            if (isPlayApp) {
                // Start monitoring loop for play app
                monitorJob = launch {
                    while (isActive) {
                        checkAndBlockIfTimeUp(packageName)
                        delay(10000) // check every 10 seconds while play app is in foreground
                    }
                }
            }
        }
    }

    private suspend fun checkAndBlockIfTimeUp(packageName: String) {
        val usedTimeMs = repository.getTodayPlayUsageMs()
        val startDateMs = repository.startDateFlow.first()
        val stats = repository.dailyUsageStatsFlow.first()
        val extendedTimeMins = stats.second
        val now = System.currentTimeMillis()

        val remainingMs = TimeCalculator.getRemainingTimeTodayMs(
            startDateMs = startDateMs,
            currentDateMs = now,
            usedTimeMs = usedTimeMs,
            extendedTimeMins = extendedTimeMins
        )

        // Also update local DB for UI to reflect latest accurate time quickly
        repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(now), usedTimeMs)

        if (remainingMs <= 0) {
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
