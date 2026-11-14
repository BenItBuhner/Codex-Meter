using System.Text.Json;

namespace CodexMeter.Core.Updates;

/// <summary>Which desktop build this copy of the app is, so the updater can pick the matching download.</summary>
public enum PackageKind
{
    /// <summary>Windows, installed with the setup .exe (an uninstaller sits next to the app).</summary>
    WindowsInstaller,
    /// <summary>Windows, run from the portable .zip.</summary>
    WindowsPortable,
    /// <summary>Linux AppImage ($APPIMAGE is set).</summary>
    AppImage,
    /// <summary>Linux, installed from the .deb into /usr/lib/codex-meter.</summary>
    Deb,
    /// <summary>A build the updater can't replace (from source, Flatpak, a distro package): it only links to the release.</summary>
    Unmanaged,
}

/// <summary>The download for one platform and architecture, and the checksum file that covers it.</summary>
public sealed record UpdateTarget(PackageKind Kind, string Arch)
{
    /// <summary>The release asset's file name for <paramref name="version"/>, as GitHub lists it.</summary>
    public string? AssetName(ReleaseVersion version) => Kind switch
    {
        PackageKind.WindowsInstaller => $"CodexMeter-{version.Normalized}-Setup-{WindowsArch}.exe",
        PackageKind.WindowsPortable => $"CodexMeter-{version.Normalized}-windows-{WindowsArch}.zip",
        PackageKind.AppImage => $"CodexMeter-{version.Normalized}-{MachineArch}.AppImage",
        // GitHub renames "~" in uploaded file names to ".": 2.9.0~alpha.24 is listed as 2.9.0.alpha.24.
        PackageKind.Deb => $"codex-meter_{DebVersion(version).Replace('~', '.')}_{DebArch}.deb",
        _ => null,
    };

    /// <summary>The name the checksum file uses for the asset (the .deb keeps its "~" there).</summary>
    public string? ChecksumEntryName(ReleaseVersion version) => Kind == PackageKind.Deb
        ? $"codex-meter_{DebVersion(version)}_{DebArch}.deb"
        : AssetName(version);

    public string? ChecksumFileName => Kind switch
    {
        PackageKind.WindowsInstaller or PackageKind.WindowsPortable => "SHA256SUMS-windows.txt",
        PackageKind.AppImage or PackageKind.Deb => $"SHA256SUMS-linux-{WindowsArch}.txt",
        _ => null,
    };

    public bool CanInstall => Kind != PackageKind.Unmanaged;

    private string WindowsArch => Arch == "arm64" ? "arm64" : "x64";
    private string MachineArch => Arch == "arm64" ? "aarch64" : "x86_64";
    private string DebArch => Arch == "arm64" ? "arm64" : "amd64";

    private static string DebVersion(ReleaseVersion version)
    {
        var text = version.Normalized;
        var dash = text.IndexOf('-');
        return dash < 0 ? text : text[..dash] + "~" + text[(dash + 1)..];
    }
}

public sealed record ReleaseAsset(string Name, string Url, long Size);

/// <summary>One GitHub release, reduced to what the updater reads.</summary>
public sealed record ReleaseInfo(
    string Tag,
    ReleaseVersion Version,
    string Name,
    bool Prerelease,
    DateTimeOffset? PublishedAt,
    string PageUrl,
    string Notes,
    ReleaseAsset? Download,
    ReleaseAsset? Checksums)
{
    /// <summary>True when this release has the build for this platform and its checksum file.</summary>
    public bool HasDownload => Download is not null && Checksums is not null;
}

/// <summary>
/// Reads GitHub's release JSON (one release or a listing) without keeping the rest of it. Each
/// release since alpha.14 lists 17 builds, which is what pushed the Android app's full listing past
/// its size cap ("GitHub returned too much release metadata"); here only the newest few releases
/// are ever requested, and only the fields below survive parsing.
/// </summary>
public static class ReleaseFeed
{
    public const int MaxNotes = 6000;

    public static List<ReleaseInfo> Parse(string json, UpdateTarget target, Func<string, bool> trustedUrl)
    {
        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 32 });
        var root = document.RootElement;
        var list = new List<ReleaseInfo>();
        if (root.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in root.EnumerateArray())
                if (ParseOne(item, target, trustedUrl) is { } release) list.Add(release);
        }
        else if (root.ValueKind == JsonValueKind.Object && ParseOne(root, target, trustedUrl) is { } single)
        {
            list.Add(single);
        }
        return list;
    }

    /// <summary>Joins several reads into one list, newest version first, each version once.</summary>
    public static List<ReleaseInfo> Merge(params IEnumerable<ReleaseInfo>[] parts) =>
        parts.SelectMany(p => p)
            .GroupBy(r => r.Version.Normalized)
            .Select(g => g.First())
            .OrderByDescending(r => r.Version)
            .ToList();

    private static ReleaseInfo? ParseOne(JsonElement release, UpdateTarget target, Func<string, bool> trustedUrl)
    {
        if (release.ValueKind != JsonValueKind.Object) return null;
        if (Bool(release, "draft")) return null;
        var tag = Clean(String(release, "tag_name"), 100);
        if (ReleaseVersion.Parse(tag) is not { } version) return null;
        var page = String(release, "html_url");
        if (!trustedUrl(page)) return null;
        var wanted = target.AssetName(version);
        var sumsName = target.ChecksumFileName;
        ReleaseAsset? download = null, sums = null;
        if (release.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
        {
            foreach (var asset in assets.EnumerateArray())
            {
                if (asset.ValueKind != JsonValueKind.Object) continue;
                var name = String(asset, "name");
                if (name != wanted && name != sumsName) continue;
                var url = String(asset, "browser_download_url");
                var size = asset.TryGetProperty("size", out var s) && s.TryGetInt64(out var n) ? n : -1;
                if (!trustedUrl(url) || size <= 0) continue;
                if (name == wanted) download = new ReleaseAsset(name, url, size);
                else sums = new ReleaseAsset(name, url, size);
            }
        }
        var title = Clean(String(release, "name"), 160);
        if (title.Length == 0) title = "Codex Meter " + version.Normalized;
        DateTimeOffset? published = DateTimeOffset.TryParse(String(release, "published_at"), out var at) ? at : null;
        return new ReleaseInfo(tag, version, title, Bool(release, "prerelease") || version.IsPrerelease, published, page,
            Clean(String(release, "body"), MaxNotes), download, sums);
    }

    private static string String(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : "";

    private static bool Bool(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;

    private static string Clean(string value, int max)
    {
        var text = value.Replace("\0", "").Trim();
        return text.Length <= max ? text : text[..max].TrimEnd() + "…";
    }
}
