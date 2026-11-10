package dev.bennett.codexmeter;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.util.Collections;
import java.util.List;

/** Reset credits in Liquid Glass (ResetCreditActivity): what's available, when it expires, and using one. */
final class GlassResetScreen extends GlassScreens.Screen {
    private final UsageProvider provider;
    private boolean applying;
    private int notificationId = -1;

    GlassResetScreen(GlassScreens screens, UsageProvider provider) {
        super(screens);
        this.provider = provider;
    }

    private boolean claude() {
        return provider == UsageProvider.CLAUDE_CODE;
    }

    private ResetCreditsSnapshot credits() {
        if (!claude()) {
            return AppPreferences.loadResetCredits(activity());
        }
        ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(activity(), provider);
        return saved == null ? null : saved.toResetCreditsSnapshot(System.currentTimeMillis());
    }

    private boolean signedIn() {
        return SecureTokenStore.isSignedIn(activity(), provider);
    }

    @Override
    void opened(Intent intent) {
        applying = false;
        refreshIfNeeded();
        if (claude() || intent == null || !intent.getBooleanExtra(AppConstants.EXTRA_PROMPT_USE_RESET, false)) {
            return;
        }
        notificationId = intent.getIntExtra(AppConstants.EXTRA_NOTIFICATION_ID, -1);
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(activity());
        if (snapshot != null && snapshot.availableCount > 0 && SecureTokenStore.isSignedIn(activity())) {
            confirmUse();
        }
    }

    @Override
    GlassModel.Page page() {
        Context c = activity();
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "reset:" + provider.id,
                provider.displayName + " reset");
        ResetCreditsSnapshot snapshot = credits();
        int available = snapshot == null ? 0 : snapshot.availableCount;
        long now = System.currentTimeMillis();
        ClaudeSavedResets saved = claude() ? ProviderPreferences.loadSavedResets(c, provider) : null;
        List<RateLimitResetCredit> credits = snapshot == null
                ? Collections.<RateLimitResetCredit>emptyList() : snapshot.availableCreditsByExpiry(now);
        long nextExpiry = snapshot == null ? 0L : snapshot.nextExpiryMillis(now);

        Section summary = GlassScreens.section("Available credits");
        Row count = GlassScreens.row(Row.Kind.INFO, "available",
                claude() && saved != null ? saved.availableTitle(now)
                        : available <= 0 ? "No resets available"
                        : available == 1 ? "1 reset available" : available + " resets available",
                nextExpiry > 0L
                        ? "Next expires " + UsageFormat.absolute(c, nextExpiry, now) + " · "
                                + UsageFormat.relative(nextExpiry, now)
                        : available > 0 ? (claude() ? "Anthropic" : "OpenAI") + " will choose an eligible credit"
                        : "No reset credit is currently available");
        count.icon = "battery";
        count.color = "#34c759";
        summary.rows.add(count);
        page.sections.add(summary);

