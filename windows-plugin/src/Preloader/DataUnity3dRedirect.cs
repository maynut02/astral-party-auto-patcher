using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using BepInEx.Preloader.Core.Patching;

[PatcherPluginInfo(
    "astral-party.korean-patch.data-redirect",
    "Astral Party data.unity3d Redirect",
    AstralBuildVersion.Value)]
public sealed class DataUnity3dRedirect : BasePatcher
{
    private const string Repository = "maynut02/astral-party-korean-patch";
    private const string LatestReleaseApi = "https://api.github.com/repos/" + Repository + "/releases/latest";
    private const long ApiLimit = 2 * 1024 * 1024;
    private const long ManifestLimit = 2 * 1024 * 1024;
    private const long TransportLimit = 128L * 1024 * 1024;
    private const long PayloadLimit = 256L * 1024 * 1024;
    private const int DownloadOverallTimeoutSeconds = 300;
    private const int DownloadIdleTimeoutSeconds = 20;

    private static readonly HttpClient Http = CreateHttpClient();

    private static string route = string.Empty;
    private static string manifestAssetName = string.Empty;
    private static string? gameRootPath;
    private static string? patchRootPath;
    private static string? sourcePath;
    private static string? replacementPath;
    private static string? legacyReplacementPath;
    private static string? statePath;
    private static string? legacyManifestCachePath;
    private static string? releaseRootPath;
    private static string? gameDataRootPath;
    private static string? payloadRootPath;
    private static string? sessionPath;
    private static string? localAddressablesRoot;
    private static string? logPath;
    private static StartupProgress? startupProgress;

    private static CreateFileWDelegate? createFileWHook;
    private static CreateFileWDelegate? originalCreateFileW;

    [UnmanagedFunctionPointer(CallingConvention.Winapi, CharSet = CharSet.Unicode)]
    private delegate IntPtr CreateFileWDelegate(
        [MarshalAs(UnmanagedType.LPWStr)] string fileName,
        uint desiredAccess,
        uint shareMode,
        IntPtr securityAttributes,
        uint creationDisposition,
        uint flagsAndAttributes,
        IntPtr templateFile);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int DobbyHookDelegate(IntPtr address, IntPtr replacement, out IntPtr original);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryW(string fileName);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr GetModuleHandleW(string moduleName);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    private static extern IntPtr GetProcAddress(IntPtr module, string procName);

    public override void Initialize()
    {
        try
        {
            var processPath = Environment.GetEnvironmentVariable("DOORSTOP_PROCESS_PATH");
            if (string.IsNullOrWhiteSpace(processPath))
                processPath = Process.GetCurrentProcess().MainModule?.FileName;

            var gameRoot = string.IsNullOrWhiteSpace(processPath)
                ? Environment.CurrentDirectory
                : Path.GetDirectoryName(processPath) ?? Environment.CurrentDirectory;
            var processName = string.IsNullOrWhiteSpace(processPath)
                ? Process.GetCurrentProcess().ProcessName
                : Path.GetFileNameWithoutExtension(processPath);
            var routeLayout = DetectSteamRoute(gameRoot, processName);
            route = routeLayout.Route;
            manifestAssetName = route + "_manifest.json";
            gameRootPath = gameRoot;
            var patchRoot = Path.Combine(gameRoot, "BepInEx", "AstralPartyKoreanPatch");
            patchRootPath = patchRoot;
            var legacyPluginRoot = Path.Combine(gameRoot, "BepInEx", "plugins", "AstralPartyKoreanPatch");

            sourcePath = Path.GetFullPath(Path.Combine(gameRoot, routeLayout.DataDirectory, "data.unity3d"));
            replacementPath = null;
            legacyReplacementPath = Path.GetFullPath(Path.Combine(patchRoot, "patched-data.unity3d"));
            statePath = Path.Combine(patchRoot, "data-unity3d-state.json");
            legacyManifestCachePath = Path.Combine(patchRoot, "cache", route + "-manifest.json");
            releaseRootPath = Path.Combine(patchRoot, "releases");
            gameDataRootPath = Path.Combine(patchRoot, "game-data");
            payloadRootPath = Path.Combine(patchRoot, "payloads");
            sessionPath = Path.Combine(patchRoot, "preloader-session.json");
            var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            localAddressablesRoot = Path.Combine(userProfile, "AppData", "LocalLow", "feimo",
                routeLayout.LocalLowDirectory, "com.unity.addressables");
            logPath = Path.Combine(gameRoot, "BepInEx", "data-redirect.log");

            Directory.CreateDirectory(patchRoot);
            Directory.CreateDirectory(releaseRootPath);
            Directory.CreateDirectory(gameDataRootPath);
            Directory.CreateDirectory(payloadRootPath);
            Directory.CreateDirectory(Path.GetDirectoryName(logPath)!);
            DeleteSessionMarker();
            MigrateLegacyData(legacyPluginRoot, patchRoot);
            CleanupStaleTempFiles(patchRoot);
            startupProgress = new StartupProgress();
            startupProgress.Start();
            startupProgress.Update("최신 패치를 확인하고 있습니다.", "GitHub에서 최신 릴리스를 확인하는 중입니다.");
            Log("initialize route=" + route + " source=" + sourcePath + " cacheRoot=" + patchRoot);

            if (!File.Exists(sourcePath))
            {
                Log("disabled reason=source-missing");
                return;
            }

            var usableReplacement = PrepareLatestReplacement();
            if (!usableReplacement)
            {
                Log("disabled reason=no-valid-replacement; Unity will use original data.unity3d");
                return;
            }

            startupProgress?.Update("게임을 시작합니다.", "패치 준비가 완료되었습니다.", 100);
            WriteSessionMarker();
            try
            {
                InstallCreateFileHook(gameRoot);
            }
            catch
            {
                DeleteSessionMarker();
                throw;
            }
        }
        catch (Exception ex)
        {
            DeleteSessionMarker();
            Log("initialize-failed type=" + ex.GetType().FullName + " message=" + ex.Message +
                "; patch disabled for this process");
        }
        finally
        {
            startupProgress?.Close();
            startupProgress = null;
        }
    }

