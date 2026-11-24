package com.carchecker.otg

import com.carchecker.otg.data.update.AppUpdateChecker
import com.carchecker.otg.data.update.AppUpdateState
import com.carchecker.otg.data.update.UpdateSecurityValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    private val checker = AppUpdateChecker()

    @Test
    fun versionComparison_handlesPrefixesAndMultiDigitSegments() {
        assertTrue(checker.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(checker.isNewerVersion("1.10.0", "1.2.0"))
        assertFalse(checker.isNewerVersion("v1.1.0", "1.1.0"))
        assertFalse(checker.isNewerVersion("1.0.9", "1.1.0"))
    }

    @Test
    fun parseLatestReleaseJson_detectsNewReleaseAndApkAssetUrl() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "name": "CarCheck OTG v1.2.0",
              "body": "- Added live CAN graphing\n- Bug fixes",
              "html_url": "https://github.com/mina-26-ata/CarCheck-OTG/releases/tag/v1.2.0",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt"
                },
                {
                  "name": "CarCheck-OTG-v1.2.0.apk",
                  "browser_download_url": "https://github.com/mina-26-ata/CarCheck-OTG/releases/download/v1.2.0/CarCheck-OTG-v1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val result = checker.parseLatestReleaseJson(json, "1.1.0")
        assertTrue(result is AppUpdateState.UpdateAvailable)
        val avail = result as AppUpdateState.UpdateAvailable
        assertEquals("1.2.0", avail.latestVersion)
        assertEquals(
            "https://github.com/mina-26-ata/CarCheck-OTG/releases/download/v1.2.0/CarCheck-OTG-v1.2.0.apk",
            avail.downloadUrl
        )
        assertTrue(avail.releaseNotes.contains("CAN graphing"))

        val upToDate = checker.parseLatestReleaseJson(json, "1.2.0")
        assertTrue(upToDate is AppUpdateState.UpToDate)
    }

    @Test
    fun parseRedirectReleaseLocation_handlesRateLimitFallbackCleanly() {
        val location = "https://github.com/mina-26-ata/CarCheck-OTG/releases/tag/v1.4.0"
        val avail = checker.parseRedirectReleaseLocation(location, "1.3.0")
        assertTrue(avail is AppUpdateState.UpdateAvailable)
        val update = avail as AppUpdateState.UpdateAvailable
        assertEquals("1.4.0", update.latestVersion)
        assertEquals(
            "https://github.com/mina-26-ata/CarCheck-OTG/releases/download/v1.4.0/CarCheck-OTG-v1.4.0.apk",
            update.downloadUrl
        )

        val same = checker.parseRedirectReleaseLocation(location, "1.4.0")
        assertTrue(same is AppUpdateState.UpToDate)
    }

    @Test
    fun updateSecurityValidator_enforcesHttpsGitHubHostsAndApkMagicHeader() {
        assertTrue(
            UpdateSecurityValidator.isAllowedDownloadUrl(
                "https://github.com/mina-26-ata/CarCheck-OTG/releases/download/v1.6.0/CarCheck-OTG-v1.6.0.apk"
            )
        )
        assertTrue(
            UpdateSecurityValidator.isAllowedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/abc123"
            )
        )
        assertTrue(
            UpdateSecurityValidator.isAllowedDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/test.apk"
            )
        )
        // Reject HTTP, untrusted domains, and spoofed suffixes
        assertFalse(
            UpdateSecurityValidator.isAllowedDownloadUrl(
                "http://github.com/mina-26-ata/CarCheck-OTG/releases/download/v1.6.0/CarCheck-OTG-v1.6.0.apk"
            )
        )
        assertFalse(UpdateSecurityValidator.isAllowedDownloadUrl("https://evil.example.com/fake.apk"))
        assertFalse(UpdateSecurityValidator.isAllowedDownloadUrl("https://github.com.evil.com/fake.apk"))

        // APK ZIP header check (PK\x03\x04)
        assertTrue(UpdateSecurityValidator.isValidApkHeader(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14)))
        assertFalse(UpdateSecurityValidator.isValidApkHeader("<html>404</html>".toByteArray()))
        assertEquals("CarCheck-OTG-v1.6.0.apk", UpdateSecurityValidator.buildSafeApkFileName("v1.6.0"))
    }
}
