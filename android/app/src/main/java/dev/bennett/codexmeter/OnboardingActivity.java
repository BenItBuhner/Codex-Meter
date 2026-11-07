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
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup built from the same One UI Design Library primitives as the app: welcome,
 * choose providers (nothing preselected), sign in to each chosen provider or skip, then done.
 */
public final class OnboardingActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    public static final String EXTRA_AUTH_RETURN = "oauth_return";

    private LinearLayout content;
    private Ui.Page page;
    private boolean dark;
    private int step;
    /** Providers picked on the second step, in enum order; drives the sign-in steps. */
    private final List<UsageProvider> chosen = new ArrayList<>();
    /** Unsaved picks while the provider step is open. */
    private final List<UsageProvider> picked = new ArrayList<>();
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
                    UsageProvider target = currentSignInProvider();
                    authMessage = "Your secure "
                            + (target == null ? "" : target.accountName + " ")
                            + "sign-in is open in the browser.";
                    render();
                    openAuthUrl(url);
                }
                return;
            }
            if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                oauthRequested = false;
                boolean success = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false);
                String message = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                UsageProvider target = currentSignInProvider();
                if (success || (target != null
                        && SecureTokenStore.isSignedIn(OnboardingActivity.this, target))) {
                    authMessage = "";
                } else {
                    authMessage = message == null || message.trim().isEmpty()
                            ? "Sign-in did not complete. Please try again."
                            : message;
                }
                render();
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
        this.chosen.addAll(AppPreferences.getOnboardingProviders(this));
        this.picked.addAll(this.chosen);
        this.step = OnboardingFlow.initialStep(AppPreferences.getOnboardingStep(this),
                this.chosen.size());
        UsageProvider target = currentSignInProvider();
        if (oauthReturn && target != null && !SecureTokenStore.isSignedIn(this, target)) {
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
        UsageProvider target = currentSignInProvider();
        if (target != null && !SecureTokenStore.isSignedIn(this, target)
                && intent.getBooleanExtra(EXTRA_AUTH_RETURN, false)) {
            this.authMessage = "Sign-in did not complete. You can safely try again.";
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the browser: show the connected state of the current sign-in step.
        UsageProvider target = currentSignInProvider();
        if (this.content != null && target != null) {
            if (SecureTokenStore.isSignedIn(this, target)) this.authMessage = "";
            render();
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
        this.page.header.setTitle("Codex Meter");
        this.page.header.setBack(this.step > OnboardingFlow.STEP_WELCOME);

        addProgress();
        UsageProvider target = currentSignInProvider();
        if (this.step == OnboardingFlow.STEP_WELCOME) {
            buildWelcome();
        } else if (this.step == OnboardingFlow.STEP_PROVIDERS) {
            buildProviders();
        } else if (target != null) {
            buildSignIn(target);
        } else {
            buildComplete();
        }
        NestedScrollView scroll = findViewById(R.id.dashboard_scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void addProgress() {
        // Before anything is chosen, count the picks so far (at least one sign-in step).
        int planned = this.step <= OnboardingFlow.STEP_PROVIDERS
                ? Math.max(1, this.picked.size()) : this.chosen.size();
        int total = OnboardingFlow.stepCount(planned);
        TextView label = Ui.text(this, "STEP " + (this.step + 1) + " OF " + total, 12.0f,
                Ui.accent(this, this.dark));
        label.setTypeface(Ui.mediumTypeface(this));
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 10));
        this.content.addView(label, labelParams);

        ProgressBar progress = Ui.progress(this, this.dark);
        progress.setProgress((this.step + 1) * 100 / total);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        progressParams.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 26));
        this.content.addView(progress, progressParams);
    }

    private void buildWelcome() {
        addIntro("Meet Codex Meter",
                "Your Codex, Claude Code, Cursor, Copilot and other AI coding limits, reset "
                        + "timing, and credits in one quick One UI view. Next, pick the ones you "
                        + "use.",
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
        addPrimaryAction("Continue", () -> showStep(OnboardingFlow.STEP_PROVIDERS));
    }

    private void buildProviders() {
        addIntro("Choose your providers",
                "Pick every service you want to track. Each one gets a tab on the dashboard, "
                        + "and you can add more later in Settings → Accounts.",
                R.drawable.ic_oui_samsung_account);

        Button next = Ui.nativePrimaryButton(this, "Continue");
        ProviderPicker picker = new ProviderPicker(this, this.dark, null, this.picked,
                provider -> {
                    next.setEnabled(!this.picked.isEmpty());
                    next.setAlpha(this.picked.isEmpty() ? 0.45f : 1.0f);
                });
        this.content.addView(picker.view());

        next.setEnabled(!this.picked.isEmpty());
        next.setAlpha(this.picked.isEmpty() ? 0.45f : 1.0f);
        next.setOnClickListener(view -> saveProvidersAndContinue());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        params.setMargins(0, Ui.dp(this, 22), 0, Ui.dp(this, 8));
        this.content.addView(next, params);
    }

    private void saveProvidersAndContinue() {
        List<UsageProvider> ordered = new ArrayList<>();
        for (UsageProvider provider : ProviderCatalog.recommendedOrder()) {
            if (this.picked.contains(provider)) ordered.add(provider);
        }
        if (ordered.isEmpty()) return;
        this.chosen.clear();
        this.chosen.addAll(ordered);
        AppPreferences.setOnboardingProviders(this, ordered);
        ProviderPreferences.setEnabledProviders(this, ordered);
        ProviderPreferences.setSelected(this, ProviderPreferences.getEnabled(this).get(0));
        DiagnosticLog.info(this, "user", "onboarding_providers_chosen",
                "count", ordered.size());
        this.authMessage = "";
        showStep(OnboardingFlow.FIRST_SIGN_IN_STEP);
    }

    private void buildSignIn(UsageProvider provider) {
        boolean signedIn = SecureTokenStore.isSignedIn(this, provider);
        if (signedIn && AccountVault.isAdding(this, provider)) {
            AccountVault.finishAdding(this, provider);
        }
        boolean adding = !signedIn && AccountVault.hasSaved(this, provider);
        int accounts = AccountVault.count(this, provider);
        addIntro(adding ? "Add another " + provider.displayName + " account"
                        : signedIn ? provider.displayName + " connected"
                        : "Connect " + provider.displayName,
                signedIn
                        ? (accounts > 1 ? accounts + " " + provider.accountName
                                + " accounts are connected. The dashboard shows one at a time; "
                                + "tap the account button to switch."
                                : "Your " + provider.accountName + " account is connected.")
                        : "Sign in with the " + provider.accountName + " account you use in "
                                + provider.displayName + ". The browser handles your password; "
                                + "Codex Meter never sees it.",
                ProvidersActivity.iconRes(provider));

        if (signedIn) {
            Ui.addSpacer(this.content, 20);
            RoundedLinearLayout card = Ui.seslRowCard(this, this.dark);
            List<AccountVault.Account> list = AccountVault.list(this, provider);
            for (int i = 0; i < list.size(); i++) {
                AccountVault.Account account = list.get(i);
                String label = account.label(i);
                card.addView(AccountSwitcher.row(this, this.dark,
                        new AccountSwitcher.AvatarDrawable(this, AccountSwitcher.initial(label),
                                AccountSwitcher.colorFor(label), 40, 0),
                        label, AccountSwitcher.summary(account), false));
            }
            View add = AccountSwitcher.row(this, this.dark,
                    AccountSwitcher.addIcon(this, this.dark), "Add another account",
                    "Work and personal, or more than one plan", false);
            add.setOnClickListener(view -> {
                DiagnosticLog.info(this, "user", "onboarding_add_another", "provider",
                        provider.id);
                AccountVault.prepareToAdd(this, provider);
                startSignIn(provider);
            });
            card.addView(add);
            this.content.addView(card);
            addPrimaryAction("Continue", this::goNext);
            return;
        }

        this.content.addView(Ui.separator(this, "Private by design"));
        RoundedLinearLayout privacy = Ui.seslRowCard(this, this.dark);
        CardItemView encrypted = Ui.actionRow(this, "Encrypted on this device",
                "Session tokens are protected by Android Keystore",
                R.drawable.ic_oui_privacy, null);
        encrypted.setShowBottomDivider(true);
        privacy.addView(encrypted);
        privacy.addView(Ui.actionRow(this, "Straight to " + provider.vendorName,
                "No Codex Meter server or analytics SDK in between",
                R.drawable.ic_oui_contact_outline, null));
        this.content.addView(privacy);

        if (!this.authMessage.isEmpty()) {
            Ui.addSpacer(this.content, 16);
            RoundedLinearLayout status = Ui.seslCard(this, this.dark);
            status.addView(Ui.text(this, this.authMessage, 14.0f, Ui.secondaryText(this.dark)));
            this.content.addView(status);
        }

        addPrimaryAction(ProviderPreferences.isSignInPending(this, provider)
                ? "Continue sign-in with " + provider.accountName
                : provider.signInLabel(), () -> startSignIn(provider));
        Button later = Ui.button(this, adding ? "Cancel" : "Skip for now", false, this.dark);
        later.setOnClickListener(view -> {
            cancelPendingSignIn();
            if (adding) {
                AccountVault.cancelAdding(this, provider);
                render();
                return;
            }
            goNext();
        });
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        laterParams.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        this.content.addView(later, laterParams);
    }

    private void buildComplete() {
        boolean any = false;
        for (UsageProvider provider : this.chosen) {
            any |= SecureTokenStore.isSignedIn(this, provider);
        }
        addIntro(any ? "You’re all set" : "Setup complete",
                any ? "Codex Meter will load your latest usage as the app opens."
                        : "You can sign in any time from Settings → Providers & accounts.",
                any ? R.drawable.ic_oui_samsung_account : R.drawable.ic_oui_info_outline);

        this.content.addView(Ui.separator(this, "Accounts"));
        RoundedLinearLayout accounts = Ui.seslRowCard(this, this.dark);
        for (int i = 0; i < this.chosen.size(); i++) {
            UsageProvider provider = this.chosen.get(i);
            int count = AccountVault.count(this, provider);
            CardItemView row = Ui.actionRow(this, provider.displayName,
                    SecureTokenStore.isSignedIn(this, provider)
                            ? ProviderAccounts.summary(this, provider)
                                    + (count > 1 ? " · +" + (count - 1) + " more" : "")
                            : "Not signed in · sign in later from Settings",
                    ProvidersActivity.iconRes(provider), null);
            row.setShowBottomDivider(i < this.chosen.size() - 1);
            accounts.addView(row);
        }
        this.content.addView(accounts);
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
        this.step = OnboardingFlow.normalizeStep(requestedStep, this.chosen.size());
        AppPreferences.setOnboardingStep(this, this.step);
        render();
    }

    private void goBack() {
        this.authMessage = "";
        showStep(OnboardingFlow.previousStep(this.step, this.chosen.size()));
    }

    private void goNext() {
        this.authMessage = "";
        showStep(OnboardingFlow.nextStep(this.step, this.chosen.size()));
    }

    /** The provider of the current sign-in step, or null on any other step. */
    private UsageProvider currentSignInProvider() {
        int index = OnboardingFlow.signInIndex(this.step, this.chosen.size());
        return index < 0 ? null : this.chosen.get(index);
    }

    private void startSignIn(UsageProvider provider) {
        if (SecureTokenStore.isSignedIn(this, provider)) {
            render();
            return;
        }
        if (ProviderCatalog.get(provider).usesSignInScreen()) {
            // Device codes, pasted tokens and keys have their own screen; it closes when done.
            startActivity(SignInActivity.intent(this, provider));
            return;
        }
        boolean resuming = ProviderPreferences.isSignInPending(this, provider);
        this.oauthRequested = true;
        this.authMessage = (resuming ? "Resuming" : "Preparing") + " secure "
                + provider.accountName + " sign-in…";
        render();
        try {
            startForegroundService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_START)
                    .putExtra(OAuthService.EXTRA_PROVIDER, provider.id));
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
