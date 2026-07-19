package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
        this.content.removeAllViews();
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(this);
        int availableCount = snapshot == null ? 0 : snapshot.availableCount;
        long now = System.currentTimeMillis();
        java.util.List<RateLimitResetCredit> credits = snapshot == null
                ? java.util.Collections.emptyList()
                : snapshot.availableCreditsSortedByExpiry(now);

        this.content.addView(Ui.sectionTitle(this, "Available credits", this.dark));
        LinearLayout summaryCard = Ui.card(this, this.dark);
        TextView summary = Ui.text(this,
                availableCount + " reset credit" + (availableCount == 1 ? "" : "s") + " available",
                20.0f, Ui.mainText(this.dark));
        summary.setTypeface(Ui.mediumTypeface(this));
        summaryCard.addView(summary);

        String summaryDetail;
        long nextExpiry = snapshot == null ? 0L : snapshot.nextExpiryMillis(now);
        if (nextExpiry > 0L) {
            summaryDetail = "Soonest expiry "
                    + UsageFormat.absolute(this, nextExpiry, now)
                    + " (" + UsageFormat.relative(nextExpiry, now) + ").";
        } else if (availableCount > 0) {
            summaryDetail = "Expiry details are unavailable; OpenAI will choose an eligible credit.";
        } else {
            summaryDetail = "No reset credit is currently available.";
        }
        View summaryDetailView = Ui.text(this, summaryDetail, 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams summaryDetailParams = new LinearLayout.LayoutParams(-1, -2);
        summaryDetailParams.setMargins(0, Ui.dp(this, 8.0f), 0, Ui.dp(this, 14.0f));
        summaryCard.addView(summaryDetailView, summaryDetailParams);

        View help = Ui.text(this,
                "Using a credit asks OpenAI to reset the currently used Codex rate-limit windows. Afterward, Codex Meter reloads both usage windows and the remaining credit inventory.",
                13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-1, -2);
        helpParams.setMargins(0, 0, 0, Ui.dp(this, 16.0f));
        summaryCard.addView(help, helpParams);

        String visibleResetCreditsError = AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            View error = Ui.text(this, visibleResetCreditsError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
            errorParams.setMargins(0, 0, 0, Ui.dp(this, 12.0f));
            summaryCard.addView(error, errorParams);
        }

        LinearLayout actions = Ui.horizontal(this, 16);
        Button cancel = Ui.button(this, "Cancel", false, this.dark);
        cancel.setOnClickListener(view -> finish());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(this, 52.0f), 1.0f));
        this.useButton = Ui.button(this,
                availableCount > 0 ? "Use reset" : "No reset available", true, this.dark);
        this.useButton.setEnabled(availableCount > 0 && SecureTokenStore.isSignedIn(this));
        LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 52.0f), 1.0f);
        useParams.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
        actions.addView(this.useButton, useParams);
        this.useButton.setOnClickListener(view -> confirmUse());
        summaryCard.addView(actions);
        this.content.addView(summaryCard);

        this.content.addView(Ui.sectionTitle(this, "Credits & expiration", this.dark));
        LinearLayout listCard = Ui.card(this, this.dark);
        if (credits.isEmpty()) {
            View empty = Ui.text(this,
                    availableCount > 0
                            ? "Individual credit expiration details are not cached yet. Pull to refresh from the dashboard, then open this screen again."
                            : "There are no available reset credits to show.",
                    13.0f, Ui.secondaryText(this.dark));
            listCard.addView(empty);
        } else {
            for (int index = 0; index < credits.size(); index++) {
                RateLimitResetCredit credit = credits.get(index);
                if (index > 0) {
                    View divider = new View(this);
                    divider.setBackgroundColor(Ui.divider(this.dark));
                    LinearLayout.LayoutParams dividerParams =
                            new LinearLayout.LayoutParams(-1, Ui.dp(this, 1.0f));
                    dividerParams.setMargins(0, Ui.dp(this, 14.0f), 0, Ui.dp(this, 14.0f));
                    listCard.addView(divider, dividerParams);
                }
                listCard.addView(buildCreditRow(credit, index + 1, now));
            }
        }
        this.content.addView(listCard);
    }

    private LinearLayout buildCreditRow(RateLimitResetCredit credit, int ordinal, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        String title = credit.title == null ? "" : credit.title.trim();
        if (title.isEmpty()) {
            title = "Reset credit " + ordinal;
        }
        TextView titleView = Ui.text(this, title, 16.0f, Ui.mainText(this.dark));
        titleView.setTypeface(Ui.mediumTypeface(this));
        row.addView(titleView);

        String expiryText;
        if (credit.expiresAtMillis > 0L) {
            expiryText = "Expires " + UsageFormat.absolute(this, credit.expiresAtMillis, now)
                    + " (" + UsageFormat.relative(credit.expiresAtMillis, now) + ")";
        } else {
            expiryText = "No expiration date available";
        }
        TextView expiryView = Ui.text(this, expiryText, 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
        expiryParams.setMargins(0, Ui.dp(this, 4.0f), 0, 0);
        row.addView(expiryView, expiryParams);

        String description = credit.description == null ? "" : credit.description.trim();
        if (!description.isEmpty()) {
            TextView descriptionView = Ui.text(this, description, 12.0f, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
            descriptionParams.setMargins(0, Ui.dp(this, 4.0f), 0, 0);
            row.addView(descriptionView, descriptionParams);
        }
        return row;
    }

    private void refreshDetailsIfNeeded() {
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(this);
        long jMax = resetCreditsSnapshotLoadResetCredits == null ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - resetCreditsSnapshotLoadResetCredits.fetchedAtMillis);
        if (SecureTokenStore.isSignedIn(this) && jMax >= 300000) {
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
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Use one Codex reset?").setMessage("This action consumes one available reset credit and cannot be undone.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Use reset", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.5
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
