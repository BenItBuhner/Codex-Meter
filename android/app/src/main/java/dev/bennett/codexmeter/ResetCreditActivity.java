package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
    private LinearLayout content;
    private boolean dark;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int expiryNotificationId = -1;
    private Button useButton;
    private boolean receiverRegistered;
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null
                    && AppConstants.ACTION_RESET_CREDITS_UPDATED.equals(intent.getAction())) {
                rebuild();
            }
        }
    };

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

    @Override
    @SuppressLint({"UnspecifiedRegisterReceiverFlag"})
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AppConstants.ACTION_RESET_CREDITS_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.updateReceiver, filter, AppConstants.INTERNAL_PERMISSION,
                        null, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(this.updateReceiver, filter, AppConstants.INTERNAL_PERMISSION,
                        null);
            }
            this.receiverRegistered = true;
        } catch (RuntimeException exception) {
            this.receiverRegistered = false;
        }
        rebuild();
    }

    @Override
    protected void onStop() {
        if (this.receiverRegistered) {
            try {
                unregisterReceiver(this.updateReceiver);
            } catch (RuntimeException ignored) {
                // Already gone; nothing to release.
            }
            this.receiverRegistered = false;
        }
        super.onStop();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.executor.shutdownNow();
        super.onDestroy();
    }

    public void rebuild() {
        this.content.removeAllViews();
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(this);
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

        String visibleResetCreditsError = AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            Ui.addSpacer(this.content, 12);
            RoundedLinearLayout errorCard = Ui.seslCard(this, this.dark);
            errorCard.addView(Ui.text(this, visibleResetCreditsError, 13.0f,
                    Ui.danger(this.dark)));
            this.content.addView(errorCard);
        }

        this.useButton = Ui.nativePrimaryButton(
                this, available > 0 ? "Use 1 reset" : "No resets available");
        this.useButton.setEnabled(available > 0 && DemoMode.hasSession(this));
        LinearLayout.LayoutParams useButtonParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 60.0f));
        useButtonParams.setMargins(0, Ui.dp(this, 22.0f), 0, Ui.dp(this, 8.0f));
        this.useButton.setOnClickListener(view -> confirmUse());
        this.content.addView(this.useButton, useButtonParams);

        addScheduledResetSection(available, now);
    }

    private void addScheduledResetSection(int available, long now) {
        this.content.addView(Ui.separator(this, "Scheduled reset"));
        RoundedLinearLayout card = Ui.seslRowCard(this, this.dark);
        ScheduledReset schedule = ScheduledResetManager.load(this);
        boolean session = DemoMode.hasSession(this);
        if (schedule != null) {
            UsageSnapshot usage = AppPreferences.loadSnapshot(this);
            String label = schedule.armedLabel(usage, ScheduledResetManager.dateTimeText(
                    this, schedule.fireAtMillis, now, false));
            String summary = ScheduledResetManager.timingSummary(this, schedule);
            if (schedule.hasCondition()) {
                summary = "Only if remaining is at or below " + schedule.conditionPercent
                        + "%. " + summary;
            }
            card.addView(Ui.actionRow(this, label, summary, R.drawable.ic_oui_alarm,
                    view -> openScheduler()));
            if (schedule.isDateTime() && !ScheduledResetManager.canScheduleExact(this)
                    && Build.VERSION.SDK_INT >= 31) {
                CardItemView allow = Ui.actionRow(this, "Allow exact timing",
                        "Opens Alarms & reminders for Codex Meter", R.drawable.ic_oui_time,
                        view -> openExactAlarmSettings());
                allow.setShowTopDivider(true);
                card.addView(allow);
            }
            Button cancel = Ui.button(this, "Cancel", false, this.dark);
            cancel.setOnClickListener(view -> cancelSchedule());
            LinearLayout.LayoutParams cancelParams =
                    new LinearLayout.LayoutParams(-1, Ui.dp(this, 52.0f));
            int inset = Ui.dp(this, 18.0f);
            cancelParams.setMargins(inset, Ui.dp(this, 4.0f), inset, inset);
            card.addView(cancel, cancelParams);
        } else {
            boolean ready = session && available > 0;
            card.addView(Ui.actionRow(this, "Scheduled reset",
                    ready ? "Use a credit automatically at a time or usage level"
                            : session ? "Needs an available reset credit"
                            : "Sign in to schedule a reset",
                    R.drawable.ic_oui_alarm, ready ? view -> openScheduler() : null));
            ScheduledResetManager.Outcome last = ScheduledResetManager.lastOutcome(this);
            if (last != null && session) {
                CardItemView row = Ui.actionRow(this, "Last run · " + last.title,
                        last.text + (last.atMillis > 0L ? " (" + UsageFormat.absolute(
                                this, last.atMillis, now) + ")" : ""), 0, null);
                row.setShowTopDivider(true);
                card.addView(row);
            }
        }
        this.content.addView(card);
        Ui.addSpacer(this.content, 24);
    }

    private void openScheduler() {
        DiagnosticLog.info(this, "user", "scheduled_reset_flow_opened", "source", "reset_screen");
        ScheduledResetFlow.start(this, this::rebuild);
    }

    private void cancelSchedule() {
        ScheduledResetManager.cancel(this);
        DiagnosticLog.info(this, "user", "scheduled_reset_cancelled");
        Toast.makeText(this, "Scheduled reset cancelled.", Toast.LENGTH_SHORT).show();
        rebuild();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Open Alarms & reminders in system settings for Codex Meter.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String summaryText(int available, long nextExpiry, long now) {
        if (nextExpiry > 0L) {
            return "Next expires " + UsageFormat.absolute(this, nextExpiry, now)
                    + " · " + UsageFormat.relative(nextExpiry, now);
        }
        if (available > 0) {
            return "OpenAI will choose an eligible credit";
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
                    "Earn credits from ChatGPT Codex", 0, null));
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
        if (DemoMode.hasSession(this) && (jMax >= 300000 || missingDetails)) {
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
                && DemoMode.hasSession(this)) {
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
