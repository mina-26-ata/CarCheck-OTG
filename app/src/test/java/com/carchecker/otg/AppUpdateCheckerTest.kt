package com.carchecker.otg

import com.carchecker.otg.data.update.AppUpdateChecker
import com.carchecker.otg.data.update.AppUpdateState
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
}
