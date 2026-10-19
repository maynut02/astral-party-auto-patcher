package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.ReleaseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchUiStateTest {
    @Test
    fun missingGameStillAllowsEntryToInstallationGuidance() {
        val state = readyState(GameTarget.INT_ANDROID).copy(gameInstalls = emptyMap())
        assertTrue(state.canPatch)
        assertFalse(state.selectedInstalled)
        assertFalse(state.canLaunch)
    }

    @Test
    fun installedCnTargetCanPatchWhenAllReadinessConditionsAreMet() {
        val state = readyState(GameTarget.CN_ANDROID)
        assertTrue(state.canPatch)
    }

    @Test
    fun sameVersionAllowsReinstallation() {
        val state = readyState(GameTarget.CN_ANDROID).copy(
            installedPatchVersion = "v3.2.0_r211",
        )
        assertTrue(state.canPatch)
        assertTrue(state.canRestore)
    }

    @Test
    fun unconfirmedResourcesAllowEntryToWarningAndTargetChecks() {
        val state = readyState(GameTarget.CN_ANDROID).copy(
            resourceIndicator = StatusIndicator.PENDING,
        )
        assertTrue(state.canPatch)
    }

    @Test
    fun missingHistoryDoesNotBlockOriginalRecovery() {
        assertTrue(readyState(GameTarget.CN_ANDROID).copy(installedPatchVersion = null).canRestore)
    }

    @Test
    fun installerLookupFailureDoesNotHideInstalledGameOrBlockPatchEntry() {
        val state = readyState(GameTarget.INT_ANDROID).copy(gameInstalls = mapOf(
            GameTarget.INT_ANDROID to GameInstall("3.3.0", false, installerKnown = false),
        ))
        assertTrue(state.selectedInstalled)
        assertTrue(state.gameReady)
        assertTrue(state.canPatch)
    }

    @Test
    fun workInProgressPreventsDuplicateOperations() {
        val state = readyState(GameTarget.CN_ANDROID).copy(busy = true)
        assertFalse(state.canPatch)
        assertFalse(state.canRestore)
    }

    @Test
    fun sameVersionNameWithChangedInstallationInvalidatesConfirmation() {
        val before = GameInstall("3.3.0", true, versionCode = 3300, lastUpdateTime = 100)
        assertFalse(sameInstallationSnapshot(before, before.copy(lastUpdateTime = 200)))
        assertFalse(sameInstallationSnapshot(before, before.copy(sourceDir = "/data/app/new/base.apk")))
        assertFalse(sameInstallationSnapshot(before, before.copy(userId = 10)))
        assertTrue(sameInstallationSnapshot(before, before.copy(installedFromPlay = false, installerKnown = false)))
        assertTrue(sameInstallationSnapshot(null, null))
        assertFalse(sameInstallationSnapshot(before, null))
    }

    @Test
    fun olderApkBlocksPatchAndRestoreButStillAllowsLaunchingTheGame() {
        val state = readyState(GameTarget.CN_ANDROID_BILIBILI).copy(latestRelease = latestRelease())
        assertEquals("3.2.0", state.gameUpdateRequirement?.installedVersion)
        assertEquals("3.3.0", state.gameUpdateRequirement?.requiredVersion)
        assertFalse(state.canPatch)
        assertFalse(state.canRestore)
        assertTrue(state.canLaunch)
    }

    @Test
    fun updatingTheSelectedApkReenablesBothOperations() {
        val before = readyState(GameTarget.CN_ANDROID_BILIBILI).copy(latestRelease = latestRelease())
        val updated = before.copy(gameInstalls = mapOf(
            before.selectedTarget to GameInstall("3.3.0", installedFromPlay = false),
        ))
        assertNull(updated.gameUpdateRequirement)
        assertTrue(updated.canPatch)
        assertTrue(updated.canRestore)
        assertTrue(updated.canLaunch)
        // A new release must recompute the requirement for the next immutable UI snapshot.
        val nextRelease = updated.copy(latestRelease = latestRelease().copy(gameVersion = "3.4.0"))
        assertEquals("3.4.0", nextRelease.gameUpdateRequirement?.requiredVersion)
        assertFalse(nextRelease.canPatch)
        assertFalse(nextRelease.canRestore)
        assertTrue(nextRelease.canLaunch)
    }

    @Test
    fun requirementUsesTheSelectedGameRatherThanAnotherInstalledChannel() {
        val state = readyState(GameTarget.CN_ANDROID_BILIBILI).copy(
            latestRelease = latestRelease(),
            gameInstalls = mapOf(
                GameTarget.INT_ANDROID to GameInstall("3.3.0", true),
                GameTarget.CN_ANDROID_BILIBILI to GameInstall("3.2.0", false),
            ),
        )
        assertFalse(state.canPatch)
        assertEquals("3.2.0", state.gameUpdateRequirement?.installedVersion)
    }

    @Test
    fun unknownApkVersionOrReleaseDoesNotDisableEntryToGuidance() {
        for (state in listOf(
            readyState(GameTarget.CN_ANDROID_BILIBILI),
            readyState(GameTarget.CN_ANDROID_BILIBILI).copy(
                latestRelease = latestRelease(),
                gameInstalls = mapOf(GameTarget.CN_ANDROID_BILIBILI to GameInstall(null, false)),
            ),
        )) {
            assertNull(state.gameUpdateRequirement)
            assertTrue(state.canPatch)
            assertTrue(state.canRestore)
        }
    }

    @Test
    fun busyStateAlsoBlocksLaunchingAnOutdatedGame() {
        val state = readyState(GameTarget.CN_ANDROID_BILIBILI).copy(latestRelease = latestRelease(), busy = true)
        assertFalse(state.canPatch)
        assertFalse(state.canRestore)
        assertTrue(state.selectedInstalled) // The floating action button stays visible.
        assertFalse(state.canLaunch) // Only the launch action is disabled while working.
    }

    private fun latestRelease() = ReleaseEntry(
        route = "CN_ANDROID", gameVersion = "3.3.0", revision = "026",
        catalogHash = "a".repeat(32), patchVersion = "v3.3.0_r026_p0",
        manifestUrl = "https://example.test/manifest.json", manifestSha256 = "b".repeat(64),
    )

    private fun readyState(target: GameTarget): PatchUiState = PatchUiState(
        selectedTarget = target,
        gameInstalls = mapOf(target to GameInstall("3.2.0", installedFromPlay = true)),
        shizukuReady = true,
        resourceIndicator = StatusIndicator.COMPLETED,
        latestPatchVersion = "v3.2.0_r211",
        patchVersion = "v3.2.0_r211",
        installedPatchVersion = null,
    )
}
