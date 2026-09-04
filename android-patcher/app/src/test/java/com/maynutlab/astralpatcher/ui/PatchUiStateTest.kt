package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.GameTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchUiStateTest {
    @Test
    fun internationalTargetCanBeSelectedButCannotPatchWhenMissing() {
        val state = readyState(GameTarget.INT_ANDROID).copy(gameInstalls = emptyMap())
        assertFalse(state.canPatch)
    }

    @Test
    fun installedCnTargetCanPatchWhenAllReadinessConditionsAreMet() {
        val state = readyState(GameTarget.CN_ANDROID)
        assertTrue(state.canPatch)
    }

    @Test
    fun latestAppliedPatchDisablesApplyAction() {
        val state = readyState(GameTarget.CN_ANDROID).copy(
            installedPatchVersion = "v3.2.0_r211",
        )
        assertFalse(state.canPatch)
        assertTrue(state.canRestore)
    }

    @Test
    fun incompleteResourcesDisableApplyAction() {
        val state = readyState(GameTarget.CN_ANDROID).copy(
            resourceIndicator = StatusIndicator.PENDING,
        )
        assertFalse(state.canPatch)
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
