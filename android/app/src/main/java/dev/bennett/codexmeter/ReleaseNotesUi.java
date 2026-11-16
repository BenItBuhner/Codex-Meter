package dev.bennett.codexmeter;

import android.content.Context;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

/** Shared TextView wiring for rendered GitHub release notes. */
public final class ReleaseNotesUi {
    private ReleaseNotesUi() {
    }

    public static TextView create(Context context, String markdown, boolean dark) {
        TextView view = Ui.text(context, "", 14, Ui.mainText(dark));
        apply(view, markdown);
        return view;
    }

    public static void apply(TextView view, String markdown) {
        String html = ReleaseNotesMarkdown.toHtml(markdown);
        if (html.isEmpty()) {
            view.setText("");
            return;
        }
        view.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
    }
}
