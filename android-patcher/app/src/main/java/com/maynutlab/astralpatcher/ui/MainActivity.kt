package com.maynutlab.astralpatcher.ui

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.core.content.ContextCompat
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
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry
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
import org.json.JSONArray
import org.json.JSONObject

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
                    onConfirmOperation = controller::confirmOperation,
                    onDismissOperation = controller::dismissOperation,
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
    val releaseIndicator: StatusIndicator = StatusIndicator.IN_PROGRESS,
    val latestPatchVersion: String? = null,
    val latestPatchUploadedAt: String? = null,
    val latestPatchUploadStatus: String = "확인 중",
    val latestPatchStatus: String = "확인 중",
    val patchVersion: String? = null,
    val installedPatchVersion: String? = null,
    val availablePatcherVersion: String? = null,
    val patcherUpdateStatus: String = "확인 중",
    val patcherUpdateCheckedAt: Long? = null,
    val latestRelease: ReleaseEntry? = null,
    val compatiblePatchVersion: String? = null,
    val confirmation: OperationConfirmation? = null,
    val actionMessage: String? = null,
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
        get() = selectedInstalled
    val canPatch: Boolean
        get() = !busy
    val canRestore: Boolean
        get() = !busy
    val canLaunch: Boolean
        get() = selectedInstalled && !busy
}

internal data class OperationConfirmation(
    val operation: String,
    val release: String,
    val installedResource: String,
    val reason: String,
)

