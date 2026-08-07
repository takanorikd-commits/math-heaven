package com.example.medicalschoolapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.medicalschoolapp.data.SettingsRepository
import com.example.medicalschoolapp.model.StudySchedule
import com.example.medicalschoolapp.model.TempPassword
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(val repository: SettingsRepository) : ViewModel() {

    val startDate = repository.startDateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val isParentPasswordSet = repository.isParentPasswordSetFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tempPasswords = repository.tempPasswordsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val appCategories = repository.appCategoriesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val baseTimeMins = repository.baseTimeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val initialBaseTimeMins = repository.initialBaseTimeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 240)
    val studySchedules = repository.studySchedulesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isPseudoRestrictionActive = repository.isPseudoRestrictionFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val todayUsedTimeMs = repository.dailyUsageStatsFlow.map { it.first }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // AppMonitorService / ウィジェットと完全に同じ式(TimeCalculator.getRemainingTimeTodayMs)を使い、1ミリ秒もズレさせない。
    // DataStoreのFlowを直接combineしているので、サービス側が addUsageMs() で書き込むたびに自動で更新される。
    val remainingTimeMs: StateFlow<Long> = combine(
        repository.dailyUsageStatsFlow,
        repository.baseTimeFlow,
        repository.initialBaseTimeFlow
    ) { stats, manualBase, initialBase ->
        val baseMins = manualBase ?: initialBase
        TimeCalculator.getRemainingTimeTodayMs(stats.first, stats.second, baseMins)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val commonTestCountdown = MutableStateFlow(TimeCalculator.getCountdownToCommonTest())

    init {
        viewModelScope.launch {
            repository.cleanupExpiredTempPasswords()
            val currentStart = repository.startDateFlow.first()
            if (currentStart == 0L) repository.setStartDate(System.currentTimeMillis())
        }
    }

    fun updateCountdown() { commonTestCountdown.value = TimeCalculator.getCountdownToCommonTest() }
    fun setParentPassword(p: String) { viewModelScope.launch { repository.setParentPassword(p) } }
    fun verifyParentPassword(p: String, onResult: (Boolean) -> Unit) { viewModelScope.launch { onResult(repository.verifyParentPassword(p)) } }
    fun toggleAppCategory(pkg: String, isPlay: Boolean) { viewModelScope.launch { repository.setAppCategory(pkg, !isPlay) } }
    fun setBaseTime(mins: Int) { viewModelScope.launch { repository.setBaseTime(mins) } }
    fun updateStudySchedules(s: List<StudySchedule>) { viewModelScope.launch { repository.setStudySchedules(s) } }
    fun togglePseudoRestriction() { viewModelScope.launch { repository.setPseudoRestriction(!repository.isPseudoRestrictionFlow.first()) } }
    fun generateTempPassword() { viewModelScope.launch { val code = (100000..999999).random().toString(); repository.addTempPassword(TempPassword(code = code, expiresAt = System.currentTimeMillis() + 86400000L)) } }
    fun addExtensionTime(mins: Int) { viewModelScope.launch { repository.notifyUnlockEvent(); repository.addExtendedTime(mins, TimeCalculator.getStartOfDayMs(System.currentTimeMillis())) } }
    fun resetStartDate() { viewModelScope.launch { repository.clearStartDate(); repository.setStartDate(System.currentTimeMillis()) } }

    fun useTempPassword(code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val passwords = repository.tempPasswordsFlow.first()
            val tp = passwords.find { it.code == code && !it.isUsed && it.expiresAt > System.currentTimeMillis() }
            if (tp != null) {
                repository.markTempPasswordUsed(code)
                repository.addExtendedTime(tp.extensionMinutes, TimeCalculator.getStartOfDayMs(System.currentTimeMillis()))
                onResult(true)
            } else onResult(false)
        }
    }
}

fun mainViewModelFactory(repository: SettingsRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
