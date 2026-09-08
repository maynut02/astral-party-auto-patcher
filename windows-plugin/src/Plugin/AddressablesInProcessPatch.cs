using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using BepInEx;
using BepInEx.Unity.IL2CPP;
using HarmonyLib;
using UnityEngine;
using UnityEngine.UI;

// Same-process Addressables payload redirect.
//
// The preloader owns release discovery, download, compatibility checks and
// verification. This plugin accepts only the current-process preloader session
// and redirects AssetBundleResource.GetLoadInfo to already verified local
// payloads. The original Addressables provider still owns the async operation
// and dependency bookkeeping; only the path and LoadType are changed.
[BepInPlugin(PluginGuid, PluginName, PluginVersion)]
public sealed class AddressablesInProcessPatch : BasePlugin
{
    public const string PluginGuid = "astral-party.korean-patch.addressables-in-process";
    public const string PluginName = "Astral Party Korean Addressables Patch";
    public const string PluginVersion = "1.1.0";

    private const string Repository = "maynut02/astral-party-korean-patch";
    private const string ResourceTypeName =
        "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleResource";
    private const string PayloadDirectoryName = "payloads";
    private const int PreparationTimeoutSeconds = 45;
    private const long TransportLimit = 64 * 1024 * 1024;
    private static readonly object Sync = new object();
    private static readonly List<PayloadEntry> Entries = new List<PayloadEntry>();
    private static readonly object UiSync = new object();
    private static StreamWriter? Writer;
    private static int PreparationState; // 0 = running, 1 = ready, 2 = failed
    private static Task? PreparationTask;
    private static string PreparedGameVersion = string.Empty;
    private static string PreparedRevision = string.Empty;
    private static string UiLatestVersion = "확인 중";
    private static string UiInstalledVersion = "확인 중";
    private static string UiGameVersion = "-";
    private static string UiStatus = "플러그인 시작 중";
    private static string UiDetail = "릴리스 정보를 확인하고 있습니다.";
    private static readonly HashSet<string> ConnectedEntries =
        new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    private static string ActiveRoute = string.Empty;
    private static string ExpectedGameVersion = string.Empty;
    private static string ExpectedCatalogHash = string.Empty;
    private static int RuntimeRedirectDisabled;
    private Harmony? harmony;

