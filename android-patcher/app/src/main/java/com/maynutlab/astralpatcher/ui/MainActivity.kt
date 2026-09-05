package com.maynutlab.astralpatcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.maynutlab.astralpatcher.BuildConfig
import com.maynutlab.astralpatcher.IPatchService
import com.maynutlab.astralpatcher.R
import com.maynutlab.astralpatcher.core.CatalogIdentity
import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.MobilePatcherRelease
import com.maynutlab.astralpatcher.core.OriginalGameRelease
import com.maynutlab.astralpatcher.core.PatchHttpClient
import com.maynutlab.astralpatcher.core.PatchManifest
import com.maynutlab.astralpatcher.core.PatchProtocol
import com.maynutlab.astralpatcher.core.selectGameTarget
import com.maynutlab.astralpatcher.core.SHIZUKU_PACKAGE
import com.maynutlab.astralpatcher.shizuku.PatchUserService
import rikka.shizuku.Shizuku
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var controller: PatchController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = PatchController(this)
        setContent {
            AstralMaterialTheme {
                PatchManagerScreen(
                    state = controller.state,
                    onSelectTarget = controller::selectTarget,
                    onRefresh = controller::manualRefresh,
                    onUpdate = controller::updatePatcher,
                    onShizuku = controller::handleShizuku,
                    onGame = controller::handleGame,
                    onPatch = controller::applyPatch,
                    onRestore = controller::restorePatch,
                    onLaunch = controller::launchGame,
                )
            }
        }
        controller.start()
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.resume()
    }
}

internal data class PatchUiState(
    val selectedTarget: GameTarget = GameTarget.INT_ANDROID,
    val gameInstalls: Map<GameTarget, GameInstall> = emptyMap(),
    val shizukuLabel: String = "Shizuku 확인 중",
    val shizukuStatus: String = "확인 중",
    val shizukuReady: Boolean = false,
    val shizukuActionLabel: String = "설정",
    val resourceLabel: String = "리소스 확인 중",
    val resourceStatus: String = "대기 중",
    val resourceIndicator: StatusIndicator = StatusIndicator.PENDING,
    val resourceNeedsDownload: Boolean = false,
    val releaseLabel: String = "한글패치 확인 중",
    val releaseStatus: String = "대기 중",
    val releaseIndicator: StatusIndicator = StatusIndicator.PENDING,
    val latestPatchVersion: String? = null,
    val latestPatchUploadedAt: String? = null,
    val latestPatchStatus: String = "확인 중",
    val patchVersion: String? = null,
    val installedPatchVersion: String? = null,
    val availablePatcherVersion: String? = null,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val progressSection: ProgressSection? = null,
    val progress: Float = 0f,
    val progressLabel: String? = null,
    val logs: List<String> = emptyList(),
) {
    val selectedInstall: GameInstall?
        get() = gameInstalls[selectedTarget]
    val selectedInstalled: Boolean
        get() = selectedInstall != null
    val gameReady: Boolean
        get() = selectedInstall?.let { install ->
            if (selectedTarget.supportsOriginalInstall) install.installedFromPlay else true
        } == true
    val canPatch: Boolean
        get() = gameReady && shizukuReady && resourceIndicator == StatusIndicator.COMPLETED &&
            patchVersion != null && patchVersion != installedPatchVersion && !busy
    val canRestore: Boolean
        get() = gameReady && shizukuReady && installedPatchVersion != null && !busy
    val canLaunch: Boolean
        get() = selectedInstalled && !busy
}

internal enum class StatusIndicator {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    ERROR,
}

internal enum class ProgressSection {
    SHIZUKU,
    APK,
    PATCH,
    PATCHER_UPDATE,
}