        Section expirations = GlassScreens.section("Credit expirations");
        for (int i = 0; i < credits.size(); i++) {
            RateLimitResetCredit credit = credits.get(i);
            String title = credit.title.trim().isEmpty() ? "Reset credit " + (i + 1) : credit.title.trim();
            if (i == 0 && credit.expiresAtMillis > 0L) {
                title += " · Next";
            }
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "c" + i, title,
                    credit.expiresAtMillis > 0L
                            ? UsageFormat.absolute(c, credit.expiresAtMillis, now) + " · "
                                    + UsageFormat.relative(credit.expiresAtMillis, now)
                            : "Expiration unavailable"));
        }
        int missing = Math.max(0, available - credits.size());
        if (missing > 0) {
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "more", "More credits",
                    credits.isEmpty() ? "Expiration details are not available yet"
                            : missing + " additional credit" + (missing == 1 ? "" : "s")
                                    + " without expiration details"));
        } else if (available == 0) {
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "none", "No available credits",
                    claude() ? "No reset credit is currently available" : "Earn credits from ChatGPT Codex"));
        }
        String error = claude() ? "" : AppPreferences.getVisibleResetCreditsError(c);
        String heldBack = "";
        if (claude() && available > 0) {
            heldBack = saved == null ? "" : saved.unavailableMessage(now);
        }
        StringBuilder footer = new StringBuilder(
                ResetCreditActivity.autoUseSummary(c, claude(), nextExpiry > 0L));
        if (!heldBack.isEmpty()) {
            if (footer.length() > 0) {
                footer.append("\n\n");
            }
            footer.append(heldBack).append('.');
        }
        expirations.footer = footer.length() == 0 ? null : footer.toString();
        page.sections.add(expirations);
        if (!error.isEmpty()) {
            GlassModel.Card card = new GlassModel.Card(GlassModel.Card.Kind.ERROR);
            card.title = error;
            page.sections.add(GlassScreens.cards(card));
        }

        Section use = GlassScreens.section(null);
        Row button = GlassScreens.row(Row.Kind.BUTTON, "use",
                applying ? "Applying…" : available > 0 ? "Use 1 reset" : "No resets available", null);
        button.enabled = !applying && available > 0 && heldBack.isEmpty() && signedIn();
        button.onPress = this::confirmUse;
        use.rows.add(button);
        page.sections.add(use);
        return page;
    }

    private void refreshIfNeeded() {
        Context app = activity().getApplicationContext();
        if (claude()) {
            if (!signedIn() || !ClaudeUsageApi.isStale(app)) {
                return;
            }
            screens.executor.execute(() -> {
                try {
                    ClaudeUsageApi.refreshAndCache(app);
                    activity().runOnUiThread(this::changed);
                } catch (Exception ignored) {
                    // The Claude tab shows refresh errors; this page keeps the cached resets.
                }
            });
            return;
        }
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(app);
        long now = System.currentTimeMillis();
        long age = snapshot == null ? Long.MAX_VALUE : Math.max(0L, now - snapshot.fetchedAtMillis);
        boolean missingDetails = snapshot != null && snapshot.availableCount > 0
                && snapshot.availableCreditsByExpiry(now).size() < snapshot.availableCount;
        if (SecureTokenStore.isSignedIn(app) && (age >= 300000 || missingDetails)) {
            screens.executor.execute(() -> {
                try {
                    ResetCreditApi.refreshAndCache(app);
                    activity().runOnUiThread(this::changed);
                } catch (Exception e) {
                    AppPreferences.setResetCreditsError(app, ResetCreditActivity.safeMessage(e));
                }
            });
        }
    }

    private void confirmUse() {
        new AppDialog(activity())
                .setTitle("Use one " + provider.displayName + " reset?")
                .setMessage("The available credit expiring soonest will be used. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use 1 reset", (dialog, which) -> consume())
                .show();
    }

    private void consume() {
        Context c = activity();
        Context app = c.getApplicationContext();
        applying = true;
        changed();
        if (claude()) {
            ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(c, provider);
            ClaudeSavedResets.Grant grant = saved == null ? null : saved.usableGrant(System.currentTimeMillis());
            if (grant == null) {
                Toast.makeText(c, "No reset credit is currently available.", Toast.LENGTH_LONG).show();
                applying = false;
                changed();
                return;
            }
            String grantId = grant.id;
            screens.executor.execute(() -> {
                ClaudeSavedResets.ClaimResult result = ClaudeResetApi.use(app, grantId, "reset_screen");
                activity().runOnUiThread(() -> done(result.userMessage(), result.applied()));
            });
            return;
        }
        screens.executor.execute(() -> {
            try {
                ResetConsumeResult result = ResetCreditApi.consumeBestAvailable(app);
                activity().runOnUiThread(() -> {
                    if (result.applied()) {
                        ResetNotificationManager.dismissResetCreditExpiryNotification(c, notificationId);
                    }
                    done(result.userMessage(), result.applied());
                });
            } catch (Exception e) {
                AppPreferences.setResetCreditsError(app, ResetCreditActivity.safeMessage(e));
                activity().runOnUiThread(() -> done(ResetCreditActivity.safeMessage(e), false));
            }
        });
    }

    private void done(String message, boolean applied) {
        Toast.makeText(activity(), message, Toast.LENGTH_LONG).show();
        applying = false;
        if (applied) {
            screens.app.pop();
        }
        changed();
    }
}
