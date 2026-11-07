namespace CodexMeter.Linux.Views;

/// <summary>A page shown in the content pane of the split view.</summary>
public interface IPage
{
    /// <summary>"codex", "claude_code", "cursor", "accounts" or "settings".</summary>
    string Tag { get; }

    Adw.NavigationPage Page { get; }

    /// <summary>The scrolled area, for screenshots that show the rest of a long page.</summary>
    Gtk.ScrolledWindow Scroller { get; }

    /// <summary>Stops listening to state changes; called when another page replaces this one.</summary>
    void Detach();
}
