package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connects one provider. The screen adapts to how the provider signs in
 * ({@link ProviderCatalog.SignIn}): a browser sign-in, a code to enter on the provider's site,
 * a token to copy back, or an API key. Device-code and browser sign-ins run in
 * {@link OAuthService} so they survive leaving the app; paste and key sign-ins finish here.
 */
public final class SignInActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    public static final String EXTRA_PROVIDER = "provider";

    /** The paste sign-in in progress, kept across screen rotation and trips to the browser. */
    private static ProviderClient.Session pasteSession;
    private static UsageProvider pasteProvider;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UsageProvider provider;
    private ProviderCatalog.Entry entry;
    private ProviderClient client;
    private LinearLayout content;
    private boolean dark;
    private boolean receiverRegistered;
    private boolean serviceStarted;
    private boolean busy;
    private String status = "";
    private boolean statusIsError;
    private String userCode = "";
    private String verifyUrl = "";
    private String lastOpenedUrl = "";
    private String pasteDraft = "";
    private String keyDraft = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            String from = intent == null ? null : intent.getStringExtra(OAuthService.EXTRA_PROVIDER);
            if (from != null && !from.equals(provider.id)) return;
            if (OAuthService.ACTION_DEVICE_CODE.equals(action)) {
                userCode = nonNull(intent.getStringExtra(OAuthService.EXTRA_USER_CODE));
                verifyUrl = nonNull(intent.getStringExtra(OAuthService.EXTRA_VERIFY_URL));
                status = "";
                render();
            } else if (AppConstants.ACTION_OAUTH_READY.equals(action)) {
                String url = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (url != null && !url.isEmpty()) {
                    status = "Finish signing in in your browser, then come back here.";
                    statusIsError = false;
                    render();
                    openUrl(url, true);
                }
            } else if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                boolean success = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false)
                        || SecureTokenStore.isSignedIn(SignInActivity.this, provider);
                serviceStarted = false;
                if (success) {
                    finishConnected();
                } else {
                    userCode = "";
                    verifyUrl = "";
                    String message = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                    showError(message == null || message.isEmpty()
                            ? "Sign-in did not complete. Try again." : message);
                }
            }
        }
    };

    public static void start(Activity from, UsageProvider provider) {
        Ui.startSecondaryActivity(from, new Intent(from, SignInActivity.class)
                .putExtra(EXTRA_PROVIDER, provider.id));
    }

    public static Intent intent(Context context, UsageProvider provider) {
        return new Intent(context, SignInActivity.class).putExtra(EXTRA_PROVIDER, provider.id);
    }

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        this.provider = UsageProvider.fromId(getIntent().getStringExtra(EXTRA_PROVIDER));
        this.entry = ProviderCatalog.get(provider);
        this.client = ProviderClients.get(provider);
        this.dark = Ui.isDark(this);
        this.content = Ui.installPage(this, provider.displayName, true).content;
        findViewById(R.id.dashboard_refresh).setEnabled(false);
        if (state != null) {
            pasteDraft = nonNull(state.getString("paste"));
            keyDraft = nonNull(state.getString("key"));
        }
        // Coming back to a sign-in that is still running shows its code again.
        if (ProviderPreferences.isSignInPending(this, provider)) {
            serviceStarted = true;
            status = "Resuming sign-in…";
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("paste", pasteDraft);
        state.putString("key", keyDraft);
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(OAuthService.ACTION_DEVICE_CODE);
        filter.addAction(AppConstants.ACTION_OAUTH_READY);
        filter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, AppConstants.INTERNAL_PERMISSION, null,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter, AppConstants.INTERNAL_PERMISSION, null);
            }
            receiverRegistered = true;
        } catch (RuntimeException exception) {
            receiverRegistered = false;
        }
        if (SecureTokenStore.isSignedIn(this, provider) && !busy) {
            finishConnected();
            return;
        }
        if (serviceStarted) {
            // Ask the running sign-in to send its code or link again.
            startService(true);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ------------------------------------------------------------------ layout

    private void render() {
        content.removeAllViews();
        addHero();
        if (entry.inferred) addExperimentalNote();
        switch (entry.signIn) {
            case DEVICE_CODE:
                addDeviceCode();
                break;
            case CODE_PASTE:
                addPaste();
                break;
            case API_KEY:
                addApiKey(true);
                break;
            case LOOPBACK:
            case BROWSER:
            default:
                addBrowser();
                break;
        }
        if (entry.acceptsApiKey && entry.signIn != ProviderCatalog.SignIn.API_KEY) {
            addApiKey(false);
        }
        addPrivacy();
    }

    private void addHero() {
        RoundedLinearLayout hero = Ui.seslCard(this, dark);
        LinearLayout top = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(ProvidersActivity.iconRes(provider));
        icon.setColorFilter(Ui.mainText(dark));
        int pad = Ui.dp(this, 12);
        icon.setPadding(pad, pad, pad, pad);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int accent = Ui.accent(this, dark);
        circle.setColor(Color.argb(dark ? 45 : 24, Color.red(accent), Color.green(accent),
                Color.blue(accent)));
        icon.setBackground(circle);
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "Connect " + provider.displayName, 22.0f,
                Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        labels.addView(title);
        labels.addView(Ui.text(this, entry.meters, 14.0f, Ui.secondaryText(dark)));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelParams.setMargins(Ui.dp(this, 16), 0, 0, 0);
        top.addView(labels, labelParams);
        hero.addView(top);
        TextView body = Ui.text(this, howItWorks(), 15.0f, Ui.secondaryText(dark));
        body.setLineSpacing(0.0f, 1.15f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.setMargins(0, Ui.dp(this, 14), 0, Ui.dp(this, 2));
        hero.addView(body, bodyParams);
        content.addView(hero);
    }

    private String howItWorks() {
        switch (entry.signIn) {
            case DEVICE_CODE:
                return "Codex Meter shows a short code. Enter it on " + provider.vendorName
                        + "’s sign-in page and approve; this screen finishes on its own.";
            case CODE_PASTE:
                return "Sign in on " + provider.vendorName + "’s page, then copy what it shows "
                        + "and paste it below.";
            case API_KEY:
                return "Create a key on " + provider.vendorName + "’s site and paste it below. "
                        + "Codex Meter only uses it to read your usage.";
            default:
                return "Sign in with the " + provider.accountName + " account you use in "
                        + provider.displayName + ". The browser handles your password; Codex "
                        + "Meter never sees it.";
        }
    }

    private void addExperimentalNote() {
        Ui.addSpacer(content, 16);
        RoundedLinearLayout note = Ui.seslCard(this, dark);
        TextView title = Ui.text(this, "Experimental", 15.0f, Ui.accent(this, dark));
        title.setTypeface(Ui.mediumTypeface(this));
        note.addView(title);
        TextView body = Ui.text(this, "This uses the same sign-in as " + provider.vendorName
                        + "’s own tools, but Codex Meter hasn’t been able to confirm the usage "
                        + "check with a live account yet. If it doesn’t work, the "
                        + provider.displayName + " tab will say why.", 14.0f,
                Ui.secondaryText(dark));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(this, 6), 0, 0);
        note.addView(body, params);
        content.addView(note);
    }

    private void addBrowser() {
        addStatus();
        Button signIn = primary(serviceStarted ? "Continue in browser"
                : provider.signInLabel());
        signIn.setOnClickListener(view -> {
            if (serviceStarted && !lastOpenedUrl.isEmpty()) {
                openUrl(lastOpenedUrl, false);
            } else {
                startService(false);
            }
        });
        if (serviceStarted) addCancel();
    }

    private void addDeviceCode() {
        if (!userCode.isEmpty() || !verifyUrl.isEmpty()) {
            Ui.addSpacer(content, 16);
            RoundedLinearLayout card = Ui.seslCard(this, dark);
            if (!userCode.isEmpty()) {
                TextView label = Ui.text(this, "Your code", 13.0f, Ui.secondaryText(dark));
                label.setGravity(Gravity.CENTER);
                card.addView(label, new LinearLayout.LayoutParams(-1, -2));
                TextView code = Ui.text(this, userCode, 34.0f, Ui.mainText(dark));
                code.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                code.setLetterSpacing(0.12f);
                code.setGravity(Gravity.CENTER);
                code.setTextIsSelectable(true);
                LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-1, -2);
                codeParams.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
                card.addView(code, codeParams);
            }
            LinearLayout waiting = Ui.horizontal(this, Gravity.CENTER);
            waiting.addView(Ui.indeterminateLoading(this, "Waiting for approval"),
                    new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            TextView waitingText = Ui.text(this, userCode.isEmpty()
                            ? "Waiting for you to approve in the browser…"
                            : "Waiting for you to enter it…", 14.0f,
                    Ui.secondaryText(dark));
            LinearLayout.LayoutParams waitingParams = new LinearLayout.LayoutParams(-2, -2);
            waitingParams.setMargins(Ui.dp(this, 10), 0, 0, 0);
            waiting.addView(waitingText, waitingParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
            card.addView(waiting, rowParams);
            content.addView(card);

            Button open = primary(userCode.isEmpty() ? "Open " + provider.vendorName
                    : "Copy code and open " + provider.vendorName);
            open.setOnClickListener(view -> {
                if (!userCode.isEmpty()) copy(userCode);
                openUrl(verifyUrl, false);
            });
            addCancel();
            return;
        }
        addStatus();
        Button start = primary(serviceStarted ? "Getting a code…" : "Get a sign-in code");
        start.setEnabled(!serviceStarted);
        start.setOnClickListener(view -> startService(false));
    }

    private void addPaste() {
        Ui.addSpacer(content, 16);
        content.addView(Ui.separator(this, "Step 1"));
        Button open = secondary("Open " + provider.vendorName + " sign-in");
        open.setOnClickListener(view -> {
            if (pasteSession == null || pasteProvider != provider) {
                pasteSession = new ProviderClient.Session();
                pasteProvider = provider;
            }
            openUrl(client.pasteUrl(pasteSession), false);
        });
        content.addView(Ui.separator(this, "Step 2"));
        EditText field = inputField(client.pasteHint(), pasteDraft, false);
        field.addTextChangedListener(new SimpleWatcher(text -> pasteDraft = text));
        addStatus();
        Button connect = primary(busy ? "Connecting…" : "Connect");
        connect.setEnabled(!busy);
        connect.setOnClickListener(view -> {
            if (pasteSession == null || pasteProvider != provider) {
                showError("Open the " + provider.vendorName + " sign-in first (step 1).");
                return;
            }
            ProviderClient.Session session = pasteSession;
            runConnect(() -> client.finishPaste(getApplicationContext(), session, pasteDraft));
        });
    }

    private void addApiKey(boolean primaryMethod) {
        Ui.addSpacer(content, 16);
        content.addView(Ui.separator(this, primaryMethod ? "API key" : "Or use an API key"));
        if (!entry.keyUrl.isEmpty()) {
            Button open = secondary("Create a key on " + provider.vendorName);
            open.setOnClickListener(view -> openUrl(entry.keyUrl, false));
        }
        EditText field = inputField("Paste your " + provider.displayName + " key", keyDraft,
                true);
        field.addTextChangedListener(new SimpleWatcher(text -> keyDraft = text));
        if (primaryMethod) addStatus();
        Button connect = primaryMethod ? primary(busy ? "Checking…" : "Connect")
                : secondary(busy ? "Checking…" : "Connect with key");
        connect.setEnabled(!busy);
        connect.setOnClickListener(view -> runConnect(
                () -> client.fromApiKey(getApplicationContext(), keyDraft)));
    }

    private void addPrivacy() {
        Ui.addSpacer(content, 24);
        TextView note = Ui.text(this, "Sign-in details are encrypted with Android Keystore and "
                        + "only sent to " + provider.vendorName + ". No Codex Meter server is "
                        + "involved.", 12.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 12));
        content.addView(note, params);
    }

    private void addStatus() {
        if (status.isEmpty()) return;
        Ui.addSpacer(content, 16);
        TextView text = Ui.text(this, status, 14.0f,
                statusIsError ? Ui.danger(dark) : Ui.secondaryText(dark));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        content.addView(text, params);
    }

    private void addCancel() {
        Button cancel = secondary("Cancel");
        cancel.setOnClickListener(view -> cancelService());
    }

    private Button primary(String label) {
        Button button = Ui.nativePrimaryButton(this, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        params.setMargins(0, Ui.dp(this, 18), 0, 0);
        content.addView(button, params);
        return button;
    }

    private Button secondary(String label) {
        Button button = Ui.button(this, label, false, dark);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        params.setMargins(0, Ui.dp(this, 12), 0, 0);
        content.addView(button, params);
        return button;
    }

    private EditText inputField(String hint, String value, boolean secret) {
        RoundedLinearLayout card = Ui.seslCard(this, dark);
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(15.0f);
        field.setTextColor(Ui.mainText(dark));
        field.setHintTextColor(Ui.secondaryText(dark));
        field.setBackground(null);
        if (secret) {
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setMinLines(3);
            field.setMaxLines(6);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(this, 12), 0, 0);
        content.addView(card, params);
        return field;
    }

    // ------------------------------------------------------------------ actions

    private void startService(boolean resume) {
        try {
            startForegroundService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_START)
                    .putExtra(OAuthService.EXTRA_PROVIDER, provider.id));
            serviceStarted = true;
            if (!resume) {
                status = entry.signIn == ProviderCatalog.SignIn.DEVICE_CODE
                        ? "" : "Opening " + provider.vendorName + " sign-in…";
                statusIsError = false;
            }
            DiagnosticLog.info(this, "user", "sign_in_requested", "provider", provider.id);
        } catch (RuntimeException exception) {
            serviceStarted = false;
            showError("Could not start sign-in: " + exception.getClass().getSimpleName());
            return;
        }
        render();
    }

    private void cancelService() {
        try {
            startService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_CANCEL_SILENT));
        } catch (RuntimeException ignored) {
            // The service may already have stopped.
        }
        AppPreferences.setOAuthPending(this, false, "");
        serviceStarted = false;
        userCode = "";
        verifyUrl = "";
        lastOpenedUrl = "";
        status = "";
        render();
    }

    private interface Connect {
        AuthTokens run() throws Exception;
    }

    /** Paste and key sign-ins: check, save, load the first usage, then close. */
    private void runConnect(Connect connect) {
        if (busy) return;
        busy = true;
        status = "";
        render();
        Context app = getApplicationContext();
        executor.execute(() -> {
            String failure = null;
            try {
                AuthTokens tokens = connect.run();
                SecureTokenStore.save(app, provider, tokens);
                if (!tokens.email.isEmpty()) ProviderPreferences.setEmail(app, provider, tokens.email);
                try {
                    ProviderUsageApi.refreshAndCache(app, provider);
                } catch (Exception refreshError) {
                    // Saved on the tab as its error; the account is still connected.
                }
                RefreshScheduler.schedulePeriodic(app);
                WidgetRenderer.updateAll(app);
            } catch (Exception exception) {
                failure = MainActivity.safeMessage(exception);
                DiagnosticLog.warn(app, "auth", "provider_connect_failed",
                        "provider", provider.id, "error", exception.getClass().getSimpleName());
            }
            final String message = failure;
            runOnUiThread(() -> {
                busy = false;
                if (message == null) {
                    pasteSession = null;
                    finishConnected();
                } else {
                    showError(message);
                }
            });
        });
    }

    private void finishConnected() {
        ProviderPreferences.setEnabled(this, provider, true);
        AppPreferences.setOAuthPending(this, false, "");
        Toast.makeText(this, provider.displayName + " connected.", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void showError(String message) {
        status = message;
        statusIsError = true;
        render();
    }

    private void openUrl(String url, boolean fromService) {
        if (url == null || url.isEmpty()) return;
        if (fromService && url.equals(lastOpenedUrl) && !hasWindowFocus()) return;
        lastOpenedUrl = url;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException exception) {
            showError("No browser is available to finish signing in.");
        }
    }

    private void copy(String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Sign-in code", text));
            Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static final class SimpleWatcher implements android.text.TextWatcher {
        interface OnText {
            void changed(String text);
        }

        private final OnText onText;

        SimpleWatcher(OnText onText) {
            this.onText = onText;
        }

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
            onText.changed(text.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable text) {
        }
    }
}
