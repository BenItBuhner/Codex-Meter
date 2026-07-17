package dev.bennett.codexmeter;

import android.content.Context;
import android.os.Build;
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
        CharSequence rendered;
        if (Build.VERSION.SDK_INT >= 24) {
            rendered = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        } else {
            rendered = Html.fromHtml(html);
        }
        view.setText(rendered);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
    }
}
