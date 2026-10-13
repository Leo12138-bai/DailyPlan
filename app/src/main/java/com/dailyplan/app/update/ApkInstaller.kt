package com.dailyplan.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    fun requestInstall(context: Context, apk: File): String? = runCatching<String?> {
        require(apk.isFile) { "安装包不存在，请重新下载" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(permissionIntent)
            null
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("DailyPlan update", uri)
            }
            context.startActivity(installIntent)
            null
        }
    }.getOrElse { it.message ?: "无法打开系统安装界面" }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
