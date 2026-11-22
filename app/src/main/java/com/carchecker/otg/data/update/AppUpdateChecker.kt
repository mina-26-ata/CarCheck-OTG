package com.carchecker.otg.data.update

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

sealed class AppUpdateState {
    data object Idle : AppUpdateState()
    data object Checking : AppUpdateState()
    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String
    ) : AppUpdateState()

    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val releaseUrl: String
    ) : AppUpdateState()

    data class Error(val message: String) : AppUpdateState()
}

/**
 * Checks GitHub Releases API for newer versions of CarCheck OTG and extracts direct APK links.
 */
class AppUpdateChecker {

    companion object {
        const val GITHUB_OWNER = "mina-26-ata"
        const val GITHUB_REPO = "CarCheck-OTG"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val LATEST_RELEASE_REDIRECT_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    }

    private val gson = Gson()

    suspend fun checkForUpdates(currentVersion: String): AppUpdateState = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CarCheck-OTG-Android/$currentVersion")
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()

            when (code) {
                in 200..299 -> parseLatestReleaseJson(body, currentVersion)
                404 -> AppUpdateState.UpToDate(
                    currentVersion = normalizeTag(currentVersion),
                    latestVersion = normalizeTag(currentVersion),
                    releaseUrl = RELEASES_PAGE_URL
                )
                403, 429 -> checkViaLatestRedirectFallback(currentVersion)
                    ?: AppUpdateState.Error("HTTP $code")
                else -> AppUpdateState.Error("HTTP $code")
            }
        } catch (e: Exception) {
            checkViaLatestRedirectFallback(currentVersion)
                ?: AppUpdateState.Error(e.localizedMessage ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Fallback for carrier-NAT IP rate limits on api.github.com (HTTP 403/429):
     * GitHub's web /releases/latest returns a 302 redirect to /releases/tag/vX.Y.Z with no API rate limit.
     */
    private fun checkViaLatestRedirectFallback(currentVersion: String): AppUpdateState? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_RELEASE_REDIRECT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "CarCheck-OTG-Android/$currentVersion")
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location").orEmpty()
            if (code in 300..399 && location.isNotBlank()) {
                parseRedirectReleaseLocation(location, currentVersion)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parseRedirectReleaseLocation(locationUrl: String, currentVersion: String): AppUpdateState? {
        val tagSegment = locationUrl
            .substringAfter("/releases/tag/", missingDelimiterValue = "")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        if (tagSegment.isBlank()) return null

        val normalizedCurrent = normalizeTag(currentVersion)
        val normalizedLatest = normalizeTag(tagSegment)
        val releaseUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tagSegment"
        val apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagSegment/CarCheck-OTG-$tagSegment.apk"

        return if (isNewerVersion(normalizedLatest, normalizedCurrent)) {
            AppUpdateState.UpdateAvailable(
                currentVersion = normalizedCurrent,
                latestVersion = normalizedLatest,
                releaseTitle = "CarCheck OTG $tagSegment",
                releaseNotes = "",
                downloadUrl = apkDownloadUrl,
                releaseUrl = releaseUrl
            )
        } else {
            AppUpdateState.UpToDate(
                currentVersion = normalizedCurrent,
                latestVersion = normalizedLatest,
                releaseUrl = releaseUrl
            )
        }
    }

    fun parseLatestReleaseJson(jsonBody: String, currentVersion: String): AppUpdateState {
        val root = gson.fromJson(jsonBody, JsonObject::class.java)
            ?: return AppUpdateState.Error("Invalid server response")

        val tagName = root.get("tag_name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (tagName.isBlank()) {
            return AppUpdateState.UpToDate(
                currentVersion = normalizeTag(currentVersion),
                latestVersion = normalizeTag(currentVersion),
                releaseUrl = RELEASES_PAGE_URL
            )
        }

        val releaseTitle = root.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            .ifBlank { tagName }
        val releaseNotes = root.get("body")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val htmlUrl = root.get("html_url")?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?: RELEASES_PAGE_URL

        val apkAssetUrl = root.getAsJsonArray("assets")
            ?.mapNotNull { el ->
                val obj = el.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val dl = obj.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) dl else null
            }
            ?.firstOrNull()
            ?: htmlUrl

        val normalizedCurrent = normalizeTag(currentVersion)
        val normalizedLatest = normalizeTag(tagName)

        return if (isNewerVersion(normalizedLatest, normalizedCurrent)) {
            AppUpdateState.UpdateAvailable(
                currentVersion = normalizedCurrent,
                latestVersion = normalizedLatest,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = apkAssetUrl,
                releaseUrl = htmlUrl
            )
        } else {
            AppUpdateState.UpToDate(
                currentVersion = normalizedCurrent,
                latestVersion = normalizedLatest,
                releaseUrl = htmlUrl
            )
        }
    }

    fun normalizeTag(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    fun parseVersionTuple(raw: String): List<Int> {
        val clean = normalizeTag(raw).substringBefore("-")
        if (clean.isBlank()) return listOf(0)
        return clean.split(".").map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun isNewerVersion(candidate: String, current: String): Boolean {
        val candParts = parseVersionTuple(candidate)
        val currParts = parseVersionTuple(current)
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val c = candParts.getOrElse(i) { 0 }
            val k = currParts.getOrElse(i) { 0 }
            if (c != k) return c > k
        }
        return false
    }
}
