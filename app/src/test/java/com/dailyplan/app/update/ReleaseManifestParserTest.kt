package com.dailyplan.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestParserTest {
    @Test
    fun parsesStableReleaseAndChangelog() {
        val release = ReleaseManifestParser.parse(releaseJson(), metadataJson(), APP_ID)

        assertEquals(2L, release.versionCode)
        assertEquals("1.1.0", release.versionName)
        assertEquals("在线更新", release.changelog)
        assertEquals(4096L, release.apkSize)
        assertEquals("DailyPlan-v1.1.0.apk", release.apkName)
        assertEquals(SHA256, release.sha256)
    }

    @Test
    fun versionCodeControlsWhetherAnUpdateExists() {
        assertTrue(ReleaseManifestParser.isNewer(2, 1))
        assertFalse(ReleaseManifestParser.isNewer(1, 1))
        assertFalse(ReleaseManifestParser.isNewer(1, 2))
    }

    @Test
    fun automaticChecksAreLimitedToOncePerTwentyFourHours() {
        val now = 10L * ReleaseManifestParser.AUTO_CHECK_INTERVAL_MS
        assertFalse(ReleaseManifestParser.shouldCheckAutomatically(now - 1, now))
        assertTrue(ReleaseManifestParser.shouldCheckAutomatically(now - ReleaseManifestParser.AUTO_CHECK_INTERVAL_MS, now))
        assertTrue(ReleaseManifestParser.shouldCheckAutomatically(now, now - 1))
    }

    @Test
    fun rejectsDifferentPackageAndUnexpectedApkName() {
        val wrongPackage = metadataJson().replace(APP_ID, "com.other.app")
        assertTrue(runCatching { ReleaseManifestParser.parse(releaseJson(), wrongPackage, APP_ID) }.isFailure)

        val wrongName = metadataJson().replace("DailyPlan-v1.1.0.apk", "other.apk")
        assertTrue(runCatching { ReleaseManifestParser.parse(releaseJson(), wrongName, APP_ID) }.isFailure)
    }

    @Test
    fun rejectsReleaseWithoutExpectedApkOrValidChecksum() {
        val invalidChecksum = metadataJson().replace(SHA256, "bad")
        assertTrue(runCatching { ReleaseManifestParser.parse(releaseJson(), invalidChecksum, APP_ID) }.isFailure)

        val missingApk = releaseJson().replace("DailyPlan-v1.1.0.apk", "Other.apk")
        assertTrue(runCatching { ReleaseManifestParser.parse(missingApk, metadataJson(), APP_ID) }.isFailure)
    }

    private fun releaseJson(): String = """
        {
          "draft": false,
          "prerelease": false,
          "body": "在线更新",
          "assets": [
            {"name":"dailyplan-update.json","browser_download_url":"$ASSET_BASE/dailyplan-update.json","size":256},
            {"name":"DailyPlan-v1.1.0.apk","browser_download_url":"$ASSET_BASE/DailyPlan-v1.1.0.apk","size":4096}
          ]
        }
    """.trimIndent()

    private fun metadataJson(): String = """
        {
          "applicationId":"$APP_ID",
          "versionCode":2,
          "versionName":"1.1.0",
          "apkName":"DailyPlan-v1.1.0.apk",
          "sha256":"$SHA256"
        }
    """.trimIndent()

    companion object {
        private const val APP_ID = "com.dailyplan.app"
        private const val ASSET_BASE = "https://github.com/Leo12138-bai/DailyPlan/releases/download/v1.1.0"
        private const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
