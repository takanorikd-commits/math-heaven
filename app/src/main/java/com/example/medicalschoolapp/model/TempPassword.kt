package com.example.medicalschoolapp.model

import java.util.UUID

data class TempPassword(
    val code: String,
    val expiresAt: Long, // timestamp in ms
    val isUsed: Boolean = false,
    val extensionMinutes: Int = 15, // user confirmed fixed at 15 mins
    val id: String = UUID.randomUUID().toString(), // stable identity for future multi-device sync
    val createdAt: Long = System.currentTimeMillis()
)
