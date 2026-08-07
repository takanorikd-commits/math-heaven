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
    suspend fun addUsageMs(ms: Long)
    suspend fun addExtendedTime(minutes: Int, dateStartOfDay: Long)
    suspend fun calculateRemainingMs(): Long
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
        val INITIAL_BASE_TIME_MINS = intPreferencesKey("initial_base_time_mins")
        val LAST_UNLOCK_TIME_MS = longPreferencesKey("last_unlock_time_ms")
        val STUDY_SCHEDULES = stringPreferencesKey("study_schedules")
        val IS_PSEUDO_RESTRICTION = booleanPreferencesKey("is_pseudo_restriction")
        private const val UNLOCK_GRACE_MS = 10 * 1000L
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
    override val initialBaseTimeFlow: Flow<Int> = context.dataStore.data.map { it[INITIAL_BASE_TIME_MINS] ?: 240 }
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
    override val studySchedulesFlow: Flow<List<StudySchedule>> = context.dataStore.data.map {
        val json = it[STUDY_SCHEDULES] ?: "[]"
        gson.fromJson(json, object : TypeToken<List<StudySchedule>>() {}.type)
    }
    override val isPseudoRestrictionFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_PSEUDO_RESTRICTION] ?: false }

    override suspend fun setStartDate(date: Long) { context.dataStore.edit { it[START_DATE] = date } }
    override suspend fun setParentPassword(password: String) { context.dataStore.edit { it[PARENT_PASSWORD] = PasswordHasher.hash(password) } }
    override suspend fun verifyParentPassword(password: String): Boolean {
        val storedHash = context.dataStore.data.map { it[PARENT_PASSWORD] }.first() ?: return false
        return PasswordHasher.matches(password, storedHash)
    }
    override suspend fun addTempPassword(tp: TempPassword) {
        context.dataStore.edit { 
            val list = it[TEMP_PASSWORDS]?.let { json -> gson.fromJson<MutableList<TempPassword>>(json, object : TypeToken<MutableList<TempPassword>>() {}.type) } ?: mutableListOf()
            list.add(tp); it[TEMP_PASSWORDS] = gson.toJson(list)
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
        return !defaultAllowedPackages.contains(packageName) && !getLauncherPackages().contains(packageName)
    }
    override suspend fun setAppCategory(packageName: String, isPlay: Boolean) {
        context.dataStore.edit { 
            val json = it[APP_CATEGORIES] ?: "{}"
            val map = gson.fromJson<MutableMap<String, Boolean>>(json, object : TypeToken<MutableMap<String, Boolean>>() {}.type) ?: mutableMapOf()
            map[packageName] = isPlay; it[APP_CATEGORIES] = gson.toJson(map)
        }
    }
    override suspend fun addUsageMs(ms: Long) {
        context.dataStore.edit {
            // 日付が変わっていたら、その場でカウンターを当日分にリセットしてから加算する
            val todayStart = TimeCalculator.getStartOfDayMs(System.currentTimeMillis())
            val storedDate = it[DAILY_USAGE_DATE] ?: 0L
            val currentMs = if (storedDate == todayStart) it[DAILY_USAGE_TIME_MS] ?: 0L else 0L
            it[DAILY_USAGE_DATE] = todayStart
            if (storedDate != todayStart) it[EXTENDED_TIME_MINS_TODAY] = 0
            it[DAILY_USAGE_TIME_MS] = currentMs + ms
        }
    }
    override suspend fun addExtendedTime(minutes: Int, dateStartOfDay: Long) {
        context.dataStore.edit { it[EXTENDED_TIME_MINS_TODAY] = (it[EXTENDED_TIME_MINS_TODAY] ?: 0) + minutes }
    }
    override suspend fun calculateRemainingMs(): Long {
        val stats = dailyUsageStatsFlow.first()
        val base = baseTimeFlow.first() ?: initialBaseTimeFlow.first()
        return TimeCalculator.getRemainingTimeTodayMs(stats.first, stats.second, base)
    }
    override suspend fun setBaseTime(minutes: Int) {
        context.dataStore.edit {
            it[BASE_TIME_MINS] = minutes; it[DAILY_USAGE_DATE] = TimeCalculator.getStartOfDayMs(System.currentTimeMillis())
            it[DAILY_USAGE_TIME_MS] = 0L; it[EXTENDED_TIME_MINS_TODAY] = 0
            // 以前の解除猶予が残っていると「保存した瞬間から確実に判定が効く」が成立しなくなるため消す
            it.remove(LAST_UNLOCK_TIME_MS)
        }
    }
    override suspend fun setStudySchedules(schedules: List<StudySchedule>) { context.dataStore.edit { it[STUDY_SCHEDULES] = gson.toJson(schedules) } }
    override suspend fun setPseudoRestriction(active: Boolean) { context.dataStore.edit { it[IS_PSEUDO_RESTRICTION] = active } }
    override suspend fun resetParentPassword() { context.dataStore.edit { it.remove(PARENT_PASSWORD) } }
    override suspend fun clearStartDate() {
        context.dataStore.edit { it.remove(START_DATE); it.remove(INITIAL_BASE_TIME_MINS); it.remove(DAILY_USAGE_DATE); it.remove(DAILY_USAGE_TIME_MS); it.remove(EXTENDED_TIME_MINS_TODAY) }
    }
    override suspend fun notifyUnlockEvent() { context.dataStore.edit { it[LAST_UNLOCK_TIME_MS] = System.currentTimeMillis() } }
    override fun isRecentlyUnlocked(): Boolean {
        val last = kotlinx.coroutines.runBlocking { context.dataStore.data.first()[LAST_UNLOCK_TIME_MS] ?: 0L }
        // ブロック画面を閉じた直後に監視ループが即座に再ブロックしてチラつくのを防ぐだけの短い猶予。
        // 以前は5分だったが、その間は時間切れ判定ごと丸ごと無効化されてしまい、
        // 「テストで一度延長操作をすると5分間は完全に無防備になる」という事故の元だったため短縮。
        return (System.currentTimeMillis() - last) < UNLOCK_GRACE_MS
    }
    override suspend fun isStudyTimeNow(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val current = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val json = context.dataStore.data.first()[STUDY_SCHEDULES] ?: "[]"
        val schedules: List<StudySchedule> = gson.fromJson(json, object : TypeToken<List<StudySchedule>>() {}.type)
        if (schedules.all { it.ranges.isEmpty() }) return false
        val today = schedules.find { it.dayOfWeek == calendar.get(java.util.Calendar.DAY_OF_WEEK) } ?: return false
        return today.ranges.any { current in (it.startHour * 60 + it.startMinute) until (it.endHour * 60 + it.endMinute) }
    }
}
