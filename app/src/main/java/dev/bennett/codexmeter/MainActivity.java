package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* JADX INFO: loaded from: classes.dex */
public final class MainActivity extends Activity {
    private String appliedStyle;
    private String appliedTheme;
    private LinearLayout content;
    private boolean dark;
    private boolean receiverRegistered;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastLaunchedAuthUrl = "";
    private final BroadcastReceiver authReceiver = new BroadcastReceiver() { // from class: dev.bennett.codexmeter.MainActivity.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (AppConstants.ACTION_OAUTH_READY.equals(action)) {
                String stringExtra = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (stringExtra != null && !stringExtra.isEmpty()) {
                    MainActivity.this.openAuthUrl(stringExtra);
                    return;
                }
                return;
            }
            if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                boolean booleanExtra = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false);
                String stringExtra2 = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                MainActivity mainActivity = MainActivity.this;
                if (stringExtra2 == null) {
                    stringExtra2 = booleanExtra ? "Signed in." : "Sign-in failed.";
                }
                Toast.makeText(mainActivity, stringExtra2, 1).show();
                MainActivity.this.rebuild();
                return;
            }
            if (AppConstants.ACTION_USAGE_UPDATED.equals(action) || AppConstants.ACTION_RESET_CREDITS_UPDATED.equals(action)) {
                MainActivity.this.rebuild();
            }
        }
    };

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        this.appliedTheme = AppPreferences.getAppTheme(this);
        this.appliedStyle = AppPreferences.getAppStyle(this);
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        this.dark = Ui.isDark(this);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Ui.background(this, this.dark));
        this.content = new LinearLayout(this);
        this.content.setOrientation(1);
        int iPageHorizontalPadding = Ui.pageHorizontalPadding(this);
        this.content.setPadding(iPageHorizontalPadding, Ui.pageTopPadding(this), iPageHorizontalPadding, Ui.dp(this, 38.0f));
        scrollView.addView(this.content, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
        Ui.configureSystemBars(this, scrollView, this.dark);
        handleLaunchIntent(getIntent());
        rebuild();
        RefreshScheduler.schedulePeriodic(this);
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
        rebuild();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        String appTheme = AppPreferences.getAppTheme(this);
        String appStyle = AppPreferences.getAppStyle(this);
        boolean zIsDark = Ui.isDark(this);
        if (!appTheme.equals(this.appliedTheme) || !appStyle.equals(this.appliedStyle) || zIsDark != this.dark) {
            recreate();
        } else {
            handleLaunchIntent(getIntent());
            rebuild();
        }
    }

    @Override // android.app.Activity
    @SuppressLint({"UnspecifiedRegisterReceiverFlag"})
    protected void onStart() {
        super.onStart();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AppConstants.ACTION_OAUTH_READY);
        intentFilter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        intentFilter.addAction(AppConstants.ACTION_USAGE_UPDATED);
        intentFilter.addAction(AppConstants.ACTION_RESET_CREDITS_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.authReceiver, intentFilter, "dev.bennett.codexmeter.permission.INTERNAL", null, 4);
            } else {
                registerReceiver(this.authReceiver, intentFilter, "dev.bennett.codexmeter.permission.INTERNAL", null);
            }
            this.receiverRegistered = true;
        } catch (RuntimeException e) {
            this.receiverRegistered = false;
            AppPreferences.setSchedulerError(this, "App update receiver: " + safeMessage(e));
        }
        rebuild();
        if (SecureTokenStore.isSignedIn(this)) {
            AppPreferences.setOAuthPending(this, false, "");
            UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(this);
            if (usageSnapshotLoadSnapshot == null || System.currentTimeMillis() - usageSnapshotLoadSnapshot.fetchedAtMillis > 300000) {
                RefreshScheduler.scheduleImmediate(this);
            }
        }
    }

    @Override // android.app.Activity
    protected void onStop() {
        if (this.receiverRegistered) {
            try {
                unregisterReceiver(this.authReceiver);
            } catch (RuntimeException e) {
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

    private void handleLaunchIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "codexmeter".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            AppPreferences.setOAuthPending(this, false, "");
            if (SecureTokenStore.isSignedIn(this)) {
                RefreshScheduler.scheduleImmediate(this);
            }
            intent.setData(null);
        }
    }

    public void rebuild() {
        if (this.content != null) {
            this.content.removeAllViews();
            addHeader();
            this.content.addView(Ui.sectionTitle(this, "Account & usage", this.dark));
            this.content.addView(buildUsageCard());
            this.content.addView(Ui.sectionTitle(this, "Codex resets", this.dark));
            this.content.addView(buildResetCreditsCard());
            this.content.addView(Ui.sectionTitle(this, "Home screen", this.dark));
            this.content.addView(buildWidgetCard());
            this.content.addView(Ui.sectionTitle(this, "Operation", this.dark));
            this.content.addView(buildOperationCard());
            TextView textViewText = Ui.text(this, "Unofficial client · No analytics · Tokens remain encrypted on this device\nVersion 1.6.0", 11.0f, Ui.secondaryText(this.dark));
            textViewText.setGravity(17);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.setMargins(0, Ui.dp(this, 28.0f), 0, 0);
            this.content.addView(textViewText, layoutParams);
        }
    }

    private void addHeader() {
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        linearLayoutHorizontal.addView(Ui.title(this, "Codex Meter", this.dark), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button = Ui.topAction(this, "Settings", this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) SettingsActivity.class));
            }
        });
        linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(-2, Ui.dp(this, 48.0f)));
        this.content.addView(linearLayoutHorizontal);
        TextView textViewText = Ui.text(this, "Native Android status for your ChatGPT Codex allowance.", 14.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, 0);
        this.content.addView(textViewText, layoutParams);
    }

    private LinearLayout buildUsageCard() {
        String str;
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        AuthTokens authTokensLoad = SecureTokenStore.load(this);
        UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(this);
        boolean z = authTokensLoad != null;
        if (z) {
            str = authTokensLoad.email.isEmpty() ? "ChatGPT connected" : authTokensLoad.email;
        } else {
            str = "Not connected";
        }
        TextView textViewText = Ui.text(this, str, 16.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        if (z && usageSnapshotLoadSnapshot != null) {
            String strPlanLabel = UsageFormat.planLabel(usageSnapshotLoadSnapshot.planType);
            View viewText = Ui.text(this, strPlanLabel.isEmpty() ? UsageFormat.updated(usageSnapshotLoadSnapshot.fetchedAtMillis, System.currentTimeMillis()) : strPlanLabel + " · " + UsageFormat.updated(usageSnapshotLoadSnapshot.fetchedAtMillis, System.currentTimeMillis()), 12.0f, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.setMargins(0, Ui.dp(this, 3.0f), 0, Ui.dp(this, 16.0f));
            linearLayoutCard.addView(viewText, layoutParams);
            addUsageRow(linearLayoutCard, "5-hour window", usageSnapshotLoadSnapshot.fiveHour);
            addUsageRow(linearLayoutCard, "Weekly window", usageSnapshotLoadSnapshot.weekly);
            String visibleRefreshError = AppPreferences.getVisibleRefreshError(this);
            if (!visibleRefreshError.isEmpty()) {
                View viewText2 = Ui.text(this, "Last refresh issue: " + visibleRefreshError, 12.0f, Ui.danger(this.dark));
                LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
                layoutParams2.setMargins(0, Ui.dp(this, 8.0f), 0, 0);
                linearLayoutCard.addView(viewText2, layoutParams2);
            }
        } else {
            View viewText3 = Ui.text(this, z ? "Connected. Refresh once to load the current Codex windows." : "Sign in through OpenAI in your browser. Codex Meter never receives your password.", 13.0f, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams3.setMargins(0, Ui.dp(this, 7.0f), 0, Ui.dp(this, 16.0f));
            linearLayoutCard.addView(viewText3, layoutParams3);
        }
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        if (!z) {
            Button button = Ui.button(this, AppPreferences.isOAuthPending(this) ? "Continue sign-in" : "Sign in with ChatGPT", true, this.dark);
            button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.3
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.startOrContinueSignIn();
                }
            });
            linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
        } else {
            final Button button2 = Ui.button(this, "Refresh now", true, this.dark);
            button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.4
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.refreshNow(button2);
                }
            });
            linearLayoutHorizontal.addView(button2, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
            Button button3 = Ui.button(this, "Sign out", false, this.dark);
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(-2, Ui.dp(this, 50.0f));
            layoutParams4.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
            linearLayoutHorizontal.addView(button3, layoutParams4);
            button3.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.5
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.confirmSignOut();
                }
            });
        }
        linearLayoutCard.addView(linearLayoutHorizontal);
        return linearLayoutCard;
    }

    private void addUsageRow(LinearLayout linearLayout, String str, UsageWindow usageWindow) {
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 80);
        linearLayoutHorizontal.addView(Ui.text(this, str, 13.0f, Ui.secondaryText(this.dark)), new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView textViewText = Ui.text(this, usageWindow == null ? "Unavailable" : usageWindow.remainingPercent() + "% left", 20.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutHorizontal.addView(textViewText);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 4.0f), 0, Ui.dp(this, 6.0f));
        linearLayout.addView(linearLayoutHorizontal, layoutParams);
        ProgressBar progressBarProgress = Ui.progress(this, this.dark);
        progressBarProgress.setProgress(usageWindow == null ? 0 : usageWindow.remainingPercent());
        linearLayout.addView(progressBarProgress);
        View viewText = Ui.text(this, usageWindow == null ? "Reset time unavailable" : UsageFormat.reset(this, usageWindow, "both", System.currentTimeMillis()), 11.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 5.0f), 0, Ui.dp(this, 13.0f));
        linearLayout.addView(viewText, layoutParams2);
    }

    private LinearLayout buildResetCreditsCard() {
        String str;
        String str2;
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        boolean zIsSignedIn = SecureTokenStore.isSignedIn(this);
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(this);
        int i = resetCreditsSnapshotLoadResetCredits == null ? 0 : resetCreditsSnapshotLoadResetCredits.availableCount;
        if (zIsSignedIn) {
            str = i + " reset credit" + (i == 1 ? "" : "s") + " available";
        } else {
            str = "Sign in to view reset credits";
        }
        TextView textViewText = Ui.text(this, str, 16.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        long jCurrentTimeMillis = System.currentTimeMillis();
        long jNextExpiryMillis = resetCreditsSnapshotLoadResetCredits == null ? 0L : resetCreditsSnapshotLoadResetCredits.nextExpiryMillis(jCurrentTimeMillis);
        if (!zIsSignedIn) {
            str2 = "Reset credits are attached to eligible ChatGPT Codex accounts.";
        } else if (jNextExpiryMillis > 0) {
            str2 = "Next credit expires " + UsageFormat.absolute(this, jNextExpiryMillis, jCurrentTimeMillis) + " (" + UsageFormat.relative(jNextExpiryMillis, jCurrentTimeMillis) + ").";
        } else if (i > 0) {
            str2 = "Expiry details were not included in the cached response. OpenAI will choose an eligible credit.";
        } else {
            str2 = "No reset credit is currently available. The inventory is refreshed with usage updates.";
        }
        TextView textViewText2 = Ui.text(this, str2, 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, Ui.dp(this, 14.0f));
        linearLayoutCard.addView(textViewText2, layoutParams);
        String visibleResetCreditsError = AppPreferences.getVisibleResetCreditsError(this);
        if (!visibleResetCreditsError.isEmpty()) {
            TextView textViewText3 = Ui.text(this, visibleResetCreditsError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams2.setMargins(0, 0, 0, Ui.dp(this, 12.0f));
            linearLayoutCard.addView(textViewText3, layoutParams2);
        }
        Button button = Ui.button(this, i > 0 ? "Use a reset" : "Reset unavailable", true, this.dark);
        button.setEnabled(zIsSignedIn && i > 0);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.6
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) ResetCreditActivity.class));
            }
        });
        linearLayoutCard.addView(button, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50.0f)));
        return linearLayoutCard;
    }

    private LinearLayout buildWidgetCard() {
        String str;
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        int length = AppWidgetManager.getInstance(this).getAppWidgetIds(new ComponentName(this, (Class<?>) CodexUsageWidget.class)).length + SamsungLockWidgetSupport.countAll(this);
        if (length == 0) {
            str = "Add Codex Meter widgets";
        } else {
            str = length + " widget" + (length == 1 ? "" : "s") + " active";
        }
        TextView textViewText = Ui.text(this, str, 16.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        View viewText = Ui.text(this, "Choose adaptive bars, circular rings, gauge dials, or a minimal layout. On compatible Galaxy devices, the lock-screen picker now includes numbers, rings, gauges, and bars in both square and wide sizes, while home widgets can use One UI Home’s native frame and blur.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, Ui.dp(this, 15.0f));
        linearLayoutCard.addView(viewText, layoutParams);
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        Button button = Ui.button(this, "Add widget", true, this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.7
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.requestPinWidget();
            }
        });
        linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
        Button button2 = Ui.button(this, "Customize", false, this.dark);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f);
        layoutParams2.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
        linearLayoutHorizontal.addView(button2, layoutParams2);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.8
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) SettingsActivity.class));
            }
        });
        linearLayoutCard.addView(linearLayoutHorizontal);
        return linearLayoutCard;
    }

    private LinearLayout buildOperationCard() {
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        TextView textViewText = Ui.text(this, "Automatic refresh every " + AppPreferences.getRefreshMinutes(this) + " minutes", 15.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        TextView textViewText2 = Ui.text(this, "Manual refreshes run immediately. Scheduled work follows Android battery and network policy, and another update is requested after the next known reset. Cached values remain visible offline.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, 0);
        linearLayoutCard.addView(textViewText2, layoutParams);
        String schedulerError = AppPreferences.getSchedulerError(this);
        if (!schedulerError.isEmpty()) {
            TextView textViewText3 = Ui.text(this, schedulerError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams2.setMargins(0, Ui.dp(this, 10.0f), 0, 0);
            linearLayoutCard.addView(textViewText3, layoutParams2);
        }
        return linearLayoutCard;
    }

    public void startOrContinueSignIn() {
        String str;
        if (SecureTokenStore.isSignedIn(this)) {
            AppPreferences.setOAuthPending(this, false, "");
            rebuild();
            return;
        }
        try {
            startForegroundService(new Intent(this, (Class<?>) OAuthService.class).setAction(OAuthService.ACTION_START));
            if (AppPreferences.isOAuthPending(this)) {
                str = "Resuming secure OpenAI sign-in…";
            } else {
                str = "Opening secure OpenAI sign-in…";
            }
            Toast.makeText(this, str, 0).show();
        } catch (RuntimeException e) {
            AppPreferences.setOAuthPending(this, false, "");
            Toast.makeText(this, "Could not start sign-in: " + safeMessage(e), 1).show();
        }
    }

    public void openAuthUrl(String str) {
        if (!str.equals(this.lastLaunchedAuthUrl) || hasWindowFocus()) {
            this.lastLaunchedAuthUrl = str;
            try {
                startActivity(new Intent("android.intent.action.VIEW", Uri.parse(str)));
            } catch (RuntimeException e) {
                Toast.makeText(this, "No browser is available to complete sign-in.", 1).show();
            }
        }
    }

    public void refreshNow(Button button) {
        button.setEnabled(false);
        button.setText(R.string.refreshing);
        final Context applicationContext = getApplicationContext();
        this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9
            @Override // java.lang.Runnable
            public void run() {
                try {
                    RefreshScheduler.scheduleAtNextReset(applicationContext, UsageApi.refreshAndCache(applicationContext));
                    WidgetRenderer.updateAll(applicationContext);
                    MainActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(MainActivity.this, "Usage updated.", 0).show();
                            MainActivity.this.rebuild();
                        }
                    });
                } catch (Exception e) {
                    AppPreferences.setLastError(applicationContext, MainActivity.safeMessage(e));
                    WidgetRenderer.updateAll(applicationContext);
                    MainActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9.2
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(MainActivity.this, MainActivity.safeMessage(e), 1).show();
                            MainActivity.this.rebuild();
                        }
                    });
                }
            }
        });
    }

    public void confirmSignOut() {
        new AlertDialog.Builder(this).setTitle("Sign out?").setMessage("This removes encrypted ChatGPT tokens and cached usage from this device.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Sign out", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.10
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.signOut();
            }
        }).show();
    }

    public void signOut() {
        final AuthTokens authTokensLoad = SecureTokenStore.load(this);
        SecureTokenStore.clear(this);
        AppPreferences.clearSnapshot(this);
        AppPreferences.setOAuthPending(this, false, "");
        RefreshScheduler.cancelAll(this);
        ResetAlertScheduler.cancelAll(this);
        WidgetRenderer.updateAll(this);
        rebuild();
        this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.11
            @Override // java.lang.Runnable
            public void run() {
                OAuthClient.revokeBestEffort(authTokensLoad);
            }
        });
    }

    public void requestPinWidget() {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        ComponentName componentName = new ComponentName(this, (Class<?>) CodexUsageWidget.class);
        if (appWidgetManager.isRequestPinAppWidgetSupported()) {
            appWidgetManager.requestPinAppWidget(componentName, null, null);
            Toast.makeText(this, "Choose a size and place the widget on your home screen.", 1).show();
        } else {
            new AlertDialog.Builder(this).setTitle("Add from your launcher").setMessage("Long-press an empty area of the home screen, open Widgets, then choose Codex Meter.").setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
        }
    }

    public static String safeMessage(Exception exc) {
        String message = exc.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "The operation failed.";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String safeMessage(RuntimeException runtimeException) {
        String message = runtimeException.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return runtimeException.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