internal enum class StatusIndicator {
    NEUTRAL,
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
    private val refreshGate = LatestRequestGate()
    @Volatile
    private var stopped = false
    private var pendingOperation: PreparedOperation? = null
    private var updatePending = false
    private var patchStatusBeforeWork: PatchInstallationStatus? = null
    @Volatile
    private var latestPatcher: MobilePatcherRelease? = null
    private var pendingSystemInstall: PendingInstall? = null
    @Volatile
    private var inspecting = false
    private var refreshAfterWork = false
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
        .tag("astral-cache-patcher-v8")
        .version(BuildConfig.VERSION_CODE)
        .processNameSuffix("patch")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (stopped) {
                runCatching { Shizuku.unbindUserService(serviceArgs, this, true) }
                return
            }
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

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.schemeSpecificPart !in GameTarget.supportedPackages) return
            // Replacement removal is temporary; query after ADDED/REPLACED instead.
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            refresh()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(activity, packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        stopped = true
        activity.unregisterReceiver(packageChanges)
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
        } else {
            refresh()
        }
    }

    fun manualRefresh() {
        if (stopped) return
        state = state.copy(refreshing = true)
        refresh()
    }

    fun refresh() {
        if (stopped) return
        refreshGate.request()
        pendingOperation = null
        state = state.copy(confirmation = null)
        drainRefresh()
    }

    private fun drainRefresh() {
        if (stopped) return
        val ticket = refreshGate.startIfIdle(state.busy) ?: return
        val installs = installedGames()
        val selected = selectGameTarget(
            previous = preferredTarget,
            installedPackages = installs.keys.mapTo(mutableSetOf()) { it.packageName },
        )
        if (selected != state.selectedTarget) persistSelectedTarget(selected)
        val selectedInstall = installs[selected]
        val gameReady = selectedInstall != null
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
                !gameReady -> "게임이 설치되지 않았습니다"
                !permissionGranted -> "Shizuku 활성화 및 권한이 필요합니다"
                else -> "리소스 상태를 확인 중입니다"
            },
            resourceIndicator = if (gameReady && permissionGranted) {
                StatusIndicator.IN_PROGRESS
            } else {
                StatusIndicator.NEUTRAL
            },
            resourceNeedsDownload = false,
            releaseLabel = when {
                !gameReady -> "한글패치 확인 대기"
                !permissionGranted -> "한글패치 확인 대기"
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
                StatusIndicator.NEUTRAL
            },
            latestPatchVersion = null,
            latestPatchUploadedAt = null,
            latestPatchUploadStatus = "확인 중",
            latestPatchStatus = "확인 중",
            patchVersion = null,
            installedPatchVersion = null,
            latestRelease = null,
            compatiblePatchVersion = null,
        )
        checkPatcherUpdate()
        if (permissionGranted && service == null) {
            bindPatchService()
        }
        inspectAndResolve(selected, selectedInstall, ticket, service.takeIf { permissionGranted && gameReady })
    }

    private fun maybeFinishManualRefresh() {
        if (!state.refreshing) return
        val statusStillUpdating =
            state.latestPatchStatus == "확인 중" ||
                (state.latestPatchVersion != null && state.latestPatchUploadStatus == "확인 중") ||
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
        if (android.os.Process.myUid() / 100_000 != 0) {
            state = state.copy(actionMessage = "원본 게임 설치는 기본 사용자 프로필에서 실행해 주세요.")
            return
        }
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
                require(patchService.getServiceInfo().startsWith("AstralPatchService/8 ")) {
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

                require(!stopped && !Thread.currentThread().isInterrupted) { "작업이 취소되었습니다." }
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
                    if (stopped) return@post
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
                    if (stopped) {
                        downloadedApk.delete()
                        return@post
                    }
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
                    if (stopped) {
                        downloadedApk.delete()
                        return@post
                    }
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

    fun applyPatch() = requestOperation("PATCH")

    fun restorePatch() = requestOperation("RESTORE")

    private fun requestOperation(operation: String) {
        if (state.busy) return
        val install = state.selectedInstall
        if (install == null) {
            state = state.copy(actionMessage = "선택한 게임을 먼저 설치해 주세요.")
            if (state.selectedTarget.supportsOriginalInstall) handleGame()
            return
        }
        if (install.userId != 0) {
            state = state.copy(actionMessage = "현재 패처는 기본 사용자 프로필에서만 파일 작업을 지원합니다. 기본 프로필에서 게임과 패처를 실행해 주세요.")
            return
        }
        if (!state.shizukuReady) {
            state = state.copy(actionMessage = "Shizuku를 시작하고 권한을 허용한 뒤 다시 시도해 주세요.")
            handleShizuku()
            return
        }
        if (service == null) {
            state = state.copy(actionMessage = "Shizuku 서비스에 연결 중입니다. 연결 후 다시 시도해 주세요.")
            bindPatchService()
            return
        }
        prepareLatestOperation(operation)
    }

    fun dismissOperation() {
        pendingOperation = null
        state = state.copy(confirmation = null)
        if (refreshAfterWork) {
            refreshAfterWork = false
            refresh()
        }
    }

    private fun prepareLatestOperation(operation: String) {
        val target = state.selectedTarget
        val install = state.selectedInstall ?: return
        val patchService = service ?: return
        val ticket = refreshGate.generation
        state = state.copy(confirmation = null, actionMessage = null)
        setBusy("최신 한글패치와 대상 파일을 확인하는 중", 0f, ProgressSection.PATCH)
        executor.execute {
            try {
                requireService(patchService)
                // Re-read the index for every action. Never prefer a cached or catalog-matched older patch.
                val index = PatchHttpClient.getIndex(::appendLog)
                val release = requireNotNull(PatchProtocol.latestReleaseForRoute(index, target.route)) {
                    "이 게임의 최신 한글패치 리소스가 없습니다."
                }
                val identity = PatchProtocol.parseInspection(patchService.inspectGame(target.packageName))
                val selectedManifest = PatchProtocol.parseManifest(PatchHttpClient.getManifest(release, ::appendLog), release, target.route)
                val inspection = PatchProtocol.parseTargetInspection(patchService.inspectPatchTargets(
                    target.packageName,
                    PatchProtocol.createTargetInspectionRequest(selectedManifest, release, operation, install.toJson()),
                ))
                logInspection(inspection)
                require(inspection.canWrite && inspection.planJson != null) {
                    inspectionFailure(inspection)
                }
                val reason = resourceVersionMismatch(inspection, identity.gameVersion.takeIf(String::isNotBlank) ?: install.version, release)
                val compatible = runCatching {
                    PatchProtocol.resolveRelease(index, identity.copy(catalogHash = inspection.catalogHash.orEmpty()), target.route)
                }.getOrNull()
                val prepared = PreparedOperation(target, install, release, selectedManifest, inspection, operation, ticket)
                main.post {
                    if (stopped) return@post
                    if (!refreshGate.isCurrent(ticket) || state.selectedTarget != target || !install.sameInstallation(installedGame(target))) {
                        state = state.copy(actionMessage = "확인 중 게임 또는 조회 상태가 바뀌었습니다. 다시 시도해 주세요.")
                        setIdle()
                        refresh()
                        return@post
                    }
                    state = state.copy(
                        latestRelease = release,
                        latestPatchVersion = release.patchVersion,
                        latestPatchUploadedAt = release.uploadedAt,
                        latestPatchUploadStatus = if (release.uploadedAt == null) "확인 중" else "확인 완료",
                        latestPatchStatus = "배포 조회 완료",
                        patchVersion = release.patchVersion,
                        compatiblePatchVersion = compatible?.patchVersion,
                    )
                    if (release.uploadedAt == null) checkPatchUploadedAt(release, target, install, ticket)
                    if (reason == null) {
                        executeOperation(prepared, patchService)
                    } else {
                        pendingOperation = prepared
                        state = state.copy(confirmation = OperationConfirmation(
                            operation, release.patchVersion, compatible?.patchVersion ?: "확인할 수 없는 배포", reason,
                        ))
                        setIdle()
                    }
                }
            } catch (error: Exception) {
                showError("작업 준비에 실패했습니다.", error)
            }
        }
    }

    fun confirmOperation() {
        val prepared = pendingOperation ?: return
        pendingOperation = null
        state = state.copy(confirmation = null)
        val patchService = service
        if (!refreshGate.isCurrent(prepared.ticket) || state.selectedTarget != prepared.target ||
            state.latestRelease != prepared.release || !prepared.install.sameInstallation(installedGame(prepared.target)) || patchService == null) {
            state = state.copy(actionMessage = "확인 후 게임 또는 최신 한글패치 정보가 바뀌었습니다. 다시 시도해 주세요.")
            refresh()
            return
        }
        executeOperation(prepared, patchService)
    }

    private fun executeOperation(prepared: PreparedOperation, patchService: IPatchService) {
        val (target, install, release, targetManifest, inspection, operation) = prepared
        val restoring = operation == "RESTORE"
        setBusy(if (restoring) "검증된 원본을 다운로드하는 중" else "최신 한글패치를 다운로드하는 중", 0f, ProgressSection.PATCH)
        executor.execute {
            val transactionId = UUID.randomUUID().toString()
            var transactionStarted = false
            var cursor = 0
            val payloads = mutableListOf<File>()
            try {
                requireService(patchService)
                // Complete every network transfer and hash check before starting any game-file writes.
                targetManifest.files.forEachIndexed { index, item ->
                    val label = "${index + 1}/${targetManifest.files.size}"
                    val onProgress: (Int) -> Unit = { percent ->
                        updateBusy("${if (restoring) "원본" else "패치"} $label 다운로드 및 검증",
                            (index + percent / 100f) / targetManifest.files.size * 0.75f)
                    }
                    payloads += if (restoring) PatchHttpClient.downloadOriginal(activity.cacheDir, item, index, onProgress, ::appendLog)
                        else PatchHttpClient.downloadPayload(activity.cacheDir, item, index, onProgress, ::appendLog)
                }
                appendLog("[CLIENT] 모든 다운로드·압축 해제·해시 검증 완료. 게임 파일 작업을 준비합니다.")
                require(!stopped && !Thread.currentThread().isInterrupted) { "작업이 취소되었습니다." }
                require(install.sameInstallation(installedGame(target, strict = true))) {
                    "다운로드 중 설치 게임이 변경되었습니다. 다시 시도해 주세요."
                }
                appendLog("${release.patchVersion} · $operation · manifest=${release.manifestSha256}")
                transactionStarted = true
                patchService.beginPlannedPatch(transactionId, target.packageName, requireNotNull(inspection.planJson))
                targetManifest.files.forEachIndexed { index, item ->
                    require(!stopped && !Thread.currentThread().isInterrupted) { "작업이 취소되었습니다." }
                    updateBusy("${if (restoring) "원본 복구" else "한글패치 적용"} ${index + 1}/${targetManifest.files.size}",
                        0.75f + index.toFloat() / targetManifest.files.size * 0.2f)
                    val file = payloads[index]
                    val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PatchProtocol.parseApplyFileResult(patchService.applyFile(transactionId, target.packageName,
                            descriptor, if (restoring) item.sourceSize else item.payloadSize,
                            if (restoring) item.sourceSha256 else item.payloadSha256,
                            item.sourceSize, item.sourceSha256, item.relativePath))
                    }
                    require(result.ok) { result.error }
                    cursor = appendServiceDiagnostics(patchService, target, transactionId, cursor, false)
                }
                patchService.commitPatch(transactionId, target.packageName, targetManifest.gameVersion,
                    targetManifest.catalogHash, targetManifest.patchVersion)
                transactionStarted = false
                main.post {
                    if (stopped) return@post
                    appendLog(if (restoring) "${release.patchVersion} 대상 원본 ${targetManifest.files.size}개를 복구했습니다."
                        else "${release.patchVersion} 설치를 완료했습니다.")
                    setIdle()
                    refresh()
                }
            } catch (error: Exception) {
                if (transactionStarted) {
                    runCatching { patchService.rollbackPatch(transactionId, target.packageName) }
                        .onFailure { appendLog("자동 롤백을 완료하지 못했습니다. 다음 작업 전에 복구가 필요합니다. ${describeError(it)}") }
                    appendServiceDiagnostics(patchService, target, transactionId, cursor, true)
                }
                showError(if (restoring) "원본 복구에 실패했습니다." else "한글패치 설치에 실패했습니다.", error)
            } finally {
                payloads.forEach(File::delete)
            }
        }
    }

    private fun requireService(patchService: IPatchService) {
        require(patchService.getServiceInfo().startsWith("AstralPatchService/8 ")) {
            "패처와 Shizuku 서비스 버전이 다릅니다. 앱을 다시 열어 서비스에 연결해 주세요."
        }
    }

    private fun inspectionFailure(result: PatchTargetInspection): String = buildList {
        if (result.missing > 0) add("대상 ${result.missing}개가 없습니다. 게임에서 리소스를 다운로드한 후 다시 시도해 주세요.")
        if (result.unreadable > 0) add("대상 ${result.unreadable}개에 접근할 수 없습니다. Shizuku 권한과 저장소 접근을 확인해 주세요.")
        if (result.ambiguous > 0) add("대상 ${result.ambiguous}개의 경로가 중복됩니다. 실제 사용 경로를 확정할 수 없어 쓰기를 중단합니다.")
    }.joinToString("\n").ifBlank { "모든 대상 파일의 경로를 확정하지 못했습니다. 새로고침 후 다시 시도해 주세요." }

    private fun logInspection(result: PatchTargetInspection) {
        result.files.forEach { file ->
            appendLog("${file.path} · ${file.state} · ${file.actualPath} · ${file.size} bytes · ${file.sha256} ${file.error}")
        }
        result.warnings.forEach(::appendLog)
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

    private fun inspectAndResolve(target: GameTarget, install: GameInstall?, ticket: Long, patchService: IPatchService?) {
        inspecting = true
        executor.execute {
            var identity: CatalogIdentity? = null
            var inspectionError: String? = null
            var releaseError: String? = null
            var releases = emptyList<ReleaseEntry>()
            var latest: ReleaseEntry? = null
            var compatible: ReleaseEntry? = null
            var resolvedManifest: PatchManifest? = null
            var targetInspection: PatchTargetInspection? = null
            var installedManifest: PatchManifest? = null
            var installedInspection: PatchTargetInspection? = null
            if (patchService != null && install?.userId == 0) {
                runCatching {
                    requireService(patchService)
                    PatchProtocol.parseInspection(patchService.inspectGame(target.packageName))
                }.onSuccess { identity = it }
                    .onFailure { inspectionError = it.message ?: it.javaClass.simpleName }
            }
            runCatching {
                val index = PatchHttpClient.getIndex(::appendLog)
                releases = PatchProtocol.releasesForRoute(index, target.route)
                latest = PatchProtocol.latestReleaseForRoute(index, target.route)
                val observed = identity
                if (observed != null && observed.gameVersion == install?.version && observed.catalogHash.isNotBlank()) {
                    // Catalog matching describes installed resources; it never selects the download target.
                    compatible = runCatching { PatchProtocol.resolveRelease(index, observed, target.route) }.getOrNull()
                }
                val choice = latest
                if (choice != null && patchService != null && install?.userId == 0) {
                    resolvedManifest = PatchProtocol.parseManifest(PatchHttpClient.getManifest(choice, ::appendLog), choice, target.route)
                    targetInspection = PatchProtocol.parseTargetInspection(patchService.inspectPatchTargets(target.packageName,
                        PatchProtocol.createTargetInspectionRequest(requireNotNull(resolvedManifest), choice, "PATCH", install.toJson())))
                    installedManifest = resolvedManifest
                    installedInspection = targetInspection
                    // Compare older translation manifests only when the latest files cannot prove the state.
                    val primary = PatchStatusObservation(requireNotNull(resolvedManifest), requireNotNull(targetInspection))
                    val compatibleObservation = compatible?.takeIf { it != choice }?.let { installedRelease ->
                        runCatching {
                            val previousManifest = PatchProtocol.parseManifest(
                                PatchHttpClient.getManifest(installedRelease, ::appendLog), installedRelease, target.route,
                            )
                            val previousInspection = PatchProtocol.parseTargetInspection(patchService.inspectPatchTargets(
                                target.packageName, PatchProtocol.createTargetInspectionRequest(
                                    previousManifest, installedRelease, "PATCH", install.toJson(),
                                ),
                            ))
                            PatchStatusObservation(previousManifest, previousInspection)
                        }.onFailure { appendLog("설치된 한글패치 상태 확인: ${describeError(it)}") }.getOrNull()
                    }
                    val statusObservation = selectPatchStatusObservation(
                        primary, compatibleObservation, observed, releases,
                        load = { previousRelease ->
                            val previousManifest = PatchProtocol.parseManifest(
                                PatchHttpClient.getManifest(previousRelease, ::appendLog), previousRelease, target.route,
                            )
                            val previousInspection = PatchProtocol.parseTargetInspection(patchService.inspectPatchTargets(
                                target.packageName, PatchProtocol.createTargetInspectionRequest(
                                    previousManifest, previousRelease, "PATCH", install.toJson(),
                                ),
                            ))
                            PatchStatusObservation(previousManifest, previousInspection)
                        },
                        onFailure = { release, error -> appendLog("구버전 설치 확인 (${release.patchVersion}): ${describeError(error)}") },
                    )
                    installedManifest = statusObservation.manifest
                    installedInspection = statusObservation.inspection
                }
            }.onFailure { releaseError = it.message ?: it.javaClass.simpleName }
            val resultIdentity = identity
            val resultInspection = targetInspection
            val resultManifest = installedManifest ?: resolvedManifest
            val resultInstalledInspection = installedInspection ?: targetInspection
            val resultReleases = releases
            val resultLatest = latest
            val resultCompatible = compatible
            val error = releaseError ?: inspectionError
            main.post {
                if (!stopped && refreshGate.isCurrent(ticket) && state.selectedTarget == target && !state.busy) {
                    if (!sameInstallationSnapshot(install, installedGame(target))) {
                        refresh()
                    } else {
                        val ready = resultInspection?.isReady == true
                        val unavailableReason = when {
                            install == null -> "게임을 설치한 뒤 한글패치를 적용해 주세요"
                            install.userId != 0 -> "기본 사용자 프로필에서 실행해 주세요"
                            patchService == null -> "Shizuku 연결과 권한을 확인해 주세요"
                            else -> null
                        }
                        val patchStatus = resolvePatchInstallationStatus(
                            resultIdentity, resultInstalledInspection, resultManifest, install?.version,
                            resultLatest, resultReleases, unavailableReason, error,
                        )
                        state = state.copy(
                            resourceLabel = when {
                                ready -> "리소스 확인 완료"
                                resultInspection?.missing?.let { it > 0 } == true -> "리소스 다운로드 필요"
                                resultInspection?.incompatible?.let { it > 0 } == true -> "리소스 호환성 확인 필요"
                                resultInspection != null -> "리소스 경로 확인 필요"
                                else -> "리소스 확인 불가"
                            },
                            resourceStatus = when {
                                ready -> "대상 파일 ${resultInspection.total}개를 확인했습니다"
                                resultInspection?.canWrite == true -> "기존 파일을 확인했습니다. 최신 한글패치로 교체할 수 있습니다."
                                resultInspection != null -> inspectionFailure(resultInspection)
                                install?.userId != null && install.userId != 0 -> "기본 사용자 프로필에서 실행해 주세요"
                                error != null -> error
                                patchService == null -> "게임 설치 및 Shizuku 연결 후 파일을 확인할 수 있습니다"
                                else -> "카탈로그를 확인하지 못했습니다. 설치 시 최신 리소스를 다시 확인합니다."
                            },
                            resourceIndicator = when {
                                ready -> StatusIndicator.COMPLETED
                                resultInspection?.unreadable?.let { it > 0 } == true -> StatusIndicator.ERROR
                                patchService == null || install == null -> StatusIndicator.NEUTRAL
                                resultInspection == null && error != null -> StatusIndicator.ERROR
                                else -> StatusIndicator.PENDING
                            },
                            resourceNeedsDownload = resultInspection?.missing?.let { it > 0 } == true,
                            releaseLabel = patchStatus.label,
                            releaseStatus = patchStatus.description,
                            releaseIndicator = patchStatus.indicator,
                            latestPatchVersion = resultLatest?.patchVersion,
                            latestPatchUploadedAt = resultLatest?.uploadedAt,
                            latestPatchUploadStatus = when {
                                resultLatest == null -> "버전 확인 후 표시"
                                resultLatest.uploadedAt == null -> "확인 중"
                                else -> "확인 완료"
                            },
                            latestPatchStatus = if (resultLatest != null) "배포 조회 완료" else if (releaseError != null) "조회 실패" else "해당 게임 배포 없음",
                            latestRelease = resultLatest,
                            compatiblePatchVersion = resultCompatible?.patchVersion,
                            patchVersion = resultLatest?.patchVersion,
                            installedPatchVersion = patchStatus.installedPatchVersion,
                        )
                        resultIdentity?.diagnostic?.takeIf(String::isNotBlank)?.let(::appendLog)
                        resultIdentity?.historyError?.takeIf(String::isNotBlank)?.let { appendLog("설치 이력 확인: $it") }
                        resultInspection?.let(::logInspection)
                        error?.let { appendLog("상태 확인: $it") }
                        resultLatest?.takeIf { it.uploadedAt == null }?.let { checkPatchUploadedAt(it, target, install, ticket) }
                    }
                } else if (!stopped && state.busy && refreshGate.isCurrent(ticket)) {
                    // An older background result must not invalidate the fresh operation's ticket.
                    // Refresh after the operation or after its confirmation is dismissed instead.
                    refreshAfterWork = true
                }
                inspecting = false
                refreshGate.finish(ticket)
                drainRefresh()
                maybeFinishManualRefresh()
            }
        }
    }

    private fun checkPatchUploadedAt(release: ReleaseEntry, target: GameTarget, install: GameInstall?, ticket: Long) {
        updateExecutor.execute {
            val result = runCatching { PatchHttpClient.getPatchUploadedAt(release) }
            main.post {
                if (stopped || !refreshGate.isCurrent(ticket) || state.selectedTarget != target ||
                    state.latestPatchVersion != release.patchVersion || !sameInstallationSnapshot(install, state.selectedInstall)) return@post
                state = state.copy(
                    latestPatchUploadedAt = result.getOrNull(),
                    latestPatchUploadStatus = if (result.isFailure) "조회 실패" else if (result.getOrNull() == null) "업로드 정보 없음" else "확인 완료",
                )
                result.exceptionOrNull()?.let { appendLog("한글패치 업로드 시각 확인 실패: ${it.message}") }
                maybeFinishManualRefresh()
            }
        }
    }

    private fun checkPatcherUpdate() {
        if (updateChecking) {
            updatePending = true
            return
        }
        updateChecking = true
        state = state.copy(patcherUpdateStatus = "확인 중")
        updateExecutor.execute {
            val result = runCatching { PatchHttpClient.getLatestMobilePatcherRelease() }
            main.post {
                if (stopped) return@post
                result.onSuccess { latest ->
                    val available = latest.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
                    latestPatcher = available
                    state = state.copy(availablePatcherVersion = available?.version,
                        patcherUpdateStatus = if (available != null) "업데이트 있음" else "최신 버전",
                        patcherUpdateCheckedAt = System.currentTimeMillis())
                }.onFailure { error ->
                    state = state.copy(patcherUpdateStatus = "확인 실패")
                    appendLog("Android 패처 업데이트 확인 실패: ${error.message}")
                }
                updateChecking = false
                if (updatePending) {
                    updatePending = false
                    checkPatcherUpdate()
                }
                maybeFinishManualRefresh()
            }
        }
    }

    private fun installedGames(): Map<GameTarget, GameInstall> = buildMap {
        GameTarget.entries.forEach { target ->
            installedGame(target)?.let { put(target, it) }
        }
    }

    private fun installedGame(target: GameTarget, strict: Boolean = false): GameInstall? {
        val info = try {
            activity.packageManager.getPackageInfo(target.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (error: Exception) {
            if (strict) throw error
            appendLog("${target.displayName} 설치 정보를 조회하지 못했습니다: ${error.message}")
            return state.gameInstalls[target]
        }
        val source = runCatching {
            activity.packageManager.getInstallSourceInfo(target.packageName).installingPackageName
        }
        return GameInstall(
            version = info.versionName,
            installedFromPlay = source.getOrNull() == PLAY_STORE_PACKAGE,
            versionCode = info.longVersionCode,
            lastUpdateTime = info.lastUpdateTime,
            sourceDir = info.applicationInfo?.sourceDir.orEmpty(),
            splitSourceDirs = info.applicationInfo?.splitSourceDirs?.toList().orEmpty(),
            userId = android.os.Process.myUid() / 100_000,
            installerKnown = source.isSuccess && source.getOrNull() != null,
            packageName = target.packageName,
        )
    }

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
        if (section == ProgressSection.PATCH && patchStatusBeforeWork == null) {
            patchStatusBeforeWork = PatchInstallationStatus(state.releaseLabel, state.releaseStatus, state.releaseIndicator)
        }
        state = state.copy(
            busy = true,
            progressSection = section,
            progressLabel = label,
            progress = progress,
            releaseLabel = if (section == ProgressSection.PATCH) "한글패치 작업 중" else state.releaseLabel,
            releaseStatus = if (section == ProgressSection.PATCH) label else state.releaseStatus,
            releaseIndicator = if (section == ProgressSection.PATCH) StatusIndicator.IN_PROGRESS else state.releaseIndicator,
        )
        appendLog(label)
    }

    private fun updateBusy(label: String, progress: Float) {
        main.post {
            if (stopped) return@post
            val nextProgress = progress.coerceIn(0f, 1f)
            state = state.copy(
                progressLabel = label,
                progress = maxOf(state.progress, nextProgress),
                releaseStatus = if (state.progressSection == ProgressSection.PATCH) label else state.releaseStatus,
            )
        }
    }

    private fun setIdle() {
        val previousPatchStatus = patchStatusBeforeWork
        patchStatusBeforeWork = null
        state = state.copy(
            busy = false,
            progressSection = null,
            progressLabel = null,
            progress = 0f,
            releaseLabel = previousPatchStatus?.label ?: state.releaseLabel,
            releaseStatus = previousPatchStatus?.description ?: state.releaseStatus,
            releaseIndicator = previousPatchStatus?.indicator ?: state.releaseIndicator,
        )
        if (refreshAfterWork && pendingOperation == null) {
            refreshAfterWork = false
            refreshGate.request()
        }
        main.post { drainRefresh() }
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
            if (stopped) return@post
            val patchWorkFailed = state.progressSection == ProgressSection.PATCH
            appendLog("$prefix ${error.message ?: error.javaClass.simpleName}")
            state = state.copy(actionMessage = "$prefix ${error.message ?: error.javaClass.simpleName}")
            setIdle()
            if (patchWorkFailed) {
                state = state.copy(
                    releaseLabel = "한글패치 작업 실패",
                    releaseStatus = "아래 안내와 실행 로그를 확인한 뒤 다시 시도해 주세요",
                    releaseIndicator = StatusIndicator.ERROR,
                )
            }
        }
    }

    private fun appendLog(message: String) {
        if (message.isBlank()) return
        val action = {
            if (!stopped) {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
                state = state.copy(logs = (state.logs + "[$timestamp] ${message.trim()}").takeLast(300))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val SHIZUKU_PERMISSION_REQUEST = 1001
        private const val PREFERENCES_NAME = "patcher-preferences"
        private const val PREFERENCE_GAME_TARGET = "game-target"
    }
}

internal data class GameInstall(
    val version: String?,
    val installedFromPlay: Boolean,
    val versionCode: Long = 0,
    val lastUpdateTime: Long = 0,
    val sourceDir: String = "",
    val splitSourceDirs: List<String> = emptyList(),
    val userId: Int = 0,
    val installerKnown: Boolean = true,
    val packageName: String = "",
) {
    fun sameInstallation(other: GameInstall?): Boolean = other != null &&
        version == other.version && versionCode == other.versionCode &&
        lastUpdateTime == other.lastUpdateTime && sourceDir == other.sourceDir &&
        splitSourceDirs == other.splitSourceDirs && userId == other.userId && packageName == other.packageName

    fun toJson(): String = JSONObject()
        .put("packageName", packageName)
        .put("versionName", version ?: JSONObject.NULL)
        .put("versionCode", versionCode)
        .put("lastUpdateTime", lastUpdateTime)
        .put("sourceDir", sourceDir)
        .put("splitSourceDirs", JSONArray(splitSourceDirs))
        .put("userId", userId)
        .toString()
}

internal fun sameInstallationSnapshot(left: GameInstall?, right: GameInstall?): Boolean =
    if (left == null) right == null else left.sameInstallation(right)

private data class PreparedOperation(
    val target: GameTarget,
    val install: GameInstall,
    val release: ReleaseEntry,
    val manifest: PatchManifest,
    val inspection: PatchTargetInspection,
    val operation: String,
    val ticket: Long,
)
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
    onConfirmOperation: () -> Unit = {},
    onDismissOperation: () -> Unit = {},
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

    if (state.confirmation != null) {
        val confirmation = state.confirmation
        AlertDialog(
            onDismissRequest = onDismissOperation,
            title = { Text("리소스 버전이 다릅니다") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("설치된 리소스: ${confirmation.installedResource}\n최신 한글패치 리소스: ${confirmation.release}\n\n${confirmation.reason}\n\n계속하면 최신 한글패치 리소스의 ${if (confirmation.operation == "RESTORE") "원본" else "패치"}으로 교체합니다.")
                }
            },
            confirmButton = { Button(onClick = onConfirmOperation) {
                Text(if (confirmation.operation == "RESTORE") "계속 원본 복구" else "계속 설치")
            } },
            dismissButton = { TextButton(onClick = onDismissOperation) { Text("취소") } },
        )
    } else if (!state.busy && updateVersion != null && deferredUpdateVersion != updateVersion) {
        val displayVersion = if (updateVersion.startsWith("v")) updateVersion else "v$updateVersion"
        AlertDialog(
            onDismissRequest = { deferredUpdateVersion = updateVersion },
            modifier = Modifier.width(dialogWidth),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("앱 업데이트") },
            text = { Text("새 버전을 사용할 수 있습니다.\n$displayVersion") },
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
            progressState = state.takeIf { it.busy && it.progressSection == ProgressSection.SHIZUKU },
        ) {
            StatusSummary(
                title = state.shizukuLabel,
                description = state.shizukuStatus,
                indicator = if (state.shizukuStatus == "확인 중") StatusIndicator.IN_PROGRESS
                    else if (state.shizukuReady) StatusIndicator.COMPLETED else StatusIndicator.PENDING,
                actionLabel = state.shizukuActionLabel.takeUnless { state.shizukuReady },
                actionEnabled = !state.busy,
                onAction = onShizuku,
            )
        }
        SectionCard(
            title = null,
            topCorner = PatcherShapes.attached,
            bottomCorner = PatcherShapes.attached,
            progressState = state.takeIf { it.busy && it.progressSection == ProgressSection.APK },
        ) {
            StatusSummary(
                title = gameApkStatusLabel(state.selectedTarget, state.selectedInstall),
                description = gameApkStatusDescription(state.selectedTarget, state.selectedInstall, state.shizukuReady),
                indicator = if (state.gameReady) StatusIndicator.COMPLETED else StatusIndicator.PENDING,
                actionLabel = "설치".takeIf { state.selectedTarget.supportsOriginalInstall && !state.gameReady },
                actionEnabled = state.shizukuReady && !state.busy,
                onAction = onGame,
            )
        }
        SectionCard(
            title = null,
            topCorner = PatcherShapes.attached,
            bottomCorner = PatcherShapes.card,
        ) {
            StatusSummary(
                title = state.resourceLabel,
                description = state.resourceStatus,
                indicator = state.resourceIndicator,
                actionLabel = "게임 실행".takeIf { state.resourceNeedsDownload && state.canLaunch },
                actionEnabled = !state.busy,
                onAction = onLaunch,
            )
        }
    }
}

