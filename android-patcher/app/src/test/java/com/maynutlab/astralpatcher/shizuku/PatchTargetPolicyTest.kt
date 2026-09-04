package com.maynutlab.astralpatcher.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class PatchTargetPolicyTest {
    @Test
    fun acceptsMatchingOriginalPatchTarget() {
        assertEquals(
            PatchTargetState.READY,
            classifyPatchTarget(
                targetExists = true,
                sourceMatches = true,
                payloadMatches = false,
            ),
        )
    }

    @Test
    fun acceptsMatchingPatchedTarget() {
        assertEquals(
            PatchTargetState.READY,
            classifyPatchTarget(
                targetExists = true,
                sourceMatches = false,
                payloadMatches = true,
            ),
        )
    }

    @Test
    fun rejectsMissingPatchTarget() {
        assertEquals(
            PatchTargetState.MISSING,
            classifyPatchTarget(
                targetExists = false,
                sourceMatches = false,
                payloadMatches = false,
            ),
        )
    }

    @Test
    fun rejectsIncompatibleExistingPatchTarget() {
        assertEquals(
            PatchTargetState.INCOMPATIBLE,
            classifyPatchTarget(
                targetExists = true,
                sourceMatches = false,
                payloadMatches = false,
            ),
        )
    }

    @Test
    fun reusesVerifiedManagedBackup() {
        assertEquals(
            OriginalBackupAction.REUSE,
            originalBackupAction(backupExists = true, backupMatches = true),
        )
    }

    @Test
    fun createsMissingManagedBackupFromReleaseOriginal() {
        assertEquals(
            OriginalBackupAction.REPLACE,
            originalBackupAction(backupExists = false, backupMatches = false),
        )
    }

    @Test
    fun replacesCorruptedManagedBackupFromReleaseOriginal() {
        assertEquals(
            OriginalBackupAction.REPLACE,
            originalBackupAction(backupExists = true, backupMatches = false),
        )
    }
}
