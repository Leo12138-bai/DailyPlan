package com.dailyplan.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.dailyplan.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class GithubUpdateClient(private val context: Context) {
    suspend fun latestRelease(): UpdateRelease = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_GITHUB_REPOSITORY
        val releaseJson = readText("https://api.github.com/repos/$repo/releases/latest")
        val metadataUrl = ReleaseManifestParser.metadataUrl(releaseJson)
        requireGitHubAssetUrl(metadataUrl, repo)
        val metadataJson = readText(metadataUrl)
        ReleaseManifestParser.parse(releaseJson, metadataJson, BuildConfig.APPLICATION_ID).also {
            requireGitHubAssetUrl(it.apkUrl, repo)
        }
    }

    suspend fun downloadAndVerify(
        release: UpdateRelease,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDirectory = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(updatesDirectory, "DailyPlan-update.apk")
        apk.delete()
        val connection = openHttps(release.apkUrl)
        try {
            val reportedSize = connection.contentLengthLong
            require(reportedSize < 0 || reportedSize == release.apkSize) { "APK file size does not match the release" }
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        total += read
                        require(total <= release.apkSize) { "APK download is larger than expected" }
                        output.write(buffer, 0, read)
                        onProgress((total * 100 / release.apkSize).toInt().coerceIn(0, 100))
                    }
                    output.fd.sync()
                    require(total == release.apkSize) { "APK download is incomplete" }
                }
            }
        } catch (error: Throwable) {
            apk.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        try {
            require(UpdateChecksum.matchesSha256(apk, release.sha256)) { "SHA-256校验失败，已阻止安装" }
            validateApk(apk, release)
            apk
        } catch (error: Throwable) {
            apk.delete()
            throw error
        }
    }

    private fun validateApk(apk: File, release: UpdateRelease) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid APK")
        require(candidate.packageName == BuildConfig.APPLICATION_ID) { "APK package name does not match" }
        val candidateVersion = packageVersionCode(candidate)
        require(candidateVersion == release.versionCode) { "APK version does not match release metadata" }
        require(candidate.versionName == release.versionName) { "APK version name does not match release metadata" }

        val installed = packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        require(packageVersionCode(candidate) > packageVersionCode(installed)) { "APK is not newer than the installed app" }
        require(signerDigests(candidate) == signerDigests(installed)) {
            "APK签名与当前安装版本不兼容，已阻止覆盖安装"
        }
    }

    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: error("APK signing information is unavailable")
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun readText(url: String): String {
        val connection = openHttps(url)
        return try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_JSON_BYTES) { "GitHub response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(initialUrl: String): HttpsURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val uri = URI(current)
            require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS downloads are accepted" }
            val host = uri.host?.lowercase() ?: error("Invalid GitHub URL")
            require(host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com")) {
                "Unexpected download host"
            }
            val connection = URL(current).openConnection() as HttpsURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "DailyPlan-Android/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in 300..399 && redirect < MAX_REDIRECTS) {
                current = uri.resolve(connection.getHeaderField("Location") ?: error("Missing redirect location")).toString()
                connection.disconnect()
            } else {
                connection.disconnect()
                error("GitHub returned HTTP $status")
            }
        }
        error("Too many GitHub redirects")
    }

    private fun requireGitHubAssetUrl(url: String, repository: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)) {
            "Release asset URL is not a GitHub HTTPS URL"
        }
        require(uri.path.startsWith("/$repository/releases/download/", ignoreCase = true)) {
            "Release asset belongs to a different repository"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_JSON_BYTES = 1024 * 1024
    }
}
