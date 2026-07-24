package com.example.medicalschoolapp.model

data class StudyTimeRange(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

data class StudySchedule(
    val dayOfWeek: Int, // java.util.Calendar.MONDAY etc.
    val ranges: List<StudyTimeRange> = emptyList()
)
