package com.maynutlab.astralpatcher.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class PatchTargetPolicyTest {
    @Test
    fun acceptsAnyExistingPatchTarget() {
        assertEquals(PatchTargetState.READY, classifyPatchTarget(targetExists = true))
    }

    @Test
    fun rejectsMissingPatchTarget() {
        assertEquals(PatchTargetState.MISSING, classifyPatchTarget(targetExists = false))
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
