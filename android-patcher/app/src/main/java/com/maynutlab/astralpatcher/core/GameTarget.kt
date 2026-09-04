package com.maynutlab.astralpatcher.core

enum class GameTarget(
    val storageKey: String,
    val packageName: String,
    val route: String,
    val displayName: String,
    val supportsOriginalInstall: Boolean,
    val selectableWhenMissing: Boolean,
) {
    INT_ANDROID(
        storageKey = "int_android",
        packageName = "com.feimo.astralpartyjpn",
        route = "INT_ANDROID",
        displayName = "일본 버전",
        supportsOriginalInstall = true,
        selectableWhenMissing = true,
    ),
    CN_ANDROID(
        storageKey = "cn_android",
        packageName = "com.feimo.astralparty",
        route = "CN_ANDROID",
        displayName = "중국 버전",
        supportsOriginalInstall = false,
        selectableWhenMissing = false,
    ),
    CN_ANDROID_BILIBILI(
        storageKey = "cn_android_bilibili",
        packageName = "com.feimo.astralparty.bilibili",
        route = "CN_ANDROID",
        displayName = "빌리빌리 버전",
        supportsOriginalInstall = false,
        selectableWhenMissing = false,
    );

    companion object {
        val supportedPackages: Set<String> = entries.mapTo(linkedSetOf(), GameTarget::packageName)

        fun fromStorageKey(value: String?): GameTarget? = entries.firstOrNull { it.storageKey == value }

        fun fromPackageName(value: String): GameTarget? = entries.firstOrNull { it.packageName == value }
    }
}

fun requireSupportedGamePackage(value: String): String {
    require(value in GameTarget.supportedPackages) { "지원하지 않는 게임 패키지입니다." }
    return value
}

fun selectGameTarget(
    previous: GameTarget?,
    installedPackages: Set<String>,
): GameTarget {
    if (previous != null && (previous.packageName in installedPackages || previous.selectableWhenMissing)) {
        return previous
    }
    return GameTarget.entries.firstOrNull { it.packageName in installedPackages }
        ?: GameTarget.INT_ANDROID
}