/** Keep the indicator with the heading; long explanations and actions each get their own width. */
@Composable
private fun StatusSummary(
    title: String,
    description: String,
    indicator: StatusIndicator,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PatcherSpacing.card, vertical = PatcherSpacing.screen),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PatcherSpacing.small),
        ) {
            StatusIndicatorDot(indicator)
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            description,
            modifier = Modifier.fillMaxWidth().padding(start = PatcherSpacing.statusTextInset, top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionLabel?.let { label ->
            FilledTonalButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.align(Alignment.End).padding(top = PatcherSpacing.small).widthIn(min = 96.dp),
            ) {
                Text(label, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Values may wrap without squeezing the label or inheriting ListItem's trailing-slot constraints. */
@Composable
private fun PatchVersionDetail(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PatcherSpacing.card, vertical = PatcherSpacing.small),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
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
        StatusSummary(
            title = state.releaseLabel,
            description = state.releaseStatus,
            indicator = state.releaseIndicator,
        )
        SectionDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
        state.actionMessage?.let { message ->
            Text(message, modifier = Modifier.padding(PatcherSpacing.card), color = MaterialTheme.colorScheme.error)
        }
        PatchVersionDetail("설치된 리소스", state.compatiblePatchVersion ?: "확인 불가")
        PatchVersionDetail("최신 한글패치 리소스", state.latestPatchVersion ?: state.latestPatchStatus)
        PatchVersionDetail("업로드 시각", state.latestPatchUploadedAt?.let(::formatPatchUploadedAt) ?: state.latestPatchUploadStatus)
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
                    if (state.installedPatchVersion != null && state.installedPatchVersion == state.patchVersion) {
                        "한글패치 재설치"
                    } else if (
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
                        if (visibleLogs.isEmpty()) "이곳에 로그가 표시됩니다."
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
        StatusIndicator.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val statusTextInset = 36.dp
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
    else -> "${target.packageName} • 설치됨 • ${install.version ?: "버전 알 수 없음"}"
}

private fun gameApkStatusLabel(target: GameTarget, install: GameInstall?): String = when {
    install == null -> "${target.displayName} 게임 미설치"
    else -> "${target.displayName} 설치됨"
}

private fun gameApkStatusDescription(
    target: GameTarget,
    install: GameInstall?,
    shizukuReady: Boolean = true,
): String = when {
    install == null && target.supportsOriginalInstall -> if (shizukuReady) {
        "설치 가능"
    } else {
        "설치 가능 • Shizuku 활성화 및 권한이 필요합니다"
    }
    install == null -> "스토어에서 吉星派对를 설치해 주세요"
    else -> (install.version ?: "버전 알 수 없음") + when {
        !install.installerKnown -> " · 설치 출처 확인 불가"
        target.supportsOriginalInstall && !install.installedFromPlay -> " · Play 외 설치 출처"
        else -> ""
    }
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

@Preview(name = "긴 리소스 안내 · 좁은 화면", showBackground = true, widthDp = 320, heightDp = 360)
@Preview(name = "긴 리소스 안내 · 큰 글꼴", showBackground = true, widthDp = 560, heightDp = 360, fontScale = 1.5f)
@Composable
private fun LongResourceStatusPreview() {
    AstralMaterialTheme {
        SectionCard(title = null) {
            StatusSummary(
                title = "리소스 다운로드 필요",
                description = "대상 1개가 없습니다. 게임에서 리소스를 다운로드한 후 다시 시도해 주세요.\n게임을 최신 상태로 업데이트하고 추가 다운로드가 끝났는지 확인해 주세요.",
                indicator = StatusIndicator.PENDING,
                actionLabel = "게임 실행",
            )
        }
    }
}

@Preview(name = "한글패치 버전 안내", showBackground = true, widthDp = 360, heightDp = 600)
@Composable
private fun PatchVersionDetailsPreview() {
    AstralMaterialTheme {
        PatchStatusSection(
            state = PatchUiState(
                releaseLabel = "한글패치 미설치",
                releaseStatus = "설치 버튼을 누르면 최신 한글패치 리소스를 적용합니다",
                releaseIndicator = StatusIndicator.NEUTRAL,
                latestPatchVersion = "v3.3.0_r026_p0",
                compatiblePatchVersion = "v3.3.0_r021_p0",
                latestPatchUploadedAt = "2026-10-19T12:25:00Z",
            ),
            onRestore = {},
            onPatch = {},
        )
    }
}

@Preview(name = "한글패치 설치 완료", showBackground = true, widthDp = 360, heightDp = 520)
@Composable
private fun InstalledPatchStatusPreview() {
    AstralMaterialTheme {
        PatchStatusSection(
            state = PatchUiState(
                releaseLabel = "최신 한글패치 설치 완료",
                releaseStatus = "v3.3.0_r026_p0",
                releaseIndicator = StatusIndicator.COMPLETED,
                installedPatchVersion = "v3.3.0_r026_p0",
                patchVersion = "v3.3.0_r026_p0",
                latestPatchVersion = "v3.3.0_r026_p0",
                compatiblePatchVersion = "v3.3.0_r026_p0",
                latestPatchUploadedAt = "2026-10-19T09:02:00Z",
            ),
            onRestore = {},
            onPatch = {},
        )
    }
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
        releaseLabel = "한글패치 미설치",
        releaseStatus = "한글패치 적용 가능",
        releaseIndicator = StatusIndicator.NEUTRAL,
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
