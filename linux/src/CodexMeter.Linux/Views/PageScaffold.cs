namespace CodexMeter.Linux.Views;

/// <summary>Header bar + scrolled, width-clamped content: the shape every content page shares.</summary>
public sealed class PageScaffold
{
    public PageScaffold(string title, string tag)
    {
        Header = Adw.HeaderBar.New();
        Body = Gtk.Box.New(Gtk.Orientation.Vertical, 12);
        Body.SetMarginTop(24);
        Body.SetMarginBottom(32);
        Body.SetMarginStart(12);
        Body.SetMarginEnd(12);
        var clamp = Adw.Clamp.New();
        clamp.SetMaximumSize(1000);
        clamp.SetTighteningThreshold(700);
        clamp.SetChild(Body);
        Scroller = Gtk.ScrolledWindow.New();
        Scroller.SetPolicy(Gtk.PolicyType.Never, Gtk.PolicyType.Automatic);
        Scroller.SetChild(clamp);
        Scroller.SetVexpand(true);
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Header);
        view.SetContent(Scroller);
        Page = Adw.NavigationPage.New(view, title);
        Page.SetTag(tag);
    }

    public Adw.NavigationPage Page { get; }
    public Adw.HeaderBar Header { get; }
    public Gtk.Box Body { get; }
    public Gtk.ScrolledWindow Scroller { get; }

    public static void Clear(Gtk.Box box)
    {
        while (box.GetFirstChild() is { } child) box.Remove(child);
    }

    /// <summary>A tinted notice with an icon, title and message (info, warning or error).</summary>
    public static Gtk.Widget Status(string kind, string title, string message, string? name = null)
    {
        var box = Gtk.Box.New(Gtk.Orientation.Horizontal, 12);
        box.AddCssClass("status-card");
        box.AddCssClass("status-" + kind);
        var icon = Gtk.Image.NewFromIconName(kind switch
        {
            "error" => "dialog-error-symbolic",
            "warning" => "dialog-warning-symbolic",
            _ => "dialog-information-symbolic",
        });
        icon.SetValign(Gtk.Align.Start);
        icon.SetMarginTop(2);
        box.Append(icon);
        var text = Gtk.Box.New(Gtk.Orientation.Vertical, 2);
        text.SetHexpand(true);
        text.Append(Widgets.Cards.Label(title, "heading"));
        text.Append(Widgets.Cards.Label(message));
        box.Append(text);
        if (name is not null) box.SetName(name);
        return box;
    }
}
