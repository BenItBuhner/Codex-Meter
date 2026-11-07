using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using CodexMeter.Core.Services;

namespace CodexMeter.Linux.Services;

/// <summary>Where Codex Meter keeps its files, following the XDG base directory spec.</summary>
public static class AppPaths
{
    public const string AppId = "io.github.benitbuhner.CodexMeter";

    private static string Xdg(string variable, string fallback)
    {
        var value = Environment.GetEnvironmentVariable(variable);
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine(string.IsNullOrWhiteSpace(value) || !Path.IsPathRooted(value) ? Path.Combine(home, fallback) : value,
            "codex-meter");
    }

    /// <summary>Credentials and the key fallback: <c>~/.local/share/codex-meter</c>.</summary>
    public static string DataDirectory { get; } = Xdg("XDG_DATA_HOME", ".local/share");

    /// <summary>Settings: <c>~/.config/codex-meter</c>.</summary>
    public static string ConfigDirectory { get; } = Xdg("XDG_CONFIG_HOME", ".config");

    /// <summary>Cached usage and the log: <c>~/.cache/codex-meter</c>.</summary>
    public static string CacheDirectory { get; } = Xdg("XDG_CACHE_HOME", ".cache");

    public static string AutostartDirectory
    {
        get
        {
            var value = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            return Path.Combine(string.IsNullOrWhiteSpace(value) ? Path.Combine(home, ".config") : value, "autostart");
        }
    }

