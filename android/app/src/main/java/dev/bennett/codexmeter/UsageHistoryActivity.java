package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/** Full local-history view with explicit privacy and deletion controls. */
public final class UsageHistoryActivity extends AppCompatActivity {
    private LinearLayout content;
    private boolean dark;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        dark = Ui.isDark(this);
        content = Ui.installPage(this, "Usage history", true).content;
        render();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void render() {
        content.removeAllViews();
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        UsageHistory five = AppPreferences.loadUsageHistory(this, UsageHistory.FIVE_HOUR);
        UsageHistory weekly = AppPreferences.loadUsageHistory(this, UsageHistory.WEEKLY);

        LinearLayout intro = Ui.card(this, dark);
        TextView title = Ui.text(this, "Burn trends", 20, Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        intro.addView(title);
        TextView detail = Ui.text(this,
                "Samples stay on this device and are recorded only after a successful refresh. "
                        + "The solid line is current usage, faint lines are previous windows, "
                        + "the dotted diagonal is a sustainable pace, and the dashed extension "
                        + "is the estimate.",
                13, Ui.secondaryText(dark));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        intro.addView(detail, detailParams);
        content.addView(intro);
        Ui.addSpacer(content, 20);

        content.addView(buildChartCard("5-hour", snapshot == null ? null : snapshot.fiveHour,
                snapshot, five));
        Ui.addSpacer(content, 20);
        content.addView(buildChartCard("Weekly", snapshot == null ? null : snapshot.weekly,
                snapshot, weekly));
        Ui.addSpacer(content, 20);

        Button clear = Ui.button(this, "Clear local history", false, dark);
        clear.setEnabled(!five.samples.isEmpty() || !weekly.samples.isEmpty());
        clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Clear usage history?")
                .setMessage("This removes every locally stored usage sample. Your latest "
                        + "allowance and account sign-in stay intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    AppPreferences.clearUsageHistory(this);
                    render();
                })
                .show());
        content.addView(clear, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));
    }

    private LinearLayout buildChartCard(String label, UsageWindow window, UsageSnapshot snapshot,
            UsageHistory history) {
        LinearLayout card = Ui.card(this, dark);
        card.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        long now = System.currentTimeMillis();
        UsagePace.Assessment pace = snapshot == null
                ? UsagePace.assess(null, 0L, now, UsagePace.BALANCED)
                : UsagePacePreferences.assess(this, snapshot, window, now);
        UsageBurnChartView chart = new UsageBurnChartView(this);
        chart.setData(label, window, history,
                snapshot == null ? now : snapshot.fetchedAtMillis, pace);
        card.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 190)));
        String summary = history.samples.size() + " samples · "
                + history.completedWindowCount() + " completed window"
                + (history.completedWindowCount() == 1 ? "" : "s");
        TextView stats = Ui.text(this, summary, 12, Ui.secondaryText(dark));
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(-1, -2);
        statsParams.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        card.addView(stats, statsParams);
        return card;
    }
}
