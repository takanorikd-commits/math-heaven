package com.example.medicalschoolapp.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted SHA-256 hashing for the parent password so it is never
 * stored or compared in plain text.
 */
object PasswordHasher {

    private const val SALT_BYTES = 16

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val digest = sha256(salt, password)
        return "${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(digest, Base64.NO_WRAP)}"
    }

    fun matches(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expectedDigest = Base64.decode(parts[1], Base64.NO_WRAP)
        val actualDigest = sha256(salt, password)
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }

    private fun sha256(salt: ByteArray, password: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }
}
