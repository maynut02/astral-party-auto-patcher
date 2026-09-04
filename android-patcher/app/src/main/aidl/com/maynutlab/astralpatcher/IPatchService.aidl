package com.maynutlab.astralpatcher;

import android.os.ParcelFileDescriptor;

interface IPatchService {
    String getServiceInfo() = 0;
    String inspectGame(String packageName) = 1;
    void beginPatch(String transactionId, String packageName, String catalogHash) = 2;
    String applyFile(
        String transactionId,
        String packageName,
        in ParcelFileDescriptor payload,
        long size,
        String sha256,
        long sourceSize,
        String sourceSha256,
        String relativePath
    ) = 3;
    void commitPatch(
        String transactionId,
        String packageName,
        String gameVersion,
        String catalogHash,
        String patchVersion
    ) = 4;
    void rollbackPatch(String transactionId, String packageName) = 5;
    String restorePatch(String packageName) = 6;
    String inspectPatchTargets(String packageName, String requirementsJson) = 7;
    String getPatchDiagnostics(String transactionId, String packageName) = 8;
    void stageOriginal(
        String transactionId,
        String packageName,
        in ParcelFileDescriptor original,
        long sourceSize,
        String sourceSha256,
        String relativePath
    ) = 9;
    void beginGameInstall(String installId, int fileCount) = 10;
    void stageGameApk(
        String installId,
        String name,
        in ParcelFileDescriptor apk,
        long size,
        String sha256
    ) = 11;
    String commitGameInstall(String installId) = 12;
    void cancelGameInstall(String installId) = 13;
    void destroy() = 16777114;
}
