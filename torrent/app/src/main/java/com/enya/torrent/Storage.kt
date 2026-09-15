package com.enya.torrent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** Where downloads go: the public Downloads folder when we may write there, else the app's own folder. */
object Storage {

    fun publicDownloadsDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Torrents")

    fun appDownloadsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")

    fun hasPublicAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** On Android 11+ the permission is granted from a system settings screen, not a dialog. */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun resolveSaveDir(context: Context): File {
        val dir = if (hasPublicAccess(context)) publicDownloadsDir() else appDownloadsDir(context)
        return if (isWritable(dir)) dir else appDownloadsDir(context).also { it.mkdirs() }
    }

    /** Creates the folder if needed and proves we can actually create a file in it. */
    fun isWritable(dir: File): Boolean = try {
        dir.mkdirs()
        val probe = File(dir, ".enya-write-test")
        probe.writeBytes(byteArrayOf(1))
        val ok = probe.isFile
        probe.delete()
        dir.isDirectory && ok
    } catch (t: Throwable) {
        false
    }
}
