package dev.bennett.codexmeter;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/** Practical, source-backed guidance for making included Codex usage last longer. */
public final class TipsActivity extends AppCompatActivity {
    private static final String OPENAI_PRICING_URL = "https://developers.openai.com/codex/pricing";
    private static final String OPENAI_SPEED_URL = "https://developers.openai.com/codex/speed";
    private static final String OPENAI_COMMANDS_URL =
            "https://developers.openai.com/codex/cli/slash-commands";

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
        content.addView(buildHero());
        content.addView(Ui.sectionTitle(this, "Make your allowance last", dark));

        addTip(content, 1, "Use Standard speed",
                "Fast mode is about 1.5× faster, but GPT-5.6 and GPT-5.5 use credits "
                        + "at 2.5× the Standard rate; GPT-5.4 uses 2×. Keep it off unless "
                        + "latency matters more than allowance.",
                "/fast off", false);
        addTip(content, 2, "Match power to the job",
                "Use a smaller model for routine edits and reserve Sol, GPT-5.5, high, "
                        + "xhigh, or Ultra-style workflows for work that truly needs deep "
                        + "reasoning. Medium is a strong starting point for ordinary coding.",
                "/model", false);
        addTip(content, 3, "Keep context lean",
                "Point Codex at only the relevant files, remove repeated instructions, and "
                        + "disable MCP servers you do not need. Precise scope saves context and "
                        + "usually reduces expensive rework.",
                null, false);
        addTip(content, 4, "Plan complex work first",
                "For migrations and multi-step changes, define a testable finish line before "
                        + "implementation. Plan mode can expose missing requirements early, when "
                        + "they are cheaper to fix.",
                "/plan", false);
        addTip(content, 5, "Spend the last few percent on one goal",
                "If Goal mode is available, start one useful, bounded task with clear checks. "
                        + "OpenAI says an active turn may continue after you reach a limit, "
                        + "subject to fair use. This helps finish work already underway; it does "
                        + "not guarantee unlimited work or unlock another turn.",
                "/goal", true);
        addTip(content, 6, "Watch the trend, not one prompt",
                "Model, reasoning, context, tools, retrieval, and caching all affect usage. "
                        + "Check your five-hour and weekly pace, then adjust before a large task "
                        + "instead of waiting until the allowance is gone.",
                "/usage", false);

        content.addView(Ui.sectionTitle(this, "Learn more", dark));
        RoundedLinearLayout sources = Ui.cardGroup(this, dark);
        sources.addView(sourceRow("OpenAI usage and pricing",
                "Official limits, credit behavior, and efficiency guidance.",
                R.drawable.ic_oui_battery, OPENAI_PRICING_URL, false));
        sources.addView(sourceRow("Fast mode",
                "Current speed and credit multipliers by model.",
                R.drawable.ic_oui_time, OPENAI_SPEED_URL, true));
        sources.addView(sourceRow("Codex commands",
                "Official reference for /model, /fast, /plan, and /usage.",
                R.drawable.ic_oui_info_outline, OPENAI_COMMANDS_URL, true));
        content.addView(sources);

        TextView note = Ui.text(this,
                "Features, models, and limits can change. Check the linked OpenAI guidance "
                        + "before relying on a specific multiplier or command.",
                12, Ui.secondaryText(dark));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 14),
                Ui.dp(this, 12), Ui.dp(this, 8));
        content.addView(note, noteParams);
    }

    private LinearLayout buildHero() {
        RoundedLinearLayout hero = new RoundedLinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(Ui.dp(this, 22), Ui.dp(this, 22),
                Ui.dp(this, 22), Ui.dp(this, 22));
        hero.setClipToOutline(true);
        int accent = Ui.accent(this, dark);
        hero.setBackground(shape(accent, Ui.dp(this, 28)));

        TextView eyebrow = Ui.text(this, "CODEX FIELD GUIDE", 12, Ui.onAccent(this, dark));
        eyebrow.setTypeface(Ui.mediumTypeface(this));
        eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);

        TextView title = Ui.text(this, "Do more with every limit", 26,
                Ui.onAccent(this, dark));
        title.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        hero.addView(title, titleParams);

        TextView summary = Ui.text(this,
                "Six practical ways to preserve included usage without blindly trading away "
                        + "quality.",
                15, withAlpha(Ui.onAccent(this, dark), 220));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        hero.addView(summary, summaryParams);
        return hero;
    }

    private void addTip(LinearLayout content, int number, String title, String body,
            String command, boolean caution) {
        LinearLayout card = Ui.card(this, dark);

        LinearLayout heading = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, String.valueOf(number), 14,
                caution ? Color.WHITE : Ui.onAccent(this, dark));
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Ui.mediumTypeface(this));
        badge.setBackground(shape(caution ? Ui.warning(dark) : Ui.accent(this, dark),
                Ui.dp(this, 999)));
        heading.addView(badge,
                new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));

        TextView headingText = Ui.text(this, title, 18, Ui.mainText(dark));
        headingText.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        heading.addView(headingText, headingParams);
        card.addView(heading);

        TextView detail = Ui.text(this, body, 14, Ui.secondaryText(dark));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(0, Ui.dp(this, 12), 0, command == null ? 0 : Ui.dp(this, 14));
        card.addView(detail, detailParams);

        if (command != null) {
            TextView commandView = Ui.text(this, command, 14, Ui.mainText(dark));
            commandView.setTypeface(Typeface.MONOSPACE);
            commandView.setGravity(Gravity.CENTER_VERTICAL);
            commandView.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                    Ui.dp(this, 14), Ui.dp(this, 10));
            commandView.setBackground(shape(Ui.controlSurface(this, dark), Ui.dp(this, 12)));
            card.addView(commandView, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, 0, 0, Ui.dp(this, 12));
        content.addView(card, cardParams);
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

    private static GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
