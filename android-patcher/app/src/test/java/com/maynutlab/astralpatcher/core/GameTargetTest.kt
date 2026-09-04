package com.maynutlab.astralpatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTargetTest {
    @Test
    fun cnPackagesShareCnAndroidRoute() {
        assertEquals("CN_ANDROID", GameTarget.CN_ANDROID.route)
        assertEquals("CN_ANDROID", GameTarget.CN_ANDROID_BILIBILI.route)
        assertEquals(GameTarget.CN_ANDROID, GameTarget.fromPackageName("com.feimo.astralparty"))
        assertEquals(
            GameTarget.CN_ANDROID_BILIBILI,
            GameTarget.fromPackageName("com.feimo.astralparty.bilibili"),
        )
    }

    @Test
    fun rejectsUnsupportedPackageForPrivilegedPaths() {
        requireSupportedGamePackage(GameTarget.INT_ANDROID.packageName)
        try {
            requireSupportedGamePackage("com.example.other")
            throw AssertionError("unsupported package was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun onlyInternationalTargetIsSelectableWhenMissing() {
        assertTrue(GameTarget.INT_ANDROID.selectableWhenMissing)
        assertFalse(GameTarget.CN_ANDROID.selectableWhenMissing)
        assertFalse(GameTarget.CN_ANDROID_BILIBILI.selectableWhenMissing)
    }

    @Test
    fun selectionKeepsValidPreviousTarget() {
        assertEquals(
            GameTarget.CN_ANDROID,
            selectGameTarget(
                previous = GameTarget.CN_ANDROID,
                installedPackages = setOf(GameTarget.CN_ANDROID.packageName),
            ),
        )
    }

    @Test
    fun missingCnSelectionFallsBackToInstalledTarget() {
        assertEquals(
            GameTarget.CN_ANDROID_BILIBILI,
            selectGameTarget(
                previous = GameTarget.CN_ANDROID,
                installedPackages = setOf(GameTarget.CN_ANDROID_BILIBILI.packageName),
            ),
        )
    }

    @Test
    fun noInstalledGameFallsBackToInternationalTarget() {
        assertEquals(GameTarget.INT_ANDROID, selectGameTarget(previous = null, installedPackages = emptySet()))
    }
}
