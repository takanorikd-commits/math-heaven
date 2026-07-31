package com.example.medicalschoolapp.util

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object TimeCalculator {

    private val commonTestDate: ZonedDateTime = ZonedDateTime.of(
        2028, 1, 15, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo")
    )

    fun getBaseAllowedMinutes(startDateMs: Long, currentDateMs: Long, initialMinutes: Int = 240): Int {
        // まだ開始日が設定されていない場合はデフォルトの初期値を返す
        if (startDateMs <= 0L) return initialMinutes
        
        // Handle invalid start date
        if (currentDateMs < startDateMs) return initialMinutes
        
        val diffMs = currentDateMs - startDateMs
        val weeksPassed = diffMs / (1000L * 60 * 60 * 24 * 7)
        // 2.5 minutes reduction per week
        val reduction = (weeksPassed * 2.5).toInt()
        val allowed = initialMinutes - reduction
        return allowed.coerceAtLeast(0)
    }

    fun getRemainingTimeTodayMs(
        startDateMs: Long,
        currentDateMs: Long,
        usedTimeMs: Long,
        extendedTimeMins: Int,
        baseAllowedMins: Int
    ): Long {
        val totalAllowedMins = baseAllowedMins + extendedTimeMins
        val totalAllowedMs = totalAllowedMins * 60 * 1000L
        // マイナスになってもそのまま返し、表示側でプラスになるように調整しやすくする
        return totalAllowedMs - usedTimeMs
    }

    fun getCountdownToCommonTest(currentDate: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))): String {
        if (currentDate.isAfter(commonTestDate)) {
            return "共通テストは終了しました"
        }
        
        var tempNow = currentDate

        val weeks = ChronoUnit.WEEKS.between(tempNow, commonTestDate)
        tempNow = tempNow.plusWeeks(weeks)

        val days = ChronoUnit.DAYS.between(tempNow, commonTestDate)
        tempNow = tempNow.plusDays(days)

        val hours = ChronoUnit.HOURS.between(tempNow, commonTestDate)
        tempNow = tempNow.plusHours(hours)

        val minutes = ChronoUnit.MINUTES.between(tempNow, commonTestDate)

        return "残り ${weeks}週${days}日${hours}時間${minutes}分"
    }

    fun getStartOfDayMs(timeMs: Long): Long {
        val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(timeMs), ZoneId.systemDefault())
        return zdt.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
