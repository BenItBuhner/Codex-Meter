using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>
/// The "What's new" page after a big update (<see cref="WhatsNew"/>), filling the window as a GNOME
/// status page: the app icon large over the title, the new features in a boxed list with their
/// symbolic icons, and a suggested Continue pill at the bottom.
/// </summary>
public sealed class WhatsNewView
{
    public WhatsNewView(Action onDone)
    {
        var status = Adw.StatusPage.New();
        status.SetIconName(AppPaths.AppId);
        status.SetTitle(WhatsNew.Title);
        status.SetName("WhatsNew");

        var list = Gtk.ListBox.New();
        list.AddCssClass("boxed-list");
        list.SetSelectionMode(Gtk.SelectionMode.None);
        foreach (var feature in WhatsNew.Features(WhatsNew.Platform.Linux))
        {
            var row = Adw.ActionRow.New();
            row.SetTitle(feature.Title);
            row.SetSubtitle(feature.Body);
            row.SetSubtitleLines(0);
            var icon = Gtk.Image.NewFromIconName(IconName(feature.Id));
            icon.SetPixelSize(24);
            icon.AddCssClass("accent");
            icon.SetMarginEnd(6);
            row.AddPrefix(icon);
            list.Append(row);
        }
        var clamp = Adw.Clamp.New();
        clamp.SetMaximumSize(520);
        clamp.SetChild(list);
        status.SetChild(clamp);

        var next = Gtk.Button.NewWithLabel(WhatsNew.Button);
        next.AddCssClass("pill");
        next.AddCssClass("suggested-action");
        next.SetHalign(Gtk.Align.Center);
        next.SetSizeRequest(240, -1);
        next.SetName("WhatsNewContinue");
        next.OnClicked += (_, _) => onDone();
        var bar = Gtk.Box.New(Gtk.Orientation.Vertical, 0);
        bar.SetMarginTop(12);
        bar.SetMarginBottom(18);
        bar.Append(next);

        var header = Adw.HeaderBar.New();
        header.SetShowTitle(false);
        View = Adw.ToolbarView.New();
        View.AddTopBar(header);
        View.SetContent(status);
        View.AddBottomBar(bar);
        Continue = next;
    }

    /// <summary>The whole page: a title bar with the window buttons, the status page and the button bar.</summary>
    public Adw.ToolbarView View { get; }

    public Gtk.Button Continue { get; }

    /// <summary>
    /// Symbolic icons for each feature, all from the set GTK 4 itself ships, so they show even where
    /// the Adwaita icon theme is missing or partial.
    /// </summary>
    public static string IconName(string id) => id switch
    {
        "providers" => "view-grid-symbolic",
        "priority" => "emoji-flags-symbolic",
        "resets" => "view-refresh-symbolic",
        "live" => "document-open-recent-symbolic",
        "alerts" => "emblem-important-symbolic",
        "value" => "emoji-objects-symbolic",
        "status" => "dialog-information-symbolic",
        "updates" => "folder-download-symbolic",
        "devices" => "user-desktop-symbolic",
        _ => "emblem-system-symbolic",
    };
}
