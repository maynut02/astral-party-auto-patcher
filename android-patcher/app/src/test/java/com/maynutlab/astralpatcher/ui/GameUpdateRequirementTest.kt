package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.ReleaseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUpdateRequirementTest {
    @Test fun olderApkRequiresTheGameVersionFromReleaseMetadata() {
        val update = requireNotNull(requiredGameUpdate("3.2.0", release("3.3.0")))
        assertEquals("3.2.0", update.installedVersion)
        assertEquals("3.3.0", update.requiredVersion)
        assertTrue(update.description.contains("3.2.0"))
        assertTrue(update.description.contains("3.3.0"))
    }

    @Test fun numericComponentsDetermineOrderRatherThanStringSorting() {
        assertEquals("3.10.0", requiredGameUpdate("3.9.0", release("3.10.0"))?.requiredVersion)
        assertEquals("4.0.0", requiredGameUpdate("3.99.99", release("4.0.0"))?.requiredVersion)
        assertEquals("3.3.0.1", requiredGameUpdate("3.3", release("3.3.0.1"))?.requiredVersion)
        assertNull(requiredGameUpdate("3.10.0", release("3.9.0")))
    }

    @Test fun equalAndNewerApksDoNotRequireUpdate() {
        for (installed in listOf("3.3", "3.3.0", "3.3.0.0", "03.03.00", "3.4.0", "4.0")) {
            assertNull(installed, requiredGameUpdate(installed, release("3.3.0")))
        }
    }

    @Test fun malformedOrUnavailableVersionsDoNotInventAnUpdateRequirement() {
        for (version in listOf(null, "", "  ", "-3.2.0", "3.-2.0", "3.2.0-beta", "v3.2.0", "3..2", "3.2.")) {
            assertNull(version, requiredGameUpdate(version, release("3.3.0")))
            if (version != null) assertNull(version, requiredGameUpdate("3.2.0", release(version)))
        }
        assertNull(requiredGameUpdate("3.2.0", null))
    }

    @Test fun resourceRevisionAndTranslationRevisionDoNotRequireApkUpdate() {
        for (latest in listOf(
            release("3.3.0").copy(revision = "999", patchVersion = "v3.3.0_r999_p0"),
            release("3.3.0").copy(patchVersion = "v3.3.0_r026_p99"),
            release("3.3.0").copy(patchVersion = "v9.9.9_r999_p99"),
        )) {
            assertNull(requiredGameUpdate("3.3.0", latest))
        }
    }

    @Test fun versionComponentsAreNotLimitedByMachineIntegerSize() {
        val huge = "999999999999999999999999999999999999999999"
        assertEquals("3.$huge", requiredGameUpdate("3.10", release("3.$huge"))?.requiredVersion)
        assertNull(requiredGameUpdate("3.$huge", release("3.10")))
    }

    @Test fun guidanceUsesTheSelectedDistributionChannel() {
        val international = gameUpdateGuidance(GameTarget.INT_ANDROID)
        val chinese = gameUpdateGuidance(GameTarget.CN_ANDROID)
        val bilibili = gameUpdateGuidance(GameTarget.CN_ANDROID_BILIBILI)
        assertTrue(international.contains("스토어"))
        assertTrue(chinese.contains("중국"))
        assertTrue(chinese.contains("배포처"))
        assertTrue(bilibili.contains("빌리빌리"))
        for (guidance in listOf(international, chinese, bilibili)) {
            assertTrue(guidance.contains("업데이트"))
            assertTrue(guidance.contains("리소스"))
            assertTrue(guidance.contains("다운로드"))
        }
    }

    private fun release(version: String) = ReleaseEntry(
        route = "INT_ANDROID", gameVersion = version, revision = "026",
        catalogHash = "a".repeat(32), patchVersion = "v3.3.0_r026_p0",
        manifestUrl = "https://example.test/manifest.json", manifestSha256 = "b".repeat(64),
    )
}
