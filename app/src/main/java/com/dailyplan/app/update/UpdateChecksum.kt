package com.dailyplan.app.update

import java.io.File
import java.security.MessageDigest

object UpdateChecksum {
    fun matchesSha256(file: File, expected: String): Boolean =
        expected.matches(Regex("[0-9a-fA-F]{64}")) && sha256(file).equals(expected, ignoreCase = true)

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
