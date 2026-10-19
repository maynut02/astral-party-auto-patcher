package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.ReleaseEntry
import java.math.BigInteger

internal data class GameUpdateRequirement(val installedVersion: String, val requiredVersion: String) {
    val description: String get() = "설치됨 $installedVersion · 필요 $requiredVersion"
}

/** Compare game APK versions numerically; a resource revision or translation p-number is separate. */
internal fun requiredGameUpdate(installedVersion: String?, latest: ReleaseEntry?): GameUpdateRequirement? {
    val installedText = installedVersion ?: return null
    val requiredText = latest?.gameVersion ?: return null
    val installed = numericVersion(installedText) ?: return null
    val required = numericVersion(requiredText) ?: return null
    for (index in 0 until maxOf(installed.size, required.size)) {
        val comparison = installed.getOrElse(index) { BigInteger.ZERO }
            .compareTo(required.getOrElse(index) { BigInteger.ZERO })
        if (comparison != 0) return if (comparison < 0) {
            GameUpdateRequirement(installedText, requiredText)
        } else null
    }
    return null
}

internal fun gameUpdateGuidance(target: GameTarget): String = when (target) {
    GameTarget.INT_ANDROID -> "게임을 설치한 스토어에서 업데이트한 뒤 게임 내 리소스를 모두 다운로드해 주세요."
    GameTarget.CN_ANDROID -> "중국 버전의 배포처에서 게임을 업데이트한 뒤 게임 내 리소스를 모두 다운로드해 주세요."
    GameTarget.CN_ANDROID_BILIBILI -> "빌리빌리에서 게임을 업데이트한 뒤 게임 내 리소스를 모두 다운로드해 주세요."
}

private fun numericVersion(value: String): List<BigInteger>? = value.trim()
    .takeIf { GAME_VERSION_PATTERN.matches(it) }
    ?.split('.')?.map(::BigInteger)

private val GAME_VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)*")
