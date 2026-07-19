package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.app.AppCompatActivity;

/* JADX INFO: loaded from: classes.dex */
public final class ResetCreditActivity extends AppCompatActivity {
    private LinearLayout content;
    private boolean dark;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int expiryNotificationId = -1;
    private Button useButton;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        this.dark = Ui.isDark(this);
        this.content = Ui.installPage(this, "Codex reset", true).content;
        rebuild();
        refreshDetailsIfNeeded();
        if (bundle == null) {
            maybePromptUseReset(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        rebuild();
        maybePromptUseReset(intent);
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.executor.shutdownNow();
        super.onDestroy();
    }

    public void rebuild() {
        String str;
        this.content.removeAllViews();
        this.content.addView(Ui.sectionTitle(this, "Available credits", this.dark));
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(this);
        int i = resetCreditsSnapshotLoadResetCredits == null ? 0 : resetCreditsSnapshotLoadResetCredits.availableCount;
        TextView textViewText = Ui.text(this, i + " reset credit" + (i == 1 ? "" : "s") + " available", 20.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        long jCurrentTimeMillis = System.currentTimeMillis();
        List<RateLimitResetCredit> availableCredits = resetCreditsSnapshotLoadResetCredits == null
                ? Collections.emptyList()
                : resetCreditsSnapshotLoadResetCredits.availableCreditsByExpiry(jCurrentTimeMillis);
        long jNextExpiryMillis = resetCreditsSnapshotLoadResetCredits == null ? 0L : resetCreditsSnapshotLoadResetCredits.nextExpiryMillis(jCurrentTimeMillis);
        if (jNextExpiryMillis > 0) {
            str = "Next credit expires " + UsageFormat.absolute(this, jNextExpiryMillis, jCurrentTimeMillis) + " (" + UsageFormat.relative(jNextExpiryMillis, jCurrentTimeMillis) + ").";
        } else if (i > 0) {
            str = "Expiry details are unavailable; OpenAI will choose an eligible credit.";
        } else {
            str = "No reset credit is currently available.";
        }
        View viewText = Ui.text(this, str, 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 8.0f), 0, Ui.dp(this, 14.0f));
        linearLayoutCard.addView(viewText, layoutParams2);
        addCreditExpirations(linearLayoutCard, availableCredits, i, jCurrentTimeMillis);
        String visibleResetCreditsError = AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            View viewText3 = Ui.text(this, visibleResetCreditsError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams4.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
            linearLayoutCard.addView(viewText3, layoutParams4);
        }
        this.useButton = Ui.nativePrimaryButton(
                this, i > 0 ? "Use 1 reset" : "No resets available");
        this.useButton.setEnabled(i > 0 && SecureTokenStore.isSignedIn(this));
        LinearLayout.LayoutParams useButtonParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 60.0f));
        useButtonParams.setMargins(0, Ui.dp(this, 16.0f), 0, 0);
        this.useButton.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ResetCreditActivity.this.confirmUse();
            }
        });
        linearLayoutCard.addView(this.useButton, useButtonParams);
        this.content.addView(linearLayoutCard);
    }

    private void addCreditExpirations(LinearLayout card,
            List<RateLimitResetCredit> credits, int availableCount, long nowMillis) {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.divider(this.dark));
        card.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1.0f)));

        TextView heading = Ui.text(this, "Credit expirations", 15.0f, Ui.mainText(this.dark));
        heading.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(0, Ui.dp(this, 15.0f), 0, Ui.dp(this, 5.0f));
        card.addView(heading, headingParams);

        for (int index = 0; index < credits.size(); index++) {
            RateLimitResetCredit credit = credits.get(index);
            LinearLayout row = Ui.horizontal(this, android.view.Gravity.CENTER_VERTICAL);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            String titleText = credit.title.trim().isEmpty()
                    ? "Reset credit " + (index + 1) : credit.title.trim();
            TextView title = Ui.text(this, titleText, 15.0f, Ui.mainText(this.dark));
            title.setTypeface(Ui.mediumTypeface(this));
            labels.addView(title);
            String expiryText = credit.expiresAtMillis > 0L
                    ? UsageFormat.absolute(this, credit.expiresAtMillis, nowMillis)
                            + " · " + UsageFormat.relative(credit.expiresAtMillis, nowMillis)
                    : "Expiration unavailable";
            TextView expiry = Ui.text(this, expiryText, 13.0f, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
            expiryParams.setMargins(0, Ui.dp(this, 3.0f), 0, 0);
            labels.addView(expiry, expiryParams);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            if (index == 0 && credit.expiresAtMillis > 0L) {
                TextView next = Ui.text(this, "Next", 12.0f, Ui.accent(this, this.dark));
                next.setTypeface(Ui.mediumTypeface(this));
                LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(-2, -2);
                nextParams.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
                row.addView(next, nextParams);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, Ui.dp(this, 9.0f), 0, Ui.dp(this, 9.0f));
            card.addView(row, rowParams);
        }

        int missingCount = Math.max(0, availableCount - credits.size());
        if (missingCount > 0) {
            String missingText = credits.isEmpty()
                    ? "Expiration details are not available yet."
                    : missingCount + " additional credit" + (missingCount == 1 ? "" : "s")
                            + " without expiration details.";
            TextView missing = Ui.text(this, missingText, 13.0f, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams missingParams = new LinearLayout.LayoutParams(-1, -2);
            missingParams.setMargins(0, Ui.dp(this, 9.0f), 0, Ui.dp(this, 5.0f));
            card.addView(missing, missingParams);
        } else if (availableCount == 0) {
            TextView none = Ui.text(this, "No available credits.", 13.0f,
                    Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams noneParams = new LinearLayout.LayoutParams(-1, -2);
            noneParams.setMargins(0, Ui.dp(this, 9.0f), 0, Ui.dp(this, 5.0f));
            card.addView(none, noneParams);
        }
    }

    private void refreshDetailsIfNeeded() {
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(this);
        long now = System.currentTimeMillis();
        long jMax = resetCreditsSnapshotLoadResetCredits == null ? Long.MAX_VALUE : Math.max(0L, now - resetCreditsSnapshotLoadResetCredits.fetchedAtMillis);
        boolean missingDetails = resetCreditsSnapshotLoadResetCredits != null
                && resetCreditsSnapshotLoadResetCredits.availableCount > 0
                && resetCreditsSnapshotLoadResetCredits.availableCreditsByExpiry(now).size()
                        < resetCreditsSnapshotLoadResetCredits.availableCount;
        if (SecureTokenStore.isSignedIn(this) && (jMax >= 300000 || missingDetails)) {
            final Context applicationContext = getApplicationContext();
            this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.4
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        ResetCreditApi.refreshAndCache(applicationContext);
                        ResetCreditActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.4.1
                            @Override // java.lang.Runnable
                            public void run() {
                                ResetCreditActivity.this.rebuild();
                            }
                        });
                    } catch (Exception e) {
                        AppPreferences.setResetCreditsError(applicationContext, ResetCreditActivity.safeMessage(e));
                    }
                }
            });
        }
    }

    public void confirmUse() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Use one Codex reset?").setMessage("The available credit expiring soonest will be used. This cannot be undone.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Use 1 reset", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.5
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                ResetCreditActivity.this.consume();
            }
        }).create();
        dialog.show();
    }

    private void maybePromptUseReset(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(
                AppConstants.EXTRA_PROMPT_USE_RESET, false)) {
            return;
        }
        this.expiryNotificationId = intent.getIntExtra(
                AppConstants.EXTRA_NOTIFICATION_ID, -1);
        intent.removeExtra(AppConstants.EXTRA_PROMPT_USE_RESET);
        intent.removeExtra(AppConstants.EXTRA_NOTIFICATION_ID);
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(this);
        if (snapshot != null && snapshot.availableCount > 0
                && SecureTokenStore.isSignedIn(this)) {
            confirmUse();
        }
    }

    public void consume() {
        if (this.useButton != null) {
            this.useButton.setEnabled(false);
            this.useButton.setText("Applying…");
        }
        final Context applicationContext = getApplicationContext();
        this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.6
            @Override // java.lang.Runnable
            public void run() {
                try {
                    final ResetConsumeResult resetConsumeResultConsumeBestAvailable = ResetCreditApi.consumeBestAvailable(applicationContext);
                    ResetCreditActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.6.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(ResetCreditActivity.this, resetConsumeResultConsumeBestAvailable.userMessage(), 1).show();
                            if (!resetConsumeResultConsumeBestAvailable.applied()) {
                                ResetCreditActivity.this.rebuild();
                            } else {
                                ResetNotificationManager.dismissResetCreditExpiryNotification(
                                        ResetCreditActivity.this,
                                        ResetCreditActivity.this.expiryNotificationId);
                                ResetCreditActivity.this.finish();
                            }
                        }
                    });
                } catch (Exception e) {
                    AppPreferences.setResetCreditsError(applicationContext, ResetCreditActivity.safeMessage(e));
                    ResetCreditActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.6.2
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(ResetCreditActivity.this, ResetCreditActivity.safeMessage(e), 1).show();
                            ResetCreditActivity.this.rebuild();
                        }
                    });
                }
            }
        });
    }

    public static String safeMessage(Exception exc) {
        String message = exc == null ? "" : exc.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "The reset could not be applied.";
        }
        String strTrim = message.trim();
        return strTrim.length() > 240 ? strTrim.substring(0, 240) : strTrim;
    }
}
