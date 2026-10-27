package dev.bennett.codexmeter;

import android.content.Context;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.List;

/**
 * Settings → Lock screen widgets: every placed lock-screen widget, each opening its settings.
 * One UI rarely opens a lock widget's own settings when you add it, so this is where providers
 * and meters get picked.
 */
public final class LockWidgetsActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private LinearLayout content;
    private boolean dark;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        this.dark = Ui.isDark(this);
        this.content = Ui.installPage(this, "Lock screen widgets", true).content;
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                findViewById(R.id.dashboard_refresh);
        if (refresh != null) refresh.setEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void render() {
        this.content.removeAllViews();
        List<SamsungLockWidgetSupport.LockInstance> widgets =
                SamsungLockWidgetSupport.placedWidgets(this);
        if (widgets.isEmpty()) {
            note("No Codex Meter widgets on your lock screen yet. Touch and hold the lock "
                    + "screen, tap Edit, then Widgets, and add Gauge or Gauges from Codex Meter. "
                    + "They come back here to be set up.", 14.0f);
            return;
        }
        note("Tap a widget to choose its providers and meters. Until you do, it shows the "
                + "providers you're signed in to, in your tab order.", 14.0f);
        RoundedLinearLayout card = Ui.cardGroup(this, this.dark);
        boolean first = true;
        for (SamsungLockWidgetSupport.LockInstance widget : widgets) {
            int slots = SamsungLockWidgetSupport.slotCount(this, widget.appWidgetId) > 1
                    ? WidgetProviders.MAX_LOCK_WIDE : 1;
            String summary = WidgetProviders.summary(ProviderPreferences.getLockWidgetProviders(
                    this, widget.appWidgetId, slots));
            if (!ProviderPreferences.hasSavedWidgetProviders(this, widget.appWidgetId)) {
                summary += " · automatic";
            }
            CardItemView row = Ui.actionRow(this, widget.label(), summary, 0, view ->
                    startActivity(new Intent(this, LockWidgetConfigActivity.class)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    widget.appWidgetId)));
            row.setShowTopDivider(!first);
            first = false;
            card.addView(row);
        }
        this.content.addView(card);
    }

    private void note(String text, float size) {
        TextView view = Ui.text(this, text, size, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 16));
        this.content.addView(view, params);
    }
}