    public override void Load()
    {
        try
        {
            var pluginRoot = Path.Combine(Paths.PluginPath, "AstralPartyKoreanPatch");
            var root = Path.Combine(Paths.BepInExRootPath, "AstralPartyKoreanPatch");
            Directory.CreateDirectory(pluginRoot);
            Directory.CreateDirectory(root);
            MigrateLegacyData(pluginRoot, root);
            Writer = new StreamWriter(Path.Combine(root, "addressables-patch.jsonl"), true,
                new UTF8Encoding(false)) { AutoFlush = true };
            LoadCachedUi(root);

            var prefix = typeof(AddressablesInProcessPatch).GetMethod(
                nameof(GetLoadInfoPrefix), BindingFlags.Static | BindingFlags.NonPublic);
            if (prefix == null) throw new MissingMethodException(nameof(GetLoadInfoPrefix));

            var resourceType = FindType(ResourceTypeName);
            if (resourceType == null)
            {
                Event("PATCH_DISABLED", "AssetBundleResource type was not found");
                return;
            }

            harmony = new Harmony(PluginGuid);
            var methods = resourceType.GetMethods(BindingFlags.Public | BindingFlags.NonPublic |
                                                   BindingFlags.Instance | BindingFlags.Static)
                .Where(m => string.Equals(m.Name, "GetLoadInfo", StringComparison.Ordinal))
                .ToArray();
            if (methods.Length == 0)
            {
                Event("PATCH_DISABLED", "GetLoadInfo method was not found");
                return;
            }

            foreach (var method in methods)
            {
                try
                {
                    harmony.Patch(method, prefix: new HarmonyMethod(prefix));
                    Event("PATCHED", MethodKey(method) + " [release redirect]");
                }
                catch (Exception ex)
                {
                    Event("PATCH_FAILED", MethodKey(method) + " error=" + ex.GetType().Name + ":" + ex.Message);
                }
            }

            // Use the game's existing EventSystem.Update as a safe main-thread
            // polling point for the close button. This avoids UnityEvent
            // delegate conversion, which can re-enter Il2CppInterop metadata
            // injection in HybridCLR builds.
            var eventSystemType = FindType("UnityEngine.EventSystems.EventSystem");
            var update = eventSystemType?.GetMethod("Update",
                BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            if (update != null)
            {
                try
                {
                    var inputPrefix = typeof(AddressablesInProcessPatch).GetMethod(
                        nameof(EventSystemUpdatePrefix), BindingFlags.Static | BindingFlags.NonPublic);
                    if (inputPrefix == null) throw new MissingMethodException(nameof(EventSystemUpdatePrefix));
                    harmony.Patch(update, prefix: new HarmonyMethod(inputPrefix));
                    Event("INPUT_PATCHED", MethodKey(update) + " [overlay polling]");
                }
                catch (Exception ex)
                {
                    Event("INPUT_DISABLED", ex.GetType().Name + ":" + ex.Message);
                }
            }
            else
            {
                Event("INPUT_UNAVAILABLE", "EventSystem.Update was not found; Escape/click close is unavailable");
            }

            // Some game controls read UnityEngine.Input directly instead of
            // using EventSystem raycasts. Suppress mouse-down/held states while
            // the pointer is over the modal overlay so those controls cannot
            // reuse the same click underneath it.
            var mousePrefix = typeof(AddressablesInProcessPatch).GetMethod(
                nameof(InputMouseButtonPrefix), BindingFlags.Static | BindingFlags.NonPublic);
            var inputType = FindType("UnityEngine.Input");
            if (mousePrefix != null && inputType != null)
            {
                foreach (var mouseMethodName in new[] { "GetMouseButtonDown", "GetMouseButton" })
                {
                    var mouseMethod = inputType.GetMethod(mouseMethodName,
                        BindingFlags.Public | BindingFlags.Static, null,
                        new[] { typeof(int) }, null);
                    if (mouseMethod == null) continue;
                    try
                    {
                        harmony.Patch(mouseMethod, prefix: new HarmonyMethod(mousePrefix));
                        Event("INPUT_MOUSE_PATCHED", MethodKey(mouseMethod) +
                              " [overlay raw-input blocker]");
                    }
                    catch (Exception ex)
                    {
                        Event("INPUT_MOUSE_PATCH_FAILED", MethodKey(mouseMethod) +
                              " error=" + ex.GetType().Name + ":" + ex.Message);
                    }
                }
            }

            // Local manifest/payload verification runs off the Unity thread.
            // A target GetLoadInfo call waits until this task has either accepted
            // the current-process preloader cache or failed open to the original path.
            PreparationTask = Task.Run(() => PrepareRelease(root));
            // The visual overlay is created lazily from the existing
            // Addressables main-thread callback. It uses only built-in Unity
            // UI components; no plugin-defined MonoBehaviour is injected.
            Event("OVERLAY_PENDING", "built-in UI overlay will initialize on the first Addressables callback");
            SetUi("패치 파일 확인 중", "Preloader가 준비한 로컬 패치 파일을 확인하고 있습니다.", null, null, null);
            Event("STARTING", "repo=" + Repository + ",route=preloader-session,source=preloader-cache");
            Log.LogInfo("Astral Party Korean Addressables patch loaded; local payload preparation started");
        }
        catch (Exception ex)
        {
            Volatile.Write(ref PreparationState, 2);
            Event("LOAD_FAILED", ex.GetType().Name + ":" + ex.Message);
            Log.LogError("Addressables patch failed to initialize: " + ex);
        }
    }

    private static void MigrateLegacyData(string pluginRoot, string sharedRoot)
    {
        try
        {
            foreach (var directoryName in new[] { "cache", "payloads" })
            {
                var source = Path.Combine(pluginRoot, directoryName);
                var destination = Path.Combine(sharedRoot, directoryName);
                if (Directory.Exists(source) && !Directory.Exists(destination))
                {
                    Directory.Move(source, destination);
                }
            }

            foreach (var fileName in new[]
                     {
                         "patched-data.unity3d",
                         "data-unity3d-state.json",
                         "addressables-patch.jsonl"
                     })
            {
                var source = Path.Combine(pluginRoot, fileName);
                var destination = Path.Combine(sharedRoot, fileName);
                if (File.Exists(source) && !File.Exists(destination))
                {
                    File.Move(source, destination);
                }
            }
        }
        catch (Exception ex)
        {
            try
            {
                BepInEx.Logging.Logger.CreateLogSource(PluginName).LogWarning(
                    "Legacy patch data migration skipped: " + ex.Message);
            }
            catch { }
        }
    }

    private static bool TryLoadPreloaderManifest(string root, out byte[] manifestBytes,
        out string releaseTag, out string manifestSha256, out string activeRoute, out string gameVersion,
        out string catalogHash)
    {
        manifestBytes = Array.Empty<byte>();
        releaseTag = string.Empty;
        manifestSha256 = string.Empty;
        activeRoute = string.Empty;
        gameVersion = string.Empty;
        catalogHash = string.Empty;
        try
        {
            var statePath = Path.Combine(root, "data-unity3d-state.json");
            var sessionPath = Path.Combine(root, "preloader-session.json");
            if (!File.Exists(statePath) || !File.Exists(sessionPath)) return false;

            using var stateDocument = JsonDocument.Parse(File.ReadAllBytes(statePath));
            var state = stateDocument.RootElement;
            releaseTag = StateString(state, "ReleaseTag", "releaseTag");
            var stateRoute = StateString(state, "Route", "route");
            var expectedManifestSha = StateString(state, "ManifestSha256", "manifestSha256");
            var stateGameVersion = StateString(state, "GameVersion", "gameVersion");
            var stateCatalogHash = StateString(state, "CatalogHash", "catalogHash").ToLowerInvariant();
            var addressablesReady = state.TryGetProperty("AddressablesReady", out var readyValue) &&
                                    readyValue.ValueKind == JsonValueKind.True;
            if (string.IsNullOrWhiteSpace(releaseTag) || !IsSupportedRoute(stateRoute) ||
                string.IsNullOrWhiteSpace(expectedManifestSha) || string.IsNullOrWhiteSpace(stateGameVersion) ||
                string.IsNullOrWhiteSpace(stateCatalogHash) || !addressablesReady)
                return false;
            ValidateHex(expectedManifestSha, 64, "preloader manifest sha256");
            ValidateHex(stateCatalogHash, 32, "preloader catalog hash");

            using var sessionDocument = JsonDocument.Parse(File.ReadAllBytes(sessionPath));
            var session = sessionDocument.RootElement;
            var sessionReady = session.TryGetProperty("Ready", out var sessionReadyValue) &&
                               sessionReadyValue.ValueKind == JsonValueKind.True;
            if (!sessionReady ||
                !session.TryGetProperty("ProcessId", out var processIdValue) ||
                !processIdValue.TryGetInt32(out var processId) ||
                !session.TryGetProperty("ProcessStartTimeUtcTicks", out var startTicksValue) ||
                !startTicksValue.TryGetInt64(out var processStartTimeUtcTicks))
                return false;

            using (var process = Process.GetCurrentProcess())
            {
                if (processId != process.Id ||
                    processStartTimeUtcTicks != process.StartTime.ToUniversalTime().Ticks)
                    return false;
            }

            var sessionRoute = StateString(session, "Route", "route");
            var sessionReleaseTag = StateString(session, "ReleaseTag", "releaseTag");
            var sessionManifestSha = StateString(session, "ManifestSha256", "manifestSha256");
            var sessionGameVersion = StateString(session, "GameVersion", "gameVersion");
            var sessionCatalogHash = StateString(session, "CatalogHash", "catalogHash").ToLowerInvariant();
            if (!string.Equals(sessionRoute, stateRoute, StringComparison.Ordinal) ||
                !string.Equals(sessionReleaseTag, releaseTag, StringComparison.Ordinal) ||
                !string.Equals(sessionManifestSha, expectedManifestSha, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(sessionGameVersion, stateGameVersion, StringComparison.Ordinal) ||
                !string.Equals(sessionCatalogHash, stateCatalogHash, StringComparison.OrdinalIgnoreCase))
                return false;

            var manifestPath = Path.Combine(root, "releases", expectedManifestSha.ToLowerInvariant(),
                "manifest.json");
            if (!File.Exists(manifestPath)) return false;
            manifestBytes = File.ReadAllBytes(manifestPath);
            manifestSha256 = Sha256(manifestBytes);
            if (!string.Equals(manifestSha256, expectedManifestSha, StringComparison.OrdinalIgnoreCase))
                return false;

            activeRoute = sessionRoute;
            gameVersion = sessionGameVersion;
            catalogHash = sessionCatalogHash;
            return true;
        }
        catch
        {
            manifestBytes = Array.Empty<byte>();
            releaseTag = string.Empty;
            manifestSha256 = string.Empty;
            activeRoute = string.Empty;
            gameVersion = string.Empty;
            catalogHash = string.Empty;
            return false;
        }
    }

    private static string StateString(JsonElement state, string primary, string fallback)
    {
        if (state.TryGetProperty(primary, out var value) && value.ValueKind == JsonValueKind.String)
            return value.GetString() ?? string.Empty;
        if (state.TryGetProperty(fallback, out value) && value.ValueKind == JsonValueKind.String)
            return value.GetString() ?? string.Empty;
        return string.Empty;
    }

    private static bool IsSupportedRoute(string route)
    {
        return string.Equals(route, "INT_STEAM", StringComparison.Ordinal) ||
               string.Equals(route, "CN_STEAM", StringComparison.Ordinal);
    }

    private static string RouteLocalLowDirectory(string route)
    {
        if (string.Equals(route, "INT_STEAM", StringComparison.Ordinal)) return "AstralParty_INT";
        if (string.Equals(route, "CN_STEAM", StringComparison.Ordinal)) return "AstralParty_CN";
        throw new InvalidDataException("unsupported Steam route: " + route);
    }

    private static Type? FindType(string fullName)
    {
        foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
        {
            try
            {
                var type = assembly.GetType(fullName, false);
                if (type != null) return type;
            }
            catch { }
        }
        return null;
    }

    private static void PrepareRelease(string root)
    {
        try
        {
            if (!TryLoadPreloaderManifest(root, out var manifestBytes, out var releaseTag,
                    out var manifestSha256, out var sessionRoute, out var sessionGameVersion,
                    out var sessionCatalogHash))
                throw new InvalidDataException("preloader did not provide a complete verified patch cache");

            SetUi("패치 정보 확인 완료", "Preloader가 준비한 최신 패치를 사용합니다.",
                releaseTag, null, null);
            Event("PRELOADER_MANIFEST_HIT", "tag=" + releaseTag + ",sha256=" + manifestSha256);

            var manifest = ParseManifest(manifestBytes, sessionRoute);
            if (!string.Equals(manifest.GameVersion, sessionGameVersion, StringComparison.Ordinal) ||
                !string.Equals(manifest.CatalogHash, sessionCatalogHash, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("preloader session game identity does not match manifest");
            SetUi("패치 파일 확인 중", "로컬 Addressables payload를 연결합니다.",
                manifest.PatchVersion, null, manifest.GameVersion + " / revision " + manifest.Revision);
            var payloadRoot = Path.Combine(root, PayloadDirectoryName);
            var prepared = new List<PayloadEntry>();

            foreach (var file in manifest.Files)
            {
                if (!string.Equals(file.Target, "addressables", StringComparison.Ordinal))
                    continue;

                var pathParts = file.Path.Replace('\\', '/').Split('/');
                if (pathParts.Length != 3 || !string.Equals(pathParts[2], "__data",
                                                             StringComparison.Ordinal))
                    throw new InvalidDataException("addressables path is not bundle/hash/__data: " + file.Path);

                var payloadPath = Path.Combine(payloadRoot, file.PayloadSha256 + ".bundle");
                if (!File.Exists(payloadPath) || new FileInfo(payloadPath).Length != file.PayloadSize)
                    throw new InvalidDataException("preloader payload is missing or has wrong size: " + file.Path);
                EnsureUnityFs(payloadPath);
                prepared.Add(new PayloadEntry(pathParts[0], pathParts[1], payloadPath));
                Event("PAYLOAD_READY_LOCAL", file.Path + " sha256=" + file.PayloadSha256);
            }

            if (prepared.Count == 0)
                throw new InvalidDataException("release has no Addressables files");

            lock (Sync)
            {
                Entries.Clear();
                Entries.AddRange(prepared);
                ConnectedEntries.Clear();
                ActiveRoute = sessionRoute;
                PreparedGameVersion = manifest.GameVersion;
                PreparedRevision = manifest.Revision;
                ExpectedGameVersion = sessionGameVersion;
                ExpectedCatalogHash = sessionCatalogHash;
                Volatile.Write(ref RuntimeRedirectDisabled, 0);
            }
            Event("RELEASE_READY", "route=" + sessionRoute + ",tag=" + manifest.PatchVersion + ",game=" +
                  manifest.GameVersion + "/" + manifest.Revision + ",catalog=" +
                  manifest.CatalogHash + ",entries=" + prepared.Count + ",source=preloader-cache");
            SetUi("한글패치 준비 완료", "Preloader가 준비한 로컬 번들을 사용합니다.",
                manifest.PatchVersion, manifest.PatchVersion,
                manifest.GameVersion + " / revision " + manifest.Revision);
            Volatile.Write(ref PreparationState, 1);
        }
        catch (Exception ex)
        {
            Volatile.Write(ref PreparationState, 2);
            SetUi("원본 리소스 사용", "Preloader 패치 준비 실패: " + ex.Message, null, null, null);
            Event("RELEASE_FAILED", ex.GetType().Name + ":" + ex.Message);
            try { BepInEx.Logging.Logger.CreateLogSource(PluginName).LogError(ex); }
            catch { }
        }
    }

    private static ReleaseManifest ParseManifest(byte[] bytes, string expectedRoute)
    {
        using (var document = JsonDocument.Parse(bytes))
        {
            var root = document.RootElement;
            if (RequiredInt(root, "schemaVersion") != 2)
                throw new InvalidDataException("unsupported manifest schemaVersion");

            var patch = RequiredObject(root, "patch");
            var route = RequiredString(patch, "route");
            if (!string.Equals(route, expectedRoute, StringComparison.Ordinal))
                throw new InvalidDataException("manifest route mismatch: " + route + " != " + expectedRoute);
            if (!string.Equals(RequiredString(patch, "channel"), "release",
                                 StringComparison.Ordinal))
                throw new InvalidDataException("manifest is not a release");
            var patchVersion = RequiredString(patch, "version");

            var game = RequiredObject(root, "game");
            var version = RequiredString(game, "version");
            var revision = RequiredString(game, "revision");
            var catalogHash = RequiredString(game, "catalogHash");
            ValidateHex(catalogHash, 32, "catalog hash");

            var files = RequiredArray(root, "files");
            var parsed = new List<ManifestFile>();
            foreach (var file in files.EnumerateArray())
            {
                var path = RequiredString(file, "path").Replace('\\', '/');
                if (path.StartsWith("/", StringComparison.Ordinal) ||
                    path.Split('/').Any(part => part == ".." || part.Length == 0))
                    throw new InvalidDataException("unsafe manifest path: " + path);
                var url = RequiredString(file, "downloadUrl");
                EnsureGitHubUrl(url);
                if (!string.Equals(RequiredString(file, "compression"), "gzip",
                                   StringComparison.Ordinal))
                    throw new InvalidDataException("unsupported compression for " + path);
                if (!string.Equals(RequiredString(file, "operation"), "replace",
                                   StringComparison.Ordinal))
                    throw new InvalidDataException("unsupported operation for " + path);
                var downloadSha = RequiredString(file, "downloadSha256");
                ValidateHex(downloadSha, 64, "download sha256");
                var payloadSha = RequiredString(file, "sha256");
                ValidateHex(payloadSha, 64, "payload sha256");
                var downloadSize = RequiredLong(file, "downloadSize");
                var payloadSize = RequiredLong(file, "size");
                if (downloadSize <= 0 || downloadSize > TransportLimit || payloadSize <= 0)
                    throw new InvalidDataException("invalid manifest size for " + path);
                parsed.Add(new ManifestFile(RequiredString(file, "target"), path, url,
                    downloadSha, downloadSize, payloadSha, payloadSize));
            }

            return new ReleaseManifest(patchVersion, version, revision, catalogHash,
                parsed);
        }
    }

    private static void EnsureUnityFs(string path)
    {
        using (var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            var header = new byte[7];
            if (input.Read(header, 0, header.Length) != header.Length ||
                Encoding.ASCII.GetString(header) != "UnityFS")
                throw new InvalidDataException("payload is not a UnityFS bundle: " + path);
        }
    }

    // GetLoadInfo has two overloads. In both, the final two arguments are
    // `ref LoadType` and `ref string path`; returning false skips Unity's
    // original path calculation and makes LoadLocalBundle open our verified
    // payload.
    private static bool GetLoadInfoPrefix(object __instance, object[] __args)
    {
        try
        {
            // GetLoadInfo is reached on Unity's main thread. Initialize and
            // refresh the visual status here instead of registering a custom
            // MonoBehaviour during chainloader startup.
            OverlayUi.TryRefresh();
            var identity = ReadIdentity(__instance, __args);
            // Wait before the first provider path is calculated. This also
            // covers bundles whose interop identity is not populated yet;
            // otherwise a target could be opened remotely before the release
            // worker has finished verifying it.
            if (Volatile.Read(ref PreparationState) == 0)
                WaitForPreparation();

            OverlayUi.TryRefresh();

            var entry = FindEntry(identity);
            if (entry == null || !File.Exists(entry.PayloadPath)) return true;
            if (!IsRuntimeGameCompatible()) return true;
            if (__args == null || __args.Length < 2) return true;

            var loadTypeIndex = __args.Length - 2;
            var pathIndex = __args.Length - 1;
            var loadType = __args[loadTypeIndex];
            if (loadType == null) return true;
            __args[loadTypeIndex] = Enum.Parse(loadType.GetType(), "Local");
            __args[pathIndex] = entry.PayloadPath;

            int connectedCount;
            int totalEntries;
            lock (Sync)
            {
                ConnectedEntries.Add(entry.Key);
                connectedCount = ConnectedEntries.Count;
                totalEntries = Entries.Count;
            }
            if (connectedCount >= totalEntries && totalEntries > 0)
                SetUi("패치 리소스 연결 완료", "한글패치 리소스가 게임에 연결되었습니다.", null, null, null);
            else
                SetUi("패치 리소스 연결 중", "한글패치 리소스를 게임에 연결하고 있습니다.", null, null, null);
            OverlayUi.TryRefresh();
            Event("REDIRECTED", identity + " path=" + entry.PayloadPath +
                " connected=" + connectedCount + "/" + totalEntries);
            return false;
        }
        catch (Exception ex)
        {
            Event("REDIRECT_FAILED", ex.GetType().Name + ":" + ex.Message);
            return true;
        }
    }

    private static bool IsRuntimeGameCompatible()
    {
        if (Volatile.Read(ref RuntimeRedirectDisabled) != 0) return false;
        try
        {
            var runtimeVersion = Application.version ?? string.Empty;
            if (!string.Equals(runtimeVersion, ExpectedGameVersion, StringComparison.Ordinal))
                return DisableRuntimeRedirect("game version changed: runtime=" + runtimeVersion +
                    " patch=" + ExpectedGameVersion);

            var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            var localLowDirectory = RouteLocalLowDirectory(ActiveRoute);
            var catalogPath = Path.Combine(userProfile, "AppData", "LocalLow", "feimo",
                localLowDirectory, "com.unity.addressables", "catalog_" + runtimeVersion + ".hash");
            if (!File.Exists(catalogPath))
                return DisableRuntimeRedirect("runtime catalog hash is missing: " + catalogPath);

            var runtimeCatalogHash = File.ReadAllText(catalogPath).Trim().ToLowerInvariant();
            if (runtimeCatalogHash.Length != 32 || runtimeCatalogHash.Any(c => !Uri.IsHexDigit(c)))
                return DisableRuntimeRedirect("runtime catalog hash is invalid");
            if (!string.Equals(runtimeCatalogHash, ExpectedCatalogHash, StringComparison.OrdinalIgnoreCase))
                return DisableRuntimeRedirect("catalog changed after preloader: runtime=" + runtimeCatalogHash +
                    " patch=" + ExpectedCatalogHash);
            return true;
        }
        catch (Exception ex)
        {
            return DisableRuntimeRedirect(ex.GetType().Name + ":" + ex.Message);
        }
    }

    private static bool DisableRuntimeRedirect(string reason)
    {
        if (Interlocked.Exchange(ref RuntimeRedirectDisabled, 1) == 0)
        {
            lock (Sync)
            {
                Entries.Clear();
                ConnectedEntries.Clear();
            }
            Volatile.Write(ref PreparationState, 2);
            SetUi("원본 리소스 사용", "게임 리소스가 변경되어 한글패치를 적용하지 않습니다.", null, null, null);
            Event("RUNTIME_COMPATIBILITY_FAILED", reason);
            OverlayUi.TryRefresh();
        }
        return false;
    }

    private static bool EventSystemUpdatePrefix()
    {
        // A close-button click is consumed before Unity's EventSystem can
        // dispatch the same pointer event to a game element underneath it.
        return !OverlayUi.PollInput();
    }

    private static bool InputMouseButtonPrefix(int button, ref bool __result)
    {
        if (button == 0 && OverlayUi.ShouldBlockRawMouseInput())
        {
            __result = false;
            return false;
        }
        return true;
    }

    private static void WaitForPreparation()
    {
        var task = PreparationTask;
        if (task == null) return;
        try
        {
            if (!task.Wait(TimeSpan.FromSeconds(PreparationTimeoutSeconds)))
                Event("PREPARE_TIMEOUT", "seconds=" + PreparationTimeoutSeconds);
        }
        catch (Exception ex)
        {
            Event("PREPARE_WAIT_FAILED", ex.GetType().Name + ":" + ex.Message);
        }
    }

    private static PayloadEntry? FindEntry(BundleIdentity identity)
    {
        if (!MatchesPreparedGame(identity)) return null;
        lock (Sync)
        {
            foreach (var entry in Entries)
            {
                if (entry.Matches(identity)) return entry;
            }

            // In the current IL2CPP wrapper AssetBundleResource.GetLoadInfo
            // often exposes the catalog hash only in IResourceLocation's
            // PrimaryKey/InternalId (for example `.../212\\<hash>.bundle`),
            // while m_Options.BundleName/Hash are still null. The hash is the
            // exact cache identity from the manifest, so use it as a strict
            // fallback rather than allowing a broad key/name match.
            var text = (identity.InternalId ?? string.Empty) + "|" +
                       (identity.TransformedInternalId ?? string.Empty) + "|" +
                       (identity.PrimaryKey ?? string.Empty);
            foreach (var entry in Entries)
            {
                if (!string.IsNullOrEmpty(entry.Hash) &&
                    text.IndexOf(entry.Hash, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    Event("IDENTITY_HASH_FALLBACK", identity + " hash=" + entry.Hash);
                    return entry;
                }
            }
        }
        return null;
    }

    private static bool MatchesPreparedGame(BundleIdentity identity)
    {
        string version;
        string revision;
        lock (Sync)
        {
            version = PreparedGameVersion;
            revision = PreparedRevision;
        }
        if (string.IsNullOrEmpty(version) || string.IsNullOrEmpty(revision)) return true;

        // The runtime location normally contains .../<game version>/<revision>/....
        // If it does, require the manifest to describe that exact game build.
        // Some Addressables interop paths expose only the bundle options; those
        // are still protected by the exact bundleName/hash match below.
        var text = (identity.InternalId ?? string.Empty) + "|" +
                   (identity.TransformedInternalId ?? string.Empty);
        var normalized = text.Replace('\\', '/');
        var expected = "/" + version + "/" + revision + "/";
        if (normalized.IndexOf("/" + version + "/", StringComparison.OrdinalIgnoreCase) < 0)
            return true;
        return normalized.IndexOf(expected, StringComparison.OrdinalIgnoreCase) >= 0;
    }

    private static BundleIdentity ReadIdentity(object resource, object[] args)
    {
        var options = ReadMember(resource, "m_Options") ?? ReadMember(resource, "Options");
        var bundleName = ReadString(options, "BundleName") ?? ReadString(options, "m_BundleName");
        var hash = ReadString(options, "Hash") ?? ReadString(options, "m_Hash");
        var handle = ReadMember(resource, "m_ProvideHandle") ?? ReadMember(resource, "ProvideHandle");
        var location = ReadMember(handle, "Location");
        var key = ReadString(location, "PrimaryKey");
        var internalId = ReadString(location, "InternalId");
        var transformed = ReadString(resource, "m_TransformedInternalId") ??
                          ReadString(resource, "TransformedInternalId");

        if ((string.IsNullOrEmpty(bundleName) || string.IsNullOrEmpty(hash)) && args != null)
        {
            foreach (var arg in args)
            {
                if (arg == null) continue;
                var name = arg.GetType().FullName ?? string.Empty;
                if (name.IndexOf("IResourceLocation", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    key = ReadString(arg, "PrimaryKey") ?? key;
                    internalId = ReadString(arg, "InternalId") ?? internalId;
                    break;
                }
                if (name.IndexOf("ProvideHandle", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    var argLocation = ReadMember(arg, "Location");
                    if (argLocation != null)
                    {
                        key = ReadString(argLocation, "PrimaryKey") ?? key;
                        internalId = ReadString(argLocation, "InternalId") ?? internalId;
                        break;
                    }
                }
            }
        }
        return new BundleIdentity(bundleName, hash, key, internalId, transformed);
    }

    private static object? ReadMember(object? instance, string name)
    {
        if (instance == null) return null;
        var type = instance.GetType();
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Static |
                                   BindingFlags.Public | BindingFlags.NonPublic;
        try
        {
            var property = type.GetProperty(name, flags);
            if (property != null && property.GetIndexParameters().Length == 0)
                return property.GetValue(instance);
        }
        catch { }
        try
        {
            var field = type.GetField(name, flags);
            if (field != null) return field.GetValue(instance);
        }
        catch { }
        try
        {
            var getter = type.GetMethod("get_" + name, flags);
            if (getter != null && getter.GetParameters().Length == 0)
                return getter.Invoke(instance, null);
        }
        catch { }
        return null;
    }

    private static string? ReadString(object? instance, string name)
    {
        var value = ReadMember(instance, name);
        if (value == null) return null;
        try { return value as string ?? value.ToString(); }
        catch { return null; }
    }

    private static string MethodKey(MethodBase method)
    {
        var parameters = string.Join(",", method.GetParameters().Select(p => p.ParameterType.FullName));
        return (method.DeclaringType?.FullName ?? "?") + "." + method.Name + "(" + parameters + ")";
    }

    private static void EnsureGitHubUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var parsed) ||
            !string.Equals(parsed.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) ||
            !((string.Equals(parsed.Host, "github.com", StringComparison.OrdinalIgnoreCase) &&
               parsed.AbsolutePath.StartsWith("/" + Repository + "/releases/", StringComparison.Ordinal)) ||
              (string.Equals(parsed.Host, "api.github.com", StringComparison.OrdinalIgnoreCase) &&
               parsed.AbsolutePath.StartsWith("/repos/" + Repository + "/releases/", StringComparison.Ordinal))))
            throw new InvalidDataException("unexpected GitHub URL: " + url);
    }

    private static string Sha256(byte[] bytes)
    {
        using (var sha = SHA256.Create()) return ToHex(sha.ComputeHash(bytes));
    }


    private static string ToHex(byte[] bytes)
    {
        var builder = new StringBuilder(bytes.Length * 2);
        foreach (var value in bytes) builder.Append(value.ToString("x2"));
        return builder.ToString();
    }

    private static void ValidateHex(string value, int length, string field)
    {
        if (value == null || value.Length != length ||
            value.Any(c => (c < '0' || c > '9') && (c < 'a' || c > 'f') &&
                           (c < 'A' || c > 'F')))
            throw new InvalidDataException(field + " is not hexadecimal");
    }

    private static string RequiredString(JsonElement element, string name)
    {
        var value = OptionalString(element, name);
        if (string.IsNullOrEmpty(value)) throw new InvalidDataException("missing " + name);
        return value;
    }

    private static string? OptionalString(JsonElement element, string name)
    {
        return element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() : null;
    }

    private static JsonElement RequiredObject(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("missing object " + name);
        return value;
    }

    private static JsonElement RequiredArray(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("missing array " + name);
        return value;
    }

    private static int RequiredInt(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || !value.TryGetInt32(out var result))
            throw new InvalidDataException("missing integer " + name);
        return result;
    }

    private static long RequiredLong(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || !value.TryGetInt64(out var result))
            throw new InvalidDataException("missing integer " + name);
        return result;
    }

    internal static void Event(string name, string? details)
    {
        var writer = Writer;
        if (writer == null) return;
        var line = "{\"utc\":\"" + Escape(DateTime.UtcNow.ToString("O")) +
                   "\",\"pid\":" + Process.GetCurrentProcess().Id +
                   ",\"event\":\"" + Escape(name) + "\",\"details\":\"" +
                   Escape(details) + "\"}";
        lock (Sync)
        {
            try { writer.WriteLine(line); } catch { }
        }
    }

    private static string Escape(string? value)
    {
        return (value ?? string.Empty).Replace("\\", "\\\\").Replace("\"", "\\\"")
            .Replace("\r", "\\r").Replace("\n", "\\n");
    }

    private static void SetUi(string? status, string? detail, string? latestVersion,
        string? installedVersion, string? gameVersion)
    {
        lock (UiSync)
        {
            if (status != null) UiStatus = status;
            if (detail != null) UiDetail = detail;
            if (latestVersion != null) UiLatestVersion = latestVersion;
            if (installedVersion != null) UiInstalledVersion = installedVersion;
            if (gameVersion != null) UiGameVersion = gameVersion;
        }
    }

    private static void LoadCachedUi(string root)
    {
        try
        {
            var statePath = Path.Combine(root, "data-unity3d-state.json");
            if (!File.Exists(statePath)) return;
            using var stateDocument = JsonDocument.Parse(File.ReadAllBytes(statePath));
            var state = stateDocument.RootElement;
            var route = StateString(state, "Route", "route");
            var manifestSha = StateString(state, "ManifestSha256", "manifestSha256");
            var releaseTag = StateString(state, "ReleaseTag", "releaseTag");
            if (!IsSupportedRoute(route)) return;
            ValidateHex(manifestSha, 64, "cached manifest sha256");
            var path = Path.Combine(root, "releases", manifestSha.ToLowerInvariant(), "manifest.json");
            if (!File.Exists(path)) return;
            var raw = File.ReadAllBytes(path);
            if (!string.Equals(Sha256(raw), manifestSha, StringComparison.OrdinalIgnoreCase)) return;
            var manifest = ParseManifest(raw, route);
            SetUi("캐시된 패치 확인 중", "저장된 패치: " + manifest.PatchVersion,
                manifest.PatchVersion, manifest.PatchVersion,
                manifest.GameVersion + " / revision " + manifest.Revision);
        }
        catch
        {
            // Stale UI metadata must never prevent the game or patch from starting.
        }
    }

    internal static UiSnapshot GetUiSnapshot()
    {
        lock (UiSync)
        {
            return new UiSnapshot(UiLatestVersion, UiInstalledVersion, UiGameVersion,
                UiStatus, UiDetail);
        }
    }

    internal readonly struct UiSnapshot
    {
        public readonly string LatestVersion;
        public readonly string InstalledVersion;
        public readonly string GameVersion;
        public readonly string Status;
        public readonly string Detail;

        public UiSnapshot(string latestVersion, string installedVersion, string gameVersion,
            string status, string detail)
        {
            LatestVersion = latestVersion;
            InstalledVersion = installedVersion;
            GameVersion = gameVersion;
            Status = status;
            Detail = detail;
        }
    }


    private sealed class ReleaseManifest
    {
        public readonly string PatchVersion;
        public readonly string GameVersion;
        public readonly string Revision;
        public readonly string CatalogHash;
        public readonly List<ManifestFile> Files;

        public ReleaseManifest(string patchVersion, string gameVersion, string revision,
            string catalogHash, List<ManifestFile> files)
        {
            PatchVersion = patchVersion; GameVersion = gameVersion; Revision = revision;
            CatalogHash = catalogHash; Files = files;
        }
    }

    private sealed class ManifestFile
    {
        public readonly string Target;
        public readonly string Path;
        public readonly string DownloadUrl;
        public readonly string DownloadSha256;
        public readonly long DownloadSize;
        public readonly string PayloadSha256;
        public readonly long PayloadSize;

        public ManifestFile(string target, string path, string downloadUrl, string downloadSha256,
            long downloadSize, string payloadSha256, long payloadSize)
        {
            Target = target; Path = path; DownloadUrl = downloadUrl; DownloadSha256 = downloadSha256;
            DownloadSize = downloadSize; PayloadSha256 = payloadSha256; PayloadSize = payloadSize;
        }
    }

    private sealed class PayloadEntry
    {
        public readonly string BundleName;
        public readonly string Hash;
        public readonly string PayloadPath;
        public string Key => BundleName + "/" + Hash;

        public PayloadEntry(string bundleName, string hash, string payloadPath)
        {
            BundleName = bundleName; Hash = hash; PayloadPath = payloadPath;
        }

        public bool Matches(BundleIdentity identity)
        {
            return !string.IsNullOrEmpty(identity.BundleName) &&
                   !string.IsNullOrEmpty(identity.Hash) &&
                   string.Equals(BundleName, identity.BundleName, StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(Hash, identity.Hash, StringComparison.OrdinalIgnoreCase);
        }
    }

    private readonly struct BundleIdentity
    {
        public readonly string? BundleName;
        public readonly string? Hash;
        public readonly string? PrimaryKey;
        public readonly string? InternalId;
        public readonly string? TransformedInternalId;

        public BundleIdentity(string? bundleName, string? hash, string? primaryKey, string? internalId,
            string? transformedInternalId)
        {
            BundleName = bundleName; Hash = hash; PrimaryKey = primaryKey; InternalId = internalId;
            TransformedInternalId = transformedInternalId;
        }

        public override string ToString()
        {
            return "bundle=" + (BundleName ?? "?") + ",hash=" + (Hash ?? "?") +
                   ",key=" + (PrimaryKey ?? "?") + ",id=" + (InternalId ?? TransformedInternalId ?? "?");
        }
    }
}

internal static class OverlayUi
{
    private static GameObject root = null!;
    private static RectTransform panelRect = null!;
    private static Text title = null!;
    private static Text gameVersionLabel = null!;
    private static Text gameVersionValue = null!;
    private static Text gameRevisionLabel = null!;
    private static Text gameRevisionValue = null!;
    private static Text installedLabel = null!;
    private static Text installedValue = null!;
    private static Text latestLabel = null!;
    private static Text latestValue = null!;
    private static Text statusLabel = null!;
    private static Text statusValue = null!;
    private static Image closeImage = null!;
    private static RectTransform closeRect = null!;
    private static Sprite roundedSprite = null!;
    private static Sprite borderSprite = null!;
    private static Sprite buttonSprite = null!;
    private static Sprite solidSprite = null!;
    private const string PreferredOverlayFontName = "Afacad-Regular";
    private static Font? overlayFont;
    private static Font? fallbackOverlayFont;
    private static bool preferredFontLogged;
    private static bool disabled;
    private static bool inputDisabled;
    private static bool visible = true;
    private static bool readingInput;

    public static void TryRefresh()
    {
        if (disabled) return;
        try
        {
            if (root == null) Create();
            RefreshPreferredFont();
            var snapshot = AddressablesInProcessPatch.GetUiSnapshot();
            title.text = "아스트랄 파티 한글패치";
            SplitGameVersion(snapshot.GameVersion, out var gameVersion, out var gameRevision);
            gameVersionValue.text = Safe(gameVersion);
            gameRevisionValue.text = Safe(gameRevision);
            installedValue.text = Safe(snapshot.InstalledVersion);
            latestValue.text = Safe(snapshot.LatestVersion);
            statusValue.text = Safe(snapshot.Status);
            statusValue.color = StatusColor(snapshot.Status);
        }
        catch (Exception ex)
        {
            disabled = true;
            AddressablesInProcessPatch.Event("OVERLAY_UI_DISABLED",
                ex.GetType().Name + ":" + ex.Message);
        }
    }

    public static bool PollInput()
    {
        if (disabled || inputDisabled || !visible || root == null || closeRect == null)
            return false;
        try
        {
            var hovered = RectTransformUtility.RectangleContainsScreenPoint(
                closeRect, Input.mousePosition, null);
            if (closeImage != null)
            {
                closeImage.color = hovered
                    ? new Color(1f, 0.20f, 0.77f, 1f)
                    : new Color(1f, 0f, 0.7058824f, 1f);
            }
            bool escape;
            bool mouseDown;
            readingInput = true;
            try
            {
                escape = Input.GetKeyDown(KeyCode.Escape);
                mouseDown = Input.GetMouseButtonDown(0);
            }
            finally
            {
                readingInput = false;
            }
            if (escape || (hovered && mouseDown))
            {
                // Some game UI code reads Unity input directly instead of
                // going through EventSystem. Clear this frame's button state
                // before hiding the overlay so that code cannot reuse the
                // close click on an element underneath the panel.
                try { Input.ResetInputAxes(); } catch { }
                Close();
                return true;
            }
            return false;
        }
        catch (Exception ex)
        {
            inputDisabled = true;
            AddressablesInProcessPatch.Event("OVERLAY_INPUT_DISABLED",
                ex.GetType().Name + ":" + ex.Message);
            return false;
        }
    }

    internal static bool ShouldBlockRawMouseInput()
    {
        if (readingInput || disabled || inputDisabled || !visible || root == null || panelRect == null)
            return false;
        try
        {
            return RectTransformUtility.RectangleContainsScreenPoint(
                panelRect, Input.mousePosition, null);
        }
        catch { return false; }
    }

    private static void Create()
    {
        root = new GameObject("AstralPartyKoreanPatchOverlay");
        UnityEngine.Object.DontDestroyOnLoad(root);

        var canvas = root.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = 32760;
        root.AddComponent<GraphicRaycaster>();

        var scaler = root.AddComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);
        scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
        scaler.matchWidthOrHeight = 0.5f;

        // Draw the border first so the opaque panel leaves a clean, even line
        // around its rounded corners.
        CreatePanelBorder(root);

        var panel = new GameObject("Panel");
        panel.transform.SetParent(root.transform, false);
        var image = panel.AddComponent<Image>();
        panelRect = panel.GetComponent<RectTransform>();
        if (panelRect == null) panelRect = panel.AddComponent<RectTransform>();
        panelRect.anchorMin = new Vector2(1f, 0f);
        panelRect.anchorMax = new Vector2(1f, 0f);
        panelRect.pivot = new Vector2(1f, 0f);
        panelRect.sizeDelta = new Vector2(520f, 400f);
        panelRect.anchoredPosition = new Vector2(-32f, 32f);

        image.sprite = GetRoundedSprite();
        image.type = Image.Type.Sliced;
        image.color = new Color(0.025f, 0.035f, 0.065f, 1f);
        image.raycastTarget = true;

        title = AddText(panel, "Title", new Vector2(32f, -22f),
            new Vector2(410f, 42f), 28, FontStyle.Normal,
            new Color(0.92f, 0.97f, 1f, 0.82f));

        gameVersionLabel = AddText(panel, "GameVersionLabel", new Vector2(32f, -76f),
            new Vector2(210f, 22f), 14, FontStyle.Normal,
            new Color(0.56f, 0.66f, 0.78f, 0.56f));
        gameVersionLabel.text = "버전";
        gameVersionValue = AddText(panel, "GameVersionValue", new Vector2(32f, -99f),
            new Vector2(210f, 36f), 23, FontStyle.Normal,
            new Color(0.94f, 0.97f, 1f, 0.82f));
        gameRevisionLabel = AddText(panel, "GameRevisionLabel", new Vector2(272f, -76f),
            new Vector2(222f, 22f), 14, FontStyle.Normal,
            new Color(0.56f, 0.66f, 0.78f, 0.56f));
        gameRevisionLabel.text = "리비전";
        gameRevisionValue = AddText(panel, "GameRevisionValue", new Vector2(272f, -99f),
            new Vector2(222f, 36f), 23, FontStyle.Normal,
            new Color(0.94f, 0.97f, 1f, 0.82f));

        installedLabel = AddText(panel, "InstalledLabel", new Vector2(32f, -148f),
            new Vector2(462f, 22f), 14, FontStyle.Normal,
            new Color(0.56f, 0.66f, 0.78f, 0.56f));
        installedLabel.text = "설치된 패치";
        installedValue = AddText(panel, "InstalledValue", new Vector2(32f, -171f),
            new Vector2(462f, 36f), 23, FontStyle.Normal,
            new Color(0.94f, 0.97f, 1f, 0.82f));

        latestLabel = AddText(panel, "LatestLabel", new Vector2(32f, -220f),
            new Vector2(462f, 22f), 14, FontStyle.Normal,
            new Color(0.56f, 0.66f, 0.78f, 0.56f));
        latestLabel.text = "최신 패치";
        latestValue = AddText(panel, "LatestValue", new Vector2(32f, -243f),
            new Vector2(462f, 36f), 23, FontStyle.Normal,
            new Color(0.94f, 0.97f, 1f, 0.82f));

        statusLabel = AddText(panel, "StatusLabel", new Vector2(32f, -292f),
            new Vector2(462f, 22f), 14, FontStyle.Normal,
            new Color(0.56f, 0.66f, 0.78f, 0.56f));
        statusLabel.text = "상태";
        statusValue = AddText(panel, "StatusValue", new Vector2(32f, -315f),
            new Vector2(462f, 38f), 24, FontStyle.Normal,
            new Color(0.32f, 0.64f, 1f, 0.82f));
        try
        {
            CreateCloseButton(panel);
        }
        catch (Exception ex)
        {
            // A missing or incompatible EventSystem must not disable the
            // status panel or the Addressables redirect itself.
            AddressablesInProcessPatch.Event("OVERLAY_CLOSE_DISABLED",
                ex.GetType().Name + ":" + ex.Message);
        }

        AddressablesInProcessPatch.Event("OVERLAY_READY",
            "built-in Canvas/Text overlay; rounded bordered panel; vertical label/value layout; " +
            "input polling enabled");
    }

    private static void CreatePanelBorder(GameObject parent)
    {
        var effect = new GameObject("PanelBorder");
        effect.transform.SetParent(parent.transform, false);
        var image = effect.AddComponent<Image>();
        image.sprite = GetBorderSprite();
        image.type = Image.Type.Sliced;
        image.color = new Color(1f, 0f, 0.7058824f, 1f); // #FF00B4
        image.raycastTarget = false;

        var rect = effect.GetComponent<RectTransform>();
        if (rect == null) rect = effect.AddComponent<RectTransform>();
        rect.anchorMin = new Vector2(1f, 0f);
        rect.anchorMax = new Vector2(1f, 0f);
        rect.pivot = new Vector2(1f, 0f);
        rect.sizeDelta = new Vector2(526f, 406f);
        // The RectTransform uses a bottom-right pivot. Move the larger
        // border rect by half its size delta so it remains centered.
        rect.anchoredPosition = new Vector2(-29f, 29f);
    }

    private static Text AddText(GameObject parent, string name, Vector2 position,
        Vector2 size, int fontSize, FontStyle fontStyle, Color color,
        TextAnchor alignment = TextAnchor.MiddleLeft)
    {
        var child = new GameObject(name);
        child.transform.SetParent(parent.transform, false);
        var label = child.AddComponent<Text>();
        var rect = child.GetComponent<RectTransform>();
        if (rect == null) rect = child.AddComponent<RectTransform>();
        rect.anchorMin = new Vector2(0f, 1f);
        rect.anchorMax = new Vector2(0f, 1f);
        rect.pivot = new Vector2(0f, 1f);
        rect.sizeDelta = size;
        rect.anchoredPosition = position;

        label.font = GetOverlayFont();
        label.fontSize = fontSize;
        label.fontStyle = fontStyle;
        label.color = color;
        label.alignment = alignment;
        label.horizontalOverflow = HorizontalWrapMode.Wrap;
        label.verticalOverflow = VerticalWrapMode.Overflow;
        label.raycastTarget = false;
        return label;
    }

    private static Font GetOverlayFont()
    {
        var preferred = FindPreferredFont();
        if (preferred != null)
        {
            overlayFont = preferred;
            LogPreferredFont(preferred);
            return preferred;
        }

        if (fallbackOverlayFont != null) return fallbackOverlayFont;

        // The player-data redirect replaces Afacad-Regular before Unity loads
        // data.unity3d. During the very first overlay construction that asset
        // may still be unavailable, so retain a temporary system-font fallback
        // and replace it as soon as the serialized game Font becomes visible.
        try { fallbackOverlayFont = Font.CreateDynamicFontFromOSFont("Malgun Gothic", 32); }
        catch { }
        if (fallbackOverlayFont == null)
        {
            try { fallbackOverlayFont = Font.CreateDynamicFontFromOSFont("맑은 고딕", 32); }
            catch { }
        }
        if (fallbackOverlayFont == null)
            fallbackOverlayFont = Resources.GetBuiltinResource<Font>("Arial.ttf");
        return fallbackOverlayFont!;
    }

    private static Font? FindPreferredFont()
    {
        try
        {
            var fonts = Resources.FindObjectsOfTypeAll<Font>();
            foreach (var font in fonts)
            {
                if (font != null && string.Equals(font.name, PreferredOverlayFontName,
                        StringComparison.Ordinal))
                    return font;
            }
        }
        catch { }
        return null;
    }

    private static void RefreshPreferredFont()
    {
        var preferred = FindPreferredFont();
        if (preferred == null || overlayFont == preferred) return;

        overlayFont = preferred;
        LogPreferredFont(preferred);
        ApplyFont(title, preferred);
        ApplyFont(gameVersionLabel, preferred);
        ApplyFont(gameVersionValue, preferred);
        ApplyFont(gameRevisionLabel, preferred);
        ApplyFont(gameRevisionValue, preferred);
        ApplyFont(installedLabel, preferred);
        ApplyFont(installedValue, preferred);
        ApplyFont(latestLabel, preferred);
        ApplyFont(latestValue, preferred);
        ApplyFont(statusLabel, preferred);
        ApplyFont(statusValue, preferred);
    }

    private static void ApplyFont(Text label, Font font)
    {
        if (label == null || font == null || label.font == font) return;
        label.font = font;
        label.SetVerticesDirty();
        label.SetLayoutDirty();
    }

    private static void LogPreferredFont(Font font)
    {
        if (preferredFontLogged) return;
        preferredFontLogged = true;
        AddressablesInProcessPatch.Event("OVERLAY_FONT_READY",
            "name=" + (font.name ?? "<unnamed>") + "; source=player-data Afacad-Regular");
    }

    private static void CreateCloseButton(GameObject panel)
    {
        var closeObject = new GameObject("CloseButton");
        closeObject.transform.SetParent(panel.transform, false);
        closeImage = closeObject.AddComponent<Image>();
        closeImage.sprite = GetButtonSprite();
        closeImage.type = Image.Type.Sliced;
        closeImage.color = new Color(1f, 0f, 0.7058824f, 1f); // #FF00B4
        closeImage.raycastTarget = true;

        closeRect = closeObject.GetComponent<RectTransform>();
        if (closeRect == null) closeRect = closeObject.AddComponent<RectTransform>();
        closeRect.anchorMin = new Vector2(1f, 1f);
        closeRect.anchorMax = new Vector2(1f, 1f);
        closeRect.pivot = new Vector2(1f, 1f);
        closeRect.sizeDelta = new Vector2(48f, 48f);
        closeRect.anchoredPosition = new Vector2(-18f, -18f);

        // Draw the close glyph from two thin UI bars. This has the same crisp,
        // vector-like appearance as an SVG X without introducing an SVG
        // package or a UnityEvent delegate conversion.
        CreateCloseGlyphBar(closeObject, "CloseLineA", 45f);
        CreateCloseGlyphBar(closeObject, "CloseLineB", -45f);
        AddressablesInProcessPatch.Event("OVERLAY_CLOSE_READY",
            "button=CloseButton; glyph=two-line-vector-style-x; " +
            "raycast blocker enabled; input=EventSystem.Update polling");
    }

    private static void CreateCloseGlyphBar(GameObject parent, string name, float rotation)
    {
        var lineObject = new GameObject(name);
        lineObject.transform.SetParent(parent.transform, false);
        var line = lineObject.AddComponent<Image>();
        line.sprite = GetSolidSprite();
        line.type = Image.Type.Simple;
        line.color = new Color(0.92f, 0.97f, 1f, 1f);
        line.raycastTarget = false;

        var rect = lineObject.GetComponent<RectTransform>();
        if (rect == null) rect = lineObject.AddComponent<RectTransform>();
        rect.anchorMin = new Vector2(0.5f, 0.5f);
        rect.anchorMax = new Vector2(0.5f, 0.5f);
        rect.pivot = new Vector2(0.5f, 0.5f);
        rect.sizeDelta = new Vector2(3f, 24f);
        rect.anchoredPosition = Vector2.zero;
        rect.localRotation = Quaternion.Euler(0f, 0f, rotation);
    }

    private static void Close()
    {
        if (root == null) return;
        visible = false;
        root.SetActive(false);
        AddressablesInProcessPatch.Event("OVERLAY_CLOSED", "user clicked close button");
    }

    private static Sprite GetRoundedSprite()
    {
        if (roundedSprite != null) return roundedSprite;

        roundedSprite = BuildRoundedSprite(26f,
            "AstralPartyKoreanPatchRoundedBackground",
            "AstralPartyKoreanPatchRoundedBackgroundSprite");
        return roundedSprite;
    }

    private static Sprite GetBorderSprite()
    {
        if (borderSprite != null) return borderSprite;

        borderSprite = BuildRoundedSprite(28f,
            "AstralPartyKoreanPatchRoundedBorder",
            "AstralPartyKoreanPatchRoundedBorderSprite");
        return borderSprite;
    }

    private static Sprite GetButtonSprite()
    {
        if (buttonSprite != null) return buttonSprite;

        buttonSprite = BuildRoundedSprite(14f,
            "AstralPartyKoreanPatchRoundedButton",
            "AstralPartyKoreanPatchRoundedButtonSprite");
        return buttonSprite;
    }

    private static Sprite BuildRoundedSprite(float radius, string textureName,
        string spriteName)
    {
        const int size = 64;

        var texture = new Texture2D(size, size, TextureFormat.RGBA32, false, true);
        texture.name = textureName;
        texture.wrapMode = TextureWrapMode.Clamp;
        texture.hideFlags = HideFlags.HideAndDontSave;

        for (var y = 0; y < size; y++)
        {
            for (var x = 0; x < size; x++)
            {
                var dx = Math.Max(Math.Max(radius - x, x - (size - 1 - radius)), 0f);
                var dy = Math.Max(Math.Max(radius - y, y - (size - 1 - radius)), 0f);
                var distance = (float)Math.Sqrt(dx * dx + dy * dy);
                var alpha = distance <= radius - 1f ? 1f :
                    distance >= radius ? 0f : radius - distance;
                texture.SetPixel(x, y, new Color(1f, 1f, 1f, alpha));
            }
        }
        texture.Apply(false, true);

        var sprite = Sprite.Create(texture, new Rect(0f, 0f, size, size),
            new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect,
            new Vector4(radius, radius, radius, radius));
        sprite.name = spriteName;
        sprite.hideFlags = HideFlags.HideAndDontSave;
        return sprite;
    }

    private static Sprite GetSolidSprite()
    {
        if (solidSprite != null) return solidSprite;

        const int size = 4;
        var texture = new Texture2D(size, size, TextureFormat.RGBA32, false, true);
        texture.name = "AstralPartyKoreanPatchSolidSprite";
        texture.wrapMode = TextureWrapMode.Clamp;
        texture.hideFlags = HideFlags.HideAndDontSave;
        var pixels = new Color[size * size];
        for (var i = 0; i < pixels.Length; i++) pixels[i] = Color.white;
        texture.SetPixels(pixels);
        texture.Apply(false, true);

        solidSprite = Sprite.Create(texture, new Rect(0f, 0f, size, size),
            new Vector2(0.5f, 0.5f), 100f);
        solidSprite.name = "AstralPartyKoreanPatchSolidSpriteAsset";
        solidSprite.hideFlags = HideFlags.HideAndDontSave;
        return solidSprite;
    }

    private static Color StatusColor(string status)
    {
        var value = status ?? string.Empty;
        if (value.IndexOf("실패", StringComparison.OrdinalIgnoreCase) >= 0 ||
            value.IndexOf("오류", StringComparison.OrdinalIgnoreCase) >= 0 ||
            value.IndexOf("원본", StringComparison.OrdinalIgnoreCase) >= 0 ||
            value.IndexOf("비활성", StringComparison.OrdinalIgnoreCase) >= 0)
            return new Color(1f, 0.30f, 0.32f, 1f); // error

        if (value.IndexOf("완료", StringComparison.OrdinalIgnoreCase) >= 0 ||
            value.IndexOf("성공", StringComparison.OrdinalIgnoreCase) >= 0 ||
            value.IndexOf("적용", StringComparison.OrdinalIgnoreCase) >= 0)
            return new Color(0.30f, 0.90f, 0.48f, 1f); // complete

        return new Color(0.30f, 0.62f, 1f, 1f); // in progress
    }

    private static void SplitGameVersion(string value, out string version, out string revision)
    {
        version = "-";
        revision = "-";
        if (string.IsNullOrWhiteSpace(value)) return;

        var parsedVersion = value.Trim();
        var parsedRevision = string.Empty;
        var separator = parsedVersion.IndexOf("/ revision ", StringComparison.OrdinalIgnoreCase);
        if (separator < 0)
            separator = parsedVersion.IndexOf("/revision ", StringComparison.OrdinalIgnoreCase);

        if (separator >= 0)
        {
            parsedRevision = parsedVersion.Substring(separator + 1).Trim();
            parsedVersion = parsedVersion.Substring(0, separator).Trim();
            if (parsedRevision.StartsWith("revision ", StringComparison.OrdinalIgnoreCase))
                parsedRevision = parsedRevision.Substring("revision ".Length).Trim();
        }

        if (!string.IsNullOrEmpty(parsedVersion))
        {
            if (!parsedVersion.StartsWith("v", StringComparison.OrdinalIgnoreCase))
                parsedVersion = "v" + parsedVersion;
            version = parsedVersion;
        }
        if (!string.IsNullOrEmpty(parsedRevision))
            revision = parsedRevision;
    }

    private static string Safe(string value, int maxLength = 48)
    {
        if (string.IsNullOrEmpty(value)) return "-";
        value = value.Replace("\r", " ").Replace("\n", " ");
        return value.Length <= maxLength ? value : value.Substring(0, maxLength - 1) + "…";
    }
}
