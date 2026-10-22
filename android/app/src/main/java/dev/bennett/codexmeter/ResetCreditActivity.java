package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/* JADX INFO: loaded from: classes.dex */
public final class ResetCreditActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private LinearLayout content;
    private boolean dark;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int expiryNotificationId = -1;
    private Button useButton;
    /** Codex by default; Claude Code reuses this screen for its saved resets. */
    private UsageProvider provider = UsageProvider.CODEX;

    /** Opens the reset screen for {@code provider} (Codex or Claude Code). */
    public static Intent intent(Context context, UsageProvider provider) {
        return new Intent(context, ResetCreditActivity.class)
                .putExtra(MainActivity.EXTRA_PROVIDER, provider.id);
    }

    private boolean claude() {
        return this.provider == UsageProvider.CLAUDE_CODE;
    }

    private ResetCreditsSnapshot loadCredits() {
        if (!claude()) return AppPreferences.loadResetCredits(this);
        ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(this, this.provider);
        return saved == null ? null : saved.toResetCreditsSnapshot(System.currentTimeMillis());
    }

    private boolean signedIn() {
        return SecureTokenStore.isSignedIn(this, this.provider);
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        this.dark = Ui.isDark(this);
        if (UsageProvider.CLAUDE_CODE.id.equals(
                getIntent().getStringExtra(MainActivity.EXTRA_PROVIDER))) {
            this.provider = UsageProvider.CLAUDE_CODE;
        }
        this.content = Ui.installPage(this, this.provider.displayName + " reset", true).content;
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
        ResetCreditsSnapshot snapshot = loadCredits();
        int available = snapshot == null ? 0 : snapshot.availableCount;
        long now = System.currentTimeMillis();
        List<RateLimitResetCredit> availableCredits = snapshot == null
                ? Collections.emptyList()
                : snapshot.availableCreditsByExpiry(now);
        long nextExpiry = snapshot == null ? 0L : snapshot.nextExpiryMillis(now);

        this.content.addView(Ui.separator(this, "Available credits"));
        RoundedLinearLayout summaryCard = Ui.seslRowCard(this, this.dark);
        summaryCard.addView(Ui.actionRow(
                this,
                available <= 0
                        ? "No resets available"
                        : (available == 1 ? "1 reset available" : available + " resets available"),
                summaryText(available, nextExpiry, now),
                R.drawable.ic_oui_battery,
                null));
        this.content.addView(summaryCard);

        this.content.addView(Ui.separator(this, "Credit expirations"));
        RoundedLinearLayout expirations = Ui.seslRowCard(this, this.dark);
        addCreditExpirations(expirations, availableCredits, available, now);
        this.content.addView(expirations);

        String visibleResetCreditsError = claude() ? ""
                : AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            Ui.addSpacer(this.content, 12);
            RoundedLinearLayout errorCard = Ui.seslCard(this, this.dark);
            errorCard.addView(Ui.text(this, visibleResetCreditsError, 13.0f,
                    Ui.danger(this.dark)));
            this.content.addView(errorCard);
        }

        String autoUse = autoUseSummary(nextExpiry > 0L);
        if (!autoUse.isEmpty()) {
            Ui.addSpacer(this.content, 8);
            android.widget.TextView autoUseText =
                    Ui.text(this, autoUse, 13.0f, Ui.secondaryText(this.dark));
            int inset = Ui.dp(this, 24.0f);
            autoUseText.setPadding(inset, 0, inset, 0);
            this.content.addView(autoUseText);
        }

        // Claude only: Anthropic can hold a reset until you reach a limit, so say why it's off.
        String heldBack = "";
        if (claude() && available > 0) {
            ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(this, this.provider);
            heldBack = saved == null ? "" : saved.unavailableMessage(now);
        }
        if (!heldBack.isEmpty()) {
            Ui.addSpacer(this.content, 8);
            android.widget.TextView heldText =
                    Ui.text(this, heldBack + ".", 13.0f, Ui.secondaryText(this.dark));
            int inset = Ui.dp(this, 24.0f);
            heldText.setPadding(inset, 0, inset, 0);
            this.content.addView(heldText);
        }

        this.useButton = Ui.nativePrimaryButton(
                this, available > 0 ? "Use 1 reset" : "No resets available");
        this.useButton.setEnabled(available > 0 && heldBack.isEmpty() && signedIn());
        LinearLayout.LayoutParams useButtonParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 60.0f));
        useButtonParams.setMargins(0, Ui.dp(this, 22.0f), 0, Ui.dp(this, 8.0f));
        this.useButton.setOnClickListener(view -> confirmUse());
        this.content.addView(this.useButton, useButtonParams);
    }

    private String summaryText(int available, long nextExpiry, long now) {
        if (nextExpiry > 0L) {
            return "Next expires " + UsageFormat.absolute(this, nextExpiry, now)
                    + " · " + UsageFormat.relative(nextExpiry, now);
        }
        if (available > 0) {
            return (claude() ? "Anthropic" : "OpenAI") + " will choose an eligible credit";
        }
        return "No reset credit is currently available";
    }

    private void addCreditExpirations(RoundedLinearLayout card,
            List<RateLimitResetCredit> credits, int availableCount, long nowMillis) {
        for (int index = 0; index < credits.size(); index++) {
            RateLimitResetCredit credit = credits.get(index);
            String titleText = credit.title.trim().isEmpty()
                    ? "Reset credit " + (index + 1) : credit.title.trim();
            if (index == 0 && credit.expiresAtMillis > 0L) {
                titleText = titleText + " · Next";
            }
            String expiryText = credit.expiresAtMillis > 0L
                    ? UsageFormat.absolute(this, credit.expiresAtMillis, nowMillis)
                            + " · " + UsageFormat.relative(credit.expiresAtMillis, nowMillis)
                    : "Expiration unavailable";
            CardItemView row = Ui.actionRow(this, titleText, expiryText, 0, null);
            row.setShowTopDivider(index > 0);
            card.addView(row);
        }

        int missingCount = Math.max(0, availableCount - credits.size());
        if (missingCount > 0) {
            String missingText = credits.isEmpty()
                    ? "Expiration details are not available yet"
                    : missingCount + " additional credit" + (missingCount == 1 ? "" : "s")
                            + " without expiration details";
            CardItemView missing = Ui.actionRow(this, "More credits", missingText, 0, null);
            missing.setShowTopDivider(!credits.isEmpty());
            card.addView(missing);
        } else if (availableCount == 0) {
            card.addView(Ui.actionRow(this, "No available credits",
                    claude() ? "No reset credit is currently available"
                            : "Earn credits from ChatGPT Codex", 0, null));
        }
    }

    private void refreshDetailsIfNeeded() {
        if (claude()) {
            if (!signedIn() || !ClaudeUsageApi.isStale(this)) return;
            final Context app = getApplicationContext();
            this.executor.execute(() -> {
                try {
                    ClaudeUsageApi.refreshAndCache(app);
                    runOnUiThread(this::rebuild);
                } catch (Exception ignored) {
                    // The Claude tab shows refresh errors; this screen keeps the cached resets.
                }
            });
            return;
        }
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
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Use one " + this.provider.displayName + " reset?").setMessage("The available credit expiring soonest will be used. This cannot be undone.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Use 1 reset", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.5
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                ResetCreditActivity.this.consume();
            }
        }).create();
        dialog.show();
    }

    private void maybePromptUseReset(Intent intent) {
        if (claude() || intent == null || !intent.getBooleanExtra(
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
        if (claude()) {
            ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(this, this.provider);
            ClaudeSavedResets.Grant grant = saved == null ? null
                    : saved.usableGrant(System.currentTimeMillis());
            if (grant == null) {
                Toast.makeText(this, "No reset credit is currently available.",
                        Toast.LENGTH_LONG).show();
                rebuild();
                return;
            }
            final String grantId = grant.id;
            this.executor.execute(() -> {
                ClaudeSavedResets.ClaimResult result =
                        ClaudeResetApi.use(applicationContext, grantId, "reset_screen");
                runOnUiThread(() -> {
                    Toast.makeText(this, result.userMessage(), Toast.LENGTH_LONG).show();
                    if (result.applied()) {
                        finish();
                    } else {
                        rebuild();
                    }
                });
            });
            return;
        }
        this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.6
            @Override // java.lang.Runnable
            public void run() {
                try {
                    final ResetConsumeResult resetConsumeResultConsumeBestAvailable = ResetCreditApi.consumeBestAvailable(applicationContext);
                    ResetCreditActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.ResetCreditActivity.6.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(ResetCreditActivity.this, resetConsumeResultConsumeBestAvailable.userMessage(), Toast.LENGTH_LONG).show();
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
                            Toast.makeText(ResetCreditActivity.this, ResetCreditActivity.safeMessage(e), Toast.LENGTH_LONG).show();
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

    /** One line describing the automatic-use rules that are on, or empty when none are. */
    private String autoUseSummary(boolean hasExpiry) {
        boolean expiryOn = claude()
                ? ResetAlertPreferences.claudeSavedResetExpiryEnabled(this)
                : ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(this);
        long lead = claude()
                ? ResetAlertPreferences.claudeSavedResetExpiryLeadMillis(this)
                : ResetAlertPreferences.resetCreditExpiryAutoApplyLeadMillis(this);
        boolean lowOn = claude()
                ? ResetAlertPreferences.claudeSavedResetLowUsageEnabled(this)
                : ResetAlertPreferences.resetCreditLowUsageAutoUseEnabled(this);
        String metric = claude()
                ? ResetAlertPreferences.claudeSavedResetMetric(this)
                : ResetAlertPreferences.resetCreditLowUsageMetric(this);
        int threshold = claude()
                ? ResetAlertPreferences.claudeSavedResetThreshold(this)
                : ResetAlertPreferences.resetCreditLowUsageThreshold(this);
        StringBuilder text = new StringBuilder();
        if (expiryOn && hasExpiry) {
            text.append("Unused credits are used automatically ")
                    .append(ResetCreditExpiryApply.leadLabel(lead))
                    .append(" before they expire.");
        }
        if (lowOn) {
            if (text.length() > 0) text.append(' ');
            String window = ResetCreditLowUsageAutoUse.METRIC_WEEKLY.equals(metric) ? "weekly"
                    : ResetCreditLowUsageAutoUse.METRIC_FIVE_HOUR.equals(metric) ? "5-hour"
                    : "5-hour or weekly";
            text.append("A credit is also used when your ").append(window)
                    .append(" limit reaches ").append(threshold).append("% remaining.");
        }
        if (text.length() > 0) text.append(" Change this in Settings > Refresh & usage.");
        return text.toString();
    }
}
