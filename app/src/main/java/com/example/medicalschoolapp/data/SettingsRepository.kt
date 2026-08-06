package com.example.medicalschoolapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.medicalschoolapp.model.StudySchedule
import com.example.medicalschoolapp.model.StudyTimeRange
import com.example.medicalschoolapp.model.TempPassword
import com.example.medicalschoolapp.util.PasswordHasher
import com.example.medicalschoolapp.util.TimeCalculator
import com.example.medicalschoolapp.util.UsageTracker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface SettingsRepository {
    val startDateFlow: Flow<Long>
    val initialBaseTimeFlow: Flow<Int>
    val isParentPasswordSetFlow: Flow<Boolean>
    val tempPasswordsFlow: Flow<List<TempPassword>>
    val appCategoriesFlow: Flow<Map<String, Boolean>>
    val dailyUsageStatsFlow: Flow<Pair<Long, Int>>
    val baseTimeFlow: Flow<Int?>
    val usageBaselineFlow: Flow<Long>
    val studySchedulesFlow: Flow<List<StudySchedule>>
    val isPseudoRestrictionFlow: Flow<Boolean>

    suspend fun setStartDate(date: Long)
    suspend fun setParentPassword(password: String)
    suspend fun verifyParentPassword(password: String): Boolean
    suspend fun addTempPassword(tempPassword: TempPassword)
    suspend fun markTempPasswordUsed(code: String)
    suspend fun cleanupExpiredTempPasswords()
    suspend fun setAppCategory(packageName: String, isPlay: Boolean)
    suspend fun isAppPlayCategory(packageName: String): Boolean
    suspend fun updateDailyUsage(dateStartOfDay: Long, usageTimeMs: Long)
    suspend fun addExtendedTime(minutes: Int, dateStartOfDay: Long)
    suspend fun getTodayPlayUsageMs(): Long
    suspend fun setBaseTime(minutes: Int)
    suspend fun setStudySchedules(schedules: List<StudySchedule>)
    suspend fun setPseudoRestriction(active: Boolean)
    suspend fun resetParentPassword()
    suspend fun clearStartDate()
    suspend fun notifyUnlockEvent()
    suspend fun isStudyTimeNow(): Boolean
    fun isRecentlyUnlocked(): Boolean
}

class LocalSettingsRepository(private val context: Context) : SettingsRepository {

    private val gson = Gson()

    companion object {
        val START_DATE = longPreferencesKey("start_date")
        val PARENT_PASSWORD = stringPreferencesKey("parent_password")
        val TEMP_PASSWORDS = stringPreferencesKey("temp_passwords")
        val APP_CATEGORIES = stringPreferencesKey("app_categories")
        val DAILY_USAGE_DATE = longPreferencesKey("daily_usage_date")
        val DAILY_USAGE_TIME_MS = longPreferencesKey("daily_usage_time_ms")
        val EXTENDED_TIME_MINS_TODAY = intPreferencesKey("extended_time_mins_today")
        val BASE_TIME_MINS = intPreferencesKey("base_time_mins")
        val USAGE_BASELINE_MS = longPreferencesKey("usage_baseline_ms")
        val INITIAL_BASE_TIME_MINS = intPreferencesKey("initial_base_time_mins")
        val LAST_UNLOCK_TIME_MS = longPreferencesKey("last_unlock_time_ms")
        val STUDY_SCHEDULES = stringPreferencesKey("study_schedules")
        val IS_PSEUDO_RESTRICTION = booleanPreferencesKey("is_pseudo_restriction")

        val defaultAllowedPackages = setOf(
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui", "com.android.incallui", "com.android.server.telecom",
            "com.android.phone", "com.android.settings", "com.android.vending",
            "com.google.android.googlequicksearchbox", "com.android.systemui",
            "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher",
            "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.openai.chatgpt"
        )
    }

