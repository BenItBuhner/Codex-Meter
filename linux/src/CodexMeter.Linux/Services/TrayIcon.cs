using CodexMeter.Core.Formatting;
using Tmds.DBus.Protocol;

namespace CodexMeter.Linux.Services;

/// <summary>
/// The tray icon, as a freedesktop StatusNotifierItem with a com.canonical.dbusmenu menu. KDE
/// Plasma, Cinnamon, Xfce, Budgie, LXQt and GNOME with the AppIndicator extension (on by default
/// in Ubuntu) show it. The icon draws the first provider's remaining percentage, the tooltip lists
/// every signed-in provider, and the menu opens the app, refreshes or quits. It re-registers when
/// the panel restarts. Without a tray host it stays hidden and the window behaves normally.
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private const string ItemPath = "/StatusNotifierItem";
    private const string MenuPath = "/MenuBar";
    private const string ItemInterface = "org.kde.StatusNotifierItem";
    private const string MenuInterface = "com.canonical.dbusmenu";
    private const string WatcherName = "org.kde.StatusNotifierWatcher";

    private readonly AppState state;
    private readonly Action open;
    private readonly Action refresh;
    private readonly Action quit;
    private readonly SynchronizationContext? ui;
    private Connection? connection;
    private string busName = "";
    private bool registered;
    private bool connected;
    private bool disposed;
    private uint revision = 1;
    private Snapshot current;

    /// <summary>Everything the D-Bus side reads, captured on the UI thread.</summary>
    private sealed record Snapshot(bool Visible, string ToolTip, (int Size, byte[] Argb)[] Pixmaps, string[] Lines);

    public TrayIcon(AppState state, Action open, Action refresh, Action quit)
    {
        this.state = state;
        this.open = open;
        this.refresh = refresh;
        this.quit = quit;
        ui = SynchronizationContext.Current;
        current = Capture();
        state.ProviderChanged += _ => Update();
        state.ProvidersChanged += Update;
        state.Settings.Changed += (_, _) => Update();
    }

    /// <summary>True while a tray host has accepted the icon and the setting is on.</summary>
    public bool IsShowing => registered && state.Settings.Current.ShowTrayIcon;

    public async Task StartAsync()
    {
        try
        {
            var address = Address.Session;
            if (string.IsNullOrEmpty(address)) return;
            connection = new Connection(address);
            await connection.ConnectAsync();
            connection.AddMethodHandler(new Handler(this, ItemPath));
            connection.AddMethodHandler(new Handler(this, MenuPath));
            busName = $"org.kde.StatusNotifierItem-{Environment.ProcessId}-1";
            await CallAsync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su",
                (ref MessageWriter w) => { w.WriteString(busName); w.WriteUInt32(4 /* DBUS_NAME_FLAG_DO_NOT_QUEUE */); });
            // Register again whenever a panel (re)starts its watcher.
            await connection.AddMatchAsync(
                new MatchRule
                {
                    Type = MessageType.Signal,
                    Sender = "org.freedesktop.DBus",
                    Interface = "org.freedesktop.DBus",
                    Member = "NameOwnerChanged",
                    Arg0 = WatcherName,
                },
                (Message message, object? _) =>
                {
                    var reader = message.GetBodyReader();
                    reader.ReadString();
                    reader.ReadString();
                    return reader.ReadString();
                },
                (Exception? error, string newOwner, object? _, object? _) =>
                {
                    if (error is not null || disposed) return;
                    registered = false;
                    if (newOwner.Length > 0) _ = RegisterAsync();
                },
                ObserverFlags.None, null, null, emitOnCapturedContext: false);
            connected = true;
            await RegisterAsync();
        }
        catch (Exception error)
        {
            AppLog.Write("Tray unavailable: " + error);
        }
    }

    private async Task RegisterAsync()
    {
        if (connection is null) return;
        try
        {
            await CallAsync(WatcherName, "/StatusNotifierWatcher", WatcherName, "RegisterStatusNotifierItem", "s",
                (ref MessageWriter w) => w.WriteString(busName));
            registered = true;
        }
        catch (DBusException error)
        {
            registered = false;
            AppLog.Write("No tray host (StatusNotifierWatcher): " + error.ErrorName);
        }
    }

    private async Task CallAsync(string destination, string path, string @interface, string member, string signature, WriteBody body)
    {
        MessageBuffer message;
        var writer = connection!.GetMessageWriter();
        try
        {
            writer.WriteMethodCallHeader(destination, path, @interface, member, signature, MessageFlags.None);
            body(ref writer);
            message = writer.CreateMessage();
        }
        finally { writer.Dispose(); }
        await connection.CallMethodAsync(message);
    }

    private void Emit(string path, string @interface, string member, string? signature = null, WriteBody? body = null)
    {
        if (connection is null || !connected) return;
        try
        {
            var writer = connection.GetMessageWriter();
            try
            {
                writer.WriteSignalHeader(null, path, @interface, member, signature);
                body?.Invoke(ref writer);
                connection.TrySendMessage(writer.CreateMessage());
            }
            finally { writer.Dispose(); }
        }
        catch (Exception error)
        {
            AppLog.Write("Tray signal failed: " + error.Message);
        }
    }

    /// <summary>MessageWriter is a ref struct: it must be passed by reference or the writes are lost.</summary>
    private delegate void WriteBody(ref MessageWriter writer);


    /// <summary>Text for the tooltip, e.g. "Codex: 62% left (5-hour)".</summary>
    public static IReadOnlyList<string> SummaryLines(AppState state)
    {
        var lines = new List<string>();
        var mode = state.Settings.Current.DisplayMode;
        foreach (var provider in state.EnabledProviders)
        {
            var entry = state[provider];
            if (!entry.SignedIn) continue;
            var window = entry.Usage?.FiveHour ?? entry.Usage?.LongWindow;
            lines.Add(window is null
                ? $"{provider.DisplayName}: no data yet"
                : $"{provider.DisplayName}: {UsageFormat.Percent(window, mode)} ({UsageFormat.WindowName(window.WindowSeconds).ToLowerInvariant()})");
        }
        return lines;
    }

    private Snapshot Capture()
    {
        var lines = SummaryLines(state).ToArray();
        var first = state.EnabledProviders.Select(p => state[p]).FirstOrDefault(e => e.SignedIn && e.Usage is not null);
        var window = first?.Usage?.FiveHour ?? first?.Usage?.LongWindow;
        var value = window is null ? (int?)null : UsageFormat.Value(window, state.Settings.Current.DisplayMode);
        var remaining = window?.RemainingPercent ?? 100;
        var pixmaps = new[] { 22, 32, 48 }.Select(size => (size, ToArgb(Draw(value, remaining, size), size))).ToArray();
        return new Snapshot(state.Settings.Current.ShowTrayIcon, lines.Length == 0 ? "No accounts connected" : string.Join("\n", lines),
            pixmaps, lines);
    }

    public void Update()
    {
        if (disposed) return;
        var previous = current;
        current = Capture();
        if (connection is null || !connected) return;
        if (previous.Visible != current.Visible)
            Emit(ItemPath, ItemInterface, "NewStatus", "s", (ref MessageWriter w) => w.WriteString(current.Visible ? "Active" : "Passive"));
        Emit(ItemPath, ItemInterface, "NewIcon");
        Emit(ItemPath, ItemInterface, "NewToolTip");
        if (!previous.Lines.SequenceEqual(current.Lines))
        {
            revision++;
            Emit(MenuPath, MenuInterface, "LayoutUpdated", "ui", (ref MessageWriter w) => { w.WriteUInt32(revision); w.WriteInt32(0); });
        }
    }

    private void OnUi(Action action)
    {
        if (ui is null) action();
        else ui.Post(_ => { if (!disposed) action(); }, null);
    }

    /// <summary>A rounded badge with the percentage on a color that turns amber, then red, as the allowance runs low.</summary>
    internal static Cairo.ImageSurface Draw(int? value, int remainingPercent, int size)
    {
        var surface = new Cairo.ImageSurface(Cairo.Format.Argb32, size, size);
        var cr = new Cairo.Context(surface);
        var (r, g, b) = value is null ? (0x60, 0x5E, 0x5C)
            : remainingPercent <= 15 ? (0xC0, 0x1C, 0x28)
            : remainingPercent <= 35 ? (0xC6, 0x6A, 0x00)
            : (0x53, 0x45, 0xF3);
        var radius = size * 0.22;
        var inset = size / 32.0;
        double x = inset, y = inset, w = size - 2 * inset, h = size - 2 * inset;
        cr.NewPath();
        cr.Arc(x + w - radius, y + radius, radius, -Math.PI / 2, 0);
        cr.Arc(x + w - radius, y + h - radius, radius, 0, Math.PI / 2);
        cr.Arc(x + radius, y + h - radius, radius, Math.PI / 2, Math.PI);
        cr.Arc(x + radius, y + radius, radius, Math.PI, 3 * Math.PI / 2);
        cr.ClosePath();
        cr.SetSourceRgb(r / 255.0, g / 255.0, b / 255.0);
        cr.Fill();
        var text = value is null ? "–" : value.Value.ToString();
        cr.SelectFontFace("Sans", Cairo.FontSlant.Normal, Cairo.FontWeight.Bold);
        cr.SetFontSize(size * (text.Length >= 3 ? 0.40 : 0.56));
        cr.TextExtents(text, out var extents);
        cr.SetSourceRgb(1, 1, 1);
        cr.MoveTo((size - extents.Width) / 2 - extents.XBearing, (size - extents.Height) / 2 - extents.YBearing);
        cr.ShowText(text);
        cr.Dispose();
        surface.Flush();
        return surface;
    }

    /// <summary>Cairo's native-endian premultiplied pixels to the ARGB32 big-endian, straight-alpha bytes SNI expects.</summary>
    internal static byte[] ToArgb(Cairo.ImageSurface surface, int size)
    {
        var data = surface.GetData();
        var stride = surface.Stride;
        var output = new byte[size * size * 4];
        for (var row = 0; row < size; row++)
        {
            for (var column = 0; column < size; column++)
            {
                var pixel = BitConverter.ToUInt32(data.Slice(row * stride + column * 4, 4));
                var a = (byte)(pixel >> 24);
                byte Unpremultiply(uint channel) => a == 0 ? (byte)0 : (byte)Math.Min(255, channel * 255 / a);
                var index = (row * size + column) * 4;
                output[index] = a;
                output[index + 1] = Unpremultiply((pixel >> 16) & 0xFF);
                output[index + 2] = Unpremultiply((pixel >> 8) & 0xFF);
                output[index + 3] = Unpremultiply(pixel & 0xFF);
            }
        }
        surface.Dispose();
        return output;
    }

    /// <summary>Writes sample badges for the screenshot gallery.</summary>
    public static bool SaveSamples(string directory)
    {
        try
        {
            foreach (var value in new[] { 62, 28, 7 })
            {
                using var surface = Draw(value, value, 64);
                surface.WriteToPng(Path.Combine(directory, $"tray-{value}.png"));
            }
            return true;
        }
        catch (Exception error)
        {
            AppLog.Write("Tray samples failed: " + error.Message);
            return false;
        }
    }

    public void Dispose()
    {
        disposed = true;
        registered = false;
        connection?.Dispose();
        connection = null;
    }

    // ---- D-Bus side (runs on the connection's thread; reads only the immutable snapshot) ----

    private const string ItemXml = """
        <interface name="org.kde.StatusNotifierItem">
          <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
          <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
          <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
          <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
          <signal name="NewTitle"/><signal name="NewIcon"/><signal name="NewToolTip"/>
          <signal name="NewStatus"><arg name="status" type="s"/></signal>
          <property name="Category" type="s" access="read"/>
          <property name="Id" type="s" access="read"/>
          <property name="Title" type="s" access="read"/>
          <property name="Status" type="s" access="read"/>
          <property name="WindowId" type="i" access="read"/>
          <property name="IconName" type="s" access="read"/>
          <property name="IconPixmap" type="a(iiay)" access="read"/>
          <property name="OverlayIconName" type="s" access="read"/>
          <property name="AttentionIconName" type="s" access="read"/>
          <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
          <property name="ItemIsMenu" type="b" access="read"/>
          <property name="Menu" type="o" access="read"/>
        </interface>
        """;

    private const string MenuXml = """
        <interface name="com.canonical.dbusmenu">
          <method name="GetLayout"><arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/><arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/></method>
          <method name="GetGroupProperties"><arg type="ai" direction="in"/><arg type="as" direction="in"/><arg type="a(ia{sv})" direction="out"/></method>
          <method name="GetProperty"><arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
          <method name="Event"><arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/><arg type="u" direction="in"/></method>
          <method name="EventGroup"><arg type="a(isvu)" direction="in"/><arg type="ai" direction="out"/></method>
          <method name="AboutToShow"><arg type="i" direction="in"/><arg type="b" direction="out"/></method>
          <method name="AboutToShowGroup"><arg type="ai" direction="in"/><arg type="ai" direction="out"/><arg type="ai" direction="out"/></method>
          <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})"/><arg type="a(ias)"/></signal>
          <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
          <property name="Version" type="u" access="read"/>
          <property name="TextDirection" type="s" access="read"/>
          <property name="Status" type="s" access="read"/>
          <property name="IconThemePath" type="as" access="read"/>
        </interface>
        """;

    private const int OpenId = 1, RefreshId = 2, QuitId = 3, SeparatorId = 4, SecondSeparatorId = 5, FirstLineId = 100;

    private sealed record MenuItem(int Id, string? Label, bool Enabled = true, bool Separator = false);

    private IReadOnlyList<MenuItem> MenuItems()
    {
        var snapshot = current;
        var items = new List<MenuItem> { new(OpenId, "Open Codex Meter"), new(SeparatorId, null, Separator: true) };
        for (var i = 0; i < snapshot.Lines.Length; i++) items.Add(new MenuItem(FirstLineId + i, snapshot.Lines[i], Enabled: false));
        if (snapshot.Lines.Length > 0) items.Add(new MenuItem(SecondSeparatorId, null, Separator: true));
        items.Add(new MenuItem(RefreshId, "Refresh Now"));
        items.Add(new MenuItem(QuitId, "Quit"));
        return items;
    }

    private static void WriteItemProperties(ref MessageWriter w, MenuItem item)
    {
        var dict = w.WriteDictionaryStart();
        if (item.Separator)
        {
            w.WriteDictionaryEntryStart(); w.WriteString("type"); w.WriteVariantString("separator");
        }
        else
        {
            w.WriteDictionaryEntryStart(); w.WriteString("label"); w.WriteVariantString(item.Label!.Replace("_", "__"));
            w.WriteDictionaryEntryStart(); w.WriteString("enabled"); w.WriteVariantBool(item.Enabled);
            w.WriteDictionaryEntryStart(); w.WriteString("visible"); w.WriteVariantBool(true);
        }
        w.WriteDictionaryEnd(dict);
    }

    private void WriteLayout(ref MessageWriter w)
    {
        w.WriteStructureStart();
        w.WriteInt32(0);
        var dict = w.WriteDictionaryStart();
        w.WriteDictionaryEntryStart(); w.WriteString("children-display"); w.WriteVariantString("submenu");
        w.WriteDictionaryEnd(dict);
        var children = w.WriteArrayStart(DBusType.Variant);
        foreach (var item in MenuItems())
        {
            w.WriteSignature("(ia{sv}av)");
            w.WriteStructureStart();
            w.WriteInt32(item.Id);
            WriteItemProperties(ref w, item);
            var none = w.WriteArrayStart(DBusType.Variant);
            w.WriteArrayEnd(none);
        }
        w.WriteArrayEnd(children);
    }

    private void WritePixmaps(ref MessageWriter w)
    {
        var array = w.WriteArrayStart(DBusType.Struct);
        foreach (var (size, argb) in current.Pixmaps)
        {
            w.WriteStructureStart();
            w.WriteInt32(size);
            w.WriteInt32(size);
            w.WriteArray(argb);
        }
        w.WriteArrayEnd(array);
    }

    private bool WriteItemProperty(ref MessageWriter w, string name)
    {
        switch (name)
        {
            case "Category": w.WriteVariantString("ApplicationStatus"); return true;
            case "Id": w.WriteVariantString("codex-meter"); return true;
            case "Title": w.WriteVariantString("Codex Meter"); return true;
            case "Status": w.WriteVariantString(current.Visible ? "Active" : "Passive"); return true;
            case "WindowId": w.WriteVariantInt32(0); return true;
            case "IconName": w.WriteVariantString(""); return true;
            case "OverlayIconName": w.WriteVariantString(""); return true;
            case "AttentionIconName": w.WriteVariantString(""); return true;
            case "ItemIsMenu": w.WriteVariantBool(false); return true;
            case "Menu": w.WriteVariantObjectPath(MenuPath); return true;
            case "IconPixmap":
                w.WriteSignature("a(iiay)");
                WritePixmaps(ref w);
                return true;
            case "ToolTip":
                w.WriteSignature("(sa(iiay)ss)");
                w.WriteStructureStart();
                w.WriteString("");
                WritePixmaps(ref w);
                w.WriteString("Codex Meter");
                w.WriteString(current.ToolTip);
                return true;
            default: return false;
        }
    }

    private static readonly string[] ItemProperties =
        ["Category", "Id", "Title", "Status", "WindowId", "IconName", "IconPixmap", "OverlayIconName", "AttentionIconName", "ToolTip", "ItemIsMenu", "Menu"];

    private static bool WriteMenuProperty(ref MessageWriter w, string name)
    {
        switch (name)
        {
            case "Version": w.WriteVariantUInt32(3); return true;
            case "TextDirection": w.WriteVariantString("ltr"); return true;
            case "Status": w.WriteVariantString("normal"); return true;
            case "IconThemePath":
                w.WriteSignature("as");
                w.WriteArray(Array.Empty<string>());
                return true;
            default: return false;
        }
    }

    private static readonly string[] MenuProperties = ["Version", "TextDirection", "Status", "IconThemePath"];

    private void OnMenuEvent(int id, string eventId)
    {
        if (eventId != "clicked") return;
        switch (id)
        {
            case OpenId: OnUi(open); break;
            case RefreshId: OnUi(refresh); break;
            case QuitId: OnUi(quit); break;
        }
    }

    private sealed class Handler(TrayIcon owner, string path) : IMethodHandler
    {
        public string Path => path;

        public bool RunMethodHandlerSynchronously(Message message) => true;

        public ValueTask HandleMethodAsync(MethodContext context)
        {
            try { Handle(context); }
            catch (Exception error)
            {
                if (!context.ReplySent && !context.NoReplyExpected) context.ReplyError("org.freedesktop.DBus.Error.Failed", error.Message);
            }
            return default;
        }

        private delegate bool ReplyBody(ref MessageWriter writer);

        /// <summary>Writes a reply; a body that returns false sends <paramref name="error"/> instead.</summary>
        private static void Reply(MethodContext context, string? signature, ReplyBody body, string error = "org.freedesktop.DBus.Error.InvalidArgs")
        {
            if (context.NoReplyExpected) return;
            var writer = context.CreateReplyWriter(signature!);
            try
            {
                if (body(ref writer)) context.Reply(writer.CreateMessage());
                else context.ReplyError(error, "Not supported.");
            }
            finally { writer.Dispose(); }
        }

        private static void Empty(MethodContext context) => Reply(context, null, (ref MessageWriter _) => true);

        private void Handle(MethodContext context)
        {
            var request = context.Request;
            var iface = request.InterfaceAsString;
            var member = request.MemberAsString;
            var isItem = path == ItemPath;

            if (context.IsDBusIntrospectRequest)
            {
                context.ReplyIntrospectXml([System.Text.Encoding.UTF8.GetBytes(isItem ? ItemXml : MenuXml)]);
                return;
            }

            if (iface == "org.freedesktop.DBus.Properties")
            {
                var reader = request.GetBodyReader();
                var target = reader.ReadString();
                if (member == "Get")
                {
                    var name = reader.ReadString();
                    Reply(context, "v", (ref MessageWriter w) => isItem
                        ? target == ItemInterface && owner.WriteItemProperty(ref w, name)
                        : target == MenuInterface && WriteMenuProperty(ref w, name), "org.freedesktop.DBus.Error.UnknownProperty");
                    return;
                }
                if (member == "GetAll")
                {
                    Reply(context, "a{sv}", (ref MessageWriter w) =>
                    {
                        var dict = w.WriteDictionaryStart();
                        if (isItem && target == ItemInterface)
                            foreach (var name in ItemProperties) { w.WriteDictionaryEntryStart(); w.WriteString(name); owner.WriteItemProperty(ref w, name); }
                        else if (!isItem && target == MenuInterface)
                            foreach (var name in MenuProperties) { w.WriteDictionaryEntryStart(); w.WriteString(name); WriteMenuProperty(ref w, name); }
                        w.WriteDictionaryEnd(dict);
                        return true;
                    });
                    return;
                }
                context.ReplyError("org.freedesktop.DBus.Error.PropertyReadOnly", "Read-only");
                return;
            }

            if (isItem && iface == ItemInterface)
            {
                if (member is "Activate" or "SecondaryActivate") owner.OnUi(owner.open);
                // ContextMenu and Scroll: hosts show the dbusmenu themselves.
                Empty(context);
                return;
            }

            if (!isItem && iface == MenuInterface)
            {
                var reader = request.GetBodyReader();
                switch (member)
                {
                    case "GetLayout":
                        Reply(context, "u(ia{sv}av)", (ref MessageWriter w) =>
                        {
                            w.WriteUInt32(owner.revision);
                            owner.WriteLayout(ref w);
                            return true;
                        });
                        return;
                    case "GetGroupProperties":
                    {
                        var ids = reader.ReadArrayOfInt32();
                        Reply(context, "a(ia{sv})", (ref MessageWriter w) =>
                        {
                            var array = w.WriteArrayStart(DBusType.Struct);
                            foreach (var item in owner.MenuItems().Where(i => ids.Length == 0 || ids.Contains(i.Id)))
                            {
                                w.WriteStructureStart();
                                w.WriteInt32(item.Id);
                                WriteItemProperties(ref w, item);
                            }
                            w.WriteArrayEnd(array);
                            return true;
                        });
                        return;
                    }
                    case "GetProperty":
                    {
                        var id = reader.ReadInt32();
                        var name = reader.ReadString();
                        var item = owner.MenuItems().FirstOrDefault(i => i.Id == id);
                        Reply(context, "v", (ref MessageWriter w) =>
                        {
                            if (name == "label" && item?.Label is { } label) w.WriteVariantString(label);
                            else if (name == "enabled" && item is not null) w.WriteVariantBool(item.Enabled);
                            else if (name == "visible") w.WriteVariantBool(true);
                            else if (name == "type" && item is { Separator: true }) w.WriteVariantString("separator");
                            else return false;
                            return true;
                        });
                        return;
                    }
                    case "Event":
                    {
                        var id = reader.ReadInt32();
                        var eventId = reader.ReadString();
                        owner.OnMenuEvent(id, eventId);
                        Empty(context);
                        return;
                    }
                    case "EventGroup":
                    {
                        var end = reader.ReadArrayStart(DBusType.Struct);
                        while (reader.HasNext(end))
                        {
                            reader.AlignStruct();
                            var id = reader.ReadInt32();
                            var eventId = reader.ReadString();
                            reader.ReadVariantValue();
                            reader.ReadUInt32();
                            owner.OnMenuEvent(id, eventId);
                        }
                        Reply(context, "ai", (ref MessageWriter w) => { w.WriteArray(Array.Empty<int>()); return true; });
                        return;
                    }
                    case "AboutToShow":
                        Reply(context, "b", (ref MessageWriter w) => { w.WriteBool(false); return true; });
                        return;
                    case "AboutToShowGroup":
                        Reply(context, "aiai", (ref MessageWriter w) =>
                        {
                            w.WriteArray(Array.Empty<int>());
                            w.WriteArray(Array.Empty<int>());
                            return true;
                        });
                        return;
                }
            }
            context.ReplyError("org.freedesktop.DBus.Error.UnknownMethod", $"{iface}.{member}");
        }
    }
}
