package com.carchecker.otg.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed class AppUpdateInstallState {
    data object Idle : AppUpdateInstallState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateInstallState()

    data class ReadyToInstall(
        val apkFile: File,
        val version: String
    ) : AppUpdateInstallState()

    data class NeedsInstallPermission(
        val apkFile: File,
        val version: String
    ) : AppUpdateInstallState()

    data class InstallerLaunched(
        val apkFile: File,
        val version: String
    ) : AppUpdateInstallState()

    data class Error(val message: String) : AppUpdateInstallState()
}

object UpdateSecurityValidator {
    private val allowedExactHosts = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
        if (host in allowedExactHosts) return true
        return host.endsWith(".githubusercontent.com") && host.count { it == '.' } >= 2
    }

    fun isValidApkHeader(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 4) return false
        return headerBytes[0] == 0x50.toByte() && // 'P'
            headerBytes[1] == 0x4B.toByte() && // 'K'
            headerBytes[2] == 0x03.toByte() &&
            headerBytes[3] == 0x04.toByte()
    }

    fun buildSafeApkFileName(version: String): String {
        val clean = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .replace(Regex("[^0-9A-Za-z._-]"), "_")
            .replace("..", "")
            .take(24)
            .ifBlank { "latest" }
        return "CarCheck-OTG-v$clean.apk"
    }
}

/**
 * Downloads verified GitHub Release APKs directly inside the app with live progress
 * and hands off directly to Android's package installer via FileProvider (no browser needed).
 */
class AppUpdateInstaller(private val context: Context) {

    companion object {
        private const val UPDATES_SUBDIR = "updates"
        private const val MAX_REDIRECTS = 6
        private const val MIN_VALID_APK_BYTES = 100_000L
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    private fun updatesDir(): File = File(context.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }

    fun cleanupOldApks() {
        runCatching {
            val dir = File(context.cacheDir, UPDATES_SUBDIR)
            if (!dir.exists()) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                    f.delete()
                }
            }
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        version: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!UpdateSecurityValidator.isAllowedDownloadUrl(downloadUrl)) {
            return@withContext Result.failure(SecurityException("Untrusted update download URL"))
        }

        val dir = updatesDir()
        val finalName = File(UpdateSecurityValidator.buildSafeApkFileName(version)).name
        val finalFile = File(dir, finalName)
        val partFile = File(dir, "$finalName.part")

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            var redirectCount = 0
            var downloadedFile: File? = null

            while (downloadedFile == null) {
                currentCoroutineContext().ensureActive()
                if (!UpdateSecurityValidator.isAllowedDownloadUrl(currentUrl)) {
                    throw SecurityException("Redirected to untrusted host")
                }

                conn?.disconnect()
                val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Accept", "application/octet-stream, */*")
                    setRequestProperty("User-Agent", "CarCheck-OTG-Android-Updater")
                }
                conn = connection

                val status = connection.responseCode
                if (status in 300..399) {
                    val loc = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                    redirectCount++
                    if (redirectCount > MAX_REDIRECTS) {
                        throw IOException("Too many redirects while downloading update")
                    }
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    continue
                }

                if (status !in 200..299) {
                    throw IOException("HTTP $status while downloading update")
                }

                val totalBytes = connection.contentLengthLong
                if (partFile.exists()) partFile.delete()

                var downloaded = 0L
                var lastReportedPercent = -1
                val magicHeader = ByteArray(4)
                var headerRead = 0

                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            if (headerRead < 4) {
                                val toCopy = minOf(4 - headerRead, n)
                                System.arraycopy(buffer, 0, magicHeader, headerRead, toCopy)
                                headerRead += toCopy
                            }
                            output.write(buffer, 0, n)
                            downloaded += n

                            val percent = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (downloaded < MIN_VALID_APK_BYTES || !UpdateSecurityValidator.isValidApkHeader(magicHeader)) {
                    partFile.delete()
                    throw IOException("Downloaded file is not a valid APK package")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(100, downloaded, if (totalBytes > 0L) totalBytes else downloaded)
                downloadedFile = finalFile
            }
            Result.success(downloadedFile)
        } catch (e: Exception) {
            runCatching { partFile.delete() }
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
