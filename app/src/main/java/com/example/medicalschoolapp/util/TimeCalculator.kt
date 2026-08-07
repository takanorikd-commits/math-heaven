package com.example.medicalschoolapp.util

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object TimeCalculator {

    private val commonTestDate: ZonedDateTime = ZonedDateTime.of(
        2028, 1, 15, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo")
    )

    fun getBaseAllowedMinutes(startDateMs: Long, currentDateMs: Long, initialMinutes: Int = 240): Int {
        return initialMinutes
    }

    fun getRemainingTimeTodayMs(
        usedMs: Long,
        extendedTimeMins: Int,
        baseAllowedMins: Int
    ): Long {
        val totalAllowedMins = baseAllowedMins + extendedTimeMins
        val totalAllowedMs = totalAllowedMins * 60 * 1000L
        return totalAllowedMs - usedMs
    }

    fun getCountdownToCommonTest(currentDate: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))): String {
        if (currentDate.isAfter(commonTestDate)) return "共通テストは終了しました"
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
