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

/**
 * Abstraction over where settings/state live. Kept as an interface so a future
 * parent-device sync backend can be swapped in behind [MainViewModel]/services
 * without touching every call site.
 */
interface SettingsRepository {
    val startDateFlow: Flow<Long>
    val isParentPasswordSetFlow: Flow<Boolean>
    val tempPasswordsFlow: Flow<List<TempPassword>>
    val appCategoriesFlow: Flow<Map<String, Boolean>>
    val dailyUsageStatsFlow: Flow<Pair<Long, Int>>
    val baseTimeFlow: Flow<Int?>
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
    fun isStudyTimeNow(): Boolean
}

class LocalSettingsRepository(private val context: Context) : SettingsRepository {

    private val gson = Gson()

    companion object {
        val START_DATE = longPreferencesKey("start_date")
        val PARENT_PASSWORD = stringPreferencesKey("parent_password") // stores "salt:hash", never plain text
        val TEMP_PASSWORDS = stringPreferencesKey("temp_passwords") // JSON list
        val APP_CATEGORIES = stringPreferencesKey("app_categories") // JSON map: packageName -> Boolean (true = play, false = study)
        val DAILY_USAGE_DATE = longPreferencesKey("daily_usage_date") // start-of-day timestamp this usage/extension applies to
        val DAILY_USAGE_TIME_MS = longPreferencesKey("daily_usage_time_ms")
        val EXTENDED_TIME_MINS_TODAY = intPreferencesKey("extended_time_mins_today")
        val BASE_TIME_MINS = intPreferencesKey("base_time_mins")
        val STUDY_SCHEDULES = stringPreferencesKey("study_schedules") // JSON list
        val IS_PSEUDO_RESTRICTION = booleanPreferencesKey("is_pseudo_restriction")

        val defaultAllowedPackages = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.googlequicksearchbox",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.inputmethod.latin",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging"
        )
    }

    fun getLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolveInfos = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.map { it.activityInfo.packageName }.toSet()
    }

    override val startDateFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[START_DATE] ?: System.currentTimeMillis()
    }

    override val isParentPasswordSetFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PARENT_PASSWORD] != null
    }

    override val tempPasswordsFlow: Flow<List<TempPassword>> = context.dataStore.data.map { preferences ->
        val json = preferences[TEMP_PASSWORDS] ?: "[]"
        val type = object : TypeToken<List<TempPassword>>() {}.type
        gson.fromJson(json, type)
    }

    override val appCategoriesFlow: Flow<Map<String, Boolean>> = context.dataStore.data.map { preferences ->
        val json = preferences[APP_CATEGORIES] ?: "{}"
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        gson.fromJson(json, type)
    }

    // Self-healing: if the stored day doesn't match "today", treat usage/extension as
    // not-yet-reset rather than showing yesterday's stale numbers until the next write.
    override val dailyUsageStatsFlow: Flow<Pair<Long, Int>> = context.dataStore.data.map { preferences ->
        val storedDate = preferences[DAILY_USAGE_DATE] ?: 0L
        val todayStart = TimeCalculator.getStartOfDayMs(System.currentTimeMillis())
        if (storedDate != todayStart) {
            Pair(0L, 0)
        } else {
            val usageMs = preferences[DAILY_USAGE_TIME_MS] ?: 0L
            val extMins = preferences[EXTENDED_TIME_MINS_TODAY] ?: 0
            Pair(usageMs, extMins)
        }
    }

    override val baseTimeFlow: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[BASE_TIME_MINS]
    }

    override val studySchedulesFlow: Flow<List<StudySchedule>> = context.dataStore.data.map { preferences ->
        val json = preferences[STUDY_SCHEDULES] ?: "[]"
        val type = object : TypeToken<List<StudySchedule>>() {}.type
        gson.fromJson(json, type)
    }

    override val isPseudoRestrictionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_PSEUDO_RESTRICTION] ?: false
    }

    override suspend fun setStartDate(date: Long) {
        context.dataStore.edit { preferences ->
            if (!preferences.contains(START_DATE)) {
                preferences[START_DATE] = date
            }
        }
    }

    override suspend fun setParentPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[PARENT_PASSWORD] = PasswordHasher.hash(password)
        }
    }

    override suspend fun verifyParentPassword(password: String): Boolean {
        val storedHash = context.dataStore.data.map { it[PARENT_PASSWORD] }.first() ?: return false
        return PasswordHasher.matches(password, storedHash)
    }

    override suspend fun addTempPassword(tempPassword: TempPassword) {
        context.dataStore.edit { preferences ->
            val json = preferences[TEMP_PASSWORDS] ?: "[]"
            val type = object : TypeToken<List<TempPassword>>() {}.type
            val list: MutableList<TempPassword> = gson.fromJson(json, type)
            list.add(tempPassword)
            preferences[TEMP_PASSWORDS] = gson.toJson(list)
        }
    }

    override suspend fun markTempPasswordUsed(code: String) {
        context.dataStore.edit { preferences ->
            val json = preferences[TEMP_PASSWORDS] ?: "[]"
            val type = object : TypeToken<List<TempPassword>>() {}.type
            val list: MutableList<TempPassword> = gson.fromJson(json, type)
            val index = list.indexOfFirst { it.code == code && !it.isUsed }
            if (index != -1) {
                list[index] = list[index].copy(isUsed = true)
                preferences[TEMP_PASSWORDS] = gson.toJson(list)
            }
        }
    }

    override suspend fun cleanupExpiredTempPasswords() {
        context.dataStore.edit { preferences ->
            val json = preferences[TEMP_PASSWORDS] ?: "[]"
            val type = object : TypeToken<List<TempPassword>>() {}.type
            val list: MutableList<TempPassword> = gson.fromJson(json, type)
            val currentTime = System.currentTimeMillis()
            val activeList = list.filter { it.expiresAt > currentTime }
            preferences[TEMP_PASSWORDS] = gson.toJson(activeList)
        }
    }

    override suspend fun isAppPlayCategory(packageName: String): Boolean {
        val json = context.dataStore.data.map { it[APP_CATEGORIES] ?: "{}" }.first()
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        val map: Map<String, Boolean> = gson.fromJson(json, type)
        
        // Explicitly set by parent
        if (map.containsKey(packageName)) {
            return map[packageName]!!
        }
        
        // Defaults: Launcher and dialer etc are not play apps
        if (defaultAllowedPackages.contains(packageName) || getLauncherPackages().contains(packageName)) {
            return false
        }
        
        return true
    }

    override suspend fun setAppCategory(packageName: String, isPlay: Boolean) {
        context.dataStore.edit { preferences ->
            val json = preferences[APP_CATEGORIES] ?: "{}"
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val map: MutableMap<String, Boolean> = gson.fromJson(json, type)
            map[packageName] = isPlay
            preferences[APP_CATEGORIES] = gson.toJson(map)
        }
    }

    override suspend fun updateDailyUsage(dateStartOfDay: Long, usageTimeMs: Long) {
        context.dataStore.edit { preferences ->
            val storedDate = preferences[DAILY_USAGE_DATE] ?: 0L
            if (storedDate != dateStartOfDay) {
                // New day: Reset manual base time override and other daily stats
                preferences[DAILY_USAGE_DATE] = dateStartOfDay
                preferences[DAILY_USAGE_TIME_MS] = usageTimeMs
                preferences[EXTENDED_TIME_MINS_TODAY] = 0
                preferences.remove(BASE_TIME_MINS) // Return to automatic time calculation
            } else {
                // Same day, update time
                preferences[DAILY_USAGE_TIME_MS] = usageTimeMs
            }
        }
    }

    override suspend fun addExtendedTime(minutes: Int, dateStartOfDay: Long) {
        context.dataStore.edit { preferences ->
            val storedDate = preferences[DAILY_USAGE_DATE] ?: 0L
            if (storedDate != dateStartOfDay) {
                preferences[DAILY_USAGE_DATE] = dateStartOfDay
                preferences[DAILY_USAGE_TIME_MS] = 0L
                preferences[EXTENDED_TIME_MINS_TODAY] = minutes
            } else {
                val currentExt = preferences[EXTENDED_TIME_MINS_TODAY] ?: 0
                preferences[EXTENDED_TIME_MINS_TODAY] = currentExt + minutes
            }
        }
    }

    override suspend fun getTodayPlayUsageMs(): Long {
        return UsageTracker.getTodayPlayUsageMs(context, this)
    }

    override suspend fun setBaseTime(minutes: Int) {
        context.dataStore.edit { preferences ->
            if (minutes < 0) {
                preferences.remove(BASE_TIME_MINS)
            } else {
                preferences[BASE_TIME_MINS] = minutes
            }
        }
    }

    override suspend fun setStudySchedules(schedules: List<StudySchedule>) {
        context.dataStore.edit { preferences ->
            preferences[STUDY_SCHEDULES] = gson.toJson(schedules)
        }
    }

    override suspend fun setPseudoRestriction(active: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_PSEUDO_RESTRICTION] = active
        }
    }

    override fun isStudyTimeNow(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(java.util.Calendar.MINUTE)
        val currentTimeInMins = currentHour * 60 + currentMinute

        val json = run {
            val prefs = try {
                // Blocking read for a quick check in service/monitor
                kotlinx.coroutines.runBlocking { context.dataStore.data.first() }
            } catch (e: Exception) {
                return false
            }
            prefs[STUDY_SCHEDULES] ?: "[]"
        }
        
        val type = object : TypeToken<List<StudySchedule>>() {}.type
        val schedules: List<StudySchedule> = gson.fromJson(json, type)
        
        val todaySchedule = schedules.find { it.dayOfWeek == currentDay } ?: return false
        
        return todaySchedule.ranges.any { range ->
            val startInMins = range.startHour * 60 + range.startMinute
            val endInMins = range.endHour * 60 + range.endMinute
            currentTimeInMins in startInMins until endInMins
        }
    }
}
