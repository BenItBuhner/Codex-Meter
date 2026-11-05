namespace CodexMeter.Linux.Widgets;

/// <summary>The few styles libadwaita doesn't already provide. Colors come from the theme's named colors.</summary>
public static class Style
{
    private const string Css = """
        .meter-card { padding: 18px 20px; }
        .extra-card { padding: 18px 20px; }
        .meter-ring.level-normal { color: @accent_bg_color; }
        .meter-ring.level-caution { color: @warning_color; }
        .meter-ring.level-critical { color: @error_color; }
        .plan-badge {
          background-color: @accent_bg_color;
          color: @accent_fg_color;
          border-radius: 999px;
          padding: 2px 10px 3px 10px;
          font-weight: bold;
          font-size: 0.85em;
        }
        .demo-badge {
          background-color: alpha(@warning_color, 0.18);
          color: @warning_fg_color;
          border-radius: 6px;
          padding: 1px 8px 2px 8px;
          font-weight: bold;
          font-size: 0.85em;
        }
        .status-card { border-radius: 12px; padding: 12px 16px; }
        .status-card.status-info { background-color: alpha(@accent_color, 0.12); }
        .status-card.status-warning { background-color: alpha(@warning_color, 0.14); }
        .status-card.status-error { background-color: alpha(@error_color, 0.12); }
        .status-card.status-info image { color: @accent_color; }
        .status-card.status-warning image { color: @warning_color; }
        .status-card.status-error image { color: @error_color; }
        .sidebar-value { font-feature-settings: "tnum"; }
        .sidebar-value.level-caution { color: @warning_color; }
        .sidebar-value.level-critical { color: @error_color; }
        .provider-hero { margin-bottom: 6px; }
        .history-chart { color: @accent_color; }
        progressbar.history-bar > trough { min-height: 6px; }
        progressbar.history-bar > trough > progress { min-height: 6px; }
        progressbar.history-bar.level-caution > trough > progress { background-color: @warning_color; }
        progressbar.history-bar.level-critical > trough > progress { background-color: @error_color; }
        .device-code { letter-spacing: 4px; margin: 6px 0; }
        .unconfirmed-tag {
          background-color: alpha(@warning_color, 0.16);
          color: @warning_fg_color;
          border-radius: 6px;
          padding: 1px 7px 2px 7px;
          font-size: 0.8em;
          font-weight: bold;
        }
        .sidebar-dot { min-width: 8px; min-height: 8px; border-radius: 999px; }
        .sidebar-dot.level-caution { background-color: @warning_color; }
        .sidebar-dot.level-critical { background-color: @error_color; }
        """;

    public static void Install()
    {
        var provider = Gtk.CssProvider.New();
        provider.LoadFromString(Css);
        var display = Gdk.Display.GetDefault();
        if (display is not null)
            Gtk.StyleContext.AddProviderForDisplay(display, provider, 600 /* GTK_STYLE_PROVIDER_PRIORITY_APPLICATION */);
    }
}
