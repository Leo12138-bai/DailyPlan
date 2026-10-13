package com.dailyplan.app.update

import org.json.JSONObject

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val changelog: String,
    val apkSize: Long,
    val apkName: String,
    val apkUrl: String,
    val sha256: String,
)

internal object ReleaseManifestParser {
    const val MANIFEST_ASSET = "dailyplan-update.json"

    fun metadataUrl(releaseJson: String): String {
        val release = JSONObject(releaseJson)
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) {
            "GitHub release is not a stable release"
        }
        val assets = release.optJSONArray("assets") ?: error("Release has no assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == MANIFEST_ASSET) {
                return asset.getString("browser_download_url")
            }
        }
        error("Release is missing $MANIFEST_ASSET")
    }

    fun parse(releaseJson: String, metadataJson: String, expectedApplicationId: String): UpdateRelease {
        val release = JSONObject(releaseJson)
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) {
            "GitHub release is not a stable release"
        }
        val metadata = JSONObject(metadataJson)
        require(metadata.getString("applicationId") == expectedApplicationId) {
            "Update package does not match this app"
        }
        val versionCode = metadata.getLong("versionCode")
        val versionName = metadata.getString("versionName")
        val apkName = metadata.getString("apkName")
        val sha256 = metadata.getString("sha256").lowercase()
        require(versionCode > 0) { "Invalid versionCode" }
        require(versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) {
            "Invalid versionName"
        }
        require(apkName == "DailyPlan-v$versionName.apk") { "Unexpected APK filename" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }

        val assets = release.optJSONArray("assets") ?: error("Release has no assets")
        var apkUrl: String? = null
        var apkSize = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == apkName) {
                apkUrl = asset.getString("browser_download_url")
                apkSize = asset.getLong("size")
                break
            }
        }
        require(apkSize in 1..MAX_APK_SIZE_BYTES) { "APK size is invalid" }
        return UpdateRelease(
            versionCode = versionCode,
            versionName = versionName,
            changelog = release.optString("body").takeUnless { it == "null" }?.trim().orEmpty(),
            apkSize = apkSize,
            apkName = apkName,
            apkUrl = requireNotNull(apkUrl) { "Release is missing $apkName" },
            sha256 = sha256,
        )
    }

    fun isNewer(remoteVersionCode: Long, installedVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode

    fun shouldCheckAutomatically(lastAutomaticCheck: Long, now: Long): Boolean =
        lastAutomaticCheck <= 0L || now - lastAutomaticCheck >= AUTO_CHECK_INTERVAL_MS || now < lastAutomaticCheck

    const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L
}
