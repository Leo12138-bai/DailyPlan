package com.dailyplan.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateChecksumTest {
    @Test
    fun computesSha256ForDownloadedBytes() {
        val file = File.createTempFile("dailyplan-update", ".apk")
        try {
            file.writeText("dailyplan test content")
            assertEquals(
                "516058226ec8f7b175d6101a16c52e288fde3cd918c1d525533cf91ae3f248e8",
                UpdateChecksum.sha256(file),
            )
            assertTrue(UpdateChecksum.matchesSha256(file, "516058226ec8f7b175d6101a16c52e288fde3cd918c1d525533cf91ae3f248e8"))
            assertFalse(UpdateChecksum.matchesSha256(file, "0000000000000000000000000000000000000000000000000000000000000000"))
            assertFalse(UpdateChecksum.matchesSha256(file, "not-a-sha256"))
        } finally {
            file.delete()
        }
    }
}
