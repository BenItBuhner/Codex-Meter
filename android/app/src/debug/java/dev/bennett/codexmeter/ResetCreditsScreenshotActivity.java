package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Debug-only lightweight renderer for the reset-credit expiry UI.
 * Avoids One UI ToolbarLayout so software emulators can still capture screenshots.
 */
public final class ResetCreditsScreenshotActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = true;
        setTheme(dark ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);

        long now = System.currentTimeMillis();
        seedDemoData(now);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView title = text("Codex Meter", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("Reset credits demo", 14, Color.parseColor("#B0B0B0"), false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.bottomMargin = dp(16);
        root.addView(subtitle, subtitleParams);

        root.addView(buildResetCard(dark, now));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(1, dp(20));
        root.addView(new View(this), spacer);
        root.addView(buildCreditsList(dark, now));

        scroll.addView(root);
        setContentView(scroll);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            capture(scroll, "01-reset-card-dashboard.png");
            // Scroll to list section and capture again after a beat.
            scroll.fullScroll(View.FOCUS_DOWN);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                capture(scroll, "02-reset-credits-list.png");
                // Keep the activity visible long enough for an adb screencap.
            }, 400);
        }, 600);
    }

    private void seedDemoData(long now) {
        try {
            SecureTokenStore.save(this, new AuthTokens(
                    "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                    "debug-demo-account", "demo@codexmeter.local"));
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(2,
                    Arrays.asList(
                            new RateLimitResetCredit("credit-soon", "both",
                                    RateLimitResetCredit.STATUS_AVAILABLE,
                                    now - TimeUnit.DAYS.toMillis(1),
                                    now + TimeUnit.DAYS.toMillis(2),
                                    "Soonest reset credit",
                                    "This credit expires first."),
                            new RateLimitResetCredit("credit-later", "both",
                                    RateLimitResetCredit.STATUS_AVAILABLE,
                                    now - TimeUnit.DAYS.toMillis(1),
                                    now + TimeUnit.DAYS.toMillis(9),
                                    "Later reset credit",
                                    "This credit expires after the soonest one.")),
                    now));
            AppPreferences.completeOnboarding(this);
            AppPreferences.setRefreshOnLaunch(this, false);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seed reset-credit screenshot demo",
                    exception);
        }
    }

    private LinearLayout buildResetCard(boolean dark, long now) {
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(this);
        int count = snapshot == null ? 0 : snapshot.availableCount;
        long nextExpiry = snapshot == null ? 0L : snapshot.nextExpiryMillis(now);

        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout countBlock = new LinearLayout(this);
        countBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView countView = text(String.valueOf(count), 30, Color.WHITE, true);
        countRow.addView(countView);
        TextView label = text("Resets available", 18, Color.WHITE, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.leftMargin = dp(18);
        countRow.addView(label, labelParams);
        countBlock.addView(countRow);

        if (nextExpiry > 0L) {
            TextView expiry = text(
                    "Next expires " + UsageFormat.absolute(this, nextExpiry, now)
                            + " (" + UsageFormat.relative(nextExpiry, now) + ")",
                    13, Color.parseColor("#B0B0B0"), false);
            LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
            expiryParams.topMargin = dp(4);
            countBlock.addView(expiry, expiryParams);
        }
        header.addView(countBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_oui_keyboard_arrow_right);
        arrow.setImageTintList(ColorStateList.valueOf(Color.parseColor("#B0B0B0")));
        arrow.setContentDescription("View all reset credits");
        header.addView(arrow, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(header);

        Button use = new Button(this);
        use.setText("Use 1 reset");
        use.setAllCaps(false);
        use.setTextColor(Color.WHITE);
        use.setBackgroundColor(Color.parseColor("#4B8BFF"));
        LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(-1, dp(56));
        useParams.topMargin = dp(12);
        card.addView(use, useParams);
        return card;
    }

    private LinearLayout buildCreditsList(boolean dark, long now) {
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(this);
        List<RateLimitResetCredit> credits = snapshot == null
                ? java.util.Collections.emptyList()
                : snapshot.availableCreditsSortedByExpiry(now);

        LinearLayout card = card();
        card.addView(text("Credits & expiration", 16, Color.WHITE, true));
        for (int index = 0; index < credits.size(); index++) {
            RateLimitResetCredit credit = credits.get(index);
            if (index > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.parseColor("#333333"));
                LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(-1, dp(1));
                dividerParams.topMargin = dp(14);
                dividerParams.bottomMargin = dp(14);
                card.addView(divider, dividerParams);
            }
            String title = credit.title == null || credit.title.trim().isEmpty()
                    ? "Reset credit " + (index + 1) : credit.title.trim();
            card.addView(text(title, 16, Color.WHITE, true));
            String expiryText = credit.expiresAtMillis > 0L
                    ? "Expires " + UsageFormat.absolute(this, credit.expiresAtMillis, now)
                    + " (" + UsageFormat.relative(credit.expiresAtMillis, now) + ")"
                    : "No expiration date available";
            TextView expiry = text(expiryText, 13, Color.parseColor("#B0B0B0"), false);
            LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
            expiryParams.topMargin = dp(4);
            card.addView(expiry, expiryParams);
        }
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundColor(Color.parseColor("#1E1E1E"));
        return card;
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private void capture(View view, String filename) {
        int width = Math.max(view.getWidth(), 1);
        int height = Math.max(view.getHeight(), 1);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        File out = new File(getExternalFilesDir(null), filename);
        if (out.getParentFile() != null) {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
        }
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.flush();
        } catch (Exception exception) {
            // Fall back to internal files if external is unavailable.
            File fallback = new File(getFilesDir(), filename);
            try (FileOutputStream stream = new FileOutputStream(fallback)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.flush();
            } catch (Exception nested) {
                throw new IllegalStateException("Could not write " + filename, nested);
            }
        } finally {
            bitmap.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