    fun getLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolveInfos = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.map { it.activityInfo.packageName }.toSet()
    }

    override val startDateFlow: Flow<Long> = context.dataStore.data.map { it[START_DATE] ?: 0L }
    override val initialBaseTimeFlow: Flow<Int> = context.dataStore.data.map { 
        it[INITIAL_BASE_TIME_MINS] ?: (if (it.contains(START_DATE)) 120 else 240)
    }
    override val isParentPasswordSetFlow: Flow<Boolean> = context.dataStore.data.map { it[PARENT_PASSWORD] != null }
    override val tempPasswordsFlow: Flow<List<TempPassword>> = context.dataStore.data.map {
        val json = it[TEMP_PASSWORDS] ?: "[]"
        gson.fromJson(json, object : TypeToken<List<TempPassword>>() {}.type)
    }
    override val appCategoriesFlow: Flow<Map<String, Boolean>> = context.dataStore.data.map {
        val json = it[APP_CATEGORIES] ?: "{}"
        gson.fromJson(json, object : TypeToken<Map<String, Boolean>>() {}.type)
    }
    override val dailyUsageStatsFlow: Flow<Pair<Long, Int>> = context.dataStore.data.map {
        val storedDate = it[DAILY_USAGE_DATE] ?: 0L
        val todayStart = TimeCalculator.getStartOfDayMs(System.currentTimeMillis())
        if (storedDate != todayStart) Pair(0L, 0) else Pair(it[DAILY_USAGE_TIME_MS] ?: 0L, it[EXTENDED_TIME_MINS_TODAY] ?: 0)
    }
    override val baseTimeFlow: Flow<Int?> = context.dataStore.data.map { it[BASE_TIME_MINS] }
    override val usageBaselineFlow: Flow<Long> = context.dataStore.data.map { it[USAGE_BASELINE_MS] ?: 0L }
    override val studySchedulesFlow: Flow<List<StudySchedule>> = context.dataStore.data.map {
        val json = it[STUDY_SCHEDULES] ?: "[]"
        gson.fromJson(json, object : TypeToken<List<StudySchedule>>() {}.type)
    }
    override val isPseudoRestrictionFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_PSEUDO_RESTRICTION] ?: false }

    override suspend fun setStartDate(date: Long) {
        context.dataStore.edit { preferences ->
            if (!preferences.contains(START_DATE)) {
                preferences[START_DATE] = date
                preferences[INITIAL_BASE_TIME_MINS] = 240
            } else if (!preferences.contains(INITIAL_BASE_TIME_MINS)) {
                preferences[INITIAL_BASE_TIME_MINS] = 120
            }
        }
    }

    override suspend fun setParentPassword(password: String) {
        context.dataStore.edit { it[PARENT_PASSWORD] = PasswordHasher.hash(password) }
    }

    override suspend fun verifyParentPassword(password: String): Boolean {
        val storedHash = context.dataStore.data.map { it[PARENT_PASSWORD] }.first() ?: return false
        return PasswordHasher.matches(password, storedHash)
    }

    override suspend fun addTempPassword(tp: TempPassword) {
        context.dataStore.edit { 
            val list = it[TEMP_PASSWORDS]?.let { json -> gson.fromJson<MutableList<TempPassword>>(json, object : TypeToken<MutableList<TempPassword>>() {}.type) } ?: mutableListOf()
            list.add(tp)
            it[TEMP_PASSWORDS] = gson.toJson(list)
        }
    }

    override suspend fun markTempPasswordUsed(code: String) {
        context.dataStore.edit { 
            val list = it[TEMP_PASSWORDS]?.let { json -> gson.fromJson<MutableList<TempPassword>>(json, object : TypeToken<MutableList<TempPassword>>() {}.type) } ?: return@edit
            val index = list.indexOfFirst { p -> p.code == code && !p.isUsed }
            if (index != -1) { list[index] = list[index].copy(isUsed = true); it[TEMP_PASSWORDS] = gson.toJson(list) }
        }
    }

    override suspend fun cleanupExpiredTempPasswords() {
        context.dataStore.edit { 
            val list = it[TEMP_PASSWORDS]?.let { json -> gson.fromJson<List<TempPassword>>(json, object : TypeToken<List<TempPassword>>() {}.type) } ?: return@edit
            it[TEMP_PASSWORDS] = gson.toJson(list.filter { p -> p.expiresAt > System.currentTimeMillis() })
        }
    }

    override suspend fun isAppPlayCategory(packageName: String): Boolean {
        val map = appCategoriesFlow.first()
        if (map.containsKey(packageName)) return map[packageName]!!
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val launcherPackages = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        return !defaultAllowedPackages.contains(packageName) && !launcherPackages.contains(packageName)
    }

    override suspend fun setAppCategory(packageName: String, isPlay: Boolean) {
        context.dataStore.edit { 
            val json = it[APP_CATEGORIES] ?: "{}"
            val map = gson.fromJson<MutableMap<String, Boolean>>(json, object : TypeToken<MutableMap<String, Boolean>>() {}.type) ?: mutableMapOf()
            map[packageName] = isPlay
            it[APP_CATEGORIES] = gson.toJson(map)
        }
    }

    override suspend fun updateDailyUsage(dateStartOfDay: Long, usageTimeMs: Long) {
        context.dataStore.edit { 
            if ((it[DAILY_USAGE_DATE] ?: 0L) != dateStartOfDay) {
                it[DAILY_USAGE_DATE] = dateStartOfDay
                it[DAILY_USAGE_TIME_MS] = usageTimeMs
                it[EXTENDED_TIME_MINS_TODAY] = 0
                it[USAGE_BASELINE_MS] = 0L
            } else { it[DAILY_USAGE_TIME_MS] = usageTimeMs }
        }
    }

    override suspend fun addExtendedTime(minutes: Int, dateStartOfDay: Long) {
        context.dataStore.edit { 
            if ((it[DAILY_USAGE_DATE] ?: 0L) != dateStartOfDay) {
                it[DAILY_USAGE_DATE] = dateStartOfDay
                it[DAILY_USAGE_TIME_MS] = 0L
                it[EXTENDED_TIME_MINS_TODAY] = minutes
            } else { it[EXTENDED_TIME_MINS_TODAY] = (it[EXTENDED_TIME_MINS_TODAY] ?: 0) + minutes }
        }
    }

    override suspend fun getTodayPlayUsageMs(): Long = UsageTracker.getTodayPlayUsageMs(context, this)

    override suspend fun setBaseTime(minutes: Int) {
        context.dataStore.edit { preferences ->
            if (minutes < 0) {
                preferences.remove(BASE_TIME_MINS)
                preferences[USAGE_BASELINE_MS] = 0L
            } else {
                preferences[BASE_TIME_MINS] = minutes
                preferences[DAILY_USAGE_DATE] = TimeCalculator.getStartOfDayMs(System.currentTimeMillis())
                preferences[EXTENDED_TIME_MINS_TODAY] = 0
                // Use the fact that this is a suspend function to avoid runBlocking if possible,
                // but edit block is not suspend. However, we can use a temporary baseline.
            }
        }
        // Update baseline after edit
        val currentUsage = getTodayPlayUsageMs()
        context.dataStore.edit { it[USAGE_BASELINE_MS] = currentUsage }
    }

    override suspend fun setStudySchedules(schedules: List<StudySchedule>) {
        context.dataStore.edit { it[STUDY_SCHEDULES] = gson.toJson(schedules) }
    }

    override suspend fun setPseudoRestriction(active: Boolean) {
        context.dataStore.edit { it[IS_PSEUDO_RESTRICTION] = active }
    }

    override suspend fun resetParentPassword() {
        context.dataStore.edit { it.remove(PARENT_PASSWORD) }
    }

    override suspend fun clearStartDate() {
        context.dataStore.edit { 
            it.remove(START_DATE); it.remove(INITIAL_BASE_TIME_MINS)
            it.remove(DAILY_USAGE_DATE); it.remove(DAILY_USAGE_TIME_MS)
            it.remove(EXTENDED_TIME_MINS_TODAY); it.remove(USAGE_BASELINE_MS)
        }
    }

    override suspend fun notifyUnlockEvent() {
        context.dataStore.edit { it[LAST_UNLOCK_TIME_MS] = System.currentTimeMillis() }
    }

    override fun isRecentlyUnlocked(): Boolean {
        val lastUnlock = kotlinx.coroutines.runBlocking { context.dataStore.data.first()[LAST_UNLOCK_TIME_MS] ?: 0L }
        return (System.currentTimeMillis() - lastUnlock) < (5 * 60 * 1000L)
    }

    override suspend fun isStudyTimeNow(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val currentTimeInMins = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val json = context.dataStore.data.first()[STUDY_SCHEDULES] ?: "[]"
        val schedules: List<StudySchedule> = gson.fromJson(json, object : TypeToken<List<StudySchedule>>() {}.type)
        val todaySchedule = schedules.find { it.dayOfWeek == calendar.get(java.util.Calendar.DAY_OF_WEEK) } ?: return false
        return todaySchedule.ranges.any { currentTimeInMins in (it.startHour * 60 + it.startMinute) until (it.endHour * 60 + it.endMinute) }
    }
}