private class PatchController(private val activity: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private var service: IPatchService? = null
    private var binding = false
    private var catalog: CatalogIdentity? = null
    private var manifest: PatchManifest? = null
    @Volatile
    private var latestPatcher: MobilePatcherRelease? = null
    private var pendingSystemInstall: PendingInstall? = null
    @Volatile
    private var inspecting = false
    @Volatile
    private var updateChecking = false
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
    private var preferredTarget = GameTarget.fromStorageKey(
        preferences.getString(PREFERENCE_GAME_TARGET, null)
    )

    var state by mutableStateOf(
        PatchUiState(selectedTarget = preferredTarget ?: GameTarget.INT_ANDROID)
    )
        private set

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(activity, PatchUserService::class.java)
    )
        .daemon(false)
        .tag("astral-cache-patcher-v6")
        .version(BuildConfig.VERSION_CODE)
        .processNameSuffix("patch")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPatchService.Stub.asInterface(binder)
            binding = false
            appendLog("Shizuku patch 서비스에 연결했습니다.")
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            appendLog("Shizuku patch 서비스 연결이 종료되었습니다.")
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) refresh()
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (binding || service != null) {
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        }
        service = null
        executor.shutdownNow()
        updateExecutor.shutdownNow()
    }

    fun resume() {
        val pending = pendingSystemInstall
        if (pending != null && activity.packageManager.canRequestPackageInstalls()) {
            pendingSystemInstall = null
            openSystemInstaller(pending.apk)
        } else if (!state.busy) {
            refresh()
        }
    }

    fun manualRefresh() {
        if (state.busy || state.refreshing) return
        state = state.copy(refreshing = true)
        refresh()
    }

    fun refresh() {
        if (state.busy) return
        val installs = installedGames()
        val selected = selectGameTarget(
            previous = preferredTarget,
            installedPackages = installs.keys.mapTo(mutableSetOf()) { it.packageName },
        )
        if (selected != state.selectedTarget) persistSelectedTarget(selected)
        val selectedInstall = installs[selected]
        val gameReady = selectedInstall?.let { install ->
            if (selected.supportsOriginalInstall) install.installedFromPlay else true
        } == true
        val shizukuInstalled = isPackageInstalled(SHIZUKU_PACKAGE)
        val binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderReady &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

        state = state.copy(
            selectedTarget = selected,
            gameInstalls = installs,
            shizukuLabel = when {
                !shizukuInstalled -> "Shizuku 미설치"
                !binderReady -> "Shizuku 비활성화"
                !permissionGranted -> "Shizuku 권한 필요"
                else -> "Shizuku 연결됨"
            },
            shizukuStatus = when {
                !shizukuInstalled -> "설치 버튼을 눌러 Shizuku를 설치하세요"
                !binderReady -> "열기 버튼을 눌러 페어링 및 서비스를 시작해 주세요"
                !permissionGranted -> "권한 허용 버튼을 눌러 권한을 허용해 주세요"
                else -> "연결 및 권한 확인이 완료되었습니다"
            },
            shizukuReady = permissionGranted,
            shizukuActionLabel = when {
                !shizukuInstalled -> "설치"
                !binderReady -> "열기"
                !permissionGranted -> "권한 허용"
                else -> "열기"
            },
            resourceLabel = when {
                !gameReady -> "리소스 확인 불가"
                !permissionGranted -> "리소스 확인 불가"
                else -> "리소스 확인 중"
            },
            resourceStatus = when {
                !gameReady -> "게임 설치가 필요합니다"
                !permissionGranted -> "Shizuku 활성화 및 권한이 필요합니다"
                else -> "리소스 상태를 확인 중입니다"
            },
            resourceIndicator = if (gameReady && permissionGranted) {
                StatusIndicator.IN_PROGRESS
            } else {
                StatusIndicator.PENDING
            },
            resourceNeedsDownload = false,
            releaseLabel = when {
                !gameReady -> "한글패치 확인 실패"
                !permissionGranted -> "한글패치 확인 실패"
                else -> "한글패치 확인 중"
            },
            releaseStatus = when {
                !gameReady -> "게임이 설치되지 않았습니다"
                !permissionGranted -> "Shizuku 활성화 및 권한이 필요합니다"
                else -> "현재 설치 상태를 확인 중입니다"
            },
            releaseIndicator = if (gameReady && permissionGranted) {
                StatusIndicator.IN_PROGRESS
            } else {
                StatusIndicator.PENDING
            },
            latestPatchVersion = null,
            latestPatchUploadedAt = null,
            latestPatchStatus = "확인 중",
            patchVersion = null,
            installedPatchVersion = null,
        )
        catalog = null
        manifest = null
        checkPatcherUpdate()
        checkLatestPatchVersion(selected, selectedInstall?.version)
        if (!permissionGranted) return
        if (service == null) {
            bindPatchService()
            return
        }
        if (!gameReady) {
            maybeFinishManualRefresh()
            return
        }
        inspectAndResolve(selected)
    }

    private fun maybeFinishManualRefresh() {
        if (!state.refreshing) return
        val statusStillUpdating =
            state.latestPatchStatus == "확인 중" ||
                state.resourceIndicator == StatusIndicator.IN_PROGRESS ||
                state.releaseIndicator == StatusIndicator.IN_PROGRESS
        if (statusStillUpdating || updateChecking || binding || inspecting) return
        state = state.copy(refreshing = false)
    }

    fun selectTarget(target: GameTarget) {
        if (state.busy || state.refreshing || target == state.selectedTarget) return
        val installed = state.gameInstalls[target] != null
        if (!installed && !target.selectableWhenMissing) return
        persistSelectedTarget(target)
        state = state.copy(selectedTarget = target)
        catalog = null
        manifest = null
        refresh()
    }

    private fun persistSelectedTarget(target: GameTarget) {
        preferredTarget = target
        preferences.edit().putString(PREFERENCE_GAME_TARGET, target.storageKey).apply()
    }

    fun handleShizuku() {
        if (state.busy) return
        when {
            !isPackageInstalled(SHIZUKU_PACKAGE) -> {
                val pending = pendingSystemInstall
                if (pending?.target == InstallTarget.SHIZUKU && pending.apk.isFile) {
                    requestSystemInstall(pending.apk, InstallTarget.SHIZUKU)
                } else {
                    installShizuku()
                }
            }
            !Shizuku.pingBinder() -> openPackage(SHIZUKU_PACKAGE)
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    appendLog("Shizuku에서 한글패치 앱 권한을 허용해 주세요.")
                    openPackage(SHIZUKU_PACKAGE)
                } else {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                }
            }
            else -> openPackage(SHIZUKU_PACKAGE)
        }
    }

    fun handleGame() {
        val target = state.selectedTarget
        if (!target.supportsOriginalInstall) return
        if (state.gameInstalls[target]?.installedFromPlay == true) {
            launchGame()
            return
        }
        if (!state.shizukuReady) {
            appendLog("원본 게임을 설치하려면 먼저 Shizuku를 실행하고 권한을 허용해 주세요.")
            handleShizuku()
            return
        }
        val patchService = service
        if (patchService == null) {
            appendLog("Shizuku 설치 서비스에 연결한 뒤 다시 시도해 주세요.")
            bindPatchService()
            return
        }
        installOriginalGame(patchService)
    }

    private fun installOriginalGame(patchService: IPatchService) {
        if (state.busy) return
        setBusy("Google Play 원본 게임 정보를 확인하는 중", 0f, ProgressSection.APK)
        executor.execute {
            val installId = UUID.randomUUID().toString()
            var installStarted = false
            var downloaded = emptyList<File>()
            try {
                require(patchService.getServiceInfo().startsWith("AstralPatchService/6 ")) {
                    "지원하지 않는 게임 설치 서비스입니다."
                }
                val release = PatchHttpClient.getLatestOriginalGameRelease()
                appendLog(
                    "원본 게임 ${release.versionName}(${release.versionCode}) · " +
                        "${release.files.size}개 split · profile=${release.deviceProfile}"
                )
                downloaded = PatchHttpClient.downloadOriginalGameApks(
                    activity.cacheDir,
                    release,
                ) { name, percent ->
                    updateBusy("원본 게임 APK 다운로드 · $name", percent / 100f * 0.8f)
                }
                verifyOriginalGameApks(downloaded, release)
                appendLog("원본 게임 APK 해시, packageName, versionCode 및 Play 인증서를 확인했습니다.")

                patchService.beginGameInstall(installId, downloaded.size)
                installStarted = true
                downloaded.forEachIndexed { index, apk ->
                    val metadata = release.files[index]
                    updateBusy(
                        "원본 게임 APK staging ${index + 1}/${downloaded.size}",
                        0.8f + (index.toFloat() / downloaded.size) * 0.15f,
                    )
                    ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        patchService.stageGameApk(
                            installId,
                            metadata.name,
                            descriptor,
                            metadata.size,
                            metadata.sha256,
                        )
                    }
                }
                updateBusy("원본 split APK를 설치하는 중", 0.96f)
                val result = patchService.commitGameInstall(installId)
                installStarted = false
                verifyInstalledOriginalGame(release)
                main.post {
                    appendLog("원본 게임 설치 완료 · $result")
                    setIdle()
                    refresh()
                }
            } catch (error: Exception) {
                if (installStarted) runCatching { patchService.cancelGameInstall(installId) }
                showError("원본 게임 설치에 실패했습니다.", error)
            } finally {
                downloaded.forEach(File::delete)
            }
        }
    }

    private fun installShizuku() {
        setBusy("최신 Shizuku 안정 버전을 확인하는 중", 0f, ProgressSection.SHIZUKU)
        updateExecutor.execute {
            var apk: File? = null
            try {
                val latest = PatchHttpClient.getLatestShizukuRelease()
                updateBusy("Shizuku ${latest.version} APK 다운로드", 0f)
                val downloadedApk = PatchHttpClient.downloadShizukuApk(activity.cacheDir, latest) { percent ->
                    updateBusy("Shizuku ${latest.version} APK 다운로드", percent / 100f)
                }
                apk = downloadedApk
                verifyShizukuApk(downloadedApk)
                main.post {
                    appendLog("Shizuku ${latest.version} APK 다운로드 및 검증을 완료했습니다.")
                    setIdle()
                    requestSystemInstall(downloadedApk, InstallTarget.SHIZUKU)
                }
            } catch (error: Exception) {
                apk?.delete()
                showError("Shizuku 다운로드에 실패했습니다.", error)
            }
        }
    }

    fun updatePatcher() {
        if (state.busy) return
        val pending = pendingSystemInstall
        if (pending?.target == InstallTarget.PATCHER && pending.apk.isFile) {
            requestSystemInstall(pending.apk, InstallTarget.PATCHER)
            return
        }
        val target = latestPatcher?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) } ?: return
        setBusy("Android 패처 ${target.version} 다운로드", 0f, ProgressSection.PATCHER_UPDATE)
        updateExecutor.execute {
            var apk: File? = null
            try {
                val downloadedApk = PatchHttpClient.downloadMobilePatcherApk(
                    activity.cacheDir,
                    target,
                ) { percent ->
                    updateBusy("Android 패처 ${target.version} 다운로드", percent / 100f)
                }
                apk = downloadedApk
                verifyMobilePatcherApk(downloadedApk, target)
                main.post {
                    appendLog("Android 패처 ${target.version} APK 다운로드 및 검증을 완료했습니다.")
                    setIdle()
                    requestSystemInstall(downloadedApk, InstallTarget.PATCHER)
                }
            } catch (error: Exception) {
                apk?.delete()
                showError("Android 패처 업데이트 다운로드에 실패했습니다.", error)
            }
        }
    }

    fun applyPatch() {
        val target = state.selectedTarget
        val targetManifest = manifest ?: return
        val targetCatalog = catalog ?: return
        val patchService = service ?: return
        require(targetManifest.route == target.route) { "선택한 게임과 patch route가 다릅니다." }
        state = state.copy(releaseIndicator = StatusIndicator.IN_PROGRESS)
        setBusy("패치 정보를 검증하는 중", 0f, ProgressSection.PATCH)
        executor.execute {
            val transactionId = UUID.randomUUID().toString()
            var transactionStarted = false
            var diagnosticsCursor = 0
            val payloads = mutableListOf<File>()
            val originals = mutableListOf<File>()
            val fileCount = targetManifest.files.size.coerceAtLeast(1)
            fun overallPatchProgress(fileIndex: Int, fileProgress: Float): Float =
                0.05f + ((fileIndex + fileProgress.coerceIn(0f, 1f)) / fileCount) * 0.9f
            try {
                val serviceInfo = patchService.getServiceInfo()
                appendLog(
                    "[CLIENT] patch 시작 · app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                        "transaction=$transactionId"
                )
                appendLog("[CLIENT] service=$serviceInfo")
                appendLog(
                    "[CLIENT] manifest · patch=${targetManifest.patchVersion} " +
                        "game=${targetManifest.gameVersion} revision=${targetManifest.revision} " +
                        "catalog=${targetManifest.catalogHash} files=${targetManifest.files.size}"
                )
                val readiness = PatchProtocol.parseTargetInspection(
                    patchService.inspectPatchTargets(
                        target.packageName,
                        PatchProtocol.createTargetInspectionRequest(targetManifest),
                    )
                )
                appendLog(
                    "[CLIENT] 리소스 사전검사 · total=${readiness.total} ready=${readiness.ready} " +
                        "missing=${readiness.missing} incompatible=${readiness.incompatible}"
                )
                require(readiness.isReady) {
                    "게임 리소스가 변경되었거나 필요한 파일이 없습니다. 새로고침 후 다시 시도해 주세요."
                }
                appendLog("[CLIENT] beginPatch 호출 · transaction=$transactionId")
                transactionStarted = true
                patchService.beginPatch(transactionId, target.packageName, targetCatalog.catalogHash)
                appendLog("[CLIENT] beginPatch 반환")
                diagnosticsCursor = appendServiceDiagnostics(
                    patchService,
                    target,
                    transactionId,
                    diagnosticsCursor,
                    includeSnapshot = true,
                )
                updateBusy("패치 작업 준비 완료", 0.05f)
                targetManifest.files.forEachIndexed { index, item ->
                    val fileLabel = "${index + 1}/${targetManifest.files.size}"
                    val original = PatchHttpClient.downloadOriginal(
                        activity.cacheDir,
                        item,
                        index,
                    ) { percent ->
                        updateBusy(
                            "원본 파일 $fileLabel 다운로드",
                            overallPatchProgress(index, percent / 100f * 0.35f),
                        )
                    }
                    originals += original
                    ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        patchService.stageOriginal(
                            transactionId,
                            target.packageName,
                            descriptor,
                            item.sourceSize,
                            item.sourceSha256,
                            item.relativePath,
                        )
                    }
                    updateBusy(
                        "원본 파일 $fileLabel 검증 및 보관 완료",
                        overallPatchProgress(index, 0.35f),
                    )
                    appendLog("[CLIENT] 파일 $fileLabel 릴리즈 원본 검증 및 보관 완료")
                    appendLog(
                        "[CLIENT] 파일 $fileLabel 다운로드 시작 · path=${item.relativePath} " +
                            "downloadBytes=${item.downloadSize} payloadBytes=${item.payloadSize} " +
                            "payloadSha256=${item.payloadSha256} sourceBytes=${item.sourceSize} " +
                            "sourceSha256=${item.sourceSha256}"
                    )
                    updateBusy(
                        "파일 $fileLabel 다운로드 및 검증",
                        overallPatchProgress(index, 0.35f),
                    )
                    val payload = PatchHttpClient.downloadPayload(
                        activity.cacheDir,
                        item,
                        index,
                    ) { percent ->
                        updateBusy(
                            "파일 $fileLabel 다운로드",
                            overallPatchProgress(index, 0.35f + percent / 100f * 0.5f),
                        )
                    }
                    payloads += payload
                    appendLog("[CLIENT] 파일 $fileLabel 다운로드 검증 완료 · localBytes=${payload.length()}")
                    updateBusy(
                        "파일 $fileLabel 적용",
                        overallPatchProgress(index, 0.9f),
                    )
                    appendLog("[CLIENT] 파일 $fileLabel applyFile 호출 · path=${item.relativePath}")
                    val applyResultJson = ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        patchService.applyFile(
                            transactionId,
                            target.packageName,
                            descriptor,
                            item.payloadSize,
                            item.payloadSha256,
                            item.sourceSize,
                            item.sourceSha256,
                            item.relativePath,
                        )
                    }
                    val applyResult = PatchProtocol.parseApplyFileResult(applyResultJson)
                    appendLog(
                        if (applyResult.ok) {
                            "[CLIENT] 파일 $fileLabel applyFile 성공"
                        } else {
                            "[CLIENT] 파일 $fileLabel applyFile 실패 · ${applyResult.error}"
                        }
                    )
                    diagnosticsCursor = appendServiceDiagnostics(
                        patchService,
                        target,
                        transactionId,
                        diagnosticsCursor,
                        includeSnapshot = true,
                    )
                    require(applyResult.ok) {
                        applyResult.error.ifBlank { "patch 파일 적용에 실패했습니다." }
                    }
                    updateBusy(
                        "파일 $fileLabel 적용 완료",
                        overallPatchProgress(index, 1f),
                    )
                }
                appendLog("[CLIENT] commitPatch 호출")
                updateBusy("패치 적용 결과를 저장하는 중", 0.98f)
                patchService.commitPatch(
                    transactionId,
                    target.packageName,
                    targetManifest.gameVersion,
                    targetManifest.catalogHash,
                    targetManifest.patchVersion,
                )
                transactionStarted = false
                appendLog("[CLIENT] commitPatch 반환")
                updateBusy("한글패치 적용 완료", 1f)
                diagnosticsCursor = appendServiceDiagnostics(
                    patchService,
                    target,
                    transactionId,
                    diagnosticsCursor,
                    includeSnapshot = true,
                )
                main.post {
                    appendLog("${targetManifest.patchVersion} 적용을 완료했습니다.")
                    setIdle()
                    refresh()
                }
            } catch (error: Exception) {
                main.post { state = state.copy(releaseIndicator = StatusIndicator.ERROR) }
                appendLog("[CLIENT] patch 예외 · ${describeError(error)}")
                diagnosticsCursor = appendServiceDiagnostics(
                    patchService,
                    target,
                    transactionId,
                    diagnosticsCursor,
                    includeSnapshot = true,
                )
                if (transactionStarted) {
                    appendLog("[CLIENT] rollbackPatch 호출")
                    runCatching { patchService.rollbackPatch(transactionId, target.packageName) }
                        .onSuccess { appendLog("[CLIENT] rollbackPatch 반환") }
                        .onFailure { rollbackError ->
                            appendLog("[CLIENT] rollbackPatch 예외 · ${describeError(rollbackError)}")
                        }
                    appendServiceDiagnostics(
                        patchService,
                        target,
                        transactionId,
                        diagnosticsCursor,
                        includeSnapshot = true,
                    )
                }
                showError("한글패치 적용에 실패했습니다.", error)
            } finally {
                payloads.forEach(File::delete)
                originals.forEach(File::delete)
            }
        }
    }

    fun restorePatch() {
        val target = state.selectedTarget
        val patchService = service ?: return
        state = state.copy(releaseIndicator = StatusIndicator.IN_PROGRESS)
        setBusy("원본 리소스를 복원하는 중", 0f, ProgressSection.PATCH)
        executor.execute {
            try {
                val result = patchService.restorePatch(target.packageName)
                main.post {
                    appendLog(result)
                    setIdle()
                    refresh()
                }
            } catch (error: Exception) {
                main.post { state = state.copy(releaseIndicator = StatusIndicator.ERROR) }
                showError("원본 복원에 실패했습니다.", error)
            }
        }
    }

    fun launchGame() {
        val target = state.selectedTarget
        val intent = activity.packageManager.getLaunchIntentForPackage(target.packageName)
        if (intent == null) appendLog("게임 실행 Activity를 찾지 못했습니다.")
        else activity.startActivity(intent)
    }

    private fun bindPatchService() {
        if (binding) return
        binding = true
        appendLog("Shizuku patch 서비스에 연결하는 중입니다.")
        runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }
            .onFailure {
                binding = false
                if (state.refreshing) state = state.copy(refreshing = false)
                showError("Shizuku patch 서비스 연결에 실패했습니다.", it)
            }
    }

    private fun inspectAndResolve(target: GameTarget) {
        val patchService = service ?: return
        if (inspecting) return
        inspecting = true
        executor.execute {
            var inspected: CatalogIdentity? = null
            var checkingTargets = false
            try {
                require(patchService.getServiceInfo().startsWith("AstralPatchService/6 ")) {
                    "지원하지 않는 patch 서비스입니다."
                }
                val catalogIdentity = PatchProtocol.parseInspection(patchService.inspectGame(target.packageName))
                inspected = catalogIdentity
                val index = PatchHttpClient.getIndex()
                val resolved = PatchProtocol.resolveRelease(index, catalogIdentity, target.route)
                if (state.selectedTarget != target) return@execute
                catalog = catalogIdentity
                if (resolved == null) {
                    manifest = null
                    main.post {
                        if (state.selectedTarget != target) return@post
                        state = state.copy(
                            resourceLabel = "리소스 확인 실패",
                            resourceStatus = "패치 대상 리소스를 확인할 수 없습니다",
                            resourceIndicator = StatusIndicator.PENDING,
                            resourceNeedsDownload = false,
                            releaseLabel = if (catalogIdentity.installedPatchVersion != null) {
                                "한글패치 확인 필요"
                            } else {
                                "한글패치 미설치"
                            },
                            releaseStatus = catalogIdentity.installedPatchVersion?.let { "$it • 호환되지 않는 패치" }
                                ?: "-",
                            releaseIndicator = StatusIndicator.PENDING,
                            patchVersion = null,
                            installedPatchVersion = catalogIdentity.installedPatchVersion,
                        )
                    }
                    return@execute
                }

                val resolvedManifest = PatchProtocol.parseManifest(
                    PatchHttpClient.getManifest(resolved),
                    resolved,
                    target.route,
                )
                checkingTargets = true
                val targetInspection = PatchProtocol.parseTargetInspection(
                    patchService.inspectPatchTargets(
                        target.packageName,
                        PatchProtocol.createTargetInspectionRequest(resolvedManifest),
                    )
                )
                val ready = targetInspection.isReady
                if (state.selectedTarget != target) return@execute
                manifest = resolvedManifest
                main.post {
                    if (state.selectedTarget != target) return@post
                    val installedPatch = catalogIdentity.installedPatchVersion
                    state = state.copy(
                        resourceLabel = when {
                            ready -> "리소스 확인 완료"
                            targetInspection.incompatible > 0 -> "리소스 업데이트 필요"
                            else -> "리소스 확인 실패"
                        },
                        resourceStatus = when {
                            ready -> "패치 대상 리소스 ${targetInspection.total}개를 확인했습니다"
                            targetInspection.incompatible > 0 && targetInspection.missing > 0 ->
                                "${targetInspection.incompatible}개의 리소스가 일치하지 않습니다\n${targetInspection.missing}개의 리소스가 누락되었습니다"
                            targetInspection.incompatible > 0 ->
                                "${targetInspection.incompatible}개의 리소스가 일치하지 않습니다"
                            else -> "패치 대상 리소스 ${targetInspection.missing}개를 확인할 수 없습니다"
                        },
                        resourceIndicator = if (ready) StatusIndicator.COMPLETED else StatusIndicator.PENDING,
                        resourceNeedsDownload = targetInspection.missing > 0 || targetInspection.incompatible > 0,
                        releaseLabel = when {
                            installedPatch == null -> "한글패치 미설치"
                            installedPatch == resolved.patchVersion -> "한글패치 설치됨"
                            else -> "한글패치 업데이트 필요"
                        },
                        releaseStatus = when {
                            installedPatch == null -> "-"
                            installedPatch == resolved.patchVersion -> "$installedPatch • 최신 버전"
                            else -> installedPatch
                        },
                        releaseIndicator = if (installedPatch == resolved.patchVersion) {
                            StatusIndicator.COMPLETED
                        } else {
                            StatusIndicator.PENDING
                        },
                        latestPatchVersion = resolved.patchVersion,
                        latestPatchUploadedAt = resolved.uploadedAt,
                        latestPatchStatus = "확인 완료",
                        patchVersion = resolved.patchVersion,
                        installedPatchVersion = installedPatch,
                    )
                }
            } catch (error: Exception) {
                val needsDownload = isResourceNotReady(error)
                val currentCatalog = inspected
                main.post {
                    if (state.selectedTarget != target) return@post
                    state = state.copy(
                        resourceLabel = if (needsDownload) {
                            "리소스 다운로드 필요"
                        } else {
                            "리소스 확인 실패"
                        },
                        resourceStatus = when {
                            needsDownload -> "게임을 실행해 리소스를 다운로드해 주세요"
                            currentCatalog != null && !checkingTargets -> "패치 대상 리소스를 확인할 수 없습니다"
                            else -> "리소스를 확인할 수 없습니다"
                        },
                        resourceIndicator = if (needsDownload) {
                            StatusIndicator.PENDING
                        } else {
                            StatusIndicator.ERROR
                        },
                        resourceNeedsDownload = needsDownload,
                        releaseLabel = "한글패치 확인 실패",
                        releaseStatus = if (currentCatalog != null && !checkingTargets) {
                            "패치 정보를 확인할 수 없습니다"
                        } else {
                            "리소스가 설치되지 않았습니다"
                        },
                        releaseIndicator = if (needsDownload) {
                            StatusIndicator.PENDING
                        } else {
                            StatusIndicator.ERROR
                        },
                        patchVersion = null,
                        installedPatchVersion = currentCatalog?.installedPatchVersion,
                    )
                    appendLog("패치 상태 확인에 실패했습니다. ${error.message ?: error.javaClass.simpleName}")
                    setIdle()
                }
            } finally {
                inspecting = false
                main.post {
                    if (state.selectedTarget != target && !state.busy) refresh()
                    maybeFinishManualRefresh()
                }
            }
        }
    }

    private fun checkLatestPatchVersion(target: GameTarget, gameVersion: String?) {
        updateExecutor.execute {
            try {
                val index = PatchHttpClient.getIndex()
                val latest = if (gameVersion != null) {
                    PatchProtocol.latestReleaseForRoute(index, target.route, gameVersion)
                } else {
                    PatchProtocol.latestReleaseForRoute(index, target.route)
                }
                main.post {
                    if (state.selectedTarget == target) {
                        state = state.copy(
                            latestPatchVersion = latest?.patchVersion,
                            latestPatchUploadedAt = latest?.uploadedAt,
                            latestPatchStatus = if (latest == null) "호환되는 정식 패치 없음" else "확인 완료",
                        )
                        maybeFinishManualRefresh()
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (state.selectedTarget == target) {
                        state = state.copy(
                            latestPatchVersion = null,
                            latestPatchUploadedAt = null,
                            latestPatchStatus = "확인 실패",
                        )
                        maybeFinishManualRefresh()
                    }
                    appendLog("최신 한글패치 버전 확인에 실패했습니다. ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun checkPatcherUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateExecutor.execute {
            try {
                val latest = PatchHttpClient.getLatestMobilePatcherRelease()
                val available = latest.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
                latestPatcher = available
                main.post {
                    state = state.copy(availablePatcherVersion = available?.version)
                }
            } catch (error: Exception) {
                latestPatcher = null
                main.post {
                    state = state.copy(availablePatcherVersion = null)
                    appendLog(
                        "Android 패처 업데이트 확인에 실패했습니다. " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            } finally {
                updateChecking = false
                main.post { maybeFinishManualRefresh() }
            }
        }
    }

    private fun isResourceNotReady(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .any { message ->
                RESOURCE_NOT_READY_MESSAGES.any(message::contains)
            }

    private fun installedGames(): Map<GameTarget, GameInstall> = buildMap {
        GameTarget.entries.forEach { target ->
            installedGame(target)?.let { put(target, it) }
        }
    }

    private fun installedGame(target: GameTarget): GameInstall? = runCatching {
        val version = activity.packageManager.getPackageInfo(target.packageName, 0).versionName
            ?: "알 수 없음"
        val installer = activity.packageManager.getInstallSourceInfo(target.packageName).installingPackageName
        GameInstall(version, installer == PLAY_STORE_PACKAGE)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun verifyOriginalGameApks(apks: List<File>, release: OriginalGameRelease) {
        require(apks.size == release.files.size) { "다운로드한 원본 게임 APK 파일 수가 다릅니다." }
        apks.forEachIndexed { index, apk ->
            val expected = release.files[index]
            require(apk.name == expected.name && apk.isFile && apk.length() == expected.size) {
                "다운로드한 원본 게임 APK 파일 정보가 다릅니다: ${expected.name}"
            }
        }
        val base = apks.single { it.name == "base.apk" }
        val info = activity.packageManager.getPackageArchiveInfo(
            base.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: error("원본 게임 base APK를 해석할 수 없습니다.")
        require(info.packageName == GameTarget.INT_ANDROID.packageName) { "원본 게임 APK packageName이 다릅니다." }
        require(info.longVersionCode == release.versionCode) {
            "원본 게임 APK versionCode가 다릅니다."
        }
        require(certificateSha256(info) == release.certificateSha256) {
            "원본 게임 APK Play 인증서가 다릅니다."
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyInstalledOriginalGame(release: OriginalGameRelease) {
        val info = activity.packageManager.getPackageInfo(
            GameTarget.INT_ANDROID.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(info.longVersionCode == release.versionCode) {
            "설치된 게임 versionCode가 원본 release와 다릅니다."
        }
        require(certificateSha256(info) == release.certificateSha256) {
            "설치된 게임 인증서가 Google Play 원본과 다릅니다."
        }
        val source = activity.packageManager.getInstallSourceInfo(GameTarget.INT_ANDROID.packageName)
        require(source.installingPackageName == PLAY_STORE_PACKAGE) {
            "설치자 기록이 Google Play로 설정되지 않았습니다: ${source.installingPackageName}"
        }
    }

    private fun certificateSha256(info: PackageInfo): String {
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        require(signers.size == 1) { "APK의 현재 signing certificate 수가 올바르지 않습니다." }
        return MessageDigest.getInstance("SHA-256")
            .digest(signers.single().toByteArray())
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        activity.packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun verifyShizukuApk(apk: File) {
        val info = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        require(info?.packageName == SHIZUKU_PACKAGE) {
            "다운로드한 APK packageName이 Shizuku와 다릅니다."
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyMobilePatcherApk(apk: File, release: MobilePatcherRelease) {
        val archive = activity.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(archive?.packageName == activity.packageName) {
            "다운로드한 APK packageName이 현재 Android 패처와 다릅니다."
        }
        require(archive.versionName == release.version) {
            "다운로드한 Android 패처 버전이 index와 다릅니다."
        }
        require(archive.longVersionCode == release.versionCode.toLong()) {
            "다운로드한 Android 패처 versionCode가 index와 다릅니다."
        }
        require(archive.longVersionCode > BuildConfig.VERSION_CODE.toLong()) {
            "현재 버전보다 새로운 Android 패처가 아닙니다."
        }
        val installed = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(hasSameCurrentSigners(installed, archive)) {
            "다운로드한 Android 패처 APK 서명이 현재 앱과 다릅니다."
        }
    }

    private fun hasSameCurrentSigners(first: PackageInfo, second: PackageInfo): Boolean {
        val firstSigners = first.signingInfo?.apkContentsSigners ?: return false
        val secondSigners = second.signingInfo?.apkContentsSigners ?: return false
        return firstSigners.size == secondSigners.size &&
            firstSigners.all { signer -> secondSigners.any(signer::equals) }
    }

    private fun requestSystemInstall(apk: File, target: InstallTarget) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingSystemInstall = PendingInstall(apk, target)
            appendLog("이 앱의 '알 수 없는 앱 설치' 권한을 허용해 주세요.")
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
            return
        }
        openSystemInstaller(apk)
    }

    private fun openSystemInstaller(apk: File) {
        require(apk.isFile) { "설치할 Shizuku APK를 찾지 못했습니다." }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openPackage(packageName: String) {
        activity.packageManager.getLaunchIntentForPackage(packageName)?.let(activity::startActivity)
            ?: appendLog("앱을 열 수 없습니다: $packageName")
    }

    private fun openUrl(value: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    }

    private fun setBusy(label: String, progress: Float, section: ProgressSection) {
        state = state.copy(
            busy = true,
            progressSection = section,
            progressLabel = label,
            progress = progress,
        )
        appendLog(label)
    }

    private fun updateBusy(label: String, progress: Float) {
        main.post {
            val nextProgress = progress.coerceIn(0f, 1f)
            state = state.copy(
                progressLabel = label,
                progress = maxOf(state.progress, nextProgress),
            )
        }
    }

    private fun setIdle() {
        state = state.copy(
            busy = false,
            progressSection = null,
            progressLabel = null,
            progress = 0f,
        )
    }

    private fun appendServiceDiagnostics(
        patchService: IPatchService,
        target: GameTarget,
        transactionId: String,
        cursor: Int,
        includeSnapshot: Boolean,
    ): Int = runCatching {
        val diagnostics = PatchProtocol.parsePatchDiagnostics(
            patchService.getPatchDiagnostics(transactionId, target.packageName),
            transactionId,
        )
        val start = cursor.takeIf { it in 0..diagnostics.events.size } ?: 0
        if (start == 0) {
            appendLog(
                "[SERVICE] ${diagnostics.serviceInfo} · diagnosticsFile=" +
                    "${diagnostics.diagnosticsFileExists}/${diagnostics.diagnosticsFileBytes}bytes"
            )
        }
        diagnostics.events.drop(start).forEach { event ->
            val eventTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
                .format(Date(event.timestampMs))
            val detail = event.detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            appendLog(
                "[SERVICE $eventTime pid=${event.pid} uid=${event.uid} thread=${event.thread}] " +
                    "${event.stage}$detail"
            )
        }
        if (diagnostics.readError.isNotBlank()) {
            appendLog("[SERVICE] 진단 파일 읽기 오류 · ${diagnostics.readError}")
        }
        if (includeSnapshot) {
            val transaction = diagnostics.transaction
            appendLog(
                "[SERVICE] transaction snapshot · exists=${transaction.exists} " +
                    "path=${transaction.path} catalog=${transaction.catalogExists}/${transaction.catalogBytes}bytes " +
                    "journal=${transaction.journalExists}/${transaction.journalBytes}bytes/" +
                    "${transaction.journalEntries}entries previous=${transaction.previousFiles}files " +
                    "staging=${transaction.stagingFiles}files"
            )
            if (transaction.journalReadError.isNotBlank()) {
                appendLog("[SERVICE] journal 읽기 오류 · ${transaction.journalReadError}")
            }
        }
        diagnostics.events.size
    }.getOrElse { error ->
        appendLog("[CLIENT] 서비스 진단 회수 실패 · ${describeError(error)}")
        cursor
    }

    private fun describeError(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(8)
            .joinToString(" <- ") { cause ->
                "${cause.javaClass.name}: ${cause.message.orEmpty()}"
            }

    private fun showError(prefix: String, error: Throwable) {
        main.post {
            appendLog("$prefix ${error.message ?: error.javaClass.simpleName}")
            setIdle()
        }
    }

    private fun appendLog(message: String) {
        if (message.isBlank()) return
        val action = {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            state = state.copy(logs = (state.logs + "[$timestamp] ${message.trim()}").takeLast(300))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val SHIZUKU_PERMISSION_REQUEST = 1001
        private const val PREFERENCES_NAME = "patcher-preferences"
        private const val PREFERENCE_GAME_TARGET = "game-target"
        private val RESOURCE_NOT_READY_MESSAGES = listOf(
            "외부 files 디렉터리를 찾지 못했습니다",
            "게임을 먼저 실행해 리소스를 다운로드",
            "Addressables bundle cache를 찾지 못했습니다",
            "게임 catalog hash를 찾지 못했습니다",
        )
    }
}

internal data class GameInstall(val version: String, val installedFromPlay: Boolean)
private data class PendingInstall(val apk: File, val target: InstallTarget)

private enum class InstallTarget {
    SHIZUKU,
    PATCHER,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatchManagerScreen(
    state: PatchUiState,
    onSelectTarget: (GameTarget) -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onShizuku: () -> Unit,
    onGame: () -> Unit,
    onPatch: () -> Unit,
    onRestore: () -> Unit,
    onLaunch: () -> Unit,
) {
    val context = LocalContext.current
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    var deferredUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleLogs = if (logsExpanded) state.logs else state.logs.takeLast(6)
    val updateVersion = state.availablePatcherVersion
    val refreshTransition = rememberInfiniteTransition(label = "refresh-icon")
    val refreshRotation by refreshTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "refresh-icon-rotation",
    )
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val wideLayout = configuration.screenWidthDp >= PatcherDimensions.twoPaneMinWidthDp
    val dialogWidth = minOf(
        screenWidth * PatcherDimensions.dialogWidthFraction,
        PatcherDimensions.dialogMaxWidth,
    )

    if (updateVersion != null && deferredUpdateVersion != updateVersion) {
        val displayVersion = if (updateVersion.startsWith("v")) updateVersion else "v$updateVersion"
        AlertDialog(
            onDismissRequest = { deferredUpdateVersion = updateVersion },
            modifier = Modifier.width(dialogWidth),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("앱 업데이트") },
            text = { Text("새 버전 ${displayVersion}을 사용할 수 있습니다.") },
            dismissButton = {
                TextButton(onClick = { deferredUpdateVersion = updateVersion }) {
                    Text("나중에")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deferredUpdateVersion = updateVersion
                        onUpdate()
                    },
                    enabled = !state.busy,
                ) {
                    Text("업데이트")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "아스트랄 파티 한글패치",
                        modifier = Modifier.padding(start = PatcherSpacing.small),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.busy && !state.refreshing,
                        modifier = Modifier.padding(end = PatcherSpacing.screen),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.sync_24),
                            contentDescription = if (state.refreshing) "새로고침 중" else "새로고침",
                            modifier = Modifier.rotate(if (state.refreshing) refreshRotation else 0f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        floatingActionButton = {
            if (state.canLaunch) {
                ExtendedFloatingActionButton(
                    text = { Text("게임 실행") },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_play_arrow_24),
                            contentDescription = null,
                        )
                    },
                    onClick = onLaunch,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(
                            max = if (wideLayout) {
                                PatcherDimensions.wideContentMaxWidth
                            } else {
                                PatcherDimensions.compactContentMaxWidth
                            }
                        )
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = PatcherSpacing.screen,
                            end = PatcherSpacing.screen,
                            top = PatcherSpacing.small,
                            bottom = PatcherSpacing.section,
                    ),
                    verticalArrangement = Arrangement.spacedBy(PatcherSpacing.section),
                ) {
                    if (wideLayout) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(PatcherSpacing.section),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(PatcherSpacing.section),
                            ) {
                                PatcherUpdateSection(state = state, onUpdate = onUpdate)
                                GameSelectionSection(
                                    state = state,
                                    onSelectTarget = onSelectTarget,
                                )
                                ExecutionLogSection(
                                    state = state,
                                    visibleLogs = visibleLogs,
                                    logsExpanded = logsExpanded,
                                    onToggleLogs = { logsExpanded = !logsExpanded },
                                    onCopyLogs = {
                                        activityClipboardManager(context).setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "실행 로그",
                                                state.logs.joinToString("\n\n"),
                                            )
                                        )
                                    },
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(PatcherSpacing.section),
                            ) {
                                EnvironmentStatusSection(
                                    state = state,
                                    onShizuku = onShizuku,
                                    onGame = onGame,
                                    onLaunch = onLaunch,
                                )
                                PatchStatusSection(
                                    state = state,
                                    onRestore = onRestore,
                                    onPatch = onPatch,
                                )
                            }
                        }
                    } else {
                        PatcherUpdateSection(state = state, onUpdate = onUpdate)
                        GameSelectionSection(
                            state = state,
                            onSelectTarget = onSelectTarget,
                        )
                        EnvironmentStatusSection(
                            state = state,
                            onShizuku = onShizuku,
                            onGame = onGame,
                            onLaunch = onLaunch,
                        )
                        PatchStatusSection(
                            state = state,
                            onRestore = onRestore,
                            onPatch = onPatch,
                        )
                        ExecutionLogSection(
                            state = state,
                            visibleLogs = visibleLogs,
                            logsExpanded = logsExpanded,
                            onToggleLogs = { logsExpanded = !logsExpanded },
                            onCopyLogs = {
                                activityClipboardManager(context).setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "실행 로그",
                                        state.logs.joinToString("\n\n"),
                                    )
                                )
                            },
                        )
                    }

                    Spacer(
                        Modifier.height(
                            if (state.canLaunch) PatcherDimensions.fabClearance
                            else PatcherSpacing.small
                        )
                    )
                }
        }
    }
}

@Composable
private fun PatcherUpdateSection(
    state: PatchUiState,
    onUpdate: () -> Unit,
) {
    state.availablePatcherVersion?.let { version ->
        val displayVersion = if (version.startsWith("v")) version else "v$version"
        SectionCard(
            title = null,
            progressState = state.takeIf {
                it.busy && it.progressSection == ProgressSection.PATCHER_UPDATE
            },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            ListItem(
                colors = transparentListItemColors(),
                contentPadding = PaddingValues(
                    start = PatcherSpacing.card,
                    top = PatcherSpacing.small,
                    end = PatcherSpacing.small,
                    bottom = PatcherSpacing.small,
                ),
                trailingContent = {
                    Button(
                        onClick = onUpdate,
                        enabled = !state.busy,
                    ) {
                        Text("업데이트")
                    }
                },
                content = {
                    Text(
                        "새 버전 사용 가능 $displayVersion",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                },
            )
        }
    }
}

@Composable
private fun GameSelectionSection(
    state: PatchUiState,
    onSelectTarget: (GameTarget) -> Unit,
) {
    SectionCard(title = "게임 선택") {
        Column(
            modifier = Modifier
                .selectableGroup()
                .padding(horizontal = PatcherSpacing.small, vertical = PatcherSpacing.small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GameTarget.entries.forEach { target ->
                val install = state.gameInstalls[target]
                val selectable = install != null || target.selectableWhenMissing
                val selected = state.selectedTarget == target
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    shape = RoundedCornerShape(22.dp),
                ) {
                    ListItem(
                        selected = selected,
                        onClick = { onSelectTarget(target) },
                        enabled = selectable && !state.busy && !state.refreshing,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = selectable && !state.busy && !state.refreshing,
                            )
                        },
                        supportingContent = {
                            Text(gameTargetDescription(target, install))
                        },
                        content = {
                            Text(target.displayName, fontWeight = FontWeight.Medium)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvironmentStatusSection(
    state: PatchUiState,
    onShizuku: () -> Unit,
    onGame: () -> Unit,
    onLaunch: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PatcherSpacing.attached),
    ) {
        SectionCard(
            title = null,
            topCorner = PatcherShapes.card,
            bottomCorner = PatcherShapes.attached,
            progressState = state.takeIf {
                it.busy && it.progressSection == ProgressSection.SHIZUKU
            },
        ) {
            ListItem(
                colors = transparentListItemColors(),
                leadingContent = {
                    StatusIndicatorDot(
                        if (state.shizukuStatus == "확인 중") StatusIndicator.IN_PROGRESS
                        else if (state.shizukuReady) StatusIndicator.COMPLETED
                        else StatusIndicator.PENDING,
                    )
                },
                trailingContent = if (state.shizukuReady) {
                    null
                } else {
                    {
                        FilledTonalButton(
                            onClick = onShizuku,
                            enabled = !state.busy,
                        ) {
                            Text(state.shizukuActionLabel)
                        }
                    }
                },
                supportingContent = { Text(state.shizukuStatus) },
                content = { Text(state.shizukuLabel) },
            )
        }

        SectionCard(
            title = null,
            topCorner = PatcherShapes.attached,
            bottomCorner = PatcherShapes.attached,
            progressState = state.takeIf {
                it.busy && it.progressSection == ProgressSection.APK
            },
        ) {
            val install = state.selectedInstall
            val ready = state.gameReady
            if (state.selectedTarget.supportsOriginalInstall) {
                ListItem(
                    colors = transparentListItemColors(),
                    leadingContent = {
                        StatusIndicatorDot(
                            if (ready) StatusIndicator.COMPLETED else StatusIndicator.PENDING
                        )
                    },
                    trailingContent = if (ready) null else {
                        {
                            FilledTonalButton(
                                onClick = onGame,
                                enabled = !state.busy,
                            ) {
                                Text("설치")
                            }
                        }
                    },
                    supportingContent = {
                        Text(gameApkStatusDescription(state.selectedTarget, install))
                    },
                    content = { Text(gameApkStatusLabel(state.selectedTarget, install)) },
                )
            } else {
                ListItem(
                    colors = transparentListItemColors(),
                    leadingContent = {
                        StatusIndicatorDot(
                            if (ready) StatusIndicator.COMPLETED else StatusIndicator.PENDING
                        )
                    },
                    supportingContent = {
                        Text(gameApkStatusDescription(state.selectedTarget, install))
                    },
                    content = { Text(gameApkStatusLabel(state.selectedTarget, install)) },
                )
            }
        }

        SectionCard(
            title = null,
            topCorner = PatcherShapes.attached,
            bottomCorner = PatcherShapes.card,
        ) {
            ListItem(
                colors = transparentListItemColors(),
                leadingContent = { StatusIndicatorDot(state.resourceIndicator) },
                trailingContent = if (
                    state.resourceIndicator == StatusIndicator.PENDING &&
                    state.resourceNeedsDownload &&
                    state.canLaunch
                ) {
                    {
                        FilledTonalButton(
                            onClick = onLaunch,
                            enabled = !state.busy,
                        ) {
                            Text("게임 실행")
                        }
                    }
                } else {
                    null
                },
                supportingContent = { Text(state.resourceStatus) },
                content = { Text(state.resourceLabel) },
            )
        }
    }
}

@Composable
private fun PatchStatusSection(
    state: PatchUiState,
    onRestore: () -> Unit,
    onPatch: () -> Unit,
) {
    SectionCard(
        title = null,
        progressState = state.takeIf {
            it.busy && it.progressSection == ProgressSection.PATCH
        },
    ) {
        ListItem(
            colors = transparentListItemColors(),
            leadingContent = { StatusIndicatorDot(state.releaseIndicator) },
            supportingContent = { Text(state.releaseStatus) },
            content = { Text(state.releaseLabel) },
        )
        SectionDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
        ListItem(
            colors = transparentListItemColors(),
            contentPadding = PaddingValues(
                start = PatcherSpacing.latestVersion,
                end = PatcherSpacing.latestVersion,
                top = PatcherSpacing.small,
                bottom = 2.dp,
            ),
            trailingContent = {
                Text(
                    state.latestPatchVersion ?: state.latestPatchStatus,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            content = {
                Text(
                    "최신 버전",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
        ListItem(
            colors = transparentListItemColors(),
            contentPadding = PaddingValues(
                start = PatcherSpacing.latestVersion,
                end = PatcherSpacing.latestVersion,
                top = 2.dp,
                bottom = PatcherSpacing.small,
            ),
            trailingContent = {
                Text(
                    formatPatchUploadedAt(state.latestPatchUploadedAt),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            content = {
                Text(
                    "업로드 시각",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PatcherSpacing.card,
                    end = PatcherSpacing.card,
                    top = PatcherSpacing.small,
                    bottom = PatcherSpacing.card,
                ),
            horizontalArrangement = Arrangement.spacedBy(PatcherSpacing.buttonGap),
        ) {
            FilledTonalButton(
                onClick = onRestore,
                enabled = state.canRestore,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(
                    topStart = PatcherShapes.buttonOuter,
                    bottomStart = PatcherShapes.buttonOuter,
                    topEnd = PatcherShapes.attached,
                    bottomEnd = PatcherShapes.attached,
                ),
            ) {
                Text("원본 복구")
            }
            Button(
                onClick = onPatch,
                enabled = state.canPatch,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(
                    topStart = PatcherShapes.attached,
                    bottomStart = PatcherShapes.attached,
                    topEnd = PatcherShapes.buttonOuter,
                    bottomEnd = PatcherShapes.buttonOuter,
                ),
            ) {
                Text(
                    if (
                        state.installedPatchVersion != null &&
                        state.patchVersion != null &&
                        state.installedPatchVersion != state.patchVersion
                    ) {
                        "한글패치 업데이트"
                    } else {
                        "한글패치 설치"
                    }
                )
            }
        }
    }
}

@Composable
private fun ExecutionLogSection(
    state: PatchUiState,
    visibleLogs: List<String>,
    logsExpanded: Boolean,
    onToggleLogs: () -> Unit,
    onCopyLogs: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PatcherSpacing.card,
                        end = PatcherSpacing.screen,
                        top = PatcherSpacing.small,
                        bottom = PatcherSpacing.small,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "실행 로그",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.logs.size > 6) {
                    TextButton(onClick = onToggleLogs) {
                        Text(if (logsExpanded) "접기" else "전체 보기")
                    }
                }
                IconButton(
                    onClick = onCopyLogs,
                    enabled = state.logs.isNotEmpty(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy_24),
                        contentDescription = "실행 로그 복사",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PatcherSpacing.small,
                        end = PatcherSpacing.small,
                        bottom = PatcherSpacing.small,
                    ),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(22.dp),
            ) {
                SelectionContainer {
                    Text(
                        if (visibleLogs.isEmpty()) "상태를 새로고침하면 여기에 기록이 표시됩니다."
                        else visibleLogs.joinToString("\n\n"),
                        modifier = Modifier.padding(PatcherSpacing.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardProgress(
    state: PatchUiState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PatcherSpacing.card,
                end = PatcherSpacing.card,
                top = PatcherSpacing.progress,
                bottom = PatcherSpacing.card,
            ),
        verticalArrangement = Arrangement.spacedBy(PatcherSpacing.progress),
    ) {
        Text(
            state.progressLabel.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        if (state.progress <= 0f) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String?,
    progressState: PatchUiState? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    topCorner: androidx.compose.ui.unit.Dp = PatcherShapes.card,
    bottomCorner: androidx.compose.ui.unit.Dp = PatcherShapes.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    var retainedProgressState by remember { mutableStateOf(progressState) }
    LaunchedEffect(progressState) {
        if (progressState != null) retainedProgressState = progressState
    }
    val displayedProgressState = progressState ?: retainedProgressState
    val animatedBottomCorner by animateDpAsState(
        targetValue = if (progressState == null) bottomCorner else PatcherShapes.attached,
        animationSpec = spring(
            dampingRatio = PatcherMotion.dampingRatio,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "section-card-bottom-corner",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            shape = RoundedCornerShape(
                topStart = topCorner,
                topEnd = topCorner,
                bottomStart = animatedBottomCorner,
                bottomEnd = animatedBottomCorner,
            ),
        ) {
            title?.let {
                Text(
                    it,
                    modifier = Modifier.padding(
                        start = PatcherSpacing.card,
                        end = PatcherSpacing.card,
                        top = PatcherSpacing.card,
                        bottom = PatcherSpacing.small,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }

        AnimatedVisibility(
            visible = progressState != null,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = PatcherMotion.dampingRatio,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(animationSpec = tween(PatcherMotion.fadeInMillis)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) + fadeOut(animationSpec = tween(PatcherMotion.fadeOutMillis)),
            label = "section-card-progress",
        ) {
            Box(modifier = Modifier.padding(top = PatcherSpacing.attached)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(
                        topStart = PatcherShapes.attached,
                        topEnd = PatcherShapes.attached,
                        bottomStart = bottomCorner,
                        bottomEnd = bottomCorner,
                    ),
                ) {
                    displayedProgressState?.let { CardProgress(it) }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicatorDot(indicator: StatusIndicator) {
    val dark = isSystemInDarkTheme()
    val color = when (indicator) {
        StatusIndicator.ERROR -> if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
        StatusIndicator.PENDING -> if (dark) Color(0xFFFFD54F) else Color(0xFFF9A825)
        StatusIndicator.IN_PROGRESS -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1967D2)
        StatusIndicator.COMPLETED -> if (dark) Color(0xFF81C995) else Color(0xFF188038)
    }
    Box(modifier = Modifier.padding(horizontal = PatcherSpacing.indicator)) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = color,
            content = {},
        )
    }
}

@Composable
private fun SectionDivider(
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = PatcherSpacing.card),
        color = color,
    )
}

@Composable
private fun ActionLabel(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun transparentListItemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
)

private fun formatPatchUploadedAt(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return runCatching {
        Instant.parse(value)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault("-")
}

private object PatcherSpacing {
    val attached = 2.dp
    val buttonGap = 4.dp
    val small = 8.dp
    val progress = 12.dp
    val indicator = 8.dp
    val screen = 16.dp
    val section = 16.dp
    val card = 20.dp
    val latestVersion = 28.dp
}

private object PatcherShapes {
    val attached = 8.dp
    val buttonOuter = 24.dp
    val card = 28.dp
}

private object PatcherDimensions {
    const val dialogWidthFraction = 0.7f
    const val twoPaneMinWidthDp = 840
    val dialogMaxWidth = 560.dp
    val compactContentMaxWidth = 760.dp
    val wideContentMaxWidth = 1200.dp
    val fabClearance = 80.dp
}

private object PatcherMotion {
    const val dampingRatio = 0.72f
    const val fadeInMillis = 180
    const val fadeOutMillis = 120
}

private fun gameTargetDescription(target: GameTarget, install: GameInstall?): String = when {
    install == null && target.supportsOriginalInstall ->
        "${target.packageName} • 미설치 • 설치 가능"
    install == null -> "${target.packageName} • 미설치"
    else -> "${target.packageName} • 설치됨 • v${install.version}"
}

private fun gameApkStatusLabel(target: GameTarget, install: GameInstall?): String = when {
    install == null -> "${target.displayName} 게임 미설치"
    target.supportsOriginalInstall && !install.installedFromPlay -> "${target.displayName} 설치 오류"
    else -> "${target.displayName} 설치됨"
}

private fun gameApkStatusDescription(
    target: GameTarget,
    install: GameInstall?,
): String = when {
    install == null && target.supportsOriginalInstall -> "설치 가능"
    install == null -> "스토어에서 吉星派对를 설치해 주세요"
    target.supportsOriginalInstall && !install.installedFromPlay -> "게임을 삭제 후 재설치해 주세요"
    else -> "v${install.version}"
}

private fun activityClipboardManager(context: android.content.Context): android.content.ClipboardManager =
    context.getSystemService(android.content.ClipboardManager::class.java)

@Composable
private fun AstralMaterialTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> androidx.compose.material3.expressiveLightColorScheme()
    }
    androidx.compose.material3.MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = androidx.compose.material3.MotionScheme.expressive(),
        content = content,
    )
}

@Preview(
  name = "패치 준비 완료",
  showBackground = true,
  widthDp = 412,
  heightDp = 915,
)
@Composable
private fun PatchManagerScreenPreview() {
  AstralMaterialTheme {
    PatchManagerScreen(
      state = PatchUiState(
        selectedTarget = GameTarget.INT_ANDROID,
        gameInstalls = mapOf(
          GameTarget.INT_ANDROID to GameInstall(
            version = "1.2.3",
            installedFromPlay = true,
          )
        ),
        shizukuStatus = "연결됨 · 권한 허용됨",
        shizukuReady = true,
        shizukuActionLabel = "열기",
        resourceStatus = "게임 리소스 확인 완료",
        resourceIndicator = StatusIndicator.COMPLETED,
        releaseStatus = "한글패치 적용 가능",
        releaseIndicator = StatusIndicator.PENDING,
        latestPatchVersion = "1.4.0",
        patchVersion = "1.4.0",
        logs = listOf(
          "[12:30:14] 게임 설치를 확인했습니다.",
          "[12:30:15] 최신 패치를 찾았습니다.",
        ),
      ),
      onSelectTarget = {},
      onRefresh = {},
      onUpdate = {},
      onShizuku = {},
      onGame = {},
      onPatch = {},
      onRestore = {},
      onLaunch = {},
    )
  }
}
