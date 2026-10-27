using CodexMeter.App.Controls;
using CodexMeter.App.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>
/// A page built in code: a scrolling column with the Windows 11 page margins. Pages subscribe to
/// app state while they are on screen and drop the subscriptions when the window swaps them out.
/// </summary>
public abstract class PageBase : UserControl
{
    protected readonly AppState State = App.Current!.State;

    protected PageBase(double maxWidth)
    {
        Body = new StackPanel { MaxWidth = maxWidth, Padding = new Thickness(36, 20, 36, 36), Spacing = 4 };
        Scroller = new ScrollViewer { Content = Body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        AutomationProperties.SetAutomationId(Scroller, "PageScroller");
        Content = Scroller;
        Loaded += (_, _) => Attach();
        Unloaded += (_, _) => Detach();
        SizeChanged += (_, args) =>
        {
            var narrow = args.NewSize.Width < 640;
            Body.Padding = narrow ? new Thickness(16, 12, 16, 24) : new Thickness(36, 20, 36, 36);
        };
    }

    public ScrollViewer Scroller { get; }

    protected StackPanel Body { get; }

    protected abstract void Attach();

    protected abstract void Detach();

    protected static TextBlock PageTitle(string text, string automationId)
    {
        var title = Cards.Text(text, "TitleTextBlockStyle", wrap: false);
        title.Margin = new Thickness(0, 0, 0, 4);
        AutomationProperties.SetAutomationId(title, automationId);
        AutomationProperties.SetHeadingLevel(title, AutomationHeadingLevel.Level1);
        return title;
    }

    protected static TextBlock SectionHeader(string text)
    {
        var header = Cards.Text(text, "BodyStrongTextBlockStyle", wrap: false);
        header.Margin = new Thickness(1, 24, 0, 6);
        AutomationProperties.SetHeadingLevel(header, AutomationHeadingLevel.Level2);
        return header;
    }
}
