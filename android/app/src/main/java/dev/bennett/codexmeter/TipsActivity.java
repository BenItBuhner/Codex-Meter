package dev.bennett.codexmeter;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/** Practical, source-backed guidance for making included Codex usage last longer. */
public final class TipsActivity extends AppCompatActivity {
    private static final String OPENAI_PRICING_URL = "https://developers.openai.com/codex/pricing";
    private static final String OPENAI_SPEED_URL = "https://developers.openai.com/codex/speed";

    private boolean dark;

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        dark = Ui.isDark(this);
        LinearLayout content = Ui.installPage(this, getString(R.string.tips_title), true).content;
        build(content);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void build(LinearLayout content) {
        content.addView(Ui.sectionTitle(this, "Reduce consumption", dark));
        RoundedLinearLayout usage = Ui.cardGroup(this, dark);
        usage.addView(tipRow("Use Standard speed",
                "Fast mode lowers latency but uses included credits faster. On supported "
                        + "models, it currently costs 2×–2.5× the Standard rate.",
                false));
        usage.addView(tipRow("Lower the reasoning level",
                "Use low or medium effort for straightforward work. Increase it only when the "
                        + "task needs deeper analysis or the lower setting is not succeeding.",
                true));
        usage.addView(tipRow("Turn off Ultra when you do not need it",
                "If your Codex client offers Ultra or another high-compute workflow, reserve it "
                        + "for genuinely difficult work instead of leaving it enabled by default.",
                true));
        usage.addView(tipRow("Use a smaller model for routine work",
                "Routine edits, searches, and boilerplate usually do not need the most capable "
                        + "model. Switching down can make included usage last much longer.",
                true));
        usage.addView(tipRow("Remove unused context and integrations",
                "Include only relevant files and instructions. Disable integrations and MCP "
                        + "servers you are not using so they do not add unnecessary context.",
                true));
        content.addView(usage);

        content.addView(Ui.sectionTitle(this, "Near your weekly limit", dark));
        RoundedLinearLayout nearLimit = Ui.cardGroup(this, dark);
        nearLimit.addView(tipRow("Use Goal mode where available",
                "With only a small amount remaining, start one complex, well-scoped task with "
                        + "clear completion checks. OpenAI may allow that active turn to continue "
                        + "after the limit is reached, subject to fair use. This does not unlock "
                        + "additional turns.",
                false));
        content.addView(nearLimit);

        content.addView(Ui.sectionTitle(this, "Official guidance", dark));
        RoundedLinearLayout sources = Ui.cardGroup(this, dark);
        sources.addView(sourceRow("OpenAI usage and pricing",
                "Official limits, credit behavior, and efficiency guidance.",
                R.drawable.ic_oui_battery, OPENAI_PRICING_URL, false));
        sources.addView(sourceRow("Fast mode",
                "Current speed and credit multipliers by model.",
                R.drawable.ic_oui_time, OPENAI_SPEED_URL, true));
        content.addView(sources);

        TextView note = Ui.text(this,
                "Based on OpenAI guidance available in July 2026. Features, models, and limits "
                        + "can vary by client and may change.",
                12, Ui.secondaryText(dark));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 14),
                Ui.dp(this, 12), Ui.dp(this, 8));
        content.addView(note, noteParams);
    }

    private CardItemView tipRow(String title, String summary, boolean divider) {
        CardItemView row = Ui.actionRow(this, title, summary, 0, null);
        row.setShowTopDivider(divider);
        return row;
    }

    private CardItemView sourceRow(String title, String summary, int icon, String url,
            boolean divider) {
        CardItemView row = Ui.actionRow(this, title, summary, icon, view -> openUrl(url));
        row.setShowTopDivider(divider);
        return row;
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