    private static bool PrepareLatestReplacement()
    {
        var activeState = LoadState();
        try
        {
            startupProgress?.Update("최신 패치를 확인하고 있습니다.", "GitHub에서 최신 릴리스를 확인하는 중입니다.");
            var latest = GetLatestRelease(activeState?.LatestEtag);
            if (latest.NotModified)
            {
                if (activeState == null)
                    throw new InvalidDataException("GitHub returned 304 without local state");
                Log("latest-not-modified tag=" + activeState.ReleaseTag);
                if (!PrepareExistingState(activeState, allowDownload: true))
                    throw new InvalidDataException("active patch cache is incomplete or incompatible");
                activeState.LatestEtag = latest.Etag ?? activeState.LatestEtag;
                SaveState(activeState);
                ActivateState(activeState);
                return true;
            }

            if (latest.Release == null)
                throw new InvalidDataException("latest release response is empty");

            var release = latest.Release;
            var digestMatches = string.IsNullOrEmpty(release.ManifestDigest) ||
                                (activeState != null && string.Equals(activeState.ManifestSha256,
                                    release.ManifestDigest, StringComparison.OrdinalIgnoreCase));
            if (activeState != null &&
                string.Equals(activeState.ReleaseTag, release.Tag, StringComparison.Ordinal) &&
                digestMatches)
            {
                activeState.LatestEtag = latest.Etag ?? activeState.LatestEtag;
                if (string.IsNullOrWhiteSpace(activeState.ManifestUrl))
                    activeState.ManifestUrl = release.ManifestUrl;
                startupProgress?.Update("로컬 패치를 확인하고 있습니다.", "게임 데이터와 번역 데이터를 확인합니다.");
                if (PrepareExistingState(activeState, allowDownload: true))
                {
                    SaveState(activeState);
                    ActivateState(activeState);
                    Log("release-cache-hit tag=" + activeState.ReleaseTag +
                        " payload=" + activeState.PayloadSha256);
                    return true;
                }
            }

            Log("release-update tag=" + release.Tag + " manifest=" + release.ManifestUrl);
            startupProgress?.Update("패치 정보를 확인하고 있습니다.", "최신 릴리스의 manifest를 내려받는 중입니다.");
            var manifestBytes = DownloadBytes(release.ManifestUrl, ManifestLimit);
            var manifestSha = Sha256(manifestBytes);
            if (!string.IsNullOrEmpty(release.ManifestDigest) &&
                !string.Equals(manifestSha, release.ManifestDigest, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("manifest digest mismatch");

            var candidate = ParseGameDataManifest(manifestBytes, release.Tag, manifestSha, release.ManifestUrl);
            candidate.LatestEtag = latest.Etag ?? string.Empty;

            // A candidate release never mutates the active state or active files until every
            // component has been downloaded and verified successfully.
            ValidateInstalledGameAgainstManifest(manifestBytes, candidate);
            startupProgress?.Update("게임 데이터를 확인하고 있습니다.", "설치 상태와 패치 파일을 검증합니다.");
            if (!VerifySource(candidate, fastPath: false))
                throw new InvalidDataException("installed data.unity3d is missing or unreadable");

            SaveCandidateManifest(manifestBytes, manifestSha);
            if (!VerifyReplacement(candidate, fastPath: false))
                DownloadReplacement(candidate);
            EnsureAddressablesPayloads(manifestBytes, allowDownload: true);

            candidate.AddressablesReady = true;
            StampFileTimes(candidate);
            SaveState(candidate); // Atomic active-generation switch; this must stay last.
            ActivateState(candidate);
            Log("release-ready tag=" + candidate.ReleaseTag + " payload=" + candidate.PayloadSha256 +
                " bytes=" + candidate.PayloadSize + " addressables=ready");
            return true;
        }
        catch (Exception onlineError)
        {
            Log("online-update-failed type=" + onlineError.GetType().Name + " message=" + onlineError.Message);
            if (activeState != null && PrepareExistingState(activeState, allowDownload: false))
            {
                SaveState(activeState);
                ActivateState(activeState);
                Log("active-cache-preserved tag=" + activeState.ReleaseTag +
                    " payload=" + activeState.PayloadSha256 + " addressables=ready");
                return true;
            }

            if (activeState != null)
                Log("active-cache-rejected tag=" + activeState.ReleaseTag +
                    "; cached patch data is incomplete or incompatible with the installed game");
            return false;
        }
    }

    private static bool PrepareExistingState(RedirectState state, bool allowDownload)
    {
        try
        {
            startupProgress?.Update("로컬 패치를 확인하고 있습니다.", "게임 데이터와 번역 데이터를 확인합니다.");
            if (!EnsureStateManifest(state, allowDownload)) return false;
            if (!EnsureCachedManifestCompatibility(state)) return false;
            if (!EnsureStateReplacement(state, allowDownload)) return false;
            if (!EnsureAddressablesFromState(state, allowDownload)) return false;
            state.AddressablesReady = true;
            StampFileTimes(state);
            return true;
        }
        catch (Exception ex)
        {
            Log("active-cache-prepare-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            return false;
        }
    }

    private static void ActivateState(RedirectState state)
    {
        replacementPath = StateReplacementPath(state);
        Log("active-generation tag=" + state.ReleaseTag + " manifest=" + state.ManifestSha256 +
            " payload=" + state.PayloadSha256 + " replacement=" + replacementPath);
    }

    private static bool EnsureStateReplacement(RedirectState state, bool allowDownload)
    {
        if (!VerifySource(state, fastPath: true))
        {
            Log("state-source-unavailable tag=" + state.ReleaseTag);
            return false;
        }

        if (VerifyReplacement(state, fastPath: true))
            return true;

        // One-time migration from the old single active file into immutable SHA storage.
        if (!string.IsNullOrEmpty(legacyReplacementPath) && File.Exists(legacyReplacementPath) &&
            IsValidReplacementFile(legacyReplacementPath, state))
        {
            CopyFileAtomic(legacyReplacementPath, StateReplacementPath(state));
            if (VerifyReplacement(state, fastPath: false)) return true;
        }

        if (!allowDownload) return false;
        Log("state-payload-miss tag=" + state.ReleaseTag + " expected=" + state.PayloadSha256);
        DownloadReplacement(state);
        return VerifyReplacement(state, fastPath: false);
    }

    private static LatestResult GetLatestRelease(string? etag)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, LatestReleaseApi);
        if (!string.IsNullOrWhiteSpace(etag))
            request.Headers.TryAddWithoutValidation("If-None-Match", etag);

        using var cancellation = CreateDownloadCancellation();
        using var response = Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation.Token)
            .GetAwaiter().GetResult();
        if (response.StatusCode == HttpStatusCode.NotModified)
            return new LatestResult(true, etag, null);

        response.EnsureSuccessStatusCode();
        var responseEtag = response.Headers.ETag?.ToString() ?? string.Empty;
        var bytes = ReadLimited(response.Content.ReadAsStream(), ApiLimit, cancellation.Token);
        using var document = JsonDocument.Parse(bytes);
        var root = document.RootElement;
        var tag = RequiredString(root, "tag_name");
        var assets = RequiredArray(root, "assets");

        foreach (var asset in assets.EnumerateArray())
        {
            if (!string.Equals(OptionalString(asset, "name"), manifestAssetName, StringComparison.Ordinal))
                continue;
            var url = RequiredString(asset, "browser_download_url");
            EnsureRepositoryReleaseUrl(url);
            var digest = OptionalString(asset, "digest") ?? string.Empty;
            if (digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                digest = digest.Substring("sha256:".Length);
            if (!string.IsNullOrEmpty(digest)) ValidateHex(digest, 64, "manifest digest");
            return new LatestResult(false, responseEtag, new ReleaseInfo(tag, url, digest));
        }

        throw new InvalidDataException("latest release does not contain " + manifestAssetName);
    }

    private static RedirectState ParseGameDataManifest(byte[] bytes, string releaseTag, string manifestSha, string manifestUrl)
    {
        using var document = JsonDocument.Parse(bytes);
        var root = document.RootElement;
        if (RequiredInt(root, "schemaVersion") != 2)
            throw new InvalidDataException("unsupported manifest schemaVersion");

        var patch = RequiredObject(root, "patch");
        if (!string.Equals(RequiredString(patch, "route"), route, StringComparison.Ordinal))
            throw new InvalidDataException("manifest route mismatch");
        if (!string.Equals(RequiredString(patch, "channel"), "release", StringComparison.Ordinal))
            throw new InvalidDataException("manifest is not a release");
        var patchVersion = RequiredString(patch, "version");
        if (!string.Equals(patchVersion, releaseTag, StringComparison.Ordinal))
            throw new InvalidDataException("manifest patch version does not match release tag");

        var game = RequiredObject(root, "game");
        var gameVersion = RequiredString(game, "version");
        var revision = RequiredString(game, "revision");
        var catalogHash = RequiredString(game, "catalogHash");
        ValidateHex(catalogHash, 32, "catalog hash");

        RedirectState? result = null;
        foreach (var file in RequiredArray(root, "files").EnumerateArray())
        {
            var target = RequiredString(file, "target");
            var path = RequiredString(file, "path").Replace('\\', '/');
            if (!string.Equals(target, "game-data", StringComparison.Ordinal) ||
                !string.Equals(path, "data.unity3d", StringComparison.Ordinal))
                continue;
            if (result != null)
                throw new InvalidDataException("manifest contains duplicate game-data/data.unity3d entries");
            if (!string.Equals(RequiredString(file, "compression"), "gzip", StringComparison.Ordinal))
                throw new InvalidDataException("unsupported game-data compression");
            if (!string.Equals(RequiredString(file, "operation"), "replace", StringComparison.Ordinal))
                throw new InvalidDataException("unsupported game-data operation");

            var downloadUrl = RequiredString(file, "downloadUrl");
            EnsureRepositoryReleaseUrl(downloadUrl);
            var downloadSha = RequiredString(file, "downloadSha256");
            var payloadSha = RequiredString(file, "sha256");
            var sourceSha = RequiredString(file, "sourceSha256");
            ValidateHex(downloadSha, 64, "download sha256");
            ValidateHex(payloadSha, 64, "payload sha256");
            ValidateHex(sourceSha, 64, "source sha256");

            var downloadSize = RequiredLong(file, "downloadSize");
            var payloadSize = RequiredLong(file, "size");
            var sourceSize = RequiredLong(file, "sourceSize");
            if (downloadSize <= 0 || downloadSize > TransportLimit)
                throw new InvalidDataException("invalid game-data download size");
            if (payloadSize <= 0 || payloadSize > PayloadLimit)
                throw new InvalidDataException("invalid game-data payload size");
            if (sourceSize <= 0 || sourceSize > PayloadLimit)
                throw new InvalidDataException("invalid game-data source size");

            result = new RedirectState
            {
                Route = route,
                ReleaseTag = releaseTag,
                ManifestUrl = manifestUrl,
                ManifestSha256 = manifestSha,
                GameVersion = gameVersion,
                Revision = revision,
                CatalogHash = catalogHash,
                DownloadUrl = downloadUrl,
                DownloadSha256 = downloadSha,
                DownloadSize = downloadSize,
                PayloadSha256 = payloadSha,
                PayloadSize = payloadSize,
                SourceSha256 = sourceSha,
                SourceSize = sourceSize,
            };
        }

        return result ?? throw new InvalidDataException("manifest has no game-data/data.unity3d entry");
    }

    private static bool VerifySource(RedirectState state, bool fastPath)
    {
        if (sourcePath == null || !File.Exists(sourcePath))
        {
            Log("source-unavailable reason=missing path=" + (sourcePath ?? "<null>"));
            return false;
        }

        try
        {
            var info = new FileInfo(sourcePath);
            using (var stream = new FileStream(sourcePath, FileMode.Open, FileAccess.Read,
                       FileShare.ReadWrite | FileShare.Delete))
            {
                _ = stream.Length;
            }

            if (fastPath && state.SourceWriteTimeUtcTicks > 0 &&
                info.LastWriteTimeUtc.Ticks == state.SourceWriteTimeUtcTicks)
                return true;

            var sizeMatches = info.Length == state.SourceSize;
            string actualSha;
            bool shaMatches;
            if (sizeMatches)
            {
                actualSha = Sha256File(sourcePath);
                shaMatches = string.Equals(actualSha, state.SourceSha256, StringComparison.OrdinalIgnoreCase);
            }
            else
            {
                actualSha = "skipped-size-mismatch";
                shaMatches = false;
            }

            var matchesManifestSource = sizeMatches && shaMatches;
            Log("source-verify match=" + matchesManifestSource +
                " sha256=" + actualSha +
                " expectedSha256=" + state.SourceSha256 +
                " bytes=" + info.Length +
                " expectedBytes=" + state.SourceSize +
                " action=" + (matchesManifestSource ? "accepted" : "ignored-mismatch"));

            // The installed data.unity3d is not used as the patch payload. As long as it exists and
            // can be opened, source hash/size differences are diagnostic only. The downloaded
            // replacement is still validated strictly before the CreateFileW redirect is enabled.
            return true;
        }
        catch (Exception ex)
        {
            Log("source-unavailable reason=unreadable type=" + ex.GetType().Name +
                " message=" + ex.Message + " path=" + sourcePath);
            return false;
        }
    }

    private static bool VerifyReplacement(RedirectState state, bool fastPath)
    {
        var path = StateReplacementPath(state);
        return IsValidReplacementFile(path, state, fastPath);
    }

    private static bool IsValidReplacementFile(string path, RedirectState state, bool fastPath = false)
    {
        try
        {
            if (!File.Exists(path)) return false;
            var info = new FileInfo(path);
            if (info.Length != state.PayloadSize) return false;
            if (fastPath && state.ReplacementWriteTimeUtcTicks > 0 &&
                info.LastWriteTimeUtc.Ticks == state.ReplacementWriteTimeUtcTicks)
                return true;
            if (!HasUnityFsSignature(path)) return false;
            var actual = Sha256File(path);
            var valid = string.Equals(actual, state.PayloadSha256, StringComparison.OrdinalIgnoreCase);
            Log("payload-verify valid=" + valid + " sha256=" + actual + " bytes=" + info.Length +
                " path=" + path);
            return valid;
        }
        catch
        {
            return false;
        }
    }

    private static void DownloadReplacement(RedirectState state)
    {
        var targetPath = StateReplacementPath(state);
        EnsureRepositoryReleaseUrl(state.DownloadUrl);
        Directory.CreateDirectory(Path.GetDirectoryName(targetPath)!);

        var temp = targetPath + ".tmp-" + Process.GetCurrentProcess().Id + "-" + Guid.NewGuid().ToString("N");
        try
        {
            Log("payload-download tag=" + state.ReleaseTag + " url=" + state.DownloadUrl +
                " compressedBytes=" + state.DownloadSize + " payloadBytes=" + state.PayloadSize);
            startupProgress?.UpdateDownload("패치 리소스를 다운로드하고 있습니다. (data.unity3d)", 0, state.DownloadSize);

            string transportSha;
            string payloadSha;
            long transportBytes;
            long payloadBytes = 0;
            var signature = new byte[7];
            var signatureLength = 0;

            using var cancellation = CreateDownloadCancellation();
            using (var response = Http.GetAsync(state.DownloadUrl, HttpCompletionOption.ResponseHeadersRead,
                       cancellation.Token).GetAwaiter().GetResult())
            {
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength is long contentLength &&
                    contentLength != state.DownloadSize)
                    throw new InvalidDataException("payload Content-Length mismatch");

                using (var network = response.Content.ReadAsStream())
                using (var compressed = new HashingReadStream(network, TransportLimit))
                using (var gzip = new GZipStream(compressed, CompressionMode.Decompress, leaveOpen: true))
                using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    1024 * 1024, FileOptions.SequentialScan))
                using (var payloadHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                {
                    var buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = ReadWithIdleTimeout(gzip, buffer, cancellation.Token)) > 0)
                    {
                        payloadBytes += read;
                        if (payloadBytes > state.PayloadSize || payloadBytes > PayloadLimit)
                            throw new InvalidDataException("decompressed payload exceeds expected size");
                        payloadHash.AppendData(buffer, 0, read);
                        if (signatureLength < signature.Length)
                        {
                            var take = Math.Min(signature.Length - signatureLength, read);
                            Buffer.BlockCopy(buffer, 0, signature, signatureLength, take);
                            signatureLength += take;
                        }
                        output.Write(buffer, 0, read);
                        startupProgress?.UpdateDownload("패치 리소스를 다운로드하고 있습니다. (data.unity3d)",
                            compressed.TotalBytes, state.DownloadSize);
                    }

                    while (ReadWithIdleTimeout(compressed, buffer, cancellation.Token) > 0) { }
                    output.Flush(true);
                    transportBytes = compressed.TotalBytes;
                    transportSha = compressed.FinishHash();
                    payloadSha = Convert.ToHexString(payloadHash.GetHashAndReset()).ToLowerInvariant();
                }
            }

            startupProgress?.Update("다운로드한 파일을 확인하고 있습니다.", "SHA-256과 UnityFS 형식을 검증합니다.", 100);
            if (transportBytes != state.DownloadSize)
                throw new InvalidDataException("download size mismatch");
            if (!string.Equals(transportSha, state.DownloadSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("download sha256 mismatch");
            if (payloadBytes != state.PayloadSize)
                throw new InvalidDataException("payload size mismatch");
            if (!string.Equals(payloadSha, state.PayloadSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("payload sha256 mismatch");
            if (signatureLength != 7 || Encoding.ASCII.GetString(signature) != "UnityFS")
                throw new InvalidDataException("payload is not a UnityFS bundle");

            File.Move(temp, targetPath, true);
            if (!IsValidReplacementFile(targetPath, state))
                throw new InvalidDataException("replacement verification failed after atomic move");
            Log("payload-ready tag=" + state.ReleaseTag + " sha256=" + state.PayloadSha256 +
                " bytes=" + state.PayloadSize + " path=" + targetPath);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    private static void StampFileTimes(RedirectState state)
    {
        if (sourcePath != null && File.Exists(sourcePath))
            state.SourceWriteTimeUtcTicks = File.GetLastWriteTimeUtc(sourcePath).Ticks;
        var path = StateReplacementPath(state);
        if (File.Exists(path))
            state.ReplacementWriteTimeUtcTicks = File.GetLastWriteTimeUtc(path).Ticks;
    }

    private static RedirectState? LoadState()
    {
        try
        {
            if (statePath == null || !File.Exists(statePath)) return null;
            var state = JsonSerializer.Deserialize<RedirectState>(File.ReadAllBytes(statePath));
            if (state == null || string.IsNullOrWhiteSpace(state.ReleaseTag)) return null;
            if (string.IsNullOrWhiteSpace(state.Route)) state.Route = route;
            if (!string.Equals(state.Route, route, StringComparison.Ordinal))
                throw new InvalidDataException("cached state route mismatch: " + state.Route + " != " + route);
            ValidateHex(state.ManifestSha256, 64, "cached manifest sha256");
            if (!string.IsNullOrWhiteSpace(state.ManifestUrl)) EnsureRepositoryReleaseUrl(state.ManifestUrl);
            if (!string.IsNullOrWhiteSpace(state.CatalogHash)) ValidateHex(state.CatalogHash, 32, "cached catalog hash");
            ValidateHex(state.DownloadSha256, 64, "cached download sha256");
            ValidateHex(state.PayloadSha256, 64, "cached payload sha256");
            ValidateHex(state.SourceSha256, 64, "cached source sha256");
            EnsureRepositoryReleaseUrl(state.DownloadUrl);
            return state;
        }
        catch (Exception ex)
        {
            Log("state-load-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            return null;
        }
    }

    private static void SaveState(RedirectState state)
    {
        if (statePath == null) return;
        var temp = statePath + ".tmp-" + Process.GetCurrentProcess().Id + "-" + Guid.NewGuid().ToString("N");
        try
        {
            var bytes = JsonSerializer.SerializeToUtf8Bytes(state, new JsonSerializerOptions { WriteIndented = true });
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(true);
            }
            File.Move(temp, statePath, true);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    private static void MigrateLegacyData(string legacyPluginRoot, string sharedRoot)
    {
        try
        {
            if (!Directory.Exists(legacyPluginRoot)) return;

            foreach (var directoryName in new[] { "cache", "payloads" })
            {
                var source = Path.Combine(legacyPluginRoot, directoryName);
                var destination = Path.Combine(sharedRoot, directoryName);
                if (Directory.Exists(source) && !Directory.Exists(destination))
                    Directory.Move(source, destination);
            }

            foreach (var fileName in new[]
                     {
                         "patched-data.unity3d",
                         "data-unity3d-state.json",
                         "addressables-patch.jsonl"
                     })
            {
                var source = Path.Combine(legacyPluginRoot, fileName);
                var destination = Path.Combine(sharedRoot, fileName);
                if (File.Exists(source) && !File.Exists(destination))
                    File.Move(source, destination);
            }
        }
        catch (Exception ex)
        {
            Log("legacy-migration-skipped type=" + ex.GetType().Name + " message=" + ex.Message);
        }
    }

    private static void CleanupStaleTempFiles(string patchRoot)
    {
        try
        {
            var removed = 0;
            foreach (var path in Directory.EnumerateFiles(patchRoot, "*.tmp-*", SearchOption.AllDirectories))
            {
                try
                {
                    File.Delete(path);
                    removed++;
                }
                catch { }
            }
            if (removed > 0) Log("stale-temp-cleanup removed=" + removed);
        }
        catch (Exception ex)
        {
            Log("stale-temp-cleanup-failed type=" + ex.GetType().Name + " message=" + ex.Message);
        }
    }

    private static string StateManifestPath(RedirectState state)
    {
        if (releaseRootPath == null)
            throw new InvalidOperationException("release cache root is unavailable");
        ValidateHex(state.ManifestSha256, 64, "manifest sha256");
        return Path.Combine(releaseRootPath, state.ManifestSha256.ToLowerInvariant(), "manifest.json");
    }

    private static string StateReplacementPath(RedirectState state)
    {
        if (gameDataRootPath == null)
            throw new InvalidOperationException("game-data cache root is unavailable");
        ValidateHex(state.PayloadSha256, 64, "payload sha256");
        return Path.Combine(gameDataRootPath, state.PayloadSha256.ToLowerInvariant() + ".unity3d");
    }

    private static bool EnsureStateManifest(RedirectState state, bool allowDownload)
    {
        var path = StateManifestPath(state);
        try
        {
            if (File.Exists(path))
            {
                var bytes = File.ReadAllBytes(path);
                if (bytes.LongLength <= ManifestLimit &&
                    string.Equals(Sha256(bytes), state.ManifestSha256, StringComparison.OrdinalIgnoreCase))
                    return true;
            }

            // One-time migration from the old mutable cache location.
            if (!string.IsNullOrEmpty(legacyManifestCachePath) && File.Exists(legacyManifestCachePath))
            {
                var legacyBytes = File.ReadAllBytes(legacyManifestCachePath);
                if (legacyBytes.LongLength <= ManifestLimit &&
                    string.Equals(Sha256(legacyBytes), state.ManifestSha256, StringComparison.OrdinalIgnoreCase))
                {
                    SaveCandidateManifest(legacyBytes, state.ManifestSha256);
                    return true;
                }
            }
        }
        catch (Exception ex)
        {
            Log("manifest-cache-check-failed type=" + ex.GetType().Name + " message=" + ex.Message);
        }

        if (!allowDownload || string.IsNullOrWhiteSpace(state.ManifestUrl)) return false;
        startupProgress?.Update("패치 정보를 확인하고 있습니다.", "저장된 manifest 캐시를 복구하는 중입니다.");
        var manifestBytes = DownloadBytes(state.ManifestUrl, ManifestLimit);
        var manifestSha = Sha256(manifestBytes);
        if (!string.Equals(manifestSha, state.ManifestSha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("cached manifest recovery sha256 mismatch");
        SaveCandidateManifest(manifestBytes, manifestSha);
        return true;
    }

    private static void SaveCandidateManifest(byte[] manifestBytes, string manifestSha)
    {
        ValidateHex(manifestSha, 64, "manifest sha256");
        if (releaseRootPath == null)
            throw new InvalidOperationException("release cache root is unavailable");
        var path = Path.Combine(releaseRootPath, manifestSha.ToLowerInvariant(), "manifest.json");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (File.Exists(path))
        {
            var existing = File.ReadAllBytes(path);
            if (string.Equals(Sha256(existing), manifestSha, StringComparison.OrdinalIgnoreCase)) return;
        }
        WriteAtomicFile(path, manifestBytes);
        Log("manifest-generation-ready path=" + path + " sha256=" + manifestSha);
    }

    private static bool EnsureCachedManifestCompatibility(RedirectState state)
    {
        try
        {
            var path = StateManifestPath(state);
            if (!File.Exists(path)) return false;
            var bytes = File.ReadAllBytes(path);
            if (!string.Equals(Sha256(bytes), state.ManifestSha256, StringComparison.OrdinalIgnoreCase))
                return false;
            ValidateInstalledGameAgainstManifest(bytes, state);
            return true;
        }
        catch (Exception ex)
        {
            Log("game-compatibility-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            return false;
        }
    }

    private static void ValidateInstalledGameAgainstManifest(byte[] manifestBytes, RedirectState state)
    {
        using var document = JsonDocument.Parse(manifestBytes);
        var game = RequiredObject(document.RootElement, "game");
        var expectedVersion = RequiredString(game, "version");
        var expectedRevision = RequiredString(game, "revision");
        var expectedCatalogHash = RequiredString(game, "catalogHash").ToLowerInvariant();
        ValidateHex(expectedCatalogHash, 32, "catalog hash");

        state.GameVersion = expectedVersion;
        state.Revision = expectedRevision;
        state.CatalogHash = expectedCatalogHash;

        var local = DiscoverLocalCatalogIdentity();
        if (local == null)
        {
            startupProgress?.Update("한글패치를 적용할 수 없습니다.",
                "게임 리소스 정보를 확인할 수 없어 원본으로 실행합니다.");
            throw new InvalidDataException("local Addressables catalog identity is unavailable");
        }

        if (!string.Equals(local.Version, expectedVersion, StringComparison.Ordinal))
        {
            startupProgress?.Update("한글패치를 적용할 수 없습니다.",
                "현재 게임 버전에 대응하는 한글패치가 아직 없습니다.");
            throw new InvalidDataException("game version mismatch: local=" + local.Version +
                " patch=" + expectedVersion);
        }

        if (!string.Equals(local.Hash, expectedCatalogHash, StringComparison.OrdinalIgnoreCase))
        {
            startupProgress?.Update("한글패치를 적용할 수 없습니다.",
                "현재 게임 리소스에 대응하는 한글패치가 아직 없습니다.");
            throw new InvalidDataException("catalog hash mismatch: local=" + local.Hash +
                " patch=" + expectedCatalogHash + " revision=" + expectedRevision);
        }

        Log("game-compatibility-ready version=" + local.Version + " catalog=" + local.Hash +
            " patchRevision=" + expectedRevision);
    }

    private static LocalCatalogIdentity? DiscoverLocalCatalogIdentity()
    {
        if (string.IsNullOrWhiteSpace(localAddressablesRoot) || !Directory.Exists(localAddressablesRoot))
            return null;

        LocalCatalogIdentity? best = null;
        foreach (var path in Directory.EnumerateFiles(localAddressablesRoot, "catalog_*.hash", SearchOption.TopDirectoryOnly))
        {
            var name = Path.GetFileName(path);
            if (!name.StartsWith("catalog_", StringComparison.OrdinalIgnoreCase) ||
                !name.EndsWith(".hash", StringComparison.OrdinalIgnoreCase))
                continue;

            var version = name.Substring("catalog_".Length,
                name.Length - "catalog_".Length - ".hash".Length);
            if (string.IsNullOrWhiteSpace(version)) continue;

            string hash;
            try
            {
                hash = File.ReadAllText(path).Trim().ToLowerInvariant();
            }
            catch
            {
                continue;
            }
            if (hash.Length != 32 || hash.Any(c => !Uri.IsHexDigit(c))) continue;

            var candidate = new LocalCatalogIdentity(version, hash);
            if (best == null || CompareGameVersions(candidate.Version, best.Version) > 0)
                best = candidate;
        }
        return best;
    }

    private static int CompareGameVersions(string left, string right)
    {
        if (Version.TryParse(left, out var leftVersion) && Version.TryParse(right, out var rightVersion))
            return leftVersion.CompareTo(rightVersion);
        return string.Compare(left, right, StringComparison.OrdinalIgnoreCase);
    }

    private static bool EnsureAddressablesFromState(RedirectState state, bool allowDownload)
    {
        if (!EnsureStateManifest(state, allowDownload)) return false;
        if (!EnsureCachedManifestCompatibility(state)) return false;
        try
        {
            var bytes = File.ReadAllBytes(StateManifestPath(state));
            EnsureAddressablesPayloads(bytes, allowDownload);
            return true;
        }
        catch (Exception ex)
        {
            Log("addressables-cache-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            return false;
        }
    }

    private static void EnsureAddressablesPayloads(byte[] manifestBytes, bool allowDownload)
    {
        if (payloadRootPath == null)
            throw new InvalidOperationException("payload root is unavailable");

        var files = ParseAddressablesManifest(manifestBytes);
        if (files.Count == 0)
            throw new InvalidDataException("manifest has no Addressables payloads");

        Directory.CreateDirectory(payloadRootPath);
        var index = 0;
        foreach (var file in files)
        {
            index++;
            var payloadPath = Path.Combine(payloadRootPath, file.PayloadSha256 + ".bundle");
            startupProgress?.Update("번역 데이터를 확인하고 있습니다.",
                index + " / " + files.Count + "  " + file.DisplayName);
            if (IsValidAddressablesPayload(payloadPath, file))
            {
                Log("addressables-cache-hit path=" + file.Path + " sha256=" + file.PayloadSha256);
                continue;
            }

            if (!allowDownload)
                throw new InvalidDataException("Addressables payload cache miss: " + file.Path);

            DownloadAddressablesPayload(file, payloadPath, index, files.Count);
        }
    }

    private static System.Collections.Generic.List<AddressablesManifestFile> ParseAddressablesManifest(byte[] bytes)
    {
        using var document = JsonDocument.Parse(bytes);
        var root = document.RootElement;
        if (RequiredInt(root, "schemaVersion") != 2)
            throw new InvalidDataException("unsupported manifest schemaVersion");

        var patch = RequiredObject(root, "patch");
        if (!string.Equals(RequiredString(patch, "route"), route, StringComparison.Ordinal) ||
            !string.Equals(RequiredString(patch, "channel"), "release", StringComparison.Ordinal))
            throw new InvalidDataException("invalid release manifest");

        var result = new System.Collections.Generic.List<AddressablesManifestFile>();
        foreach (var file in RequiredArray(root, "files").EnumerateArray())
        {
            if (!string.Equals(RequiredString(file, "target"), "addressables", StringComparison.Ordinal))
                continue;

            var path = RequiredString(file, "path").Replace('\\', '/');
            var parts = path.Split('/');
            if (parts.Length != 3 || !string.Equals(parts[2], "__data", StringComparison.Ordinal) ||
                parts.Any(part => part.Length == 0 || part == ".."))
                throw new InvalidDataException("invalid Addressables path: " + path);
            if (!string.Equals(RequiredString(file, "compression"), "gzip", StringComparison.Ordinal) ||
                !string.Equals(RequiredString(file, "operation"), "replace", StringComparison.Ordinal))
                throw new InvalidDataException("unsupported Addressables operation: " + path);

            var url = RequiredString(file, "downloadUrl");
            EnsureRepositoryReleaseUrl(url);
            var downloadSha = RequiredString(file, "downloadSha256");
            var payloadSha = RequiredString(file, "sha256");
            ValidateHex(downloadSha, 64, "Addressables download sha256");
            ValidateHex(payloadSha, 64, "Addressables payload sha256");
            var downloadSize = RequiredLong(file, "downloadSize");
            var payloadSize = RequiredLong(file, "size");
            if (downloadSize <= 0 || downloadSize > TransportLimit ||
                payloadSize <= 0 || payloadSize > PayloadLimit)
                throw new InvalidDataException("invalid Addressables size: " + path);

            result.Add(new AddressablesManifestFile(
                path, parts[0] + "/" + parts[1], url,
                downloadSha, downloadSize, payloadSha, payloadSize));
        }
        return result;
    }

    private static bool IsValidAddressablesPayload(string path, AddressablesManifestFile file)
    {
        try
        {
            if (!File.Exists(path)) return false;
            var info = new FileInfo(path);
            if (info.Length != file.PayloadSize || !HasUnityFsSignature(path)) return false;
            return string.Equals(Sha256File(path), file.PayloadSha256, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private static void DownloadAddressablesPayload(AddressablesManifestFile file, string payloadPath,
        int index, int total)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(payloadPath)!);
        var temp = payloadPath + ".tmp-" + Process.GetCurrentProcess().Id + "-" + Guid.NewGuid().ToString("N");
        try
        {
            Log("addressables-download path=" + file.Path + " url=" + file.DownloadUrl +
                " compressedBytes=" + file.DownloadSize + " payloadBytes=" + file.PayloadSize);
            var status = "패치 리소스를 다운로드하고 있습니다. (" + index + "/" + total + ")";
            startupProgress?.UpdateDownload(status, 0, file.DownloadSize);

            string transportSha;
            string payloadSha;
            long transportBytes;
            long payloadBytes = 0;
            var signature = new byte[7];
            var signatureLength = 0;

            using var cancellation = CreateDownloadCancellation();
            using (var response = Http.GetAsync(file.DownloadUrl, HttpCompletionOption.ResponseHeadersRead,
                       cancellation.Token).GetAwaiter().GetResult())
            {
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength is long contentLength &&
                    contentLength != file.DownloadSize)
                    throw new InvalidDataException("Addressables Content-Length mismatch");

                using (var network = response.Content.ReadAsStream())
                using (var compressed = new HashingReadStream(network, TransportLimit))
                using (var gzip = new GZipStream(compressed, CompressionMode.Decompress, leaveOpen: true))
                using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    1024 * 1024, FileOptions.SequentialScan))
                using (var payloadHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                {
                    var buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = ReadWithIdleTimeout(gzip, buffer, cancellation.Token)) > 0)
                    {
                        payloadBytes += read;
                        if (payloadBytes > file.PayloadSize || payloadBytes > PayloadLimit)
                            throw new InvalidDataException("Addressables payload exceeds expected size");
                        payloadHash.AppendData(buffer, 0, read);
                        if (signatureLength < signature.Length)
                        {
                            var take = Math.Min(signature.Length - signatureLength, read);
                            Buffer.BlockCopy(buffer, 0, signature, signatureLength, take);
                            signatureLength += take;
                        }
                        output.Write(buffer, 0, read);
                        startupProgress?.UpdateDownload(status, compressed.TotalBytes, file.DownloadSize);
                    }
                    while (ReadWithIdleTimeout(compressed, buffer, cancellation.Token) > 0) { }
                    output.Flush(true);
                    transportBytes = compressed.TotalBytes;
                    transportSha = compressed.FinishHash();
                    payloadSha = Convert.ToHexString(payloadHash.GetHashAndReset()).ToLowerInvariant();
                }
            }

            if (transportBytes != file.DownloadSize ||
                !string.Equals(transportSha, file.DownloadSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Addressables transport verification failed: " + file.Path);
            if (payloadBytes != file.PayloadSize ||
                !string.Equals(payloadSha, file.PayloadSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Addressables payload verification failed: " + file.Path);
            if (signatureLength != 7 || Encoding.ASCII.GetString(signature) != "UnityFS")
                throw new InvalidDataException("Addressables payload is not UnityFS: " + file.Path);

            File.Move(temp, payloadPath, true);
            Log("addressables-ready path=" + file.Path + " sha256=" + file.PayloadSha256 +
                " bytes=" + file.PayloadSize);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    private static void WriteAtomicFile(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp-" + Process.GetCurrentProcess().Id + "-" + Guid.NewGuid().ToString("N");
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(true);
            }
            File.Move(temp, path, true);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    private static void CopyFileAtomic(string source, string destination)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var temp = destination + ".tmp-" + Process.GetCurrentProcess().Id + "-" + Guid.NewGuid().ToString("N");
        try
        {
            using (var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                input.CopyTo(output);
                output.Flush(true);
            }
            File.Move(temp, destination, true);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    private static void WriteSessionMarker()
    {
        if (sessionPath == null)
            throw new InvalidOperationException("session path is unavailable");
        var state = LoadState();
        if (state == null || !state.AddressablesReady)
            throw new InvalidDataException("verified patch state is unavailable for this process");

        using var process = Process.GetCurrentProcess();
        var session = new PreloaderSession
        {
            ProcessId = process.Id,
            ProcessStartTimeUtcTicks = process.StartTime.ToUniversalTime().Ticks,
            Route = state.Route,
            ReleaseTag = state.ReleaseTag,
            ManifestSha256 = state.ManifestSha256,
            GameVersion = state.GameVersion,
            Revision = state.Revision,
            CatalogHash = state.CatalogHash,
            Ready = true,
        };
        var bytes = JsonSerializer.SerializeToUtf8Bytes(session, new JsonSerializerOptions { WriteIndented = true });
        WriteAtomicFile(sessionPath, bytes);
        Log("session-ready pid=" + session.ProcessId + " tag=" + session.ReleaseTag +
            " game=" + session.GameVersion + "/" + session.Revision + " catalog=" + session.CatalogHash);
    }

    private static void DeleteSessionMarker()
    {
        try
        {
            if (sessionPath != null && File.Exists(sessionPath)) File.Delete(sessionPath);
        }
        catch (Exception ex)
        {
            Log("session-delete-failed type=" + ex.GetType().Name + " message=" + ex.Message);
        }
    }

    private static bool HasUnityFsSignature(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var header = new byte[7];
        return stream.Read(header, 0, header.Length) == header.Length && Encoding.ASCII.GetString(header) == "UnityFS";
    }

    private static void InstallCreateFileHook(string gameRoot)
    {
        var kernel32 = GetModuleHandleW("kernel32.dll");
        if (kernel32 == IntPtr.Zero)
            throw new InvalidOperationException("GetModuleHandleW(kernel32.dll) failed: " + Marshal.GetLastWin32Error());

        var createFileW = GetProcAddress(kernel32, "CreateFileW");
        if (createFileW == IntPtr.Zero)
            throw new InvalidOperationException("GetProcAddress(CreateFileW) failed: " + Marshal.GetLastWin32Error());

        var dobbyPath = Path.Combine(gameRoot, "BepInEx", "core", "dobby.dll");
        var dobby = LoadLibraryW(dobbyPath);
        if (dobby == IntPtr.Zero)
            throw new InvalidOperationException("LoadLibraryW(dobby.dll) failed: " + Marshal.GetLastWin32Error());

        var dobbyHookPtr = GetProcAddress(dobby, "DobbyHook");
        if (dobbyHookPtr == IntPtr.Zero)
            throw new InvalidOperationException("GetProcAddress(DobbyHook) failed: " + Marshal.GetLastWin32Error());

        var dobbyHook = Marshal.GetDelegateForFunctionPointer<DobbyHookDelegate>(dobbyHookPtr);
        createFileWHook = CreateFileWHooked;
        var replacementPtr = Marshal.GetFunctionPointerForDelegate(createFileWHook);
        var result = dobbyHook(createFileW, replacementPtr, out var originalPtr);
        if (result != 0 || originalPtr == IntPtr.Zero)
            throw new InvalidOperationException("DobbyHook(CreateFileW) failed: result=" + result +
                " original=0x" + originalPtr.ToInt64().ToString("X"));

        originalCreateFileW = Marshal.GetDelegateForFunctionPointer<CreateFileWDelegate>(originalPtr);
        Log("hook-installed createFileW=0x" + createFileW.ToInt64().ToString("X") +
            " trampoline=0x" + originalPtr.ToInt64().ToString("X"));
    }

    private static IntPtr CreateFileWHooked(
        string fileName,
        uint desiredAccess,
        uint shareMode,
        IntPtr securityAttributes,
        uint creationDisposition,
        uint flagsAndAttributes,
        IntPtr templateFile)
    {
        var original = originalCreateFileW;
        if (original == null) return new IntPtr(-1);

        var actualPath = fileName;
        try
        {
            if (!string.IsNullOrEmpty(sourcePath) && !string.IsNullOrEmpty(replacementPath) &&
                IsTargetPath(fileName, sourcePath))
            {
                actualPath = replacementPath;
                Log("redirect requested=" + fileName + " actual=" + actualPath +
                    " access=0x" + desiredAccess.ToString("X") + " share=0x" + shareMode.ToString("X") +
                    " disposition=" + creationDisposition);
            }
        }
        catch (Exception ex)
        {
            Log("redirect-check-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            actualPath = fileName;
        }

        return original(actualPath, desiredAccess, shareMode, securityAttributes,
            creationDisposition, flagsAndAttributes, templateFile);
    }

    private static bool IsTargetPath(string candidate, string target)
    {
        if (string.IsNullOrWhiteSpace(candidate)) return false;
        try { return string.Equals(Path.GetFullPath(candidate), target, StringComparison.OrdinalIgnoreCase); }
        catch { return string.Equals(candidate, target, StringComparison.OrdinalIgnoreCase); }
    }

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("astral-party-korean-patch-preloader/" + AstralBuildVersion.Value);
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        client.DefaultRequestHeaders.TryAddWithoutValidation("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }

    private static byte[] DownloadBytes(string url, long maxBytes)
    {
        EnsureRepositoryReleaseUrl(url);
        using var cancellation = CreateDownloadCancellation();
        using var response = Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellation.Token)
            .GetAwaiter().GetResult();
        response.EnsureSuccessStatusCode();
        return ReadLimited(response.Content.ReadAsStream(), maxBytes, cancellation.Token);
    }

    private static CancellationTokenSource CreateDownloadCancellation()
    {
        return new CancellationTokenSource(TimeSpan.FromSeconds(DownloadOverallTimeoutSeconds));
    }

    private static int ReadWithIdleTimeout(Stream input, byte[] buffer, CancellationToken overallToken)
    {
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(overallToken);
        idle.CancelAfter(TimeSpan.FromSeconds(DownloadIdleTimeoutSeconds));
        try
        {
            return input.ReadAsync(buffer, 0, buffer.Length, idle.Token).GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (!overallToken.IsCancellationRequested)
        {
            throw new TimeoutException("download stalled for more than " + DownloadIdleTimeoutSeconds + " seconds");
        }
        catch (OperationCanceledException)
        {
            throw new TimeoutException("download exceeded " + DownloadOverallTimeoutSeconds + " seconds");
        }
    }

    private static byte[] ReadLimited(Stream input, long maxBytes, CancellationToken cancellationToken)
    {
        using var output = new MemoryStream();
        var buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = ReadWithIdleTimeout(input, buffer, cancellationToken)) > 0)
        {
            total += read;
            if (total > maxBytes) throw new InvalidDataException("response exceeds size limit");
            output.Write(buffer, 0, read);
        }
        return output.ToArray();
    }

    private static string Sha256(byte[] bytes)
    {
        using var hash = SHA256.Create();
        return Convert.ToHexString(hash.ComputeHash(bytes)).ToLowerInvariant();
    }

    private static string Sha256File(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            1024 * 1024, FileOptions.SequentialScan);
        using var hash = SHA256.Create();
        return Convert.ToHexString(hash.ComputeHash(stream)).ToLowerInvariant();
    }

    private static void EnsureRepositoryReleaseUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
            !string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(uri.Host, "github.com", StringComparison.OrdinalIgnoreCase) ||
            !uri.AbsolutePath.StartsWith("/" + Repository + "/releases/download/", StringComparison.Ordinal))
            throw new InvalidDataException("untrusted release URL: " + url);
    }

    private static JsonElement RequiredObject(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("missing object: " + name);
        return value;
    }

    private static JsonElement RequiredArray(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("missing array: " + name);
        return value;
    }

    private static string RequiredString(JsonElement element, string name)
    {
        var value = OptionalString(element, name);
        if (string.IsNullOrWhiteSpace(value)) throw new InvalidDataException("missing string: " + name);
        return value;
    }

    private static string? OptionalString(JsonElement element, string name)
    {
        return element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private static int RequiredInt(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || !value.TryGetInt32(out var parsed))
            throw new InvalidDataException("missing int: " + name);
        return parsed;
    }

    private static long RequiredLong(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || !value.TryGetInt64(out var parsed))
            throw new InvalidDataException("missing long: " + name);
        return parsed;
    }

    private static void ValidateHex(string value, int length, string label)
    {
        if (value.Length != length || value.Any(c => !Uri.IsHexDigit(c)))
            throw new InvalidDataException("invalid " + label);
    }

    private static new void Log(string message)
    {
        try
        {
            if (string.IsNullOrEmpty(logPath)) return;
            File.AppendAllText(logPath, DateTime.Now.ToString("O") + " " + message + Environment.NewLine,
                new UTF8Encoding(false));
        }
        catch { }
    }

    private sealed class StartupProgress
    {
        private const int ShowDelayMilliseconds = 350;
        private const uint WsPopup = 0x80000000;
        private const uint WsBorder = 0x00800000;
        private const uint WsChild = 0x40000000;
        private const uint WsVisible = 0x10000000;
        private const uint WsClipChildren = 0x02000000;
        private const uint WsExTopmost = 0x00000008;
        private const uint WsExToolWindow = 0x00000080;
        private const uint WsExNoActivate = 0x08000000;
        private const uint PbsSmooth = 0x00000001;
        private const int SwShowNoActivate = 4;
        private const int SwHide = 0;
        private const int SwRestore = 9;
        private const uint GwOwner = 4;
        private const int SmCxScreen = 0;
        private const int SmCyScreen = 1;
        private const uint WmQuit = 0x0012;
        private const uint WmSetFont = 0x0030;
        private const uint WmCtlColorStatic = 0x0138;
        private const uint PbmSetPos = 0x0402;
        private const uint PbmSetRange32 = 0x0406;
        private const uint IccProgressClass = 0x00000020;
        private const int DefaultGuiFont = 17;
        private const int ColorBtnFace = 15;
        private const int TransparentBackground = 1;
        private const int GwlpWndProc = -4;
        private const uint SwpNoSize = 0x0001;
        private const uint SwpNoMove = 0x0002;
        private const uint SwpShowWindow = 0x0040;
        private static readonly IntPtr HwndTopmost = new IntPtr(-1);
        private static readonly IntPtr HwndNoTopmost = new IntPtr(-2);

        [UnmanagedFunctionPointer(CallingConvention.Winapi)]
        private delegate IntPtr WindowProcDelegate(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

        [UnmanagedFunctionPointer(CallingConvention.Winapi)]
        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        private readonly object sync = new object();
        private Thread? thread;
        private bool closed;
        private uint uiThreadId;
        private IntPtr window;
        private WindowProcDelegate? windowProc;
        private IntPtr originalWindowProc;
        private IntPtr titleLabel;
        private IntPtr statusLabel;
        private IntPtr detailLabel;
        private IntPtr progressBar;
        private string status = "한글패치를 준비하고 있습니다.";
        private string detail = string.Empty;
        private int percent;
        private long lastDownloadUpdateTick;

        public void Start()
        {
            lock (sync)
            {
                if (thread != null) return;
                thread = new Thread(Run)
                {
                    IsBackground = true,
                    Name = "AstralPartyKoreanPatch.StartupProgress"
                };
                thread.Start();
            }
        }

        public void Update(string newStatus, string newDetail, int newPercent = 0)
        {
            IntPtr statusHandle;
            IntPtr detailHandle;
            IntPtr progressHandle;
            lock (sync)
            {
                if (closed) return;
                status = newStatus ?? string.Empty;
                detail = newDetail ?? string.Empty;
                percent = Math.Max(0, Math.Min(100, newPercent));
                statusHandle = statusLabel;
                detailHandle = detailLabel;
                progressHandle = progressBar;
            }

            Apply(statusHandle, detailHandle, progressHandle, status, detail, percent);
        }

        public void UpdateDownload(string statusText, long currentBytes, long totalBytes)
        {
            var now = Environment.TickCount64;
            if (currentBytes < totalBytes && now - Interlocked.Read(ref lastDownloadUpdateTick) < 100)
                return;
            Interlocked.Exchange(ref lastDownloadUpdateTick, now);

            var safeTotal = Math.Max(1L, totalBytes);
            var safeCurrent = Math.Max(0L, Math.Min(currentBytes, safeTotal));
            var progress = (int)Math.Min(100L, safeCurrent * 100L / safeTotal);
            Update(
                statusText,
                FormatMegabytes(safeCurrent) + " / " + FormatMegabytes(safeTotal),
                progress);
        }

        public void Close()
        {
            IntPtr handle;
            uint threadId;
            lock (sync)
            {
                if (closed) return;
                closed = true;
                handle = window;
                threadId = uiThreadId;
            }

            if (handle != IntPtr.Zero) ShowWindow(handle, SwHide);
            if (threadId != 0) PostThreadMessageW(threadId, WmQuit, IntPtr.Zero, IntPtr.Zero);
            ScheduleGameForegroundRestore(handle);
        }

        private static void ScheduleGameForegroundRestore(IntPtr progressWindow)
        {
            var worker = new Thread(() =>
            {
                try
                {
                    var processId = (uint)Process.GetCurrentProcess().Id;
                    for (var attempt = 0; attempt < 600; attempt++)
                    {
                        var gameWindow = FindUnityGameWindow(processId, progressWindow);
                        if (gameWindow != IntPtr.Zero)
                        {
                            var foreground = TryActivateGameWindow(gameWindow);
                            Log("game-window-foreground hwnd=0x" + gameWindow.ToInt64().ToString("X") +
                                " activated=" + foreground +
                                " foreground=0x" + GetForegroundWindow().ToInt64().ToString("X"));
                            return;
                        }
                        Thread.Sleep(100);
                    }
                    Log("game-window-foreground-timeout");
                }
                catch (Exception ex)
                {
                    Log("game-window-foreground-failed type=" + ex.GetType().Name + " message=" + ex.Message);
                }
            })
            {
                IsBackground = true,
                Name = "AstralPartyKoreanPatch.ForegroundRestore"
            };
            worker.Start();
        }

        private static IntPtr FindUnityGameWindow(uint processId, IntPtr progressWindow)
        {
            IntPtr fallback = IntPtr.Zero;
            EnumWindows((hWnd, _) =>
            {
                if (hWnd == progressWindow || !IsWindowVisible(hWnd) || GetWindow(hWnd, GwOwner) != IntPtr.Zero)
                    return true;

                GetWindowThreadProcessId(hWnd, out var ownerProcessId);
                if (ownerProcessId != processId) return true;

                var className = new StringBuilder(128);
                GetClassNameW(hWnd, className, className.Capacity);
                if (string.Equals(className.ToString(), "UnityWndClass", StringComparison.Ordinal))
                {
                    fallback = hWnd;
                    return false;
                }

                if (fallback == IntPtr.Zero) fallback = hWnd;
                return true;
            }, IntPtr.Zero);
            return fallback;
        }

        private static bool TryActivateGameWindow(IntPtr gameWindow)
        {
            ShowWindow(gameWindow, SwRestore);

            // Create an input queue for this worker thread before attaching it
            // to the foreground/game UI threads. This avoids the foreground
            // lock that made a direct SetForegroundWindow call return false.
            PeekMessageW(out _, IntPtr.Zero, 0, 0, 0);
            var currentThread = GetCurrentThreadId();
            var foregroundWindow = GetForegroundWindow();
            var foregroundThread = foregroundWindow != IntPtr.Zero
                ? GetWindowThreadProcessId(foregroundWindow, out _)
                : 0;
            var gameThread = GetWindowThreadProcessId(gameWindow, out _);

            var attachedForeground = false;
            var attachedGame = false;
            try
            {
                if (foregroundThread != 0 && foregroundThread != currentThread)
                    attachedForeground = AttachThreadInput(currentThread, foregroundThread, true);
                if (gameThread != 0 && gameThread != currentThread && gameThread != foregroundThread)
                    attachedGame = AttachThreadInput(currentThread, gameThread, true);

                BringWindowToTop(gameWindow);
                SetActiveWindow(gameWindow);
                SetFocus(gameWindow);
                if (SetForegroundWindow(gameWindow) && GetForegroundWindow() == gameWindow)
                    return true;
            }
            finally
            {
                if (attachedGame) AttachThreadInput(currentThread, gameThread, false);
                if (attachedForeground) AttachThreadInput(currentThread, foregroundThread, false);
            }

            // Fallback: change only the z-order momentarily, then immediately
            // clear TOPMOST before asking Windows to activate the window again.
            // The game does not remain always-on-top after this call.
            SetWindowPos(gameWindow, HwndTopmost, 0, 0, 0, 0,
                SwpNoMove | SwpNoSize | SwpShowWindow);
            SetWindowPos(gameWindow, HwndNoTopmost, 0, 0, 0, 0,
                SwpNoMove | SwpNoSize | SwpShowWindow);
            BringWindowToTop(gameWindow);
            SetForegroundWindow(gameWindow);
            return GetForegroundWindow() == gameWindow;
        }

        private void Run()
        {
            Thread.Sleep(ShowDelayMilliseconds);
            lock (sync)
            {
                if (closed) return;
            }

            try
            {
                var controls = new InitCommonControlsExData
                {
                    Size = (uint)Marshal.SizeOf<InitCommonControlsExData>(),
                    Icc = IccProgressClass
                };
                InitCommonControlsEx(ref controls);

                var width = 520;
                var height = 170;
                var x = Math.Max(0, (GetSystemMetrics(SmCxScreen) - width) / 2);
                var y = Math.Max(0, (GetSystemMetrics(SmCyScreen) - height) / 2);
                var parent = CreateWindowExW(
                    WsExTopmost | WsExToolWindow | WsExNoActivate,
                    "STATIC", string.Empty,
                    WsPopup | WsBorder | WsClipChildren,
                    x, y, width, height,
                    IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                if (parent == IntPtr.Zero) return;

                windowProc = ParentWindowProc;
                originalWindowProc = SetWindowLongPtrW(
                    parent, GwlpWndProc, Marshal.GetFunctionPointerForDelegate(windowProc));
                if (originalWindowProc == IntPtr.Zero)
                    throw new InvalidOperationException("failed to subclass preloader window");

                var title = CreateWindowExW(0, "STATIC", "아스트랄 파티 한글패치",
                    WsChild | WsVisible, 24, 18, 470, 24,
                    parent, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                var statusHandle = CreateWindowExW(0, "STATIC", string.Empty,
                    WsChild | WsVisible, 24, 52, 470, 22,
                    parent, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                var detailHandle = CreateWindowExW(0, "STATIC", string.Empty,
                    WsChild | WsVisible, 24, 78, 470, 20,
                    parent, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                var progressHandle = CreateWindowExW(0, "msctls_progress32", string.Empty,
                    WsChild | WsVisible | PbsSmooth, 24, 112, 470, 20,
                    parent, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);

                var font = GetStockObject(DefaultGuiFont);
                if (font != IntPtr.Zero)
                {
                    SendMessageW(title, WmSetFont, font, new IntPtr(1));
                    SendMessageW(statusHandle, WmSetFont, font, new IntPtr(1));
                    SendMessageW(detailHandle, WmSetFont, font, new IntPtr(1));
                }
                SendMessageW(progressHandle, PbmSetRange32, IntPtr.Zero, new IntPtr(100));

                string currentStatus;
                string currentDetail;
                int currentPercent;
                lock (sync)
                {
                    if (closed) return;
                    uiThreadId = GetCurrentThreadId();
                    window = parent;
                    titleLabel = title;
                    statusLabel = statusHandle;
                    detailLabel = detailHandle;
                    progressBar = progressHandle;
                    currentStatus = status;
                    currentDetail = detail;
                    currentPercent = percent;
                }

                Apply(statusHandle, detailHandle, progressHandle,
                    currentStatus, currentDetail, currentPercent);
                ShowWindow(parent, SwShowNoActivate);
                UpdateWindow(parent);

                while (GetMessageW(out var message, IntPtr.Zero, 0, 0) > 0)
                {
                    TranslateMessage(ref message);
                    DispatchMessageW(ref message);
                }
            }
            catch (Exception ex)
            {
                Log("progress-window-failed type=" + ex.GetType().Name + " message=" + ex.Message);
            }
            finally
            {
                lock (sync)
                {
                    window = IntPtr.Zero;
                    titleLabel = IntPtr.Zero;
                    statusLabel = IntPtr.Zero;
                    detailLabel = IntPtr.Zero;
                    progressBar = IntPtr.Zero;
                    uiThreadId = 0;
                    originalWindowProc = IntPtr.Zero;
                    windowProc = null;
                }
            }
        }

        private IntPtr ParentWindowProc(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam)
        {
            if (message == WmCtlColorStatic && wParam != IntPtr.Zero)
            {
                SetBkMode(wParam, TransparentBackground);
                return GetSysColorBrush(ColorBtnFace);
            }

            return originalWindowProc != IntPtr.Zero
                ? CallWindowProcW(originalWindowProc, hWnd, message, wParam, lParam)
                : DefWindowProcW(hWnd, message, wParam, lParam);
        }

        private static void Apply(IntPtr statusHandle, IntPtr detailHandle, IntPtr progressHandle,
            string currentStatus, string currentDetail, int currentPercent)
        {
            if (statusHandle != IntPtr.Zero) SetWindowTextW(statusHandle, currentStatus);
            if (detailHandle != IntPtr.Zero) SetWindowTextW(detailHandle, currentDetail);
            if (progressHandle != IntPtr.Zero)
                SendMessageW(progressHandle, PbmSetPos, new IntPtr(currentPercent), IntPtr.Zero);
        }

        private static string FormatMegabytes(long bytes)
        {
            return (bytes / 1024d / 1024d).ToString("0.0") + " MB";
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct InitCommonControlsExData
        {
            public uint Size;
            public uint Icc;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Point
        {
            public int X;
            public int Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Message
        {
            public IntPtr HWnd;
            public uint Value;
            public UIntPtr WParam;
            public IntPtr LParam;
            public uint Time;
            public Point Pt;
            public uint Private;
        }

        [DllImport("comctl32.dll")]
        private static extern bool InitCommonControlsEx(ref InitCommonControlsExData controls);

        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [DllImport("gdi32.dll")]
        private static extern IntPtr GetStockObject(int index);

        [DllImport("gdi32.dll")]
        private static extern int SetBkMode(IntPtr hdc, int mode);

        [DllImport("user32.dll")]
        private static extern IntPtr GetSysColorBrush(int index);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowExW(uint exStyle, string className, string windowName,
            uint style, int x, int y, int width, int height, IntPtr parent, IntPtr menu,
            IntPtr instance, IntPtr parameter);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool SetWindowTextW(IntPtr handle, string text);

        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
        private static extern IntPtr SetWindowLongPtrW(IntPtr hWnd, int index, IntPtr newLong);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr CallWindowProcW(
            IntPtr previousWindowProc, IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern IntPtr SendMessageW(IntPtr handle, uint message, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr handle, int command);

        [DllImport("user32.dll")]
        private static extern bool UpdateWindow(IntPtr handle);

        [DllImport("user32.dll")]
        private static extern int GetSystemMetrics(int index);

        [DllImport("user32.dll")]
        private static extern int GetMessageW(out Message message, IntPtr handle, uint min, uint max);

        [DllImport("user32.dll")]
        private static extern bool TranslateMessage(ref Message message);

        [DllImport("user32.dll")]
        private static extern IntPtr DispatchMessageW(ref Message message);

        [DllImport("user32.dll")]
        private static extern bool PostThreadMessageW(uint threadId, uint message, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr GetWindow(IntPtr hWnd, uint command);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassNameW(IntPtr hWnd, StringBuilder className, int maxCount);

        [DllImport("user32.dll")]
        private static extern bool BringWindowToTop(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);

        [DllImport("user32.dll")]
        private static extern IntPtr SetActiveWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr SetFocus(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter,
            int x, int y, int width, int height, uint flags);

        [DllImport("user32.dll")]
        private static extern bool PeekMessageW(out Message message, IntPtr hWnd,
            uint minFilter, uint maxFilter, uint removeMessage);
    }

    private static SteamRouteLayout DetectSteamRoute(string gameRoot, string processName)
    {
        var directoryName = Path.GetFileName(Path.TrimEndingDirectorySeparator(gameRoot));
        if (string.Equals(directoryName, "8vJXnINT", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(processName, "AstralParty_INT", StringComparison.OrdinalIgnoreCase))
            return new SteamRouteLayout("INT_STEAM", "AstralParty_INT_Data", "AstralParty_INT");
        if (string.Equals(directoryName, "8vJXn6CN", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(processName, "AstralParty_CN", StringComparison.OrdinalIgnoreCase))
            return new SteamRouteLayout("CN_STEAM", "AstralParty_CN_Data", "AstralParty_CN");
        throw new InvalidOperationException("unsupported Astral Party Steam install: " + gameRoot);
    }

    private sealed class SteamRouteLayout
    {
        public SteamRouteLayout(string route, string dataDirectory, string localLowDirectory)
        {
            Route = route;
            DataDirectory = dataDirectory;
            LocalLowDirectory = localLowDirectory;
        }

        public string Route { get; }
        public string DataDirectory { get; }
        public string LocalLowDirectory { get; }
    }

    private sealed class ReleaseInfo
    {
        public ReleaseInfo(string tag, string manifestUrl, string manifestDigest)
        {
            Tag = tag;
            ManifestUrl = manifestUrl;
            ManifestDigest = manifestDigest;
        }
        public string Tag { get; }
        public string ManifestUrl { get; }
        public string ManifestDigest { get; }
    }

    private sealed class LatestResult
    {
        public LatestResult(bool notModified, string? etag, ReleaseInfo? release)
        {
            NotModified = notModified;
            Etag = etag;
            Release = release;
        }
        public bool NotModified { get; }
        public string? Etag { get; }
        public ReleaseInfo? Release { get; }
    }

    private sealed class AddressablesManifestFile
    {
        public AddressablesManifestFile(string path, string displayName, string downloadUrl,
            string downloadSha256, long downloadSize, string payloadSha256, long payloadSize)
        {
            Path = path;
            DisplayName = displayName;
            DownloadUrl = downloadUrl;
            DownloadSha256 = downloadSha256;
            DownloadSize = downloadSize;
            PayloadSha256 = payloadSha256;
            PayloadSize = payloadSize;
        }

        public string Path { get; }
        public string DisplayName { get; }
        public string DownloadUrl { get; }
        public string DownloadSha256 { get; }
        public long DownloadSize { get; }
        public string PayloadSha256 { get; }
        public long PayloadSize { get; }
    }

    private sealed class LocalCatalogIdentity
    {
        public LocalCatalogIdentity(string version, string hash)
        {
            Version = version;
            Hash = hash;
        }
        public string Version { get; }
        public string Hash { get; }
    }

    private sealed class PreloaderSession
    {
        public int ProcessId { get; set; }
        public long ProcessStartTimeUtcTicks { get; set; }
        public string Route { get; set; } = string.Empty;
        public string ReleaseTag { get; set; } = string.Empty;
        public string ManifestSha256 { get; set; } = string.Empty;
        public string GameVersion { get; set; } = string.Empty;
        public string Revision { get; set; } = string.Empty;
        public string CatalogHash { get; set; } = string.Empty;
        public bool Ready { get; set; }
    }

    private sealed class RedirectState
    {
        public string Route { get; set; } = string.Empty;
        public string ReleaseTag { get; set; } = string.Empty;
        public string LatestEtag { get; set; } = string.Empty;
        public string ManifestUrl { get; set; } = string.Empty;
        public string ManifestSha256 { get; set; } = string.Empty;
        public string GameVersion { get; set; } = string.Empty;
        public string Revision { get; set; } = string.Empty;
        public string CatalogHash { get; set; } = string.Empty;
        public string DownloadUrl { get; set; } = string.Empty;
        public string DownloadSha256 { get; set; } = string.Empty;
        public long DownloadSize { get; set; }
        public string PayloadSha256 { get; set; } = string.Empty;
        public long PayloadSize { get; set; }
        public string SourceSha256 { get; set; } = string.Empty;
        public long SourceSize { get; set; }
        public long SourceWriteTimeUtcTicks { get; set; }
        public long ReplacementWriteTimeUtcTicks { get; set; }
        public bool AddressablesReady { get; set; }
    }

    private sealed class HashingReadStream : Stream
    {
        private readonly Stream inner;
        private readonly IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        private readonly long maxBytes;
        private bool finished;
        private string? finalHash;

        public HashingReadStream(Stream inner, long maxBytes)
        {
            this.inner = inner;
            this.maxBytes = maxBytes;
        }

        public long TotalBytes { get; private set; }
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var read = inner.Read(buffer, offset, count);
            Record(buffer, offset, read);
            return read;
        }

        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            var read = await inner.ReadAsync(buffer, offset, count, cancellationToken).ConfigureAwait(false);
            Record(buffer, offset, read);
            return read;
        }

        private void Record(byte[] buffer, int offset, int read)
        {
            if (read <= 0) return;
            TotalBytes += read;
            if (TotalBytes > maxBytes) throw new InvalidDataException("compressed response exceeds size limit");
            hash.AppendData(buffer, offset, read);
        }

        public string FinishHash()
        {
            if (!finished)
            {
                finalHash = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
                finished = true;
            }
            return finalHash!;
        }

        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                hash.Dispose();
                inner.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
