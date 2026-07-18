package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/* JADX INFO: loaded from: classes.dex */
public final class MainActivity extends AppCompatActivity {
    private static final int MENU_SETTINGS = 8101;
    private String appliedStyle;
    private String appliedTheme;
    private LinearLayout content;
    private SwipeRefreshLayout swipeRefresh;
    private boolean dark;
    private boolean receiverRegistered;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastLaunchedAuthUrl = "";
    private boolean launchSignInRequested;
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
                return;
            }
            if (AppConstants.ACTION_RELEASES_UPDATED.equals(action)) {
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
        if (routeToOnboarding(getIntent())) {
            return;
        }
        this.dark = Ui.isDark(this);
        Ui.Page page = Ui.installPage(this, "Codex Meter", false);
        this.content = page.content;
        this.swipeRefresh = findViewById(R.id.dashboard_refresh);
        int refreshAccent = Ui.accent(this, this.dark);
        // OneUI's four-dot SwipeRefresh drawable indexes two palette entries while drawing.
        this.swipeRefresh.setColorSchemeColors(refreshAccent, refreshAccent);
        this.swipeRefresh.setProgressBackgroundColorSchemeColor(Ui.cardColor(this, this.dark));
        this.swipeRefresh.setOnRefreshListener(this::refreshFromPull);
        handleLaunchIntent(getIntent());
        WidgetUpgradeRepair.runIfNeeded(this);
        rebuild();
        RefreshScheduler.schedulePeriodic(this);
        ReleaseUpdateScheduler.ensureScheduled(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem settings = menu.add(Menu.NONE, MENU_SETTINGS, 0, "Settings")
                .setIcon(R.drawable.ic_oui_settings_outline)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (!Ui.isOneUi(this) && settings.getIcon() != null) {
            settings.getIcon().mutate().setTint(Ui.mainText(this, this.dark));
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            Ui.startSecondaryActivity(this, SettingsActivity.class);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (routeToOnboarding(intent)) {
            return;
        }
        handleLaunchIntent(intent);
        rebuild();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        String appTheme = AppPreferences.getAppTheme(this);
        String appStyle = AppPreferences.getAppStyle(this);
        boolean zIsDark = Ui.isDark(this);
        if (!appTheme.equals(this.appliedTheme) || !appStyle.equals(this.appliedStyle)
                || zIsDark != this.dark) {
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
        intentFilter.addAction(AppConstants.ACTION_RELEASES_UPDATED);
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
        if (this.launchSignInRequested) {
            this.launchSignInRequested = false;
            startOrContinueSignIn();
        }
        if (SecureTokenStore.isSignedIn(this)) {
            AppPreferences.setOAuthPending(this, false, "");
            UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(this);
            if (AppPreferences.getRefreshOnLaunch(this)
                    && (usageSnapshotLoadSnapshot == null || System.currentTimeMillis() - usageSnapshotLoadSnapshot.fetchedAtMillis > 300000)) {
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
        if (intent != null && intent.getBooleanExtra("start_sign_in", false)) {
            this.launchSignInRequested = true;
            intent.removeExtra("start_sign_in");
        }
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "codexmeter".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            if (SecureTokenStore.isSignedIn(this)) {
                AppPreferences.setOAuthPending(this, false, "");
                RefreshScheduler.scheduleImmediate(this);
            }
            intent.setData(null);
        }
    }

    private boolean routeToOnboarding(Intent intent) {
        boolean oauthReturn = isOAuthReturnIntent(intent);
        int action = OnboardingFlow.launchAction(
                AppPreferences.isOnboardingComplete(this),
                SecureTokenStore.isSignedIn(this),
                oauthReturn);
        if (action == OnboardingFlow.LAUNCH_MAIN_AND_COMPLETE) {
            // Existing signed-in installs predate onboarding and should not be interrupted.
            AppPreferences.completeOnboarding(this);
            return false;
        }
        if (action != OnboardingFlow.LAUNCH_ONBOARDING) {
            return false;
        }
        if (oauthReturn) {
            if (SecureTokenStore.isSignedIn(this)) {
                AppPreferences.setOAuthPending(this, false, "");
            }
            intent.setData(null);
        }
        startActivity(new Intent(this, OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_AUTH_RETURN, oauthReturn)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        return true;
    }

    private static boolean isOAuthReturnIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        return data != null
                && "codexmeter".equals(data.getScheme())
                && "auth".equals(data.getHost())
                && data.getPath() != null
                && data.getPath().startsWith("/complete");
    }

    public void rebuild() {
        if (this.content != null) {
            this.content.removeAllViews();
            boolean material = !Ui.isOneUi(this);
            int sectionGap = material ? 16 : 20;
            GitHubRelease update = UpdatePreferences.availableUpdate(this);
            if (update != null) {
                this.content.addView(buildUpdateCard(update));
                Ui.addSpacer(this.content, sectionGap);
            }
            if (material) {
                addMaterialHero();
            }
            this.content.addView(buildUsageDashboard());
            Ui.addSpacer(this.content, sectionGap);
            if (!SecureTokenStore.isSignedIn(this)) {
                Button signIn = Ui.nativePrimaryButton(this,
                        AppPreferences.isOAuthPending(this) ? "Continue sign-in" : "Sign in with ChatGPT");
                signIn.setOnClickListener(view -> startOrContinueSignIn());
                this.content.addView(signIn, new LinearLayout.LayoutParams(-1,
                        Ui.dp(this, material ? 64 : 60)));
                Ui.addSpacer(this.content, sectionGap);
            }
            this.content.addView(buildResetCreditsCard());
        }
    }

    private void addMaterialHero() {
        boolean signedIn = SecureTokenStore.isSignedIn(this);
        TextView eyebrow = Ui.text(this, signedIn ? "ALLOWANCE" : "GET STARTED", 12.0f,
                Ui.accent(this, this.dark));
        eyebrow.setTypeface(Ui.emphasizedTypeface(this));
        eyebrow.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams eyeParams = new LinearLayout.LayoutParams(-1, -2);
        eyeParams.setMargins(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 6));
        this.content.addView(eyebrow, eyeParams);

        TextView headline = Ui.text(this,
                signedIn ? "What's left" : "Your Codex\nat a glance",
                34.0f, Ui.mainText(this, this.dark));
        headline.setTypeface(Ui.emphasizedTypeface(this));
        headline.setLetterSpacing(-0.03f);
        headline.setLineSpacing(0f, 0.95f);
        LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
        headParams.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 18));
        this.content.addView(headline, headParams);
    }

    private LinearLayout buildUpdateCard(GitHubRelease release) {
        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, "Codex Meter " + release.version + " is ready", 18,
                Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        TextView summary = Ui.text(this,
                "A signed GitHub release is available. The APK will be checksum-verified before "
                        + "Android asks you to approve installation.",
                13, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(0, Ui.dp(this, 7), 0, Ui.dp(this, 14));
        card.addView(summary, summaryParams);
        Button update = Ui.nativePrimaryButton(this, "Review update");
        update.setOnClickListener(view -> startActivity(new Intent(this, UpdateActivity.class)
                .putExtra(UpdateActivity.EXTRA_VERSION, release.version)));
        card.addView(update, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        return card;
    }

    private void addHeader() {
        TextView textViewText = Ui.text(this, "Your Codex allowance at a glance.", 15.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(Ui.dp(this, 4.0f), Ui.dp(this, 4.0f), 0, Ui.dp(this, 2.0f));
        this.content.addView(textViewText, layoutParams);
    }

    private LinearLayout buildUsageDashboard() {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        boolean signedIn = SecureTokenStore.isSignedIn(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        if (!Ui.isOneUi(this)) {
            column.addView(buildMaterialMetricCard("5 hour",
                    signedIn && snapshot != null ? snapshot.fiveHour : null, signedIn, 0));
            Ui.addSpacer(column, 14);
            // Secondary container + asymmetric corners so the pair is not twin lavender blobs.
            column.addView(buildMaterialMetricCard("Weekly",
                    signedIn && snapshot != null ? snapshot.weekly : null, signedIn, 1));
            return column;
        }
        column.addView(buildMetricCard("5 hour", signedIn && snapshot != null ? snapshot.fiveHour : null, signedIn, false));
        Ui.addSpacer(column, 20);
        column.addView(buildMetricCard("Weekly", signedIn && snapshot != null ? snapshot.weekly : null, signedIn, true));
        return column;
    }

    private LinearLayout buildMetricCard(String label, UsageWindow window, boolean signedIn, boolean invertedWave) {
        LinearLayout card = Ui.card(this, this.dark);
        card.setPadding(0, 0, 0, 0);
        card.setMinimumHeight(Ui.dp(this, 103.0f));
        String reset = window == null
                ? (signedIn ? "Waiting for data" : "Not connected")
                : UsageFormat.reset(this, window, WidgetOptions.RESET_RELATIVE, System.currentTimeMillis());
        UsageWaveView wave = new UsageWaveView(this);
        wave.setUsage(label, reset, window == null ? 0 : window.remainingPercent(),
                "Weekly".equals(label) ? R.drawable.ic_oui_calendar_week : R.drawable.ic_oui_time,
                invertedWave);
        card.addView(wave, new LinearLayout.LayoutParams(-1, Ui.dp(this, 103.0f)));
        return card;
    }

    /**
     * Material 3 Expressive metric card — primary/secondary containers, display % type,
     * fat progress, asymmetric corners. Intentionally not the One UI wave treatment.
     */
    private LinearLayout buildMaterialMetricCard(String label, UsageWindow window, boolean signedIn,
            int tone) {
        int fill;
        int onFill;
        if (tone == 0) {
            fill = Ui.primaryContainer(this, this.dark);
            onFill = Ui.onPrimaryContainer(this, this.dark);
        } else if (tone == 2) {
            fill = Ui.tertiaryContainer(this, this.dark);
            onFill = Ui.onTertiaryContainer(this, this.dark);
        } else {
            fill = Ui.secondaryContainer(this, this.dark);
            onFill = Ui.onSecondaryContainer(this, this.dark);
        }
        int mutedOn = Color.argb(178, Color.red(onFill), Color.green(onFill), Color.blue(onFill));
        LinearLayout card = Ui.expressiveCard(this, this.dark, fill, tone);
        card.setMinimumHeight(Ui.dp(this, 168.0f));

        LinearLayout header = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, label, 16.0f, onFill);
        title.setTypeface(Ui.emphasizedTypeface(this));
        title.setLetterSpacing(0.01f);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        ImageView icon = new ImageView(this);
        icon.setImageResource("Weekly".equals(label)
                ? R.drawable.ic_oui_calendar_week : R.drawable.ic_oui_time);
        icon.setColorFilter(onFill);
        header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        card.addView(header);

        int percent = window == null ? 0 : window.remainingPercent();
        TextView giant = Ui.text(this, percent + "%", 64.0f, onFill);
        giant.setTypeface(Ui.emphasizedTypeface(this));
        giant.setLetterSpacing(-0.045f);
        giant.setIncludeFontPadding(false);
        LinearLayout.LayoutParams giantParams = new LinearLayout.LayoutParams(-1, -2);
        giantParams.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 14));
        card.addView(giant, giantParams);

        ProgressBar bar = Ui.progress(this, this.dark);
        int pill = Ui.dp(this, 999);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.argb(this.dark ? 60 : 40, Color.red(onFill), Color.green(onFill),
                Color.blue(onFill)));
        track.setCornerRadius(pill);
        GradientDrawable fillBar = new GradientDrawable();
        fillBar.setColor(Ui.accent(this, this.dark));
        fillBar.setCornerRadius(pill);
        ClipDrawable clip = new ClipDrawable(fillBar, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layer);
        bar.setProgress(percent);
        card.addView(bar);

        String reset = window == null
                ? (signedIn ? "Waiting for live usage" : "Sign in to load remaining allowance")
                : UsageFormat.reset(this, window, WidgetOptions.RESET_RELATIVE,
                        System.currentTimeMillis());
        TextView resetView = Ui.text(this, reset, 14.0f, mutedOn);
        resetView.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-1, -2);
        resetParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        card.addView(resetView, resetParams);
        return card;
    }

    private LinearLayout buildUsageCard() {
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        linearLayoutCard.setPadding(Ui.dp(this, 20.0f), Ui.dp(this, 20.0f), Ui.dp(this, 20.0f), Ui.dp(this, 10.0f));
        AuthTokens tokens = SecureTokenStore.load(this);
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        boolean signedIn = tokens != null;

        LinearLayout account = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.codex_profile_avatar);
        Ui.makeAvatar(avatar);
        account.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 44.0f), Ui.dp(this, 44.0f)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        String titleText = signedIn ? "ChatGPT account" : "Not connected";
        TextView title = Ui.text(this, titleText, 18.0f, Ui.mainText(this.dark));
        title.setSingleLine(true);
        identity.addView(title);
        TextView subtitle = Ui.text(this, signedIn && !tokens.email.isEmpty() ? tokens.email : (signedIn ? "Connected" : "Sign in to view your usage"), 14.0f, Ui.secondaryText(this.dark));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(subtitle);
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        identityParams.setMargins(Ui.dp(this, 20.0f), 0, Ui.dp(this, 10.0f), 0);
        account.addView(identity, identityParams);
        if (signedIn && snapshot != null) {
            String plan = UsageFormat.planLabel(snapshot.planType);
            TextView badge = Ui.text(this, plan.isEmpty() ? "Codex" : plan, 14.0f, Ui.mainText(this.dark));
            badge.setTypeface(Ui.mediumTypeface(this));
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(Ui.dp(this, 13.0f), Ui.dp(this, 6.0f), Ui.dp(this, 13.0f), Ui.dp(this, 6.0f));
            badge.setBackground(Ui.pillBackground(this, this.dark));
            account.addView(badge);
        }
        linearLayoutCard.addView(account, new LinearLayout.LayoutParams(-1, Ui.dp(this, 55.0f)));

        View divider = new View(this);
        divider.setBackgroundColor(Ui.divider(this.dark));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1.0f));
        dividerParams.setMargins(0, Ui.dp(this, 10.0f), 0, Ui.dp(this, 10.0f));
        linearLayoutCard.addView(divider, dividerParams);

        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        if (!signedIn) {
            Button button = Ui.button(this, AppPreferences.isOAuthPending(this) ? "Continue sign-in" : "Sign in with ChatGPT", true, this.dark);
            button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.3
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.startOrContinueSignIn();
                }
            });
            linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
        } else {
            final Button button2 = Ui.button(this, "Refresh", true, this.dark);
            button2.setCompoundDrawables(null, null, null, null);
            button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.4
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.refreshNow(button2);
                }
            });
            linearLayoutHorizontal.addView(button2, new LinearLayout.LayoutParams(0, Ui.dp(this, 60.0f), 1.0f));
            Button button3 = Ui.button(this, "Sign out", false, this.dark);
            button3.setCompoundDrawables(null, null, null, null);
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(0, Ui.dp(this, 60.0f), 1.0f);
            layoutParams4.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
            linearLayoutHorizontal.addView(button3, layoutParams4);
            button3.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.5
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.confirmSignOut();
                }
            });
        }
        linearLayoutCard.addView(linearLayoutHorizontal, new LinearLayout.LayoutParams(-1, Ui.dp(this, 74.0f)));
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
        boolean zIsSignedIn = SecureTokenStore.isSignedIn(this);
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(this);
        int i = resetCreditsSnapshotLoadResetCredits == null ? 0 : resetCreditsSnapshotLoadResetCredits.availableCount;
        if (!Ui.isOneUi(this)) {
            return buildMaterialResetCreditsCard(zIsSignedIn, i);
        }
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        linearLayoutCard.setPadding(Ui.dp(this, 10.0f), Ui.dp(this, 10.0f), Ui.dp(this, 10.0f), Ui.dp(this, 10.0f));

        LinearLayout countRow = Ui.horizontal(this, Gravity.CENTER);
        TextView count = Ui.text(this, zIsSignedIn ? String.valueOf(i) : "—", 30.0f, Ui.mainText(this.dark));
        count.setTypeface(Ui.mediumTypeface(this));
        countRow.addView(count);
        TextView label = Ui.text(this, zIsSignedIn ? "Resets available" : "Sign in to view resets", 18.0f, Ui.mainText(this.dark));
        label.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMargins(Ui.dp(this, 18.0f), 0, 0, 0);
        countRow.addView(label, labelParams);
        linearLayoutCard.addView(countRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66.0f)));

        Button button = Ui.nativePrimaryButton(this, i > 0 ? "Use 1 reset" : "No resets available");
        button.setEnabled(zIsSignedIn && i > 0);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.6
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) ResetCreditActivity.class));
            }
        });
        linearLayoutCard.addView(button, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60.0f)));
        return linearLayoutCard;
    }

    private LinearLayout buildMaterialResetCreditsCard(boolean signedIn, int available) {
        LinearLayout card = Ui.expressiveCard(this, this.dark,
                Ui.tertiaryContainer(this, this.dark), 2);
        int on = Ui.onTertiaryContainer(this, this.dark);

        TextView label = Ui.text(this, "RESET CREDITS", 12.0f, on);
        label.setTypeface(Ui.emphasizedTypeface(this));
        label.setLetterSpacing(0.08f);
        card.addView(label);

        LinearLayout row = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        TextView count = Ui.text(this, signedIn ? String.valueOf(available) : "—", 52.0f, on);
        count.setTypeface(Ui.emphasizedTypeface(this));
        count.setLetterSpacing(-0.04f);
        row.addView(count);
        TextView detail = Ui.text(this,
                signedIn ? (available == 1 ? "reset\nready" : "resets\nready")
                        : "Sign in to\nsee credits",
                18.0f, on);
        detail.setTypeface(Ui.mediumTypeface(this));
        detail.setLineSpacing(0f, 0.95f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        detailParams.setMargins(Ui.dp(this, 16), 0, 0, 0);
        row.addView(detail, detailParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        card.addView(row, rowParams);

        Button button = Ui.nativePrimaryButton(this,
                available > 0 ? "Use 1 reset" : "No resets available");
        button.setEnabled(signedIn && available > 0);
        button.setOnClickListener(view ->
                startActivity(new Intent(this, ResetCreditActivity.class)));
        card.addView(button, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        return card;
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
        View viewText = Ui.text(this, "Home and Galaxy lock-screen widgets use two battery-style dials for 5-hour and weekly usage remaining, with One UI Home handling the native frame and blur.", 13.0f, Ui.secondaryText(this.dark));
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
                Ui.startSecondaryActivity(MainActivity.this, SettingsActivity.class);
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

    private void refreshFromPull() {
        if (!SecureTokenStore.isSignedIn(this)) {
            this.swipeRefresh.setRefreshing(false);
            Toast.makeText(this, "Sign in from Settings to refresh usage.", Toast.LENGTH_SHORT).show();
            Ui.startSecondaryActivity(this, SettingsActivity.class);
            return;
        }
        final Context applicationContext = getApplicationContext();
        this.executor.execute(() -> {
            try {
                RefreshScheduler.scheduleAtNextReset(applicationContext, UsageApi.refreshAndCache(applicationContext));
                WidgetRenderer.updateAll(applicationContext);
                runOnUiThread(() -> {
                    this.swipeRefresh.setRefreshing(false);
                    rebuild();
                });
            } catch (Exception e) {
                AppPreferences.setLastError(applicationContext, safeMessage(e));
                WidgetRenderer.updateAll(applicationContext);
                runOnUiThread(() -> {
                    this.swipeRefresh.setRefreshing(false);
                    Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
                    rebuild();
                });
            }
        });
    }

    public void confirmSignOut() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sign out?").setMessage("This removes encrypted ChatGPT tokens and cached usage from this device.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Sign out", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.10
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.signOut();
            }
        }).create();
        dialog.show();
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
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add from your launcher").setMessage("Long-press an empty area of the home screen, open Widgets, then choose Codex Meter.").setPositiveButton("OK", (DialogInterface.OnClickListener) null).create();
            dialog.show();
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
