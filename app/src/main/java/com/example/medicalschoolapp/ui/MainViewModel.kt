package com.example.medicalschoolapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.medicalschoolapp.data.SettingsRepository
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

    // Bumped whenever we want remainingTimeMs to re-query live usage (periodic ticker,
    // or right after a temp password grants extra time) rather than relying only on
    // whatever the accessibility service last wrote to the cache.
    private val refreshTrigger = MutableStateFlow(0L)

    private val liveUsedTimeMsFlow = refreshTrigger.map { repository.getTodayPlayUsageMs() }

    val remainingTimeMs: StateFlow<Long> = combine(
        repository.startDateFlow,
        liveUsedTimeMsFlow,
        repository.dailyUsageStatsFlow
    ) { start, usedTimeMs, stats ->
        val extendedTimeMins = stats.second
        val now = System.currentTimeMillis()
        TimeCalculator.getRemainingTimeTodayMs(start, now, usedTimeMs, extendedTimeMins)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 120 * 60 * 1000L)

    val commonTestCountdown = MutableStateFlow(TimeCalculator.getCountdownToCommonTest())

    init {
        viewModelScope.launch {
            repository.cleanupExpiredTempPasswords()
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

    fun generateTempPassword(expiresInHours: Int = 24) {
        viewModelScope.launch {
            val code = (100000..999999).random().toString()
            val expiresAt = System.currentTimeMillis() + (expiresInHours * 60 * 60 * 1000L)
            val tp = TempPassword(code = code, expiresAt = expiresAt)
            repository.addTempPassword(tp)
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
