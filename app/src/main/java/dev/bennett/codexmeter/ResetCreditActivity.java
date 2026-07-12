package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private Button useButton;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        this.dark = Ui.isDark(this);
        this.content = Ui.installPage(this, "Codex reset", true).content;
        rebuild();
        refreshDetailsIfNeeded();
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
        View viewText2 = Ui.text(this, "Using a credit asks OpenAI to reset the currently used Codex rate-limit windows. Afterward, Codex Meter reloads both usage windows and the remaining credit inventory.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, 0, 0, Ui.dp(this, 16.0f));
        linearLayoutCard.addView(viewText2, layoutParams3);
        String visibleResetCreditsError = AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            View viewText3 = Ui.text(this, visibleResetCreditsError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams4.setMargins(0, 0, 0, Ui.dp(this, 12.0f));
            linearLayoutCard.addView(viewText3, layoutParams4);
        }
        LinearLayout linearLayoutHorizontal2 = Ui.horizontal(this, 16);
        Button button = Ui.button(this, "Cancel", false, this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ResetCreditActivity.this.finish();
            }
        });
        linearLayoutHorizontal2.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 52.0f), 1.0f));
        this.useButton = Ui.button(this, i > 0 ? "Use reset" : "No reset available", true, this.dark);
        this.useButton.setEnabled(i > 0 && SecureTokenStore.isSignedIn(this));
        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(0, Ui.dp(this, 52.0f), 1.0f);
        layoutParams5.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
        linearLayoutHorizontal2.addView(this.useButton, layoutParams5);
        this.useButton.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.ResetCreditActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ResetCreditActivity.this.confirmUse();
            }
        });
        linearLayoutCard.addView(linearLayoutHorizontal2);
        this.content.addView(linearLayoutCard);
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
