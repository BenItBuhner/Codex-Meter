package dev.bennett.codexmeter;

import android.content.Context;

/**
 * Account details and sign-out for every provider, shared by Settings, Providers, and setup so
 * the dashboards themselves carry no account controls.
 */
public final class ProviderAccounts {
    private ProviderAccounts() {
    }

    public static boolean isSignedIn(Context context, UsageProvider provider) {
        return SecureTokenStore.isSignedIn(context, provider);
    }

    /** The signed-in email, or an empty string when unknown. */
    public static String email(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) {
            AuthTokens tokens = SecureTokenStore.load(context);
            return tokens == null ? "" : tokens.email;
        }
        String saved = ProviderPreferences.getEmail(context, provider);
        if (!saved.isEmpty()) return saved;
        AuthTokens tokens = SecureTokenStore.load(context, provider);
        return tokens == null ? "" : tokens.email;
    }

    /** Plan badge text from the last usage refresh, or an empty string. */
    public static String plan(Context context, UsageProvider provider) {
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, provider);
        if (snapshot == null || snapshot.planType == null) return "";
        return provider == UsageProvider.CODEX
                ? UsageFormat.planLabel(snapshot.planType) : snapshot.planType;
    }

    /** One line for an account row: "email · Plan", "Signed in", or "Not signed in". */
    public static String summary(Context context, UsageProvider provider) {
        if (!isSignedIn(context, provider)) {
            return ProviderPreferences.isSignInPending(context, provider)
                    ? "Sign-in in progress" : "Not signed in";
        }
        String email = email(context, provider);
        String plan = plan(context, provider);
        String who = email.isEmpty() ? "Signed in" : email;
        return plan.isEmpty() ? who : who + " · " + plan;
    }

    /** Removes one provider's tokens and cached usage; other providers stay signed in. */
    public static void signOut(Context context, UsageProvider provider) {
        Context app = context.getApplicationContext();
        DiagnosticLog.info(app, "user", "sign_out_confirmed", "provider", provider.id);
        AuthTokens tokens = SecureTokenStore.load(app, provider);
        boolean pending = ProviderPreferences.isSignInPending(app, provider);
        SecureTokenStore.clear(app, provider);
        if (provider == UsageProvider.CODEX) {
            AppPreferences.clearSnapshot(app);
            RefreshScheduler.cancelAll(app);
            ResetAlertScheduler.cancelAll(app);
        } else {
            ProviderPreferences.clearProvider(app, provider);
        }
        if (pending || provider == UsageProvider.CODEX) {
            AppPreferences.setOAuthPending(app, false, "");
        }
        // A removed account's tab goes too, unless it is the last tab left.
        ProviderPreferences.setEnabled(app, provider, false);
        // Keeps background refresh alive for the providers that are still signed in.
        RefreshScheduler.schedulePeriodic(app);
        WidgetRenderer.updateAll(app);
        if (provider == UsageProvider.CODEX && tokens != null) {
            new Thread(() -> OAuthClient.revokeBestEffort(app, tokens), "codex-sign-out").start();
        }
    }
}
