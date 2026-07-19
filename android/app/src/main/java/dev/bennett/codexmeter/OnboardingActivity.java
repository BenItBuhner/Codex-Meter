package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/** First-run setup built from the same One UI Design Library primitives as the app. */
public final class OnboardingActivity extends AppCompatActivity {
    public static final String EXTRA_AUTH_RETURN = "oauth_return";

    private LinearLayout content;
    private Ui.Page page;
    private boolean dark;
    private int step;
    private boolean receiverRegistered;
    private boolean oauthRequested;
    private String authMessage = "";
    private String lastLaunchedAuthUrl = "";

    private final BroadcastReceiver authReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (AppConstants.ACTION_OAUTH_READY.equals(action)) {
                String url = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (url != null && !url.isEmpty()) {
                    authMessage = "Your secure ChatGPT sign-in is open in the browser.";
                    render();
                    openAuthUrl(url);
                }
                return;
            }
            if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                oauthRequested = false;
                boolean success = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false);
                String message = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                if (success || SecureTokenStore.isSignedIn(OnboardingActivity.this)) {
                    showStep(OnboardingFlow.STEP_COMPLETE);
                } else {
                    authMessage = message == null || message.trim().isEmpty()
                            ? "Sign-in did not complete. Please try again."
                            : message;
                    showStep(OnboardingFlow.STEP_ACCOUNT);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        if (AppPreferences.isOnboardingComplete(this)) {
            openMain();
            return;
        }
        this.dark = Ui.isDark(this);
        this.page = Ui.installPage(this, "Codex Meter", false);
        this.content = this.page.content;
        findViewById(R.id.dashboard_refresh).setEnabled(false);
        boolean oauthReturn = getIntent().getBooleanExtra(EXTRA_AUTH_RETURN, false);
        this.oauthRequested = AppPreferences.isOAuthPending(this);
        this.step = OnboardingFlow.initialStep(
                AppPreferences.getOnboardingStep(this),
                SecureTokenStore.isSignedIn(this),
                oauthReturn);
        if (oauthReturn && !SecureTokenStore.isSignedIn(this)) {
            this.authMessage = "Sign-in did not complete. You can safely try again.";
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (step > OnboardingFlow.STEP_WELCOME) {
                    goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        this.oauthRequested = AppPreferences.isOAuthPending(this);
        if (SecureTokenStore.isSignedIn(this)) {
            showStep(OnboardingFlow.STEP_COMPLETE);
        } else if (intent.getBooleanExtra(EXTRA_AUTH_RETURN, false)) {
            this.authMessage = "Sign-in did not complete. You can safely try again.";
            showStep(OnboardingFlow.STEP_ACCOUNT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.content != null && SecureTokenStore.isSignedIn(this)
                && this.step != OnboardingFlow.STEP_COMPLETE) {
            showStep(OnboardingFlow.STEP_COMPLETE);
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConstants.ACTION_OAUTH_READY);
        filter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.authReceiver, filter, AppConstants.INTERNAL_PERMISSION, null,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(this.authReceiver, filter, AppConstants.INTERNAL_PERMISSION, null);
            }
            this.receiverRegistered = true;
        } catch (RuntimeException exception) {
            this.receiverRegistered = false;
            this.authMessage = "Sign-in updates are unavailable: " + safeMessage(exception);
            render();
        }
    }

    @Override
    protected void onStop() {
        if (this.receiverRegistered) {
            try {
                unregisterReceiver(this.authReceiver);
            } catch (RuntimeException ignored) {
            }
            this.receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        goBack();
        return true;
    }

    private void render() {
        if (this.content == null) return;
        this.content.removeAllViews();
        this.page.toolbar.setTitle("Codex Meter");
        this.page.toolbar.setShowNavigationButtonAsBack(this.step > OnboardingFlow.STEP_WELCOME);

        addProgress();
        if (this.step == OnboardingFlow.STEP_WELCOME) {
            buildWelcome();
        } else if (this.step == OnboardingFlow.STEP_USAGE) {
            buildUsage();
        } else if (this.step == OnboardingFlow.STEP_ACCOUNT) {
            buildAccount();
        } else {
            buildComplete();
        }
        NestedScrollView scroll = findViewById(R.id.dashboard_scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void addProgress() {
        TextView label = Ui.text(this, "STEP " + (this.step + 1) + " OF "
                + OnboardingFlow.STEP_COUNT, 12.0f, Ui.accent(this, this.dark));
        label.setTypeface(Ui.mediumTypeface(this));
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 10));
        this.content.addView(label, labelParams);

        ProgressBar progress = Ui.progress(this, this.dark);
        progress.setProgress((this.step + 1) * 100 / OnboardingFlow.STEP_COUNT);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        progressParams.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 26));
        this.content.addView(progress, progressParams);
    }

    private void buildWelcome() {
        addIntro("Meet Codex Meter",
                "Your ChatGPT Codex allowance, reset timing, and available reset credits in one "
                        + "quick One UI view.",
                R.drawable.ic_oui_battery);

        Ui.addSpacer(this.content, 20);
        RoundedLinearLayout card = Ui.seslCard(this, this.dark);
        TextView title = Ui.text(this, "Built to feel at home on Galaxy", 18.0f,
                Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        TextView body = Ui.text(this,
                "Reachable layouts, responsive cards, system theming, and Samsung lock-screen "
                        + "widgets all use the app’s native One UI components.",
                15.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(body, bodyParams);
        this.content.addView(card);
        addPrimaryAction("Continue", () -> showStep(OnboardingFlow.STEP_USAGE));
    }

    private void buildUsage() {
        addIntro("Everything important at a glance",
                "See what remains without digging through ChatGPT, then keep it visible with "
                        + "home-screen and supported Galaxy lock-screen widgets.",
                R.drawable.ic_oui_time);

        this.content.addView(Ui.separator(this, "What you get"));
        RoundedLinearLayout features = Ui.seslRowCard(this, this.dark);
        CardItemView limits = Ui.actionRow(this, "Live Codex limits",
                "Five-hour and weekly allowance with reset timing",
                R.drawable.ic_oui_calendar_week, null);
        limits.setShowBottomDivider(true);
        features.addView(limits);
        CardItemView widgets = Ui.actionRow(this, "Native One UI widgets",
                "At-a-glance usage on your home and lock screens",
                R.drawable.ic_oui_add_home, null);
        widgets.setShowBottomDivider(true);
        features.addView(widgets);
        features.addView(Ui.actionRow(this, "Useful alerts",
                "Optional updates when limits reset or credits arrive",
                R.drawable.ic_oui_notification, null));
        this.content.addView(features);
        addPrimaryAction("Continue", () -> showStep(OnboardingFlow.STEP_ACCOUNT));
    }

    private void buildAccount() {
        addIntro("Connect your ChatGPT account",
                "Use OpenAI’s secure browser flow to sign up or sign in. Codex Meter never sees "
                        + "your password.",
                R.drawable.ic_oui_samsung_account);

        this.content.addView(Ui.separator(this, "Private by design"));
        RoundedLinearLayout privacy = Ui.seslRowCard(this, this.dark);
        CardItemView encrypted = Ui.actionRow(this, "Encrypted on this device",
                "Session tokens are protected by Android Keystore",
                R.drawable.ic_oui_privacy, null);
        encrypted.setShowBottomDivider(true);
        privacy.addView(encrypted);
        privacy.addView(Ui.actionRow(this, "No analytics SDK",
                "Your account and usage are not sent through a Codex Meter server",
                R.drawable.ic_oui_contact_outline, null));
        this.content.addView(privacy);

        if (!this.authMessage.isEmpty()) {
            Ui.addSpacer(this.content, 16);
            RoundedLinearLayout status = Ui.seslCard(this, this.dark);
            TextView message = Ui.text(this, this.authMessage, 14.0f,
                    Ui.secondaryText(this.dark));
            status.addView(message);
            this.content.addView(status);
        }

        String signInLabel = AppPreferences.isOAuthPending(this)
                ? "Continue sign-in with ChatGPT"
                : "Sign up or sign in with ChatGPT";
        addPrimaryAction(signInLabel, this::startSignIn);
        Button later = Ui.button(this, "Not now", false, this.dark);
        later.setOnClickListener(view -> completeAndOpenMain());
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        laterParams.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        this.content.addView(later, laterParams);
    }

    private void buildComplete() {
        boolean signedIn = SecureTokenStore.isSignedIn(this);
        addIntro(signedIn ? "You’re all set" : "Setup complete",
                signedIn
                        ? "Your ChatGPT account is connected. Codex Meter will load your latest "
                            + "allowance as the app opens."
                        : "You can connect ChatGPT later from the Codex Meter dashboard.",
                signedIn ? R.drawable.ic_oui_samsung_account : R.drawable.ic_oui_info_outline);

        Ui.addSpacer(this.content, 20);
        RoundedLinearLayout account = Ui.seslRowCard(this, this.dark);
        AuthTokens tokens = SecureTokenStore.load(this);
        account.addView(Ui.actionRow(this,
                signedIn ? "ChatGPT connected" : "Continue without an account",
                signedIn && tokens != null && !tokens.email.isEmpty()
                        ? tokens.email
                        : (signedIn ? "Secure sign-in complete" : "Sign in whenever you’re ready"),
                signedIn ? R.drawable.ic_oui_contact_outline : R.drawable.ic_oui_privacy,
                null));
        this.content.addView(account);
        addPrimaryAction("Open Codex Meter", this::completeAndOpenMain);
    }

    private void addIntro(String titleText, String bodyText, int iconResource) {
        RoundedLinearLayout hero = Ui.seslCard(this, this.dark);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(Ui.accent(this, this.dark));
        icon.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setShape(GradientDrawable.OVAL);
        int accent = Ui.accent(this, this.dark);
        iconBackground.setColor(Color.argb(this.dark ? 45 : 24,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        icon.setBackground(iconBackground);
        hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 62)));

        TextView title = Ui.title(this, titleText, this.dark);
        title.setTextSize(34.0f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 24), 0, 0);
        hero.addView(title, titleParams);

        TextView body = Ui.text(this, bodyText, 16.0f, Ui.secondaryText(this.dark));
        body.setLineSpacing(0.0f, 1.18f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        hero.addView(body, bodyParams);
        this.content.addView(hero);
    }

    private void addPrimaryAction(String label, Runnable action) {
        Button button = Ui.nativePrimaryButton(this, label);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        params.setMargins(0, Ui.dp(this, 22), 0, Ui.dp(this, 8));
        this.content.addView(button, params);
    }

    private void showStep(int requestedStep) {
        this.step = OnboardingFlow.normalizeStep(requestedStep);
        AppPreferences.setOnboardingStep(this, this.step);
        render();
    }

    private void goBack() {
        showStep(OnboardingFlow.previousStep(this.step));
    }

    private void startSignIn() {
        if (SecureTokenStore.isSignedIn(this)) {
            showStep(OnboardingFlow.STEP_COMPLETE);
            return;
        }
        boolean resuming = AppPreferences.isOAuthPending(this);
        this.oauthRequested = true;
        this.authMessage = resuming
                ? "Resuming secure ChatGPT sign-in…"
                : "Preparing secure ChatGPT sign-in…";
        render();
        try {
            startForegroundService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_START));
        } catch (RuntimeException exception) {
            this.oauthRequested = false;
            AppPreferences.setOAuthPending(this, false, "");
            this.authMessage = "Could not start sign-in: " + safeMessage(exception);
            render();
        }
    }

    private void openAuthUrl(String url) {
        if (!url.equals(this.lastLaunchedAuthUrl) || hasWindowFocus()) {
            this.lastLaunchedAuthUrl = url;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException exception) {
                this.authMessage = "No browser is available to complete sign-in.";
                render();
            }
        }
    }

    private void completeAndOpenMain() {
        cancelPendingSignIn();
        AppPreferences.completeOnboarding(this);
        openMain();
    }

    private void cancelPendingSignIn() {
        if (!this.oauthRequested && !AppPreferences.isOAuthPending(this)) {
            return;
        }
        this.oauthRequested = false;
        try {
            startService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_CANCEL_SILENT));
        } catch (RuntimeException ignored) {
            // The service may already have stopped after the browser returned.
        }
        AppPreferences.setOAuthPending(this, false, "");
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