    /// <summary>Creates a folder only this user can read (0700).</summary>
    public static void EnsurePrivate(string directory)
    {
        Directory.CreateDirectory(directory);
        try { File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }
}

public static class AppLog
{
    public static void Write(string message)
    {
        Console.Error.WriteLine(message);
        try
        {
            Directory.CreateDirectory(AppPaths.CacheDirectory);
            var path = Path.Combine(AppPaths.CacheDirectory, "codexmeter.log");
            if (File.Exists(path) && new FileInfo(path).Length > 512 * 1024) File.Delete(path);
            File.AppendAllText(path, $"{DateTimeOffset.Now:u} {message}{Environment.NewLine}");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}

/// <summary>Where the token encryption key lives.</summary>
public enum KeyLocation { Keyring, PrivateFile }

/// <summary>
/// Encrypts token files with AES-256-GCM. The key is a random 256-bit value kept in the desktop's
/// login keyring (Secret Service: GNOME Keyring, KWallet, KeePassXC…). Without a keyring the key
/// falls back to a file only this user can read, next to the tokens.
/// </summary>
public sealed class AesKeyProtector : IDataProtector
{
    private static readonly byte[] Magic = "CMK1"u8.ToArray();
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private readonly byte[] key;

    public AesKeyProtector(byte[] key, KeyLocation location)
    {
        if (key.Length != 32) throw new ArgumentException("The key must be 256 bits.", nameof(key));
        this.key = key;
        Location = location;
    }

    public KeyLocation Location { get; }

    /// <summary>Loads (or creates) the key: the keyring first, then the private file.</summary>
    public static AesKeyProtector Open(string dataDirectory)
    {
        try
        {
            if (SecretService.Lookup() is { } stored && TryDecode(stored) is { } existing)
                return new AesKeyProtector(existing, KeyLocation.Keyring);
            var path = Path.Combine(dataDirectory, "token-key");
            // A key that was already in the private file moves into the keyring once one appears.
            var fresh = ReadKeyFile(path) ?? RandomNumberGenerator.GetBytes(32);
            if (SecretService.Store(Convert.ToBase64String(fresh)))
            {
                if (File.Exists(path)) File.Delete(path);
                return new AesKeyProtector(fresh, KeyLocation.Keyring);
            }
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException or IOException)
        {
            AppLog.Write("Keyring unavailable: " + error.Message);
        }
        return new AesKeyProtector(FileKey(dataDirectory), KeyLocation.PrivateFile);
    }

    private static byte[]? TryDecode(string text)
    {
        try
        {
            var bytes = Convert.FromBase64String(text.Trim());
            return bytes.Length == 32 ? bytes : null;
        }
        catch (FormatException) { return null; }
    }

    private static byte[]? ReadKeyFile(string path) => File.Exists(path) ? TryDecode(File.ReadAllText(path)) : null;

    internal static byte[] FileKey(string dataDirectory)
    {
        AppPaths.EnsurePrivate(dataDirectory);
        var path = Path.Combine(dataDirectory, "token-key");
        if (ReadKeyFile(path) is { } existing) return existing;
        var key = RandomNumberGenerator.GetBytes(32);
        using (var stream = new FileStream(path, new FileStreamOptions
               {
                   Mode = FileMode.Create,
                   Access = FileAccess.Write,
                   UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite,
               }))
        {
            stream.Write(Encoding.ASCII.GetBytes(Convert.ToBase64String(key)));
        }
        return key;
    }

    public byte[] Protect(byte[] data)
    {
        var output = new byte[Magic.Length + NonceSize + TagSize + data.Length];
        Magic.CopyTo(output, 0);
        var nonce = output.AsSpan(Magic.Length, NonceSize);
        RandomNumberGenerator.Fill(nonce);
        using var aes = new AesGcm(key, TagSize);
        aes.Encrypt(nonce, data, output.AsSpan(Magic.Length + NonceSize + TagSize), output.AsSpan(Magic.Length + NonceSize, TagSize), Magic);
        return output;
    }

    public byte[] Unprotect(byte[] data)
    {
        if (data.Length < Magic.Length + NonceSize + TagSize || !data.AsSpan(0, Magic.Length).SequenceEqual(Magic))
            throw new CryptographicException("Not a Codex Meter token file.");
        var plain = new byte[data.Length - Magic.Length - NonceSize - TagSize];
        using var aes = new AesGcm(key, TagSize);
        aes.Decrypt(data.AsSpan(Magic.Length, NonceSize), data.AsSpan(Magic.Length + NonceSize + TagSize),
            data.AsSpan(Magic.Length + NonceSize, TagSize), plain, Magic);
        return plain;
    }
}

/// <summary>
/// The token key in the Secret Service through libsecret. Uses the non-variadic <c>…v_sync</c>
/// calls with a GHashTable of attributes, so no varargs cross the P/Invoke boundary.
/// </summary>
internal static partial class SecretService
{
    private const string LibSecret = "libsecret-1.so.0";
    private const string LibGlib = "libglib-2.0.so.0";
    private const string SchemaName = AppPaths.AppId + ".TokenKey";
    private const string Label = "Codex Meter token encryption key";

    // SecretSchema on 64-bit Linux: name (8), flags (4 + 4 padding), 32 attributes of
    // { name pointer (8), type (4 + 4 padding) }, reserved int (4 + 4), then 7 reserved pointers.
    private const int SchemaSize = 16 + 32 * 16 + 8 + 7 * 8;
    private const int AttributesOffset = 16;

    [LibraryImport(LibSecret, EntryPoint = "secret_password_lookupv_sync")]
    private static partial IntPtr LookupV(IntPtr schema, IntPtr attributes, IntPtr cancellable, out IntPtr error);

    [LibraryImport(LibSecret, EntryPoint = "secret_password_storev_sync")]
    [return: MarshalAs(UnmanagedType.I4)]
    private static partial int StoreV(IntPtr schema, IntPtr attributes, IntPtr collection, IntPtr label, IntPtr password,
        IntPtr cancellable, out IntPtr error);

    [LibraryImport(LibSecret, EntryPoint = "secret_password_free")]
    private static partial void PasswordFree(IntPtr password);

    [LibraryImport(LibGlib, EntryPoint = "g_hash_table_new")]
    private static partial IntPtr HashTableNew(IntPtr hash, IntPtr equal);

    [LibraryImport(LibGlib, EntryPoint = "g_hash_table_insert")]
    private static partial int HashTableInsert(IntPtr table, IntPtr key, IntPtr value);

    [LibraryImport(LibGlib, EntryPoint = "g_hash_table_unref")]
    private static partial void HashTableUnref(IntPtr table);

    [LibraryImport(LibGlib, EntryPoint = "g_error_free")]
    private static partial void ErrorFree(IntPtr error);

    private static T WithSchema<T>(Func<IntPtr, IntPtr, T> call)
    {
        var name = Marshal.StringToCoTaskMemUTF8(SchemaName);
        var attribute = Marshal.StringToCoTaskMemUTF8("purpose");
        var value = Marshal.StringToCoTaskMemUTF8("token-key");
        var schema = Marshal.AllocHGlobal(SchemaSize);
        var table = IntPtr.Zero;
        try
        {
            unsafe { new Span<byte>((void*)schema, SchemaSize).Clear(); }
            Marshal.WriteIntPtr(schema, 0, name);
            Marshal.WriteInt32(schema, 8, 0); // SECRET_SCHEMA_NONE
            Marshal.WriteIntPtr(schema, AttributesOffset, attribute);
            Marshal.WriteInt32(schema, AttributesOffset + 8, 0); // SECRET_SCHEMA_ATTRIBUTE_STRING
            var glib = NativeLibrary.Load(LibGlib);
            table = HashTableNew(NativeLibrary.GetExport(glib, "g_str_hash"), NativeLibrary.GetExport(glib, "g_str_equal"));
            HashTableInsert(table, attribute, value);
            return call(schema, table);
        }
        finally
        {
            if (table != IntPtr.Zero) HashTableUnref(table);
            Marshal.FreeHGlobal(schema);
            Marshal.FreeCoTaskMem(name);
            Marshal.FreeCoTaskMem(attribute);
            Marshal.FreeCoTaskMem(value);
        }
    }

    /// <summary>The stored key, or null when there is none or no keyring answers.</summary>
    public static string? Lookup() => WithSchema((schema, table) =>
    {
        var result = LookupV(schema, table, IntPtr.Zero, out var error);
        if (error != IntPtr.Zero)
        {
            ErrorFree(error);
            throw new IOException("The Secret Service didn't answer.");
        }
        if (result == IntPtr.Zero) return null;
        try { return Marshal.PtrToStringUTF8(result); }
        finally { PasswordFree(result); }
    });

    public static bool Store(string secret) => WithSchema((schema, table) =>
    {
        var label = Marshal.StringToCoTaskMemUTF8(Label);
        var password = Marshal.StringToCoTaskMemUTF8(secret);
        try
        {
            var ok = StoreV(schema, table, IntPtr.Zero, label, password, IntPtr.Zero, out var error);
            if (error != IntPtr.Zero) ErrorFree(error);
            return ok != 0 && error == IntPtr.Zero;
        }
        finally
        {
            Marshal.FreeCoTaskMem(label);
            Marshal.ZeroFreeCoTaskMemUTF8(password);
        }
    });
}

/// <summary>"Start when you log in" through an XDG autostart entry (no root needed).</summary>
public static class Autostart
{
    public static string EntryPath => Path.Combine(AppPaths.AutostartDirectory, AppPaths.AppId + ".desktop");

    public static bool IsEnabled => File.Exists(EntryPath);

    /// <summary>The command that starts this copy: the AppImage itself when running from one.</summary>
    public static string LaunchCommand
    {
        get
        {
            var appImage = Environment.GetEnvironmentVariable("APPIMAGE");
            var path = !string.IsNullOrEmpty(appImage) ? appImage : Environment.ProcessPath ?? "codex-meter";
            return Quote(path) + " --minimized";
        }
    }

    internal static string Quote(string path) =>
        path.IndexOfAny([' ', '"', '\'', '\\', '$', '`']) < 0 ? path : "\"" + path.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("$", "\\$").Replace("`", "\\`") + "\"";

    internal static string Entry(string command) => $"""
        [Desktop Entry]
        Type=Application
        Name=Codex Meter
        Comment=Coding-agent usage limits
        Exec={command}
        Icon={AppPaths.AppId}
        Terminal=false
        X-GNOME-Autostart-enabled=true
        NoDisplay=true

        """;

    public static void Set(bool enabled)
    {
        if (enabled)
        {
            Directory.CreateDirectory(AppPaths.AutostartDirectory);
            File.WriteAllText(EntryPath, Entry(LaunchCommand));
        }
        else if (File.Exists(EntryPath))
        {
            File.Delete(EntryPath);
        }
    }
}

public static class Browser
{
    /// <summary>Variables the AppImage sets for its bundled GTK; other programs must not inherit them.</summary>
    private static readonly string[] AppImageVariables =
    [
        "LD_LIBRARY_PATH", "GTK_DATA_PREFIX", "GTK_EXE_PREFIX", "GTK_PATH", "GTK_THEME", "GDK_BACKEND",
        "GSETTINGS_SCHEMA_DIR", "GI_TYPELIB_PATH", "GDK_PIXBUF_MODULE_FILE", "GIO_MODULE_DIR",
    ];

    /// <summary>Opens a web page or folder with the desktop's default handler.</summary>
    public static Task OpenAsync(Uri url)
    {
        var appDir = Environment.GetEnvironmentVariable("CODEX_METER_APPDIR");
        if (!string.IsNullOrEmpty(appDir) && OpenOutsideAppImage(url, appDir)) return Task.CompletedTask;
        try
        {
            Gio.AppInfoHelper.LaunchDefaultForUri(url.AbsoluteUri, null);
        }
        catch (Exception error)
        {
            AppLog.Write("Couldn't open " + url.Scheme + " link through GIO: " + error.Message);
            OpenOutsideAppImage(url, appDir);
        }
        return Task.CompletedTask;
    }

    /// <summary>xdg-open with the AppImage's library and GTK variables removed, so the browser uses its own.</summary>
    private static bool OpenOutsideAppImage(Uri url, string? appDir)
    {
        try
        {
            var start = new System.Diagnostics.ProcessStartInfo("xdg-open", url.AbsoluteUri) { UseShellExecute = false };
            foreach (var name in AppImageVariables) start.Environment.Remove(name);
            if (!string.IsNullOrEmpty(appDir) && start.Environment.TryGetValue("XDG_DATA_DIRS", out var dirs) && dirs is not null)
                start.Environment["XDG_DATA_DIRS"] = string.Join(':', dirs.Split(':').Where(d => !d.StartsWith(appDir, StringComparison.Ordinal)));
            using var process = System.Diagnostics.Process.Start(start);
            return process is not null;
        }
        catch (Exception error)
        {
            AppLog.Write("xdg-open failed: " + error.Message);
            return false;
        }
    }
}
