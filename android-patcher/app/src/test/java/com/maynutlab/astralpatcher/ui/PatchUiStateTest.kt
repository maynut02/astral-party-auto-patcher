package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.GameTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchUiStateTest {
    @Test
    fun missingGameStillAllowsEntryToInstallationGuidance() {
        val state = readyState(GameTarget.INT_ANDROID).copy(gameInstalls = emptyMap())
        assertTrue(state.canPatch)
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
