package com.example.medicalschoolapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.medicalschoolapp.data.SettingsRepository
import com.example.medicalschoolapp.model.StudySchedule
import com.example.medicalschoolapp.model.TempPassword
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: SettingsRepository) : ViewModel() {

    val startDate = repository.startDateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())
    val isParentPasswordSet = repository.isParentPasswordSetFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tempPasswords = repository.tempPasswordsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val appCategories = repository.appCategoriesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val baseTimeMins = repository.baseTimeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val initialBaseTimeMins = repository.initialBaseTimeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 240)
    val studySchedules = repository.studySchedulesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isPseudoRestrictionActive = repository.isPseudoRestrictionFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Bumped whenever we want remainingTimeMs to re-query live usage (periodic ticker,
    // or right after a temp password grants extra time) rather than relying only on
    // whatever the accessibility service last wrote to the cache.
    private val refreshTrigger = MutableStateFlow(0L)

    private val liveUsedTimeMsFlow = refreshTrigger.map { repository.getTodayPlayUsageMs() }
    val todayUsedTimeMs = liveUsedTimeMsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val remainingTimeMs: StateFlow<Long> = combine(
        repository.startDateFlow,
        liveUsedTimeMsFlow,
        repository.dailyUsageStatsFlow,
        repository.baseTimeFlow,
        repository.initialBaseTimeFlow,
        repository.usageBaselineFlow
    ) { flows ->
        val start = flows[0] as Long
        val usedTimeMs = flows[1] as Long
        val stats = flows[2] as Pair<Long, Int>
        val manualBaseMins = flows[3] as Int?
        val initialBaseMins = flows[4] as Int
        val baselineMs = flows[5] as Long

        val extendedTimeMins = stats.second
        val now = System.currentTimeMillis()
        
        // Use manual override if set, otherwise use the auto-calculating base time
        val baseMins = manualBaseMins ?: TimeCalculator.getBaseAllowedMinutes(start, now, initialBaseMins)

        // 基準点（設定した時点の使用量）を差し引いて、純粋にそれ以降の使用量を計算
        val effectiveUsedMs = (usedTimeMs - baselineMs).coerceAtLeast(0L)

        TimeCalculator.getRemainingTimeTodayMs(start, now, effectiveUsedMs, extendedTimeMins, baseMins)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 120 * 60 * 1000L)

    val commonTestCountdown = MutableStateFlow(TimeCalculator.getCountdownToCommonTest())

    init {
        viewModelScope.launch {
            repository.cleanupExpiredTempPasswords()
            // Ensure start date is fixed upon first launch if not already set
            val currentStart = repository.startDateFlow.first()
            repository.setStartDate(currentStart)
        }
    }

    fun updateCountdown() {
        commonTestCountdown.value = TimeCalculator.getCountdownToCommonTest()
    }

    fun refreshRemainingTime() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    fun setParentPassword(password: String) {
        viewModelScope.launch {
            repository.setParentPassword(password)
        }
    }

    fun verifyParentPassword(password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(repository.verifyParentPassword(password))
        }
    }

    fun toggleAppCategory(packageName: String, currentIsPlay: Boolean) {
        viewModelScope.launch {
            repository.setAppCategory(packageName, !currentIsPlay)
        }
    }

    fun setBaseTime(minutes: Int) {
        viewModelScope.launch {
            // 現在の累積使用時間を取得
            val currentUsage = repository.getTodayPlayUsageMs()
            // 使用時間を上書き（リセット）してから基本時間を設定
            repository.updateDailyUsage(TimeCalculator.getStartOfDayMs(System.currentTimeMillis()), currentUsage)
            repository.setBaseTime(minutes)
            refreshRemainingTime()
        }
    }

    fun updateStudySchedules(schedules: List<StudySchedule>) {
        viewModelScope.launch {
            repository.setStudySchedules(schedules)
        }
    }

    fun togglePseudoRestriction() {
        viewModelScope.launch {
            val current = repository.isPseudoRestrictionFlow.first()
            repository.setPseudoRestriction(!current)
        }
    }

    fun generateTempPassword(expiresInHours: Int = 24) {
        viewModelScope.launch {
            val code = (100000..999999).random().toString()
            val expiresAt = System.currentTimeMillis() + (expiresInHours * 60 * 60 * 1000L)
            val tp = TempPassword(code = code, expiresAt = expiresAt)
            repository.addTempPassword(tp)
        }
    }

    fun addExtensionTime(minutes: Int) {
        viewModelScope.launch {
            repository.notifyUnlockEvent() // 解除イベントを通知
            repository.addExtendedTime(minutes, TimeCalculator.getStartOfDayMs(System.currentTimeMillis()))
            refreshRemainingTime()
        }
    }

    fun resetStartDate() {
        viewModelScope.launch {
            repository.clearStartDate()
            // 削除後、現在の時刻で再初期化
            repository.setStartDate(System.currentTimeMillis())
            refreshRemainingTime()
        }
    }

    fun useTempPassword(code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val passwords = repository.tempPasswordsFlow.first()
            val tp = passwords.find { it.code == code && !it.isUsed && it.expiresAt > System.currentTimeMillis() }
            if (tp != null) {
                repository.markTempPasswordUsed(code)
                repository.addExtendedTime(tp.extensionMinutes, TimeCalculator.getStartOfDayMs(System.currentTimeMillis()))
                refreshRemainingTime()
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }
}

fun mainViewModelFactory(repository: SettingsRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
